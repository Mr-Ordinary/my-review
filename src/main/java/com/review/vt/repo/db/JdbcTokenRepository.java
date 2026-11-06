package com.review.vt.repo.db;

import com.review.vt.auth.AuthContext;
import com.review.vt.repo.TokenRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** auth_token 表存取：token_hash 是 SHA-256 十六进制，user_id 为自增 BIGINT。 */
@Repository
@Profile("!json")
public class JdbcTokenRepository implements TokenRepository {

    private final JdbcTemplate jdbc;

    public JdbcTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(String tokenHash, Long userId, long createdAt, long expiresAt) {
        jdbc.update("INSERT INTO auth_token (token_hash, user_id, created_at, expires_at) VALUES (?,?,?,?) " +
                "ON CONFLICT (token_hash) DO UPDATE SET user_id=EXCLUDED.user_id, " +
                "created_at=EXCLUDED.created_at, expires_at=EXCLUDED.expires_at",
                tokenHash, userId, createdAt, expiresAt);
    }

    @Override
    public Optional<AuthContext.CurrentUser> findUser(String tokenHash, long now) {
        List<AuthContext.CurrentUser> list = jdbc.query(
                "SELECT u.id, u.username FROM auth_token t JOIN users u ON u.id = t.user_id " +
                        "WHERE t.token_hash = ? AND t.expires_at > ?",
                (rs, i) -> new AuthContext.CurrentUser(rs.getLong("id"), rs.getString("username")),
                tokenHash, now);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public void delete(String tokenHash) {
        jdbc.update("DELETE FROM auth_token WHERE token_hash = ?", tokenHash);
    }

    @Override
    public void deleteByUserId(Long userId) {
        jdbc.update("DELETE FROM auth_token WHERE user_id = ?", userId);
    }

    @Override
    public int deleteExpired(long now) {
        return jdbc.update("DELETE FROM auth_token WHERE expires_at <= ?", now);
    }
}
