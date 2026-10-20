package com.review.vt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;

/**
 * 出站 HTTP 的代理选择：企业内网必须走 HTTP_PROXY/HTTPS_PROXY 网关，
 * 而 Java HttpClient 默认不认这些环境变量，必须显式配。
 *
 * LlmService / WebSearch / WebFetcher 共用。
 */
final class HttpProxies {

    private static final Logger log = LoggerFactory.getLogger(HttpProxies.class);

    private HttpProxies() {}

    /** 优先用 HTTPS_PROXY / HTTP_PROXY 环境变量；无则走系统默认 */
    static ProxySelector selector() {
        String proxy = firstNonBlank(System.getenv("HTTPS_PROXY"), System.getenv("HTTP_PROXY"),
                System.getenv("https_proxy"), System.getenv("http_proxy"));
        if (proxy == null) {
            log.debug("无代理环境变量，使用系统默认代理");
            return ProxySelector.getDefault();
        }
        try {
            URI uri = URI.create(proxy);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            if (host == null) return ProxySelector.getDefault();
            log.info("出站 HTTP 代理：{}:{}", host, port);
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (Exception e) {
            log.warn("解析代理环境变量失败，使用默认代理：{}", e.getMessage());
            return ProxySelector.getDefault();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
