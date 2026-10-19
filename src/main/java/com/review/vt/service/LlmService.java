package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 纯对话 LLM 调用（Anthropic Messages API 原生格式：POST {apiBase}/v1/messages）。
 * 认证用 x-api-key + anthropic-version；system 是顶层字段，messages 只含 user。
 * 无工具调用：提示词要求模型只输出 JSON，这里做稳健提取。
 * 走 HTTP_PROXY/HTTPS_PROXY 环境变量代理（企业网关场景）。
 */
@Service
public class LlmService {

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
    }

    /** Anthropic Messages API 必填；JSON 输出足够用 */
    private static final int MAX_TOKENS = 8192;

    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public LlmService(SettingsService settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .proxy(proxySelector())
                .build();
    }

    /** 优先用 HTTPS_PROXY / HTTP_PROXY 环境变量；无则走系统默认 */
    private static ProxySelector proxySelector() {
        String proxy = firstNonBlank(System.getenv("HTTPS_PROXY"), System.getenv("HTTP_PROXY"),
                System.getenv("https_proxy"), System.getenv("http_proxy"));
        if (proxy == null) return ProxySelector.getDefault();
        try {
            URI uri = URI.create(proxy);
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            if (host == null) return ProxySelector.getDefault();
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (Exception e) {
            System.err.println("[myreview] 解析代理环境变量失败，使用默认代理：" + e.getMessage());
            return ProxySelector.getDefault();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** 发起一轮对话，返回解析后的 JSON 对象 */
    public JsonNode chatJson(String system, String user) {
        String content = chat(system, user);
        return extractJson(content);
    }

    private String chat(String system, String user) {
        var s = settings.get();
        String apiBase = String.valueOf(s.get("apiBase")).replaceAll("/+$", "");
        String apiKey = String.valueOf(s.get("apiKey"));
        String model = String.valueOf(s.get("model"));
        double temperature = s.get("temperature") instanceof Number n ? n.doubleValue() : 1.0;
        // Anthropic 要求 temperature ∈ [0.0, 1.0]，超界会 400
        if (temperature < 0.0) temperature = 0.0;
        if (temperature > 1.0) temperature = 1.0;

        try {
            String body = buildBody(model, temperature, system, user);
            HttpResponse<String> resp = post(apiBase, apiKey, body);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new LlmException("AI 接口返回错误（HTTP " + resp.statusCode() + "）：" + truncate(resp.body(), 300)
                        + "。请检查系统设置中的 API Base / API Key / 模型配置。");
            }
            JsonNode root = mapper.readTree(resp.body());
            String text = extractText(root);
            if (text.isBlank()) {
                throw new LlmException("AI 返回内容为空，请重试。原始返回：" + truncate(resp.body(), 300));
            }
            return text;
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("AI 请求被中断，请重试。");
        } catch (Exception e) {
            throw new LlmException("AI 请求失败：" + e.getMessage() + "。请检查网络和系统设置。");
        }
    }

    private HttpResponse<String> post(String apiBase, String apiKey, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/v1/messages"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private String buildBody(String model, double temperature, String system, String user) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        body.put("temperature", temperature);
        // system 是 Anthropic Messages API 的顶层字段，不进 messages
        body.put("system", system);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content", user);
        return mapper.writeValueAsString(body);
    }

    /** 从 Anthropic 响应中提取文本：content 数组里 type=text 的块拼接 */
    private String extractText(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    /** 从模型输出中稳健提取 JSON 对象（容忍 ```json 代码块、前后缀文字） */
    private JsonNode extractJson(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LlmException("AI 返回的不是合法 JSON，请重试。返回内容：" + truncate(content, 300));
        }
        try {
            return mapper.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            throw new LlmException("AI 返回的 JSON 解析失败，请重试。返回内容：" + truncate(content, 300));
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
