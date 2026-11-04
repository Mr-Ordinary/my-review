package com.review.vt.service.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.vt.domain.Attempt;
import com.review.vt.domain.FollowUp;
import com.review.vt.domain.KnowledgePoint;
import com.review.vt.domain.MemoryPoint;
import com.review.vt.domain.Mistake;
import com.review.vt.domain.Note;
import com.review.vt.domain.Paper;
import com.review.vt.domain.Practice;
import com.review.vt.domain.Question;
import com.review.vt.domain.ReviewDoc;
import com.review.vt.domain.Source;
import com.review.vt.domain.Stage;
import com.review.vt.domain.User;
import com.review.vt.repo.UserRepository;
import com.review.vt.security.AesGcmCrypto;
import com.review.vt.security.MasterKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次性数据迁移：旧本地 JSON（data/store/*.json，单用户）→ PostgreSQL（多用户）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>全部旧数据归属预建用户 admin</b>（初始密码 888888，登录后请立即修改）</li>
 *   <li><b>幂等</b>：全部 INSERT ... ON CONFLICT (id) DO NOTHING，中断后重启重跑不翻倍；
 *       每表一个事务，失败的表整体回滚</li>
 *   <li><b>先备份后删源</b>：{data-dir}/backup/backup-&lt;ts&gt;/ 留全量副本，
 *       全部成功后才删 data/settings.json 与 data/store/*.json</li>
 *   <li><b>API Key 加密迁移</b>：data/settings.json 里的明文 key 加密后进 admin 行，
 *       原文件不再保留明文</li>
 * </ul>
 */
@Service
public class JsonToDbMigrator {

    private static final Logger log = LoggerFactory.getLogger(JsonToDbMigrator.class);

    private static final String MIGRATION_KEY = "migration.v1";

    private static final String[] STORE_FILES = {
            "stages.json", "practices.json", "papers.json", "questions.json", "attempts.json",
            "followups.json", "knowledge_points.json", "memory_points.json", "mistakes.json",
            "notes.json", "reviews.json", "sources.json"
    };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PasswordEncoder encoder;
    private final UserRepository users;
    private final AesGcmCrypto crypto;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path storeDir;
    private final Path settingsFile;
    private final Path backupRoot;

    public JsonToDbMigrator(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                            PasswordEncoder encoder, UserRepository users, MasterKeyService masterKey,
                            @Value("${myreview.store-dir:./data/store}") String storeDir,
                            @Value("${myreview.settings-file:./data/settings.json}") String settingsFile,
                            @Value("${myreview.data-dir:E:/m_review}") String dataDir) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.encoder = encoder;
        this.users = users;
        this.crypto = new AesGcmCrypto(masterKey.key());
        this.storeDir = Path.of(storeDir);
        this.settingsFile = Path.of(settingsFile);
        this.backupRoot = Path.of(dataDir, "backup");
    }

    public void run() {
        if (migrated()) {
            log.info("已迁移过（app_meta 含 {}），跳过", MIGRATION_KEY);
            return;
        }
        log.warn("========== 一次性数据迁移开始：data/store JSON → PostgreSQL（归属 admin/888888） ==========");
        try {
            backup();
            User admin = ensureAdmin();
            migrateApiKey(admin);
            migrateStages(admin.getId());
            migratePractices(admin.getId());
            migratePapers(admin.getId());
            migrateQuestions(admin.getId());
            migrateAttempts(admin.getId());
            migrateFollowUps(admin.getId());
            migrateKnowledgePoints(admin.getId());
            migrateMemoryPoints(admin.getId());
            migrateMistakes(admin.getId());
            migrateNotes(admin.getId());
            migrateReviewDocs(admin.getId());
            migrateSources();
            mark();
            removeOriginals();
            log.warn("========== 迁移完成。旧文件已备份到 {}，原 data/ JSON 已删除 ==========", backupRoot);
        } catch (Throwable e) {
            // 连 Error 也兜住：迁移失败不能拖垮应用启动（NoClassDefFoundError 这类也能优雅降级）
            log.error("数据迁移失败（迁移器幂等，修复后重启可重跑；原 JSON 未删除）：{}", e.getMessage(), e);
        }
    }

    /* ================= 步骤 ================= */

    private boolean migrated() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM app_meta WHERE key = ?", Integer.class, MIGRATION_KEY);
        return n != null && n > 0;
    }

    /** ① 全量备份：data/store → backup/backup-<ts>/store/，settings.json 一并 */
    private void backup() throws IOException {
        Path dest = backupRoot.resolve("backup-" + System.currentTimeMillis());
        Path storeDest = dest.resolve("store");
        Files.createDirectories(storeDest);
        if (Files.isDirectory(storeDir)) {
            try (var stream = Files.list(storeDir)) {
                for (Path f : stream.toList()) {
                    if (Files.isRegularFile(f)) {
                        Files.copy(f, storeDest.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        if (Files.exists(settingsFile)) {
            Files.copy(settingsFile, dest.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("① 旧数据已备份到 {}", dest);
    }

    /** ② 建/取 admin 用户（幂等） */
    private User ensureAdmin() {
        var existing = users.findByUsername("admin");
        if (existing.isPresent()) {
            log.info("② admin 用户已存在，跳过创建");
            return existing.get();
        }
        long now = System.currentTimeMillis();
        User admin = new User();
        admin.setId(UUID.randomUUID().toString());
        admin.setUsername("admin");
        admin.setNickname("admin");
        admin.setPasswordHash(encoder.encode("888888"));
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        users.insert(admin);
        log.warn("② 已创建初始管理员 admin / 888888 —— 请登录后立即到个人中心修改密码");
        return admin;
    }

    /** ③ 旧 settings.json 的明文 API Key → 加密进 admin 行 */
    private void migrateApiKey(User admin) {
        if (!Files.exists(settingsFile)) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> s = mapper.readValue(settingsFile.toFile(), Map.class);
            Object key = s.get("apiKey");
            if (!(key instanceof String k) || k.isBlank()) {
                log.info("③ 旧 settings.json 无 API Key，跳过");
                return;
            }
            String trimmed = k.trim();
            AesGcmCrypto.Encrypted enc = crypto.encrypt(trimmed);
            admin.setApiKeyCipher(enc.cipherText());
            admin.setApiKeyNonce(enc.nonce());
            admin.setApiKeyVersion(AesGcmCrypto.VERSION);
            admin.setApiKeyLast4(trimmed.length() <= 4 ? trimmed : trimmed.substring(trimmed.length() - 4));
            admin.setUpdatedAt(System.currentTimeMillis());
            users.update(admin);
            log.info("③ 旧 API Key 已加密迁移到 admin 用户（AES-256-GCM，明文文件随后删除）");
        } catch (Exception e) {
            log.warn("③ 旧 settings.json 读取失败，API Key 留空继续：{}", e.getMessage());
        }
    }

    /** ④ 全部成功后写迁移标记 */
    private void mark() {
        jdbc.update("INSERT INTO app_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                MIGRATION_KEY, String.valueOf(System.currentTimeMillis()));
    }

    /** ⑤ 删除原 JSON（备份已在 backup/） */
    private void removeOriginals() throws IOException {
        for (String f : STORE_FILES) Files.deleteIfExists(storeDir.resolve(f));
        Files.deleteIfExists(settingsFile);
    }

    /* ================= 通用读入 ================= */

    private <T> List<T> read(String file, TypeReference<List<T>> type) throws IOException {
        Path f = storeDir.resolve(file);
        if (!Files.exists(f)) return List.of();
        return mapper.readValue(f.toFile(), type);
    }

    private <T> int insertAll(String table, List<T> rows, String sql, SqlSetter<T> setter) {
        if (rows.isEmpty()) return 0;
        return tx.execute(status -> {
            int n = 0;
            for (T row : rows) {
                n += jdbc.update(sql, setter.args(row));
            }
            return n;
        });
    }

    @FunctionalInterface
    private interface SqlSetter<T> {
        Object[] args(T row);
    }

    /* ================= 12 张表 ================= */

    private void migrateStages(String uid) throws IOException {
        List<Stage> rows = read("stages.json", new TypeReference<>() {});
        int n = insertAll("stage", rows,
                "INSERT INTO stage (id, user_id, name, status, summary_json, created_at, updated_at, completed_at) " +
                        "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
                s -> new Object[]{s.getId(), uid, s.getName(), s.getStatus(), s.getSummaryJson(),
                        s.getCreatedAt(), s.getUpdatedAt(), s.getCompletedAt()});
        log.info("阶段：{} 条（文件 {} 条）", n, rows.size());
    }

    private void migratePractices(String uid) throws IOException {
        List<Practice> rows = read("practices.json", new TypeReference<>() {});
        int n = insertAll("practice", rows,
                "INSERT INTO practice (id, user_id, stage_id, mode, topic, scope_mode, scope_text, level, intensity, " +
                        "paper_size, review, status, memory_json, notes_text, notes_updated_at, digest_json, " +
                        "created_at, updated_at, completed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO NOTHING",
                p -> new Object[]{p.getId(), uid, p.getStageId(), p.getMode(), p.getTopic(),
                        p.getScopeMode(), p.getScopeText(), p.getLevel(), p.getIntensity(), p.getPaperSize(),
                        p.isReview(), p.getStatus(), p.getMemoryJson(), p.getNotesText(), p.getNotesUpdatedAt(),
                        p.getDigestJson(), p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt()});
        log.info("练习：{} 条（文件 {} 条）", n, rows.size());
    }

    private void migratePapers(String uid) throws IOException {
        List<Paper> rows = read("papers.json", new TypeReference<>() {});
        int n = insertAll("paper", rows,
                "INSERT INTO paper (id, user_id, practice_id, sequence, kind, focus_points_json, composition_mode, " +
                        "status, summary_json, progress_json, plan_json, max_score, created_at, submitted_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
                p -> new Object[]{p.getId(), uid, p.getPracticeId(), p.getSequence(), p.getKind(),
                        p.getFocusPointsJson(), p.getCompositionMode(), p.getStatus(), p.getSummaryJson(),
                        p.getProgressJson(), p.getPlanJson(), p.getMaxScore(), p.getCreatedAt(), p.getSubmittedAt()});
        log.info("卷：{} 条（文件 {} 条）", n, rows.size());
    }

    private void migrateQuestions(String uid) throws IOException {
        List<Question> rows = read("questions.json", new TypeReference<>() {});
        int n = insertAll("question", rows,
                "INSERT INTO question (id, user_id, practice_id, paper_id, sequence, prompt, reference_answer, " +
                        "knowledge_points_json, demands_json, assigned_points_json, angle, tier, question_type, " +
                        "points, revealed_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO NOTHING",
                q -> new Object[]{q.getId(), uid, q.getPracticeId(), q.getPaperId(), q.getSequence(),
                        q.getPrompt(), q.getReferenceAnswer(), q.getKnowledgePointsJson(), q.getDemandsJson(),
                        q.getAssignedPointsJson(), q.getAngle(), q.getTier(), q.getQuestionType(),
                        q.getPoints(), q.getRevealedAt(), q.getCreatedAt()});
        log.info("题目：{} 条（文件 {} 条）", n, rows.size());
    }

    private void migrateAttempts(String uid) throws IOException {
        List<Attempt> rows = read("attempts.json", new TypeReference<>() {});
        int n = insertAll("attempt", rows,
                "INSERT INTO attempt (id, user_id, question_id, sequence, answer, score, points, feedback, " +
                        "dimensions_json, submitted_at, evaluated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO NOTHING",
                a -> new Object[]{a.getId(), uid, a.getQuestionId(), a.getSequence(), a.getAnswer(),
                        a.getScore(), a.getPoints(), a.getFeedback(), a.getDimensionsJson(),
                        a.getSubmittedAt(), a.getEvaluatedAt()});
        log.info("作答：{} 条（文件 {} 条）", n, rows.size());
    }

    private void migrateFollowUps(String uid) throws IOException {
        List<FollowUp> rows = read("followups.json", new TypeReference<>() {});
        int n = insertAll("follow_up", rows,
                "INSERT INTO follow_up (id, user_id, practice_id, question_id, role, content, created_at) " +
                        "VALUES (?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
                f -> new Object[]{f.getId(), uid, f.getPracticeId(), f.getQuestionId(), f.getRole(),
                        f.getContent(), f.getCreatedAt()});
        log.info("追问：{} 条（文件 {} 条）", n, rows.size());
    }

    private void migrateKnowledgePoints(String uid) throws IOException {
        List<KnowledgePoint> rows = read("knowledge_points.json", new TypeReference<>() {});
        int n = insertAll("knowledge_point", rows,
                "INSERT INTO knowledge_point (id, user_id, practice_id, category, name, source, in_scope, priority, " +
                        "evidence_json, bound_quick, bound_std, bound_deep, times_seen, history_json, angles_json, " +
                        "mastery, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO NOTHING",
                k -> new Object[]{k.getId(), uid, k.getPracticeId(), k.getCategory(), k.getName(),
                        k.getSource(), k.isInScope(), k.getPriority(), k.getEvidenceJson(),
                        k.getBoundQuick(), k.getBoundStd(), k.getBoundDeep(), k.getTimesSeen(),
                        k.getHistoryJson(), k.getAnglesJson(), k.getMastery(), k.getStatus(),
                        k.getCreatedAt(), k.getUpdatedAt()});
        log.info("知识点：{} 条（文件 {} 条）", n, rows.size());
    }

    private void migrateMemoryPoints(String uid) throws IOException {
        List<MemoryPoint> rows = read("memory_points.json", new TypeReference<>() {});
        int n = insertAll("memory_point", rows,
                "INSERT INTO memory_point (id, user_id, practice_id, question_id, point_names_json, text, created_at) " +
                        "VALUES (?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
                m -> new Object[]{m.getId(), uid, m.getPracticeId(), m.getQuestionId(),
                        m.getPointNamesJson(), m.getText(), m.getCreatedAt()});
        log.info("记忆点：{} 条（文件 {} 条）", n, rows.size());
    }

    private void migrateMistakes(String uid) throws IOException {
        List<Mistake> rows = read("mistakes.json", new TypeReference<>() {});
        int n = insertAll("mistake", rows,
                "INSERT INTO mistake (id, user_id, practice_id, question_id, source, reason, score, status, " +
                        "resolved_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO NOTHING",
                m -> new Object[]{m.getId(), uid, m.getPracticeId(), m.getQuestionId(), m.getSource(),
                        m.getReason(), m.getScore(), m.getStatus(), m.getResolvedAt(),
                        m.getCreatedAt(), m.getUpdatedAt()});
        log.info("错题：{} 条（文件 {} 条）", n, rows.size());
    }

    private void migrateNotes(String uid) throws IOException {
        List<Note> rows = read("notes.json", new TypeReference<>() {});
        int n = insertAll("note", rows,
                "INSERT INTO note (id, user_id, practice_id, question_id, scene, text, created_at, updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
                nt -> new Object[]{nt.getId(), uid, nt.getPracticeId(), nt.getQuestionId(), nt.getScene(),
                        nt.getText(), nt.getCreatedAt(), nt.getUpdatedAt()});
        log.info("笔记：{} 条（文件 {} 条）", n, rows.size());
    }

    private void migrateReviewDocs(String uid) throws IOException {
        List<ReviewDoc> rows = read("reviews.json", new TypeReference<>() {});
        int n = insertAll("review_doc", rows,
                "INSERT INTO review_doc (id, user_id, practice_id, status, content_json, progress_json, " +
                        "created_at, updated_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (id) DO NOTHING",
                d -> new Object[]{d.getId(), uid, d.getPracticeId(), d.getStatus(), d.getContentJson(),
                        d.getProgressJson(), d.getCreatedAt(), d.getUpdatedAt()});
        log.info("复习提纲：{} 条（文件 {} 条）", n, rows.size());
    }

    /** 资料缓存全局共享（无 user_id），多用户复用省抓取 */
    private void migrateSources() throws IOException {
        List<Source> rows = read("sources.json", new TypeReference<>() {});
        int n = insertAll("source", rows,
                "INSERT INTO source (id, url, title, text, fetched_at, error) VALUES (?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO NOTHING",
                s -> new Object[]{s.getId(), s.getUrl(), s.getTitle(), s.getText(), s.getFetchedAt(), s.getError()});
        log.info("资料缓存：{} 条（文件 {} 条）", n, rows.size());
    }
}
