package com.review.vt.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Token 校验过滤器：/api/** 除注册/登录外都要求 `Authorization: Bearer <token>`。
 *
 * <p>token 是不透明的（库中只存哈希），撤销即失效；不依赖 cookie，天然免疫 CSRF。
 * 未登录返回 401 JSON，与 GlobalExceptionHandler 的 {"error":"…"} 格式一致。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private final TokenService tokens;

    public AuthFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/")) {
            chain.doFilter(req, res);
            return;
        }
        // 注册与登录是仅有的公开接口
        if (path.equals("/api/auth/login") || path.equals("/api/auth/register")) {
            chain.doFilter(req, res);
            return;
        }

        String token = bearer(req);
        var user = tokens.validate(token);
        if (user.isEmpty()) {
            reject(res, 401, "请先登录");
            return;
        }

        AuthContext.set(user.get());
        try {
            chain.doFilter(req, res);
        } finally {
            AuthContext.clear();
        }
    }

    /** 从 Authorization: Bearer xxx 里取 token；没有返回 null */
    public static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null) return null;
        String prefix = "Bearer ";
        return h.regionMatches(true, 0, prefix, 0, prefix.length()) ? h.substring(prefix.length()).trim() : null;
    }

    private static void reject(HttpServletResponse res, int code, String msg) throws IOException {
        res.setStatus(code);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
