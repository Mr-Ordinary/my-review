package com.review.vt.pojo.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 登录/注册/改密的返回：token + 过期时间 + 用户信息 */
public record LoginResponse(String token, long expiresAt, AuthUser user) {}
