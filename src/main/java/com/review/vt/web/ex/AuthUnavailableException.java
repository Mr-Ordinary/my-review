package com.review.vt.web.ex;

/** Redis 不可用（登录服务依赖的缓存挂了）→ 503，区别于 401「请先登录」 */
public class AuthUnavailableException extends RuntimeException {
    public AuthUnavailableException(String message) {
        super(message);
    }

    public AuthUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
