package com.review.vt.service;

/** 资源不存在时抛出，由全局异常处理转 404。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}
