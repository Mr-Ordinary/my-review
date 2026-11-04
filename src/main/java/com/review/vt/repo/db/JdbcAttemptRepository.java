package com.review.vt.repo.db;

import com.review.vt.domain.Attempt;
import com.review.vt.repo.AttemptRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 作答表 PostgreSQL 实现。全部查询带 user_id（AuthContext）。 */
@Repository
@Profile("!json")
public class JdbcAttemptRepository extends AbstractJdbcRepo implements AttemptRepository {

    private static final String COLS =
            "id, user_id, question_id, sequence, answer, score, points, feedback, dimensions_json, " +
            "submitted_at, evaluated_at";

    private static final RowMapper<Attempt> MAPPER = (rs, i) -> {
        Attempt a = new Attempt();
        a.setId(rs.getString("id"));
        a.setUserId(rs.getString("user_id"));
        a.setQuestionId(rs.getString("question_id"));
        a.setSequence(rs.getInt("sequence"));
        a.setAnswer(rs.getString("answer"));
        double sc = rs.getDouble("score");
        a.setScore(rs.wasNull() ? null : sc);
        double pt = rs.getDouble("points");
        a.setPoints(rs.wasNull() ? null : pt);
        a.setFeedback(rs.getString("feedback"));
        a.setDimensionsJson(rs.getString("dimensions_json"));
        a.setSubmittedAt(rs.getLong("submitted_at"));
        long ea = rs.getLong("evaluated_at");
        a.setEvaluatedAt(rs.wasNull() ? null : ea);
        return a;
    };

    public JdbcAttemptRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Attempt save(Attempt a) {
        jdbc.update("INSERT INTO attempt (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO UPDATE SET user_id=EXCLUDED.user_id, question_id=EXCLUDED.question_id, " +
                        "sequence=EXCLUDED.sequence, answer=EXCLUDED.answer, score=EXCLUDED.score, " +
                        "points=EXCLUDED.points, feedback=EXCLUDED.feedback, dimensions_json=EXCLUDED.dimensions_json, " +
                        "submitted_at=EXCLUDED.submitted_at, evaluated_at=EXCLUDED.evaluated_at",
                a.getId(), uidOf(a.getUserId()), a.getQuestionId(), a.getSequence(), a.getAnswer(),
                a.getScore(), a.getPoints(), a.getFeedback(), a.getDimensionsJson(),
                a.getSubmittedAt(), a.getEvaluatedAt());
        return a;
    }

    @Override
    public List<Attempt> findByQuestionIdOrderBySequenceAsc(String questionId) {
        return jdbc.query("SELECT " + COLS + " FROM attempt WHERE user_id = ? AND question_id = ? ORDER BY sequence ASC",
                MAPPER, userId(), questionId);
    }

    @Override
    public void deleteAll(List<Attempt> attempts) {
        deleteByIds("attempt", attempts.stream().map(Attempt::getId).toList());
    }
}
