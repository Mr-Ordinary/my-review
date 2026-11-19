package com.review.vt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.review.vt.pojo.dto.*;

import com.review.vt.pojo.param.*;

import com.review.vt.auth.AuthContext;
import com.review.vt.auth.AuthFilter;
import com.review.vt.auth.AuthService;
import com.review.vt.pojo.dto.UserCache;
import com.review.vt.pojo.entity.User;
import com.review.vt.service.UserCacheService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口。除 register/login 外都过 {@link AuthFilter}，未登录 401。
 *
 * <p>登录/注册/改密返回 {token, expiresAt, user}；前端把 token 存 localStorage，
 * 后续请求带 `Authorization: Bearer <token>`。API Key 只回显掩码——明文永远不出后端。
 */
@Tag(name = "认证", description = "登录 / 注册 / 改密 / 当前用户")
@RestController
@RequestMapping("/api/auth")
public class AuthController {


    private final AuthService authService;
    private final UserCacheService userCache;

    public AuthController(AuthService authService, UserCacheService userCache) {
        this.authService = authService;
        this.userCache = userCache;
    }

    @Operation(summary = "注册", description = "创建账号并自动登录")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse register(@RequestBody RegisterParam req) {
        return response(authService.register(req.username(), req.password()));
    }

    @Operation(summary = "登录", description = "用户名+密码换 token（6 小时有效）")
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginParam req) {
        return response(authService.login(req.username(), req.password()));
    }

    @Operation(summary = "当前用户信息", description = "token 有效期内取登录用户资料")
    @GetMapping("/me")
    public AuthUser me() {
        return userCache.currentCached().toAuthUser();   // Redis 快照，零 SQL
    }

    @Operation(summary = "退出登录", description = "作废当前 token")
    @PostMapping("/logout")
    public void logout(HttpServletRequest http) {
        authService.logout(AuthFilter.bearer(http));
    }

    /** 改密后旧 token 全部作废，返回新 token（前端要替换 localStorage） */
    @Operation(summary = "修改密码", description = "改密成功后旧 token 作废、需重新登录")
    @PostMapping("/password")
    public LoginResponse changePassword(@RequestBody PasswordParam req) {
        return response(authService.changePassword(AuthContext.require().userId(),
                req.oldPassword(), req.newPassword()));
    }

    private static LoginResponse response(AuthService.LoginResult r) {
        return new LoginResponse(r.issued().token(), r.issued().expiresAt(), dto(r.user()));
    }

    private static AuthUser dto(User u) {
        return UserCache.from(u).toAuthUser();   // 与缓存同构，消重
    }
}
