package com.review.vt.web.ex;

/** 未登录 / 登录失败 / 凭证错误。全局异常处理器统一转 401 JSON。 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
