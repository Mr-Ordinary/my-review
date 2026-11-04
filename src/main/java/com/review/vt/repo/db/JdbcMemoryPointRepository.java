package com.review.vt.repo.db;

import com.review.vt.domain.MemoryPoint;
import com.review.vt.repo.MemoryPointRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 记忆点表 PostgreSQL 实现。全部查询带 user_id（AuthContext）。 */
@Repository
@Profile("!json")
public class JdbcMemoryPointRepository extends AbstractJdbcRepo implements MemoryPointRepository {

    private static final String COLS =
            "id, user_id, practice_id, question_id, point_names_json, text, created_at";

    private static final RowMapper<MemoryPoint> MAPPER = (rs, i) -> {
        MemoryPoint m = new MemoryPoint();
        m.setId(rs.getString("id"));
        m.setUserId(rs.getString("user_id"));
        m.setPracticeId(rs.getString("practice_id"));
        m.setQuestionId(rs.getString("question_id"));
        m.setPointNamesJson(rs.getString("point_names_json"));
        m.setText(rs.getString("text"));
        m.setCreatedAt(rs.getLong("created_at"));
        return m;
    };

    public JdbcMemoryPointRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<MemoryPoint> findById(String id) {
        List<MemoryPoint> list = jdbc.query("SELECT " + COLS + " FROM memory_point WHERE id = ? AND user_id = ?",
                MAPPER, id, userId());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public MemoryPoint save(MemoryPoint m) {
        jdbc.update("INSERT INTO memory_point (" + COLS + ") VALUES (?,?,?,?,?,?,?) " +
                        "ON CONFLICT (id) DO UPDATE SET user_id=EXCLUDED.user_id, practice_id=EXCLUDED.practice_id, " +
                        "question_id=EXCLUDED.question_id, point_names_json=EXCLUDED.point_names_json, " +
                        "text=EXCLUDED.text, created_at=EXCLUDED.created_at",
                m.getId(), uidOf(m.getUserId()), m.getPracticeId(), m.getQuestionId(),
                m.getPointNamesJson(), m.getText(), m.getCreatedAt());
        return m;
    }

    @Override
    public List<MemoryPoint> findByPracticeIdOrderByCreatedAtDesc(String practiceId) {
        return jdbc.query("SELECT " + COLS + " FROM memory_point WHERE user_id = ? AND practice_id = ? ORDER BY created_at DESC",
                MAPPER, userId(), practiceId);
    }

    @Override
    public void deleteAll(List<MemoryPoint> points) {
        deleteByIds("memory_point", points.stream().map(MemoryPoint::getId).toList());
    }
}
