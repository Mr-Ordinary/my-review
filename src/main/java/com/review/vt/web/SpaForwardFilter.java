package com.review.vt.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * SPA 深链接回退：Vue Router 用 history 模式，刷新 /stages、/session/x/answer 这类
 * 路径时把请求转发到 index.html 交给前端路由。
 * API 与带扩展名的静态资源（css/js/图片等）原样放行；旧版单页仍在 classpath:/static 里保留。
 */
@Component
public class SpaForwardFilter extends OncePerRequestFilter {

    private static final String[] EXCLUDE_PREFIXES = {
            "/api", "/css", "/js", "/lib", "/assets", "/v3", "/swagger-ui", "/webjars", "/.well-known",
    };

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean forward = "GET".equals(request.getMethod())
                && !path.equals("/") && !path.equals("/index.html") && !path.equals("/doc.html")
                && !hasExtension(path)
                && !startsWithAny(path);
        if (forward) {
            request.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 带扩展名的路径按静态资源处理（最后一位含点） */
    private static boolean hasExtension(String path) {
        int slash = path.lastIndexOf('/');
        return path.indexOf('.', slash) >= 0;
    }

    private static boolean startsWithAny(String path) {
        for (String p : EXCLUDE_PREFIXES) {
            if (path.startsWith(p)) return true;
        }
        return false;
    }
}
