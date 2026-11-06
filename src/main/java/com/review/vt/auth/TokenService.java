package com.review.vt.auth;

import com.review.vt.repo.TokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 不透明登录 token：32 字节随机数（Base64URL），库里只存 SHA-256 哈希。
 *
 * <p>默认有效期 6 小时（myreview.auth.token-ttl-hours 可调）。token 撤销只需删库，
 * 服务重启也不会掉登录；登录/改密时会顺带清理过期 token。
 */
@Service
public class TokenService {

    public record Issued(String token, long expiresAt) {}

    private final TokenRepository tokens;
    private final long ttlMillis;
    private final SecureRandom random = new SecureRandom();

    public TokenService(TokenRepository tokens,
                        @Value("${myreview.auth.token-ttl-hours:6}") long ttlHours) {
        this.tokens = tokens;
        this.ttlMillis = Math.max(1, ttlHours) * 3600_000L;
    }

    /** 给用户签发新 token（返回明文，只此一次） */
    public Issued issue(Long userId) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long now = System.currentTimeMillis();
        long expiresAt = now + ttlMillis;
        tokens.insert(hash(token), userId, now, expiresAt);
        tokens.deleteExpired(now);
        return new Issued(token, expiresAt);
    }

    /** 校验 token，返回当前用户；无效/过期返回空 */
    public Optional<AuthContext.CurrentUser> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return tokens.findUser(hash(token), System.currentTimeMillis());
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) tokens.delete(hash(token));
    }

    public void revokeAll(Long userId) {
        tokens.deleteByUserId(userId);
    }

    /** 只存哈希：拿到数据库也还原不出 token 明文 */
    static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("token 哈希失败", e);
        }
    }
}
