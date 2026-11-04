package com.review.vt.auth;

import com.review.vt.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口。除 register/login 外都过 {@link AuthFilter}，未登录 401。
 *
 * <p>API Key 只回显掩码（last4）——明文永远不出后端。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(String username, String password) {}
    public record LoginRequest(String username, String password) {}
    public record PasswordRequest(String oldPassword, String newPassword) {}

    /** 登录后/会话中返回给前端的用户信息（无任何密文字段） */
    public record AuthUser(String id, String username, String nickname, String avatarExt,
                           String apiKeyLast4, boolean hasApiKey, boolean hasSummary, Long summaryAt) {}

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthUser register(@RequestBody RegisterRequest req) {
        return dto(auth.register(req.username(), req.password()));
    }

    @PostMapping("/login")
    public AuthUser login(@RequestBody LoginRequest req, HttpServletRequest http) {
        return dto(auth.login(req.username(), req.password(), http));
    }

    @GetMapping("/me")
    public AuthUser me() {
        return dto(auth.me(AuthContext.require().userId()));
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest http) {
        auth.logout(http);
    }

    @PutMapping("/password")
    public AuthUser changePassword(@RequestBody PasswordRequest req) {
        return dto(auth.changePassword(AuthContext.require().userId(),
                req.oldPassword(), req.newPassword()));
    }

    private static AuthUser dto(User u) {
        boolean hasKey = u.getApiKeyCipher() != null && !u.getApiKeyCipher().isBlank();
        boolean hasSummary = u.getSummaryJson() != null && !u.getSummaryJson().isBlank();
        return new AuthUser(u.getId(), u.getUsername(), u.getNickname(), u.getAvatarExt(),
                u.getApiKeyLast4(), hasKey, hasSummary, u.getSummaryAt());
    }
}
