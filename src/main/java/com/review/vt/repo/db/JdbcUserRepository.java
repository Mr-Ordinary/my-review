package com.review.vt.repo.db;

import com.review.vt.domain.User;
import com.review.vt.repo.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * users 表存取。用户数据按 username 唯一，自身无租户概念（不走 AuthContext）。
 * api_key_cipher / api_key_nonce 只在此处与加密服务之间流转，绝不出后端。
 * 主键为自增 BIGINT，insert 用 RETURNING 回填。
 */
@Repository
@Profile("!json")
public class JdbcUserRepository implements UserRepository {

    /** 不含 id：insert 时由数据库自增生成 */
    private static final String INSERT_COLS =
            "username, password_hash, nickname, avatar_ext, api_key_cipher, api_key_nonce, " +
            "api_key_version, api_key_last4, api_base, model, api_format, temperature, " +
            "summary_json, summary_at, created_at, updated_at";

    private static final String COLS = "id, " + INSERT_COLS;

    /** 查询列 = 插入列 + 总结任务状态列（状态列只走专用原子更新，不进 insert/update） */
    private static final String SELECT_COLS = COLS + ", summary_status, summary_error, summary_started_at";

    private static final RowMapper<User> MAPPER = (rs, i) -> {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setNickname(rs.getString("nickname"));
        u.setAvatarExt(rs.getString("avatar_ext"));
        u.setApiKeyCipher(rs.getString("api_key_cipher"));
        u.setApiKeyNonce(rs.getString("api_key_nonce"));
        u.setApiKeyVersion(rs.getInt("api_key_version"));
        u.setApiKeyLast4(rs.getString("api_key_last4"));
        u.setApiBase(rs.getString("api_base"));
        u.setModel(rs.getString("model"));
        u.setApiFormat(rs.getString("api_format"));
        double temp = rs.getDouble("temperature");
        u.setTemperature(rs.wasNull() ? null : temp);
        u.setSummaryJson(rs.getString("summary_json"));
        long sa = rs.getLong("summary_at");
        u.setSummaryAt(rs.wasNull() ? null : sa);
        u.setSummaryStatus(rs.getString("summary_status"));
        u.setSummaryError(rs.getString("summary_error"));
        long ss = rs.getLong("summary_started_at");
        u.setSummaryStartedAt(rs.wasNull() ? null : ss);
        u.setCreatedAt(rs.getLong("created_at"));
        u.setUpdatedAt(rs.getLong("updated_at"));
        return u;
    };

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> findById(Long id) {
        List<User> list = jdbc.query("SELECT " + SELECT_COLS + " FROM users WHERE id = ?", MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        List<User> list = jdbc.query("SELECT " + SELECT_COLS + " FROM users WHERE username = ?", MAPPER, username);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public boolean existsByUsername(String username) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        return n != null && n > 0;
    }

    @Override
    public User insert(User u) {
        Long id = jdbc.queryForObject("INSERT INTO users (" + INSERT_COLS + ") " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                u.getUsername(), u.getPasswordHash(), u.getNickname(), u.getAvatarExt(),
                u.getApiKeyCipher(), u.getApiKeyNonce(), u.getApiKeyVersion(), u.getApiKeyLast4(),
                u.getApiBase(), u.getModel(), u.getApiFormat(), u.getTemperature(),
                u.getSummaryJson(), u.getSummaryAt(), u.getCreatedAt(), u.getUpdatedAt());
        u.setId(id);
        return u;
    }

    @Override
    public void update(User u) {
        jdbc.update("UPDATE users SET username=?, password_hash=?, nickname=?, avatar_ext=?, " +
                        "api_key_cipher=?, api_key_nonce=?, api_key_version=?, api_key_last4=?, " +
                        "api_base=?, model=?, api_format=?, temperature=?, " +
                        "created_at=?, updated_at=? WHERE id=?",
                u.getUsername(), u.getPasswordHash(), u.getNickname(), u.getAvatarExt(),
                u.getApiKeyCipher(), u.getApiKeyNonce(), u.getApiKeyVersion(), u.getApiKeyLast4(),
                u.getApiBase(), u.getModel(), u.getApiFormat(), u.getTemperature(),
                u.getCreatedAt(), u.getUpdatedAt(), u.getId());
    }

    /*
     * 求职总结的结果与任务状态只走下面三个原子更新，不进 update()：
     * 后台任务写回时，用户可能正好在改昵称/密码，整行覆盖会互相踩。
     */

    @Override
    public boolean tryStartSummary(Long userId, long now, long staleBefore) {
        int n = jdbc.update("UPDATE users SET summary_status='generating', summary_error=NULL, " +
                        "summary_started_at=? WHERE id=? AND " +
                        "(summary_status IS DISTINCT FROM 'generating' OR summary_started_at IS NULL OR summary_started_at < ?)",
                now, userId, staleBefore);
        return n > 0;
    }

    @Override
    public void finishSummary(Long userId, String summaryJson, long at) {
        jdbc.update("UPDATE users SET summary_json=?, summary_at=?, summary_status='idle', " +
                "summary_error=NULL, summary_started_at=NULL WHERE id=?", summaryJson, at, userId);
    }

    @Override
    public void failSummary(Long userId, String error) {
        jdbc.update("UPDATE users SET summary_status='failed', summary_error=?, " +
                "summary_started_at=NULL WHERE id=?", error, userId);
    }

    @Override
    public int failInterruptedSummaries(String error) {
        return jdbc.update("UPDATE users SET summary_status='failed', summary_error=?, " +
                "summary_started_at=NULL WHERE summary_status='generating'", error);
    }

    @Override
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return n == null ? 0 : n;
    }
}
