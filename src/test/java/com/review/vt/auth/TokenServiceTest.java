package com.review.vt.auth;

import com.review.vt.pojo.dto.UserCache;
import com.review.vt.pojo.entity.User;
import com.review.vt.service.UserCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** token 签发/校验：写缓存入参是完整 User、TTL 与 expiresAt 同源；校验只走缓存，miss 不回库 */
class TokenServiceTest {

    private static User user() {
        User u = new User();
        u.setId(1L);
        u.setUsername("u1");
        u.setNickname("n1");
        u.setCreatedAt(1000L);
        u.setUpdatedAt(2000L);
        return u;
    }

    @Test
    @DisplayName("issue：写入缓存（TTL=token 有效期），expiresAt = now + TTL")
    void issueWritesCache() {
        UserCacheService cache = mock(UserCacheService.class);
        TokenService svc = new TokenService(cache, 6);
        TokenService.Issued issued = svc.issue(user());

        assertNotNull(issued.token());
        assertTrue(issued.token().length() >= 40, "32 字节 Base64URL");
        long now = System.currentTimeMillis();
        assertTrue(issued.expiresAt() > now + 5 * 3600_000L, "≈6 小时");
        assertTrue(issued.expiresAt() <= now + 6 * 3600_000L + 1000);

        verify(cache).writeOnLogin(eq(issued.token()), isA(User.class), eq(6 * 3600L));
    }

    @Test
    @DisplayName("issue 的两次调用 token 不同")
    void issueTokensUnique() {
        UserCacheService cache = mock(UserCacheService.class);
        TokenService svc = new TokenService(cache, 6);
        assertNotEquals(svc.issue(user()).token(), svc.issue(user()).token());
    }

    @Test
    @DisplayName("validate：空 token 直接空；缓存 miss = 空（不回库）")
    void validateMissIsEmpty() {
        UserCacheService cache = mock(UserCacheService.class);
        TokenService svc = new TokenService(cache, 6);
        assertTrue(svc.validate(null).isEmpty());
        assertTrue(svc.validate("  ").isEmpty());

        when(cache.readByToken("tok")).thenReturn(Optional.empty());
        assertTrue(svc.validate("tok").isEmpty(), "缓存 miss = 未登录，不回库");
        verify(cache, never()).loadForRead(any());
    }

    @Test
    @DisplayName("validate：命中返回缓存用户（身份 + 资料一次给出）")
    void validateHitReturnsCache() {
        UserCacheService cache = mock(UserCacheService.class);
        TokenService svc = new TokenService(cache, 6);
        UserCache uc = new UserCache(1L, "u1", "n1", null, null, null, 0, null,
                null, null, null, null, null, null, null, null, null, 1L, 2L);
        when(cache.readByToken("tok")).thenReturn(Optional.of(uc));

        UserCache got = svc.validate("tok").orElseThrow();
        assertEquals(1L, got.toCurrentUser().userId());
        assertEquals("u1", got.toCurrentUser().username());
    }

    @Test
    @DisplayName("revoke/revokeAll 委托缓存删除")
    void revokeDelegates() {
        UserCacheService cache = mock(UserCacheService.class);
        TokenService svc = new TokenService(cache, 6);
        svc.revoke("tok");
        svc.revokeAll(1L);
        verify(cache).deleteSession("tok");
        verify(cache).deleteAllSessions(1L);
    }
}
