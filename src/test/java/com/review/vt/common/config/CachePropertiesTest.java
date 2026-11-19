package com.review.vt.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 随机 TTL 配置：启动校验 0 < min < max，否则拒绝启动；
 * randomTtlSeconds() 落在 [min*60, max*60]。
 */
class CachePropertiesTest {

    @Test
    @DisplayName("合法区间通过启动校验")
    void validPasses() {
        CacheProperties p = new CacheProperties();
        p.setTtlMinMinutes(20);
        p.setTtlMaxMinutes(40);
        assertDoesNotThrow(p::validate);
    }

    @Test
    @DisplayName("min >= max 或非正数 → 抛异常拒绝启动")
    void invalidRefusesStartup() {
        CacheProperties a = new CacheProperties();
        a.setTtlMinMinutes(40);
        a.setTtlMaxMinutes(40);
        assertThrows(IllegalStateException.class, a::validate, "min == max 拒绝启动");

        CacheProperties b = new CacheProperties();
        b.setTtlMinMinutes(50);
        b.setTtlMaxMinutes(40);
        assertThrows(IllegalStateException.class, b::validate, "min > max 拒绝启动");

        CacheProperties c = new CacheProperties();
        c.setTtlMinMinutes(0);
        c.setTtlMaxMinutes(40);
        assertThrows(IllegalStateException.class, c::validate, "min <= 0 拒绝启动");
    }

    @Test
    @DisplayName("randomTtlSeconds 落在 [min*60, max*60]")
    void randomTtlInRange() {
        CacheProperties p = new CacheProperties();
        p.setTtlMinMinutes(20);
        p.setTtlMaxMinutes(40);
        for (int i = 0; i < 200; i++) {
            long t = p.randomTtlSeconds();
            assertTrue(t >= 1200 && t <= 2400, "TTL 越界: " + t);
        }
    }
}
