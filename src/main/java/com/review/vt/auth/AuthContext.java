package com.review.vt.auth;

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

    private AuthContext() {}

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** @return 当前用户；未登录为 null */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** @return 当前用户；未登录抛 401 语义的异常 */
    public static CurrentUser require() {
        CurrentUser u = HOLDER.get();
        if (u == null) throw new UnauthorizedException("请先登录");
        return u;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
