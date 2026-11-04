package com.review.vt.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码学 Bean。只用 spring-security-crypto（Argon2id 口令哈希），
 * 不引入完整 Spring Security 过滤器链——单人工具两个角色，手写 AuthFilter 足够。
 */
@Configuration
public class SecurityBeans {

    /**
     * Argon2id：当前公认最强的口令哈希（内存难 + 可调参数），
     * 编码串自带盐与参数，无需单独存盐列。默认 64MiB / 3 轮。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
