package com.review.vt.repo.db;

import com.review.vt.domain.Stage;
import com.review.vt.repo.StageRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 阶段表 PostgreSQL 实现。全部查询带 user_id（AuthContext），跨用户数据天然不可见。 */
@Repository
@Profile("!json")
public class JdbcStageRepository extends AbstractJdbcRepo implements StageRepository {

    private static final String COLS =
            "id, user_id, name, status, summary_json, created_at, updated_at, completed_at";

    private static final RowMapper<Stage> MAPPER = (rs, i) -> {
        Stage s = new Stage();
        s.setId(rs.getLong("id"));
        s.setUserId(rs.getLong("user_id"));
        s.setName(rs.getString("name"));
        s.setStatus(rs.getString("status"));
        s.setSummaryJson(rs.getString("summary_json"));
        s.setCreatedAt(rs.getLong("created_at"));
        s.setUpdatedAt(rs.getLong("updated_at"));
        long ca = rs.getLong("completed_at");
        s.setCompletedAt(rs.wasNull() ? null : ca);
        return s;
    };

    public JdbcStageRepository(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Stage> findById(Long id) {
        List<Stage> list = jdbc.query("SELECT " + COLS + " FROM stage WHERE id = ? AND user_id = ?",
                MAPPER, id, userId());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public boolean existsById(Long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM stage WHERE id = ? AND user_id = ?",
                Integer.class, id, userId());
        return n != null && n > 0;
    }

    @Override
    public Stage save(Stage s) {
        saveRow("stage",
                new String[]{"user_id", "name", "status", "summary_json", "created_at", "updated_at", "completed_at"},
                new Object[]{uidOf(s.getUserId()), s.getName(), s.getStatus(), s.getSummaryJson(),
                        s.getCreatedAt(), s.getUpdatedAt(), s.getCompletedAt()},
                s.getId(), s::setId);
        return s;
    }

    @Override
    public void delete(Stage s) {
        jdbc.update("DELETE FROM stage WHERE id = ? AND user_id = ?", s.getId(), userId());
    }

    @Override
    public List<Stage> findAllByOrderByUpdatedAtDesc() {
        return jdbc.query("SELECT " + COLS + " FROM stage WHERE user_id = ? ORDER BY updated_at DESC",
                MAPPER, userId());
    }
}
