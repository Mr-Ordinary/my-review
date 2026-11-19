package com.review.vt.auth;

import com.review.vt.pojo.dto.UserCache;
import com.review.vt.pojo.entity.User;
import com.review.vt.service.UserCacheService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * 不透明登录 token：32 字节随机数（Base64URL），存 Redis（user:{token} → 用户信息 JSON，带 TTL）。
 *
 * <p>默认有效期 6 小时（myreview.auth.token-ttl-hours 可调），TTL 即 token 过期时间，无需清理任务。
 * 校验/用户信息只走 Redis；缓存缺失 = 未登录（安全规范：不查库回填）。
 */
@Service
public class TokenService {

    public record Issued(String token, long expiresAt) {}

    private final UserCacheService userCache;
    private final long ttlMillis;
    private final SecureRandom random = new SecureRandom();

    public TokenService(UserCacheService userCache,
                        @Value("${myreview.auth.token-ttl-hours:6}") long ttlHours) {
        this.userCache = userCache;
        this.ttlMillis = Math.max(1, ttlHours) * 3600_000L;
    }

    /** 给用户签发新 token（返回明文，只此一次）；写缓存入参要完整 User */
    public Issued issue(User user) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        long expiresAt = System.currentTimeMillis() + ttlMillis;
        userCache.writeOnLogin(token, user, ttlMillis / 1000);
        return new Issued(token, expiresAt);
    }

    /** 校验 token，返回缓存的用户信息；无效/过期/坏数据返回空（缓存 miss 不回库） */
    public Optional<UserCache> validate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return userCache.readByToken(token);
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) userCache.deleteSession(token);
    }

    /** 撤销该用户全部 token（改密前调用）；Redis 失败抛异常，调用方应中止修改 */
    public void revokeAll(Long userId) {
        userCache.deleteAllSessions(userId);
    }
}
