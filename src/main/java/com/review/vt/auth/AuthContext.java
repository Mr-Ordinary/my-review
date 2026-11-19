package com.review.vt.auth;
import com.review.vt.pojo.dto.UserCache;
import com.review.vt.web.ex.UnauthorizedException;

/**
 * 当前登录用户上下文（ThreadLocal）。
 *
 * <p>由 {@link AuthFilter} 在请求线程上设置、finally 清理；后台任务线程由
 * {@code PracticeService.submitTask} 显式传递（submit 前捕获、worker 里重新设置）。
 * 存储层的 userId 过滤统一从 {@link #require()} 取。
 */
public final class AuthContext {

    /** 当前用户标识（自增 Long id + 用户名）。只放不可变的最小信息，其余按需查库 */
    public record CurrentUser(Long userId, String username) {}

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();
    /** 请求期用户信息快照（AuthFilter 从 Redis 一次 GET 得到）；工作线程上为 null，走 UserCacheService.loadForRead */
    private static final ThreadLocal<UserCache> CACHED = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** 请求期用户信息快照；可能为 null */
    public static void setCached(UserCache user) {
        CACHED.set(user);
    }

    /** @return 当前用户；未登录为 null */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** @return 请求期用户信息快照；后台线程/未设置为 null */
    public static UserCache cached() {
        return CACHED.get();
    }

    /** @return 当前用户；未登录抛 401 语义的异常 */
    public static CurrentUser require() {
        CurrentUser u = HOLDER.get();
        if (u == null) throw new UnauthorizedException("请先登录");
        return u;
    }

    public static void clear() {
        HOLDER.remove();
        CACHED.remove();
    }
}
