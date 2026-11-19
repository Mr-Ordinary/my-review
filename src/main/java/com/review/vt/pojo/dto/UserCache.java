package com.review.vt.pojo.dto;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.User;

/**
 * Redis 中缓存的用户信息 = User 除 passwordHash（登录/修改时写入，见 UserCacheService）。
 *
 * <p>刻意用独立 record 而不是 User 实体：类型上不可能误传给 userMapper.update()
 * （update-strategy: ignored 是全列写，会把 password_hash 冲掉）。
 */
public record UserCache(
        Long id, String username, String nickname, String avatarExt,
        String apiKeyCipher, String apiKeyNonce, int apiKeyVersion, String apiKeyLast4,
        String apiBase, String model, String apiFormat, Double temperature,
        String summaryJson, Long summaryAt, String summaryStatus, String summaryError,
        Long summaryStartedAt, long createdAt, long updatedAt) {

    public static UserCache from(User u) {
        return new UserCache(
                u.getId(), u.getUsername(), u.getNickname(), u.getAvatarExt(),
                u.getApiKeyCipher(), u.getApiKeyNonce(), u.getApiKeyVersion(), u.getApiKeyLast4(),
                u.getApiBase(), u.getModel(), u.getApiFormat(), u.getTemperature(),
                u.getSummaryJson(), u.getSummaryAt(), u.getSummaryStatus(), u.getSummaryError(),
                u.getSummaryStartedAt(), u.getCreatedAt(), u.getUpdatedAt());
    }

    public AuthContext.CurrentUser toCurrentUser() {
        return new AuthContext.CurrentUser(id, username);
    }

    /** 与 AuthController/MeController 的 dto(User) 同构（hasApiKey=密文非空，hasSummary=总结非空） */
    public AuthUser toAuthUser() {
        return new AuthUser(
                id == null ? null : String.valueOf(id),
                username, nickname, avatarExt,
                apiKeyLast4,
                apiKeyCipher != null && !apiKeyCipher.isBlank(),
                summaryJson != null && !summaryJson.isBlank(),
                summaryAt);
    }
}
