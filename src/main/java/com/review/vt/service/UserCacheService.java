package com.review.vt.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.vt.auth.AuthContext;
import com.review.vt.mapper.UserMapper;
import com.review.vt.pojo.dto.UserCache;
import com.review.vt.pojo.entity.User;
import com.review.vt.web.ex.AuthUnavailableException;
import com.review.vt.web.ex.NotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Redis 用户缓存：登录会话（user:{token} → UserCache JSON，带 TTL）+ token 索引（user_tokens:{userId} → SET）。
 *
 * <p><b>缓存纪律（安全规范）</b>：不存在「查库后回填缓存」。入缓存只有两种情况——
 * ① 登录/注册/改密签发（{@link #writeOnLogin}）；
 * ② 用户修改的两步同步：修改前 {@link #evictForUpdate}（删）+ 修改后 {@link #writeAfter}（写）。
 * 写入失败一律删除对应 key：宁可掉登录（缓存缺失 = 401），绝不留旧数据。
 * 无 token 上下文的读取（{@link #loadForRead}）走索引反查 → DB 只读兜底，绝不写缓存。
 *
 * <p>key 约定：user:{明文token}，user_tokens:{userId}；仅用 Redis 5 兼容命令（无 GETDEL/SET GET）。
 */
@Service
public class UserCacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final UserMapper userMapper;
    /** 两步同步的 per-user 互斥：evict → DB 写 → writeBack 期间，同用户的第二个修改排队 */
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public UserCacheService(StringRedisTemplate redis, UserMapper userMapper) {
        this.redis = redis;
        this.userMapper = userMapper;
        this.json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    static String userKey(String token) { return "user:" + token; }
    static String userTokensKey(Long userId) { return "user_tokens:" + userId; }

    /* ==================== 写入（入缓存的唯一入口） ==================== */

    /** (a) 登录/注册/改密签发时写入 */
    public void writeOnLogin(String token, User user, long ttlSeconds) {
        String val = toJson(UserCache.from(user));
        try {
            redis.opsForValue().set(userKey(token), val, ttlSeconds, java.util.concurrent.TimeUnit.SECONDS);
            String setKey = userTokensKey(user.getId());
            redis.opsForSet().add(setKey, token);
            redis.expire(setKey, ttlSeconds, java.util.concurrent.TimeUnit.SECONDS);   // 每次刷新，≥ 成员剩余寿命
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /** (b) 修改后写入：把调用方手里的最新 User 写回 plan 捕获的每个 token key（剩余 TTL 不变，会话不掉线） */
    public void writeAfter(UpdatePlan plan, User fresh) {
        String val = toJson(UserCache.from(fresh));
        boolean failed = false;
        for (Map.Entry<String, Long> e : plan.remainingTtl().entrySet()) {
            try {
                redis.opsForValue().set(userKey(e.getKey()), val, e.getValue(), java.util.concurrent.TimeUnit.SECONDS);
            } catch (DataAccessException ex) {
                failed = true;
            }
        }
        if (failed) {
            // 宁可全部掉登录（缓存缺失=401），绝不留旧数据
            for (String t : plan.remainingTtl().keySet()) deleteQuietly(t);
        }
    }

    /** (b') 列级写（总结原子 SQL）后写入：重读行属于「修改后写入」的组成部分；用户已删则只删不写 */
    public UserCache writeAfterReload(UpdatePlan plan) {
        try {
            Optional<User> fresh = userMapper.findById(plan.userId());
            if (fresh.isEmpty()) {
                for (String t : plan.remainingTtl().keySet()) deleteQuietly(t);
                return null;
            }
            writeAfter(plan, fresh.get());
            return UserCache.from(fresh.get());
        } catch (DataAccessException e) {
            for (String t : plan.remainingTtl().keySet()) deleteQuietly(t);
            throw e;
        }
    }

    /* ==================== 两步同步 ==================== */

    /** 修改后写入的计划：捕获 token→剩余 TTL，并持有 per-user 锁；必须 try-with-resources */
    public record UpdatePlan(Long userId, Map<String, Long> remainingTtl, ReentrantLock lock) implements AutoCloseable {
        @Override public void close() { lock.unlock(); }
    }

    /**
     * 步骤 1「修改前删除」——捕获各 token 剩余 TTL 后删除全部 user:{t}。
     * 必须在 DB 写之前调用；Redis 挂则抛异常，调用方应中止整个修改（缓存不能留旧数据）。
     */
    public UpdatePlan evictForUpdate(Long userId) {
        ReentrantLock lock = locks.computeIfAbsent(userId, k -> new ReentrantLock());
        lock.lock();
        try {
            Map<String, Long> remaining = new LinkedHashMap<>();
            Set<String> tokens = members(userId);
            for (String t : tokens) {
                Long n = ttlSeconds(userKey(t));
                if (n != null && n > 0) remaining.put(t, n);   // -2 已过期 / -1 无 TTL → 不写回
            }
            for (String t : tokens) {
                redis.delete(userKey(t));
            }
            return new UpdatePlan(userId, remaining, lock);
        } catch (DataAccessException e) {
            lock.unlock();
            throw unavailable(e);
        }
    }

    /* ==================== 读 ==================== */

    /** 按 token 读（鉴权主路径）；缺失/坏 JSON = 空（401 语义），Redis 挂 = 503 语义 */
    public Optional<UserCache> readByToken(String token) {
        String val;
        try {
            val = redis.opsForValue().get(userKey(token));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
        return parse(val);
    }

    /** 按 userId 反查（token 索引 → 任一存活 token 的值）；无存活 token 为空 */
    public Optional<UserCache> readByUserId(Long userId) {
        Set<String> tokens;
        try {
            tokens = redis.opsForSet().members(userTokensKey(userId));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
        if (tokens == null) return Optional.empty();
        for (String t : tokens) {
            Optional<UserCache> u = readByToken(t);
            if (u.isPresent()) return u;
        }
        return Optional.empty();
    }

    /**
     * 无 token 上下文的读取（后台任务、头像接口、共享查他人）：反查缓存 → miss 则 DB 只读兜底。
     * 兜底路径绝不写缓存（安全规范）。
     */
    public Optional<UserCache> loadForRead(Long userId) {
        Optional<UserCache> cached = readByUserId(userId);
        if (cached.isPresent()) return cached;
        try {
            return userMapper.findById(userId).map(UserCache::from);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /** 当前请求的用户信息：AuthFilter 的快照优先，否则按 userId 反查/兜底 */
    public UserCache currentCached() {
        UserCache snap = AuthContext.cached();
        if (snap != null) return snap;
        return loadForRead(AuthContext.require().userId())
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    /* ==================== 删除 ==================== */

    /** 登出/撤销单 token：DEL user:{t} + SREM 索引 */
    public void deleteSession(String token) {
        try {
            parse(redis.opsForValue().get(userKey(token))).ifPresent(u ->
                    redis.opsForSet().remove(userTokensKey(u.id()), token));
            redis.delete(userKey(token));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /** 撤销该用户全部 token（改密）：SMEMBERS → DEL 逐个 → DEL 索引 */
    public void deleteAllSessions(Long userId) {
        try {
            for (String t : members(userId)) {
                redis.delete(userKey(t));
            }
            redis.delete(userTokensKey(userId));
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /* ==================== 内部 ==================== */

    private Set<String> members(Long userId) {
        Set<String> tokens = redis.opsForSet().members(userTokensKey(userId));
        return tokens == null ? Set.of() : tokens;
    }

    private Long ttlSeconds(String key) {
        return redis.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void deleteQuietly(String token) {
        try {
            redis.delete(userKey(token));
        } catch (DataAccessException ignored) {
            // 尽力而为：删不掉就等 TTL 自过期
        }
    }

    private Optional<UserCache> parse(String val) {
        if (val == null || val.isBlank()) return Optional.empty();
        try {
            return Optional.of(json.readValue(val, UserCache.class));
        } catch (Exception e) {
            return Optional.empty();   // 坏 JSON = 未登录，不外泄细节
        }
    }

    private String toJson(UserCache u) {
        try {
            return json.writeValueAsString(u);
        } catch (Exception e) {
            throw new AuthUnavailableException("登录服务暂不可用，请稍后重试", e);
        }
    }

    private AuthUnavailableException unavailable(DataAccessException e) {
        return new AuthUnavailableException("登录服务暂不可用，请稍后重试", e);
    }
}
