package com.review.vt.service.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * schema v3：把旧 review_doc.content_json（整份大 JSON）拆进 review_card（一行一卡）。
 *
 * <p>幂等：已写 schema.v3 标记则跳过；按 (review_doc_id, point_name) 唯一键去重。
 * 迁移后 content_json 作为遗留列保留（不删，防止回滚需要），应用不再读写它。
 */
@Service
public class ReviewCardMigrator {

    private static final Logger log = LoggerFactory.getLogger(ReviewCardMigrator.class);
    private static final String MARK = "schema.v3";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReviewCardMigrator(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public void run() {
        Integer marked = jdbc.queryForObject("SELECT COUNT(*) FROM app_meta WHERE key = ?", Integer.class, MARK);
        if (marked != null && marked > 0) {
            log.info("schema v3 已迁移过，跳过");
            return;
        }
        // review_doc 可能还不存在（极端情况），直接跳过
        Integer hasTable = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'review_doc'", Integer.class);
        if (hasTable == null || hasTable == 0) return;

        List<Map<String, Object>> docs = jdbc.queryForList(
                "SELECT id, user_id, content_json, created_at, updated_at FROM review_doc " +
                        "WHERE content_json IS NOT NULL AND content_json <> ''");
        if (docs.isEmpty()) {
            mark();
            log.info("schema v3：没有需要拆分的复习提纲，写入标记");
            return;
        }

        log.warn("========== schema v3 迁移开始：复习提纲 content_json → review_card ==========");
        try {
            tx.executeWithoutResult(status -> {
                int cards = 0;
                for (Map<String, Object> doc : docs) {
                    Long docId = ((Number) doc.get("id")).longValue();
                    Long userId = ((Number) doc.get("user_id")).longValue();
                    long createdAt = doc.get("created_at") == null ? System.currentTimeMillis() : ((Number) doc.get("created_at")).longValue();
                    long updatedAt = doc.get("updated_at") == null ? createdAt : ((Number) doc.get("updated_at")).longValue();
                    JsonNode root;
                    try {
                        root = mapper.readTree(String.valueOf(doc.get("content_json")));
                    } catch (Exception e) {
                        log.warn("复习提纲 {} 的 content_json 解析失败，跳过：{}", docId, e.getMessage());
                        continue;
                    }
                    int seq = 0;
                    for (JsonNode card : root.path("points")) {
                        String name = card.path("name").asText("").trim();
                        if (name.isBlank()) name = "未命名点-" + (seq + 1);
                        int n = jdbc.update(
                                "INSERT INTO review_card (user_id, review_doc_id, point_name, category, priority, " +
                                        "content_json, sequence, created_at, updated_at) " +
                                        "VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (review_doc_id, point_name) DO NOTHING",
                                userId, docId, name,
                                card.path("category").asText(null), card.path("priority").asText(null),
                                card.toString(), seq, createdAt, updatedAt);
                        cards += n;
                        seq++;
                    }
                }
                mark();
                log.warn("========== schema v3 迁移完成：{} 份提纲，写入 {} 张卡 ==========", docs.size(), cards);
            });
        } catch (Exception e) {
            log.error("schema v3 迁移失败，已回滚（旧 content_json 仍在，可重试）：{}", e.getMessage(), e);
        }
    }

    private void mark() {
        jdbc.update("INSERT INTO app_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                MARK, String.valueOf(System.currentTimeMillis()));
    }
}
