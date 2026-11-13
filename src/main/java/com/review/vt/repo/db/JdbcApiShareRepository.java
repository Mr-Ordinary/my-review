package com.review.vt.repo.db;

import com.review.vt.domain.ApiShare;
import com.review.vt.repo.ApiShareRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * llm_share 表 PostgreSQL 实现。共享关系跨用户，不走 AuthContext 过滤。
 */
@Repository
@Profile("!json")
public class JdbcApiShareRepository implements ApiShareRepository {

    private static final String COLS = "id, config_id, owner_user_id, target_user_id, created_at";

    private static final RowMapper<ApiShare> MAPPER = (rs, i) -> {
        ApiShare s = new ApiShare();
        s.setId(rs.getLong("id"));
        s.setConfigId(rs.getLong("config_id"));
        s.setOwnerUserId(rs.getLong("owner_user_id"));
        s.setTargetUserId(rs.getLong("target_user_id"));
        s.setCreatedAt(rs.getLong("created_at"));
        return s;
    };

    private final JdbcTemplate jdbc;

    public JdbcApiShareRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ApiShare> findByConfigIdAndTargetUserId(Long configId, Long targetUserId) {
        List<ApiShare> list = jdbc.query(
                "SELECT " + COLS + " FROM llm_share WHERE config_id = ? AND target_user_id = ?",
                MAPPER, configId, targetUserId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<ApiShare> findByConfigId(Long configId) {
        return jdbc.query("SELECT " + COLS + " FROM llm_share WHERE config_id = ? ORDER BY created_at ASC",
                MAPPER, configId);
    }

    @Override
    public List<ApiShare> findByTargetUserId(Long targetUserId) {
        return jdbc.query("SELECT " + COLS + " FROM llm_share WHERE target_user_id = ? ORDER BY created_at ASC",
                MAPPER, targetUserId);
    }

    @Override
    public ApiShare insert(ApiShare s) {
        Long id = jdbc.queryForObject("INSERT INTO llm_share (config_id, owner_user_id, target_user_id, created_at) " +
                        "VALUES (?,?,?,?) RETURNING id",
                Long.class, s.getConfigId(), s.getOwnerUserId(), s.getTargetUserId(), s.getCreatedAt());
        s.setId(id);
        return s;
    }

    @Override
    public void deleteById(Long id) {
        jdbc.update("DELETE FROM llm_share WHERE id = ?", id);
    }

    @Override
    public void deleteByConfigId(Long configId) {
        jdbc.update("DELETE FROM llm_share WHERE config_id = ?", configId);
    }
}
