package com.review.vt.repo.db;

import com.review.vt.domain.FollowUp;
import com.review.vt.repo.FollowUpRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 追问聊天表 PostgreSQL 实现。全部查询带 user_id（AuthContext）。 */
@Repository
@Profile("!json")
public class JdbcFollowUpRepository extends AbstractJdbcRepo implements FollowUpRepository {

    private static final String COLS = "id, user_id, practice_id, question_id, role, content, created_at";

    private static final RowMapper<FollowUp> MAPPER = (rs, i) -> {
        FollowUp f = new FollowUp();
        f.setId(rs.getLong("id"));
        f.setUserId(rs.getLong("user_id"));
        f.setPracticeId(rs.getLong("practice_id"));
        f.setQuestionId(rs.getLong("question_id"));
        f.setRole(rs.getString("role"));
        f.setContent(rs.getString("content"));
        f.setCreatedAt(rs.getLong("created_at"));
        return f;
    };

    public JdbcFollowUpRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public FollowUp save(FollowUp f) {
        saveRow("follow_up",
                new String[]{"user_id", "practice_id", "question_id", "role", "content", "created_at"},
                new Object[]{uidOf(f.getUserId()), f.getPracticeId(), f.getQuestionId(),
                        f.getRole(), f.getContent(), f.getCreatedAt()},
                f.getId(), f::setId);
        return f;
    }

    @Override
    public List<FollowUp> findByQuestionIdOrderByCreatedAtAsc(Long questionId) {
        return jdbc.query("SELECT " + COLS + " FROM follow_up WHERE user_id = ? AND question_id = ? ORDER BY created_at ASC",
                MAPPER, userId(), questionId);
    }

    @Override
    public List<FollowUp> findByPracticeId(Long practiceId) {
        return jdbc.query("SELECT " + COLS + " FROM follow_up WHERE user_id = ? AND practice_id = ?",
                MAPPER, userId(), practiceId);
    }

    @Override
    public long countByQuestionId(Long questionId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM follow_up WHERE user_id = ? AND question_id = ?",
                Long.class, userId(), questionId);
        return n == null ? 0 : n;
    }

    @Override
    public void deleteAll(List<FollowUp> followUps) {
        deleteByIds("follow_up", followUps.stream().map(FollowUp::getId).toList());
    }
}
