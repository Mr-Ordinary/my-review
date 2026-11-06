package com.review.vt.auth;

import com.review.vt.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口。除 register/login 外都过 {@link AuthFilter}，未登录 401。
 *
 * <p>登录/注册/改密返回 {token, expiresAt, user}；前端把 token 存 localStorage，
 * 后续请求带 `Authorization: Bearer <token>`。API Key 只回显掩码——明文永远不出后端。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(String username, String password) {}
    public record LoginRequest(String username, String password) {}
    public record PasswordRequest(String oldPassword, String newPassword) {}

    /** 登录/注册/改密的返回：token + 过期时间 + 用户信息 */
    public record LoginResponse(String token, long expiresAt, AuthUser user) {}

    /** 返回给前端的用户信息（无任何密文字段） */
    public record AuthUser(String id, String username, String nickname, String avatarExt,
                           String apiKeyLast4, boolean hasApiKey, boolean hasSummary, Long summaryAt) {}

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public LoginResponse register(@RequestBody RegisterRequest req) {
        return response(auth.register(req.username(), req.password()));
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        return response(auth.login(req.username(), req.password()));
    }

    @GetMapping("/me")
    public AuthUser me() {
        return dto(auth.me(AuthContext.require().userId()));
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest http) {
        auth.logout(AuthFilter.bearer(http));
    }

    /** 改密后旧 token 全部作废，返回新 token（前端要替换 localStorage） */
    @PutMapping("/password")
    public LoginResponse changePassword(@RequestBody PasswordRequest req) {
        return response(auth.changePassword(AuthContext.require().userId(),
                req.oldPassword(), req.newPassword()));
    }

    private static LoginResponse response(AuthService.LoginResult r) {
        return new LoginResponse(r.issued().token(), r.issued().expiresAt(), dto(r.user()));
    }

    private static AuthUser dto(User u) {
        boolean hasKey = u.getApiKeyCipher() != null && !u.getApiKeyCipher().isBlank();
        boolean hasSummary = u.getSummaryJson() != null && !u.getSummaryJson().isBlank();
        return new AuthUser(String.valueOf(u.getId()), u.getUsername(), u.getNickname(), u.getAvatarExt(),
                u.getApiKeyLast4(), hasKey, hasSummary, u.getSummaryAt());
    }
}
