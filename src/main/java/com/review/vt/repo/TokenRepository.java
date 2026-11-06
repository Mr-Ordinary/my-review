package com.review.vt.repo;

import com.review.vt.auth.AuthContext;

import java.util.Optional;

/** 登录 token 存储接口（只存 SHA-256 哈希，不存明文）。 */
public interface TokenRepository {

    void insert(String tokenHash, Long userId, long createdAt, long expiresAt);

    /** 按 token 哈希 + 过期时间查用户；过期的返回空 */
    Optional<AuthContext.CurrentUser> findUser(String tokenHash, long now);

    void delete(String tokenHash);

    void deleteByUserId(Long userId);

    int deleteExpired(long now);
}
