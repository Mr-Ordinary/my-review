package com.review.vt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

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
            InetSocketAddress addr = new InetSocketAddress(host, port);
            // 本机地址不走代理——否则本地模型（Ollama / vLLM / 测试 mock）也会被推去公司网关，
            // 表现就是「AI 请求失败：header parser received no bytes」
            return new LocalhostBypassSelector(addr);
        } catch (Exception e) {
            log.warn("解析代理环境变量失败，使用默认代理：{}", e.getMessage());
            return ProxySelector.getDefault();
        }
    }

    /** 127.0.0.1 / localhost / ::1 直连，其余走给定代理 */
    private static final class LocalhostBypassSelector extends ProxySelector {
        private final InetSocketAddress proxy;
        private final List<java.net.Proxy> viaProxy;
        private final List<java.net.Proxy> direct = List.of(java.net.Proxy.NO_PROXY);

        LocalhostBypassSelector(InetSocketAddress proxy) {
            this.proxy = proxy;
            this.viaProxy = List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP, proxy));
        }

        @Override
        public List<java.net.Proxy> select(URI uri) {
            String host = uri == null ? null : uri.getHost();
            if (isLocal(host)) return direct;
            return viaProxy;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
            log.debug("代理连接失败：{} → {}", uri, ioe.getMessage());
        }

        private static boolean isLocal(String host) {
            if (host == null) return true;
            String h = host.trim().toLowerCase();
            return h.isEmpty() || "localhost".equals(h) || "127.0.0.1".equals(h)
                    || "::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
