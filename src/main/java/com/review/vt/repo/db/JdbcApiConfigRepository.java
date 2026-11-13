package com.review.vt.repo.db;

import com.review.vt.domain.ApiConfig;
import com.review.vt.repo.ApiConfigRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * llm_config 表 PostgreSQL 实现。共享场景跨用户读配置，不走 AuthContext 过滤（同 users 表）。
 * api_key_cipher / api_key_nonce 只在此处与加密服务之间流转，绝不出后端。
 */
@Repository
@Profile("!json")
public class JdbcApiConfigRepository implements ApiConfigRepository {

    private static final String INSERT_COLS =
            "user_id, name, api_key_cipher, api_key_nonce, api_key_version, api_key_last4, " +
            "api_base, model, api_format, temperature, created_at, updated_at";

    private static final String COLS = "id, " + INSERT_COLS;

    private static final RowMapper<ApiConfig> MAPPER = (rs, i) -> {
        ApiConfig c = new ApiConfig();
        c.setId(rs.getLong("id"));
        c.setUserId(rs.getLong("user_id"));
        c.setName(rs.getString("name"));
        c.setApiKeyCipher(rs.getString("api_key_cipher"));
        c.setApiKeyNonce(rs.getString("api_key_nonce"));
        c.setApiKeyVersion(rs.getInt("api_key_version"));
        c.setApiKeyLast4(rs.getString("api_key_last4"));
        c.setApiBase(rs.getString("api_base"));
        c.setModel(rs.getString("model"));
        c.setApiFormat(rs.getString("api_format"));
        double temp = rs.getDouble("temperature");
        c.setTemperature(rs.wasNull() ? null : temp);
        c.setCreatedAt(rs.getLong("created_at"));
        c.setUpdatedAt(rs.getLong("updated_at"));
        return c;
    };

    private final JdbcTemplate jdbc;

    public JdbcApiConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ApiConfig> findById(Long id) {
        List<ApiConfig> list = jdbc.query("SELECT " + COLS + " FROM llm_config WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<ApiConfig> findByUserId(Long userId) {
        return jdbc.query("SELECT " + COLS + " FROM llm_config WHERE user_id = ? ORDER BY created_at ASC",
                MAPPER, userId);
    }

    @Override
    public long countByUserId(Long userId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM llm_config WHERE user_id = ?", Long.class, userId);
        return n == null ? 0 : n;
    }

    @Override
    public ApiConfig insert(ApiConfig c) {
        Long id = jdbc.queryForObject("INSERT INTO llm_config (" + INSERT_COLS + ") " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                c.getUserId(), c.getName(), c.getApiKeyCipher(), c.getApiKeyNonce(),
                c.getApiKeyVersion(), c.getApiKeyLast4(), c.getApiBase(), c.getModel(),
                c.getApiFormat(), c.getTemperature(), c.getCreatedAt(), c.getUpdatedAt());
        c.setId(id);
        return c;
    }

    @Override
    public void update(ApiConfig c) {
        jdbc.update("UPDATE llm_config SET user_id=?, name=?, api_key_cipher=?, api_key_nonce=?, " +
                        "api_key_version=?, api_key_last4=?, api_base=?, model=?, api_format=?, " +
                        "temperature=?, created_at=?, updated_at=? WHERE id=?",
                c.getUserId(), c.getName(), c.getApiKeyCipher(), c.getApiKeyNonce(),
                c.getApiKeyVersion(), c.getApiKeyLast4(), c.getApiBase(), c.getModel(),
                c.getApiFormat(), c.getTemperature(), c.getCreatedAt(), c.getUpdatedAt(), c.getId());
    }

    @Override
    public void deleteById(Long id) {
        jdbc.update("DELETE FROM llm_config WHERE id = ?", id);
    }
}
