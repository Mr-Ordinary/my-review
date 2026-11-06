package com.review.vt.service.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次性 schema v2 迁移：把 v1 的 UUID 主键表迁移成自增 BIGINT。
 *
 * <p>步骤（整体一个事务，失败回滚）：
 * <ol>
 *   <li>检测：已存在 app_meta('schema.v2') → 已完成；stage.id 已是 bigint → 新库，只写标记</li>
 *   <li>把旧表改名为 v1_*，重新执行 schema.sql 建新表</li>
 *   <li>按依赖顺序导入：users → stage → practice → paper → question → attempt →
 *       follow_up / knowledge_point / memory_point / mistake / note / review_doc / source，
 *       同时建立旧 UUID → 新 BIGINT 的映射并重写所有外键</li>
 *   <li>删除 v1_* 表，写 schema.v2 标记</li>
 * </ol>
 */
@Service
public class SchemaV2Migrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaV2Migrator.class);
    private static final String MARK = "schema.v2";

    /** 旧表 → 新表名（顺序即导入顺序；source 无外键，最后） */
    private static final String[] TABLES = {
            "users", "stage", "practice", "paper", "question", "attempt",
            "follow_up", "knowledge_point", "memory_point", "mistake", "note", "review_doc", "source"
    };

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private final java.nio.file.Path avatarDir;

    public SchemaV2Migrator(JdbcTemplate jdbc, DataSource dataSource,
                            PlatformTransactionManager txManager,
                            @org.springframework.beans.factory.annotation.Value("${myreview.data-dir:E:/m_review}") String dataDir) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.tx = new TransactionTemplate(txManager);
        this.avatarDir = java.nio.file.Path.of(dataDir, "avatars");
    }

    public void run() {
        if (marked()) {
            log.info("schema v2 已迁移过，跳过");
            return;
        }
        String idType = idTypeOf("stage");
        if (idType == null) {
            log.warn("找不到 stage 表，schema 未初始化，跳过 v2 迁移");
            return;
        }
        if (!"character varying".equalsIgnoreCase(idType)) {
            mark();
            log.info("数据库已是 BIGINT 主键（{}），写入 schema.v2 标记", idType);
            return;
        }

        log.warn("========== schema v2 迁移开始：UUID 主键 → 自增 BIGINT ==========");
        backupOldTables();
        try {
            tx.executeWithoutResult(status -> {
                try {
                    renameOldTables();
                    createNewTables();
                    migrateData();
                    dropOldTables();
                    // 旧索引名在 v1_* 表上占着（Postgres 索引名是 schema 级），
                    // 删掉旧表后再执行一次 schema.sql 把新表的二级索引补齐
                    createNewTables();
                    mark();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            log.warn("========== schema v2 迁移完成（数据已保留，旧表已删除） ==========");
        } catch (Exception e) {
            log.error("schema v2 迁移失败，已回滚（旧表仍在，修复后可重启重跑）：{}", e.getMessage(), e);
        }
    }

    /* ================= 检测 / 标记 ================= */

    private boolean marked() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM app_meta WHERE key = ?", Integer.class, MARK);
        return n != null && n > 0;
    }

    private String idTypeOf(String table) {
        List<String> types = jdbc.queryForList(
                "SELECT data_type FROM information_schema.columns WHERE table_name = ? AND column_name = 'id'",
                String.class, table);
        return types.isEmpty() ? null : types.get(0);
    }

    private void mark() {
        jdbc.update("INSERT INTO app_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                MARK, String.valueOf(System.currentTimeMillis()));
    }

    /**
     * 迁移前把旧表完整复制到 v2_backup schema（事务外执行）。
     * 万一迁移失败/需要回滚，数据还在；迁移成功后可手动 DROP SCHEMA v2_backup CASCADE。
     */
    private void backupOldTables() {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS v2_backup");
        int copied = 0;
        for (String t : TABLES) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
                    Integer.class, t);
            if (n != null && n > 0) {
                jdbc.execute("DROP TABLE IF EXISTS v2_backup." + t);
                jdbc.execute("CREATE TABLE v2_backup." + t + " AS TABLE " + t);
                copied++;
            }
        }
        log.warn("迁移前备份完成：{} 张表已复制到 schema v2_backup", copied);
    }

    /* ================= 建表 / 改表 ================= */

    private void renameOldTables() {
        for (String t : TABLES) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, t);
            if (n != null && n > 0) {
                jdbc.execute("ALTER TABLE " + t + " RENAME TO v1_" + t);
                log.info("旧表 {} → v1_{}", t, t);
            }
        }
    }

    private void createNewTables() throws Exception {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    private void dropOldTables() {
        for (int i = TABLES.length - 1; i >= 0; i--) {
            jdbc.execute("DROP TABLE IF EXISTS v1_" + TABLES[i] + " CASCADE");
        }
    }

    /* ================= 数据导入 ================= */

    private void migrateData() {
        Map<String, Long> userIds = new HashMap<>();
        Map<String, Long> stageIds = new HashMap<>();
        Map<String, Long> practiceIds = new HashMap<>();
        Map<String, Long> paperIds = new HashMap<>();
        Map<String, Long> questionIds = new HashMap<>();

        // users
        for (Map<String, Object> r : rows("v1_users")) {
            Long id = insertReturningId(
                    "INSERT INTO users (username, password_hash, nickname, avatar_ext, api_key_cipher, " +
                            "api_key_nonce, api_key_version, api_key_last4, api_base, model, api_format, " +
                            "temperature, summary_json, summary_at, summary_status, summary_error, " +
                            "summary_started_at, created_at, updated_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    r.get("username"), r.get("password_hash"), r.get("nickname"), r.get("avatar_ext"),
                    r.get("api_key_cipher"), r.get("api_key_nonce"), r.get("api_key_version"), r.get("api_key_last4"),
                    r.get("api_base"), r.get("model"), r.get("api_format"), r.get("temperature"),
                    r.get("summary_json"), r.get("summary_at"), r.get("summary_status"), r.get("summary_error"),
                    r.get("summary_started_at"), r.get("created_at"), r.get("updated_at"));
            userIds.put(str(r.get("id")), id);
            copyAvatar(str(r.get("id")), id, str(r.get("avatar_ext")));
        }
        log.info("users 迁移 {} 条", userIds.size());

        // stage
        int n = 0;
        for (Map<String, Object> r : rows("v1_stage")) {
            Long id = insertReturningId(
                    "INSERT INTO stage (user_id, name, status, summary_json, created_at, updated_at, completed_at) " +
                            "VALUES (?,?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), r.get("name"), r.get("status"), r.get("summary_json"),
                    r.get("created_at"), r.get("updated_at"), r.get("completed_at"));
            stageIds.put(str(r.get("id")), id);
            n++;
        }
        log.info("stage 迁移 {} 条", n);

        // practice
        n = 0;
        for (Map<String, Object> r : rows("v1_practice")) {
            Long id = insertReturningId(
                    "INSERT INTO practice (user_id, stage_id, mode, topic, scope_mode, scope_text, level, " +
                            "intensity, paper_size, review, status, memory_json, notes_text, notes_updated_at, " +
                            "digest_json, created_at, updated_at, completed_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), stageIds.get(str(r.get("stage_id"))),
                    r.get("mode"), r.get("topic"), r.get("scope_mode"), r.get("scope_text"), r.get("level"),
                    r.get("intensity"), r.get("paper_size"), r.get("review"), r.get("status"),
                    r.get("memory_json"), r.get("notes_text"), r.get("notes_updated_at"),
                    r.get("digest_json"), r.get("created_at"), r.get("updated_at"), r.get("completed_at"));
            practiceIds.put(str(r.get("id")), id);
            n++;
        }
        log.info("practice 迁移 {} 条", n);

        // paper
        n = 0;
        for (Map<String, Object> r : rows("v1_paper")) {
            Long id = insertReturningId(
                    "INSERT INTO paper (user_id, practice_id, sequence, kind, focus_points_json, " +
                            "composition_mode, status, summary_json, progress_json, plan_json, max_score, " +
                            "created_at, submitted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), practiceIds.get(str(r.get("practice_id"))),
                    r.get("sequence"), r.get("kind"), r.get("focus_points_json"), r.get("composition_mode"),
                    r.get("status"), r.get("summary_json"), r.get("progress_json"), r.get("plan_json"),
                    r.get("max_score"), r.get("created_at"), r.get("submitted_at"));
            paperIds.put(str(r.get("id")), id);
            n++;
        }
        log.info("paper 迁移 {} 条", n);

        // question
        n = 0;
        for (Map<String, Object> r : rows("v1_question")) {
            Long id = insertReturningId(
                    "INSERT INTO question (user_id, practice_id, paper_id, sequence, prompt, reference_answer, " +
                            "knowledge_points_json, demands_json, assigned_points_json, angle, tier, " +
                            "question_type, points, revealed_at, created_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), practiceIds.get(str(r.get("practice_id"))),
                    paperIds.get(str(r.get("paper_id"))), r.get("sequence"), r.get("prompt"),
                    r.get("reference_answer"), r.get("knowledge_points_json"), r.get("demands_json"),
                    r.get("assigned_points_json"), r.get("angle"), r.get("tier"), r.get("question_type"),
                    r.get("points"), r.get("revealed_at"), r.get("created_at"));
            questionIds.put(str(r.get("id")), id);
            n++;
        }
        log.info("question 迁移 {} 条", n);

        // attempt
        n = 0;
        for (Map<String, Object> r : rows("v1_attempt")) {
            insertReturningId(
                    "INSERT INTO attempt (user_id, question_id, sequence, answer, score, points, feedback, " +
                            "dimensions_json, submitted_at, evaluated_at) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), questionIds.get(str(r.get("question_id"))),
                    r.get("sequence"), r.get("answer"), r.get("score"), r.get("points"), r.get("feedback"),
                    r.get("dimensions_json"), r.get("submitted_at"), r.get("evaluated_at"));
            n++;
        }
        log.info("attempt 迁移 {} 条", n);

        // follow_up
        n = 0;
        for (Map<String, Object> r : rows("v1_follow_up")) {
            insertReturningId(
                    "INSERT INTO follow_up (user_id, practice_id, question_id, role, content, created_at) " +
                            "VALUES (?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), practiceIds.get(str(r.get("practice_id"))),
                    questionIds.get(str(r.get("question_id"))), r.get("role"), r.get("content"),
                    r.get("created_at"));
            n++;
        }
        log.info("follow_up 迁移 {} 条", n);

        // knowledge_point
        n = 0;
        for (Map<String, Object> r : rows("v1_knowledge_point")) {
            insertReturningId(
                    "INSERT INTO knowledge_point (user_id, practice_id, category, name, source, in_scope, " +
                            "priority, evidence_json, bound_quick, bound_std, bound_deep, times_seen, " +
                            "history_json, angles_json, mastery, status, created_at, updated_at) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), practiceIds.get(str(r.get("practice_id"))),
                    r.get("category"), r.get("name"), r.get("source"), r.get("in_scope"),
                    r.get("priority"), r.get("evidence_json"), r.get("bound_quick"), r.get("bound_std"),
                    r.get("bound_deep"), r.get("times_seen"), r.get("history_json"), r.get("angles_json"),
                    r.get("mastery"), r.get("status"), r.get("created_at"), r.get("updated_at"));
            n++;
        }
        log.info("knowledge_point 迁移 {} 条", n);

        // memory_point
        n = 0;
        for (Map<String, Object> r : rows("v1_memory_point")) {
            insertReturningId(
                    "INSERT INTO memory_point (user_id, practice_id, question_id, point_names_json, text, created_at) " +
                            "VALUES (?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), practiceIds.get(str(r.get("practice_id"))),
                    questionIds.get(str(r.get("question_id"))), r.get("point_names_json"), r.get("text"),
                    r.get("created_at"));
            n++;
        }
        log.info("memory_point 迁移 {} 条", n);

        // mistake
        n = 0;
        for (Map<String, Object> r : rows("v1_mistake")) {
            insertReturningId(
                    "INSERT INTO mistake (user_id, practice_id, question_id, source, reason, score, status, " +
                            "resolved_at, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), practiceIds.get(str(r.get("practice_id"))),
                    questionIds.get(str(r.get("question_id"))), r.get("source"), r.get("reason"),
                    r.get("score"), r.get("status"), r.get("resolved_at"), r.get("created_at"),
                    r.get("updated_at"));
            n++;
        }
        log.info("mistake 迁移 {} 条", n);

        // note
        n = 0;
        for (Map<String, Object> r : rows("v1_note")) {
            insertReturningId(
                    "INSERT INTO note (user_id, practice_id, question_id, scene, text, created_at, updated_at) " +
                            "VALUES (?,?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), practiceIds.get(str(r.get("practice_id"))),
                    questionIds.get(str(r.get("question_id"))), r.get("scene"), r.get("text"),
                    r.get("created_at"), r.get("updated_at"));
            n++;
        }
        log.info("note 迁移 {} 条", n);

        // review_doc
        n = 0;
        for (Map<String, Object> r : rows("v1_review_doc")) {
            insertReturningId(
                    "INSERT INTO review_doc (user_id, practice_id, status, content_json, progress_json, " +
                            "created_at, updated_at) VALUES (?,?,?,?,?,?,?) RETURNING id",
                    userIds.get(str(r.get("user_id"))), practiceIds.get(str(r.get("practice_id"))),
                    r.get("status"), r.get("content_json"), r.get("progress_json"),
                    r.get("created_at"), r.get("updated_at"));
            n++;
        }
        log.info("review_doc 迁移 {} 条", n);

        // source（全局缓存：旧主键是 URL，新主键自增，url 唯一）
        n = 0;
        for (Map<String, Object> r : rows("v1_source")) {
            insertReturningId(
                    "INSERT INTO source (url, title, text, fetched_at, error) VALUES (?,?,?,?,?) " +
                            "ON CONFLICT (url) DO NOTHING RETURNING id",
                    r.get("url"), r.get("title"), r.get("text"), r.get("fetched_at"), r.get("error"));
            n++;
        }
        log.info("source 迁移 {} 条", n);
    }

    /** 头像文件是按用户 id 命名的：UUID 文件名要改成新的自增 id 文件名 */
    private void copyAvatar(String oldUserId, Long newUserId, String ext) {
        if (oldUserId == null || ext == null || ext.isBlank()) return;
        try {
            java.nio.file.Path oldFile = avatarDir.resolve(oldUserId + "." + ext);
            java.nio.file.Path newFile = avatarDir.resolve(newUserId + "." + ext);
            if (java.nio.file.Files.isRegularFile(oldFile)) {
                java.nio.file.Files.copy(oldFile, newFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("头像迁移：{}.{} → {}.{}", oldUserId, ext, newUserId, ext);
            }
        } catch (Exception e) {
            log.warn("头像文件迁移失败（不影响数据）：{}", e.getMessage());
        }
    }

    /* ================= 工具 ================= */

    private List<Map<String, Object>> rows(String table) {
        return jdbc.queryForList("SELECT * FROM " + table);
    }

    private Long insertReturningId(String sql, Object... args) {
        try {
            return jdbc.queryForObject(sql, Long.class, args);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            // ON CONFLICT DO NOTHING 时没有返回行（source 重复）
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
