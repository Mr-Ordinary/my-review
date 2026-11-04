package com.review.vt.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * 登录校验过滤器：/api/** 除注册/登录外都要求会话。
 *
 * <p>CSRF 防御（双层）：
 * ① 会话 cookie SameSite=Lax——跨站 POST 根本不携带 cookie；
 * ② 变更类请求再校验 Origin 头（同源才放行，防御 in-depth）。
 * 未登录返回 401 JSON，与 GlobalExceptionHandler 的 {"error":"…"} 格式一致。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private static final String SESSION_ATTR = "user";

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

        HttpSession session = req.getSession(false);
        AuthContext.CurrentUser user = session == null ? null
                : (AuthContext.CurrentUser) session.getAttribute(SESSION_ATTR);
        if (user == null) {
            reject(res, 401, "请先登录");
            return;
        }
        if (!"GET".equalsIgnoreCase(req.getMethod()) && !"HEAD".equalsIgnoreCase(req.getMethod())) {
            String origin = req.getHeader("Origin");
            if (origin != null && !originMatches(origin, req)) {
                reject(res, 403, "跨站请求被拒绝");
                return;
            }
        }

        AuthContext.set(user);
        try {
            chain.doFilter(req, res);
        } finally {
            AuthContext.clear();
        }
    }

    /** Origin 与请求自身 Host 同源才算数；解析不了直接拒绝 */
    private static boolean originMatches(String origin, HttpServletRequest req) {
        try {
            URI u = URI.create(origin);
            String originHost = u.getHost();
            String requestHost = req.getServerName();
            return originHost != null && originHost.equalsIgnoreCase(requestHost);
        } catch (Exception e) {
            return false;
        }
    }

    private static void reject(HttpServletResponse res, int code, String msg) throws IOException {
        res.setStatus(code);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
