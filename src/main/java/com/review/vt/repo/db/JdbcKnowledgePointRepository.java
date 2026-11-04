package com.review.vt.repo.db;

import com.review.vt.domain.KnowledgePoint;
import com.review.vt.repo.KnowledgePointRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 知识点表 PostgreSQL 实现。全部查询带 user_id（AuthContext）。 */
@Repository
@Profile("!json")
public class JdbcKnowledgePointRepository extends AbstractJdbcRepo implements KnowledgePointRepository {

    private static final String COLS =
            "id, user_id, practice_id, category, name, source, in_scope, priority, evidence_json, " +
            "bound_quick, bound_std, bound_deep, times_seen, history_json, angles_json, mastery, " +
            "status, created_at, updated_at";

    private static final RowMapper<KnowledgePoint> MAPPER = (rs, i) -> {
        KnowledgePoint k = new KnowledgePoint();
        k.setId(rs.getString("id"));
        k.setUserId(rs.getString("user_id"));
        k.setPracticeId(rs.getString("practice_id"));
        k.setCategory(rs.getString("category"));
        k.setName(rs.getString("name"));
        k.setSource(rs.getString("source"));
        k.setInScope(rs.getBoolean("in_scope"));
        k.setPriority(rs.getString("priority"));
        k.setEvidenceJson(rs.getString("evidence_json"));
        k.setBoundQuick(rs.getString("bound_quick"));
        k.setBoundStd(rs.getString("bound_std"));
        k.setBoundDeep(rs.getString("bound_deep"));
        k.setTimesSeen(rs.getInt("times_seen"));
        k.setHistoryJson(rs.getString("history_json"));
        k.setAnglesJson(rs.getString("angles_json"));
        k.setMastery(rs.getDouble("mastery"));
        k.setStatus(rs.getString("status"));
        k.setCreatedAt(rs.getLong("created_at"));
        k.setUpdatedAt(rs.getLong("updated_at"));
        return k;
    };

    public JdbcKnowledgePointRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<KnowledgePoint> findById(String id) {
        List<KnowledgePoint> list = jdbc.query("SELECT " + COLS + " FROM knowledge_point WHERE id = ? AND user_id = ?",
                MAPPER, id, userId());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public KnowledgePoint save(KnowledgePoint k) {
        jdbc.update("INSERT INTO knowledge_point (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO UPDATE SET user_id=EXCLUDED.user_id, practice_id=EXCLUDED.practice_id, " +
                        "category=EXCLUDED.category, name=EXCLUDED.name, source=EXCLUDED.source, " +
                        "in_scope=EXCLUDED.in_scope, priority=EXCLUDED.priority, evidence_json=EXCLUDED.evidence_json, " +
                        "bound_quick=EXCLUDED.bound_quick, bound_std=EXCLUDED.bound_std, bound_deep=EXCLUDED.bound_deep, " +
                        "times_seen=EXCLUDED.times_seen, history_json=EXCLUDED.history_json, " +
                        "angles_json=EXCLUDED.angles_json, mastery=EXCLUDED.mastery, status=EXCLUDED.status, " +
                        "created_at=EXCLUDED.created_at, updated_at=EXCLUDED.updated_at",
                k.getId(), uidOf(k.getUserId()), k.getPracticeId(), k.getCategory(), k.getName(),
                k.getSource(), k.isInScope(), k.getPriority(), k.getEvidenceJson(),
                k.getBoundQuick(), k.getBoundStd(), k.getBoundDeep(), k.getTimesSeen(),
                k.getHistoryJson(), k.getAnglesJson(), k.getMastery(), k.getStatus(),
                k.getCreatedAt(), k.getUpdatedAt());
        return k;
    }

    @Override
    public List<KnowledgePoint> findByPracticeIdOrderByCreatedAtAsc(String practiceId) {
        return jdbc.query("SELECT " + COLS + " FROM knowledge_point WHERE user_id = ? AND practice_id = ? ORDER BY created_at ASC",
                MAPPER, userId(), practiceId);
    }

    @Override
    public void deleteAll(List<KnowledgePoint> points) {
        deleteByIds("knowledge_point", points.stream().map(KnowledgePoint::getId).toList());
    }
}
