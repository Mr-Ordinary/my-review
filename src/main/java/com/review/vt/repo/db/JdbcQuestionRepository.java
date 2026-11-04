package com.review.vt.repo.db;

import com.review.vt.domain.Question;
import com.review.vt.repo.QuestionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 题目表 PostgreSQL 实现。全部查询带 user_id（AuthContext）。
 * 注意：JSON 版 findByPracticeIdOrderBySequenceAsc 实际按 createdAt 排（历史 bug），
 * 这里按方法名语义修正为 sequence 排序。
 */
@Repository
@Profile("!json")
public class JdbcQuestionRepository extends AbstractJdbcRepo implements QuestionRepository {

    private static final String COLS =
            "id, user_id, practice_id, paper_id, sequence, prompt, reference_answer, " +
            "knowledge_points_json, demands_json, assigned_points_json, angle, tier, question_type, " +
            "points, revealed_at, created_at";

    private static final RowMapper<Question> MAPPER = (rs, i) -> {
        Question q = new Question();
        q.setId(rs.getString("id"));
        q.setUserId(rs.getString("user_id"));
        q.setPracticeId(rs.getString("practice_id"));
        q.setPaperId(rs.getString("paper_id"));
        q.setSequence(rs.getInt("sequence"));
        q.setPrompt(rs.getString("prompt"));
        q.setReferenceAnswer(rs.getString("reference_answer"));
        q.setKnowledgePointsJson(rs.getString("knowledge_points_json"));
        q.setDemandsJson(rs.getString("demands_json"));
        q.setAssignedPointsJson(rs.getString("assigned_points_json"));
        q.setAngle(rs.getString("angle"));
        q.setTier(rs.getString("tier"));
        q.setQuestionType(rs.getString("question_type"));
        q.setPoints(rs.getInt("points"));
        long ra = rs.getLong("revealed_at");
        q.setRevealedAt(rs.wasNull() ? null : ra);
        q.setCreatedAt(rs.getLong("created_at"));
        return q;
    };

    public JdbcQuestionRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Question> findById(String id) {
        List<Question> list = jdbc.query("SELECT " + COLS + " FROM question WHERE id = ? AND user_id = ?",
                MAPPER, id, userId());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Question save(Question q) {
        jdbc.update("INSERT INTO question (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO UPDATE SET user_id=EXCLUDED.user_id, practice_id=EXCLUDED.practice_id, " +
                        "paper_id=EXCLUDED.paper_id, sequence=EXCLUDED.sequence, prompt=EXCLUDED.prompt, " +
                        "reference_answer=EXCLUDED.reference_answer, knowledge_points_json=EXCLUDED.knowledge_points_json, " +
                        "demands_json=EXCLUDED.demands_json, assigned_points_json=EXCLUDED.assigned_points_json, " +
                        "angle=EXCLUDED.angle, tier=EXCLUDED.tier, question_type=EXCLUDED.question_type, " +
                        "points=EXCLUDED.points, revealed_at=EXCLUDED.revealed_at, created_at=EXCLUDED.created_at",
                q.getId(), uidOf(q.getUserId()), q.getPracticeId(), q.getPaperId(), q.getSequence(),
                q.getPrompt(), q.getReferenceAnswer(), q.getKnowledgePointsJson(), q.getDemandsJson(),
                q.getAssignedPointsJson(), q.getAngle(), q.getTier(), q.getQuestionType(),
                q.getPoints(), q.getRevealedAt(), q.getCreatedAt());
        return q;
    }

    @Override
    public List<Question> findByPaperIdOrderBySequenceAsc(String paperId) {
        return jdbc.query("SELECT " + COLS + " FROM question WHERE user_id = ? AND paper_id = ? ORDER BY sequence ASC",
                MAPPER, userId(), paperId);
    }

    @Override
    public List<Question> findByPracticeIdOrderBySequenceAsc(String practiceId) {
        return jdbc.query("SELECT " + COLS + " FROM question WHERE user_id = ? AND practice_id = ? ORDER BY sequence ASC",
                MAPPER, userId(), practiceId);
    }

    @Override
    public long countByPaperId(String paperId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM question WHERE user_id = ? AND paper_id = ?",
                Long.class, userId(), paperId);
        return n == null ? 0 : n;
    }

    @Override
    public void deleteAll(List<Question> questions) {
        deleteByIds("question", questions.stream().map(Question::getId).toList());
    }
}
