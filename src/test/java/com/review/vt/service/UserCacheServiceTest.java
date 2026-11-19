package com.review.vt.service;

import com.review.vt.pojo.entity.User;
import com.review.vt.web.ex.AuthUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户缓存纪律：入缓存只有登录/修改两处；两步同步（修改前删+修改后写）；
 * 写入失败删除 key（宁可掉登录，绝不留旧数据）；反查 miss 走 DB 只读兜底。
 */
class UserCacheServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private com.review.vt.mapper.UserMapper userMapper;
    private UserCacheService svc;

    private static User user(long id, String nickname) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setNickname(nickname);
        u.setCreatedAt(1000L);
        u.setUpdatedAt(2000L);
        return u;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        userMapper = mock(com.review.vt.mapper.UserMapper.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        svc = new UserCacheService(redis, userMapper);
    }

    @Test
    @DisplayName("登录写入：SET user:{t} 带 TTL + SADD 索引 + 刷新 set 过期")
    void writeOnLoginWritesThreeCommands() {
        svc.writeOnLogin("tok", user(1, "昵称"), 3600);
        verify(valueOps).set(eq("user:tok"), contains("\"nickname\":\"昵称\""), eq(3600L), eq(TimeUnit.SECONDS));
        verify(setOps).add("user_tokens:1", "tok");
        verify(redis).expire("user_tokens:1", 3600, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("登录写入不落 passwordHash")
    void writeOnLoginNeverStoresPassword() {
        User u = user(1, "n");
        u.setPasswordHash("argon2-SECRET");
        svc.writeOnLogin("tok", u, 60);
        verify(valueOps).set(eq("user:tok"), argThat(s -> !s.contains("SECRET") && !s.contains("passwordHash")), anyLong(), any());
    }

    @Test
    @DisplayName("validate：GET 拿到值可解析为用户；空/坏 JSON = 空")
    void readByTokenScenarios() {
        when(valueOps.get("user:tok")).thenReturn(null);
        assertTrue(svc.readByToken("tok").isEmpty(), "无 key = 未登录");

        when(valueOps.get("user:tok")).thenReturn("{坏 json");
        assertTrue(svc.readByToken("tok").isEmpty(), "坏 JSON = 未登录");

        when(valueOps.get("user:tok")).thenReturn("{\"id\":7,\"username\":\"u7\",\"apiKeyVersion\":1,\"createdAt\":1,\"updatedAt\":2}");
        var u = svc.readByToken("tok");
        assertTrue(u.isPresent());
        assertEquals(7L, u.get().id());
    }

    @Test
    @DisplayName("Redis 连接失败 = AuthUnavailableException（503 语义）")
    void redisDownThrowsAuthUnavailable() {
        when(valueOps.get(anyString())).thenThrow(Mockito.mock(DataAccessException.class));
        assertThrows(AuthUnavailableException.class, () -> svc.readByToken("tok"));
    }

    @Test
    @DisplayName("两步同步：evict 捕获剩余 TTL 并删除；writeAfter 用捕获 TTL 写回")
    void twoStepSyncPreservesTtl() {
        when(setOps.members("user_tokens:1")).thenReturn(Set.of("t1", "t2"));
        when(redis.getExpire(eq("user:t1"), eq(TimeUnit.SECONDS))).thenReturn(3600L);
        when(redis.getExpire(eq("user:t2"), eq(TimeUnit.SECONDS))).thenReturn(1800L);

        UserCacheService.UpdatePlan plan = svc.evictForUpdate(1L);
        verify(redis).delete("user:t1");
        verify(redis).delete("user:t2");
        assertEquals(2, plan.remainingTtl().size());

        svc.writeAfter(plan, user(1, "新昵称"));
        verify(valueOps).set(eq("user:t1"), contains("新昵称"), eq(3600L), eq(TimeUnit.SECONDS));
        verify(valueOps).set(eq("user:t2"), contains("新昵称"), eq(1800L), eq(TimeUnit.SECONDS));
        plan.close();
    }

    @Test
    @DisplayName("writeAfter 任一 SET 失败 → 删除全部相关 key（宁可掉登录，绝不留旧数据）")
    void writeAfterFailureDeletesAll() {
        when(setOps.members("user_tokens:1")).thenReturn(Set.of("t1", "t2"));
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(3600L);
        UserCacheService.UpdatePlan plan = svc.evictForUpdate(1L);

        doThrow(Mockito.mock(DataAccessException.class))
                .when(valueOps).set(eq("user:t1"), anyString(), anyLong(), any());

        svc.writeAfter(plan, user(1, "x"));
        // evict 已删过一次 + 失败兜底再删（宁可多删，不留旧数据）
        verify(redis, atLeastOnce()).delete("user:t1");
        verify(redis, atLeastOnce()).delete("user:t2");
        plan.close();
    }

    @Test
    @DisplayName("evict 阶段 Redis 挂 → 抛异常，调用方应中止修改")
    void evictFailureThrows() {
        when(setOps.members("user_tokens:1")).thenThrow(Mockito.mock(DataAccessException.class));
        assertThrows(AuthUnavailableException.class, () -> svc.evictForUpdate(1L));
    }

    @Test
    @DisplayName("deleteAllSessions：DEL 逐个 token + DEL 索引")
    void deleteAllSessions() {
        when(setOps.members("user_tokens:1")).thenReturn(Set.of("t1", "t2"));
        svc.deleteAllSessions(1L);
        verify(redis).delete("user:t1");
        verify(redis).delete("user:t2");
        verify(redis).delete("user_tokens:1");
    }

    @Test
    @DisplayName("loadForRead：反查命中不查库；miss 走 DB 只读兜底且绝不写缓存")
    void loadForReadFallsBackToDbReadOnly() {
        // 命中：不碰 userMapper
        when(setOps.members("user_tokens:1")).thenReturn(Set.of("t1"));
        when(valueOps.get("user:t1")).thenReturn("{\"id\":1,\"username\":\"u1\",\"createdAt\":1,\"updatedAt\":2}");
        assertTrue(svc.loadForRead(1L).isPresent());
        verifyNoInteractions(userMapper);

        // miss：查 DB，且不写缓存（没有任何 SET）
        when(setOps.members("user_tokens:2")).thenReturn(Set.of());
        when(userMapper.findById(2L)).thenReturn(java.util.Optional.of(user(2, "n2")));
        assertTrue(svc.loadForRead(2L).isPresent());
        verify(userMapper).findById(2L);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }
}
