package com.review.vt.repo.db;

import com.review.vt.domain.Mistake;
import com.review.vt.repo.MistakeRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 错题本表 PostgreSQL 实现。全部查询带 user_id（AuthContext）。 */
@Repository
@Profile("!json")
public class JdbcMistakeRepository extends AbstractJdbcRepo implements MistakeRepository {

    private static final String COLS =
            "id, user_id, practice_id, question_id, source, reason, score, status, " +
            "resolved_at, created_at, updated_at";

    private static final RowMapper<Mistake> MAPPER = (rs, i) -> {
        Mistake m = new Mistake();
        m.setId(rs.getLong("id"));
        m.setUserId(rs.getLong("user_id"));
        m.setPracticeId(rs.getLong("practice_id"));
        m.setQuestionId(rs.getLong("question_id"));
        m.setSource(rs.getString("source"));
        m.setReason(rs.getString("reason"));
        double sc = rs.getDouble("score");
        m.setScore(rs.wasNull() ? null : sc);
        m.setStatus(rs.getString("status"));
        long ra = rs.getLong("resolved_at");
        m.setResolvedAt(rs.wasNull() ? null : ra);
        m.setCreatedAt(rs.getLong("created_at"));
        m.setUpdatedAt(rs.getLong("updated_at"));
        return m;
    };

    public JdbcMistakeRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Mistake> findById(Long id) {
        List<Mistake> list = jdbc.query("SELECT " + COLS + " FROM mistake WHERE id = ? AND user_id = ?",
                MAPPER, id, userId());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Mistake save(Mistake m) {
        saveRow("mistake",
                new String[]{"user_id", "practice_id", "question_id", "source", "reason", "score",
                        "status", "resolved_at", "created_at", "updated_at"},
                new Object[]{uidOf(m.getUserId()), m.getPracticeId(), m.getQuestionId(), m.getSource(),
                        m.getReason(), m.getScore(), m.getStatus(), m.getResolvedAt(),
                        m.getCreatedAt(), m.getUpdatedAt()},
                m.getId(), m::setId);
        return m;
    }

    @Override
    public List<Mistake> findByPracticeIdOrderByCreatedAtDesc(Long practiceId) {
        return jdbc.query("SELECT " + COLS + " FROM mistake WHERE user_id = ? AND practice_id = ? ORDER BY created_at DESC",
                MAPPER, userId(), practiceId);
    }

    @Override
    public void deleteAll(List<Mistake> mistakes) {
        deleteByIds("mistake", mistakes.stream().map(Mistake::getId).toList());
    }
}
