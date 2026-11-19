package com.review.vt.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** 数据缓存配置（myreview.cache.*）：随机 TTL 兜底（防缓存雪崩）+ 启动热备开关/分块大小 */
@Component
@ConfigurationProperties(prefix = "myreview.cache")
public class CacheProperties {

    /** 漏删兜底 TTL 下限（分钟） */
    private int ttlMinMinutes = 20;

    /** 漏删兜底 TTL 上限（分钟） */
    private int ttlMaxMinutes = 40;

    /** 启动热备开关 */
    private boolean warmupEnabled = true;

    /** 热备键集分页每块行数 */
    private int warmupChunkSize = 500;

    /**
     * 启动校验：0 < min < max，否则拒绝启动（抛异常让容器起不来）。
     * 刻意做成可直接调用的 validate()，单测不依赖 Spring。
     */
    @PostConstruct
    void validate() {
        if (ttlMinMinutes <= 0 || ttlMaxMinutes <= 0 || ttlMinMinutes >= ttlMaxMinutes) {
            throw new IllegalStateException(
                    "myreview.cache.ttl-min-minutes / ttl-max-minutes 非法：要求 0 < min < max，当前 min="
                            + ttlMinMinutes + " max=" + ttlMaxMinutes);
        }
    }

    /** 每次写缓存随机 TTL（秒）∈ [min*60, max*60]——同一批缓存不过期在同一时刻，防雪崩 */
    public long randomTtlSeconds() {
        long lo = ttlMinMinutes * 60L;
        long hi = ttlMaxMinutes * 60L;
        return lo + ThreadLocalRandom.current().nextLong(hi - lo + 1);
    }

    public int getTtlMinMinutes() { return ttlMinMinutes; }
    public void setTtlMinMinutes(int ttlMinMinutes) { this.ttlMinMinutes = ttlMinMinutes; }

    public int getTtlMaxMinutes() { return ttlMaxMinutes; }
    public void setTtlMaxMinutes(int ttlMaxMinutes) { this.ttlMaxMinutes = ttlMaxMinutes; }

    public boolean isWarmupEnabled() { return warmupEnabled; }
    public void setWarmupEnabled(boolean warmupEnabled) { this.warmupEnabled = warmupEnabled; }

    public int getWarmupChunkSize() { return warmupChunkSize; }
    public void setWarmupChunkSize(int warmupChunkSize) { this.warmupChunkSize = warmupChunkSize; }
}
