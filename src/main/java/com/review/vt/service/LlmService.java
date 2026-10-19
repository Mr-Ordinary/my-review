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
 * LLM 调用：把「system + user」发给模型，拿回一段 JSON。
 *
 * 支持两种接口协议（系统设置里的 apiFormat）：
 * - {@code anthropic}：Anthropic Messages API（POST {apiBase}/v1/messages，x-api-key）
 * - {@code openai}：OpenAI Chat Completions（POST {apiBase}/v1/chat/completions，Bearer），
 *   DeepSeek / 通义 / Ollama / vLLM 等兼容端点都能用
 *
 * 无工具调用：提示词要求模型只输出 JSON，这里做稳健提取。
 * 走 HTTP_PROXY/HTTPS_PROXY 环境变量代理（企业网关场景）。
 */
@Service
public class LlmService {

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
    }

    /** 默认输出上限；批量出题等长输出场景用 chatJson(system, user, maxTokens) 覆盖 */
    private static final int MAX_TOKENS = 8192;

    /**
     * 单次调用的输出上限。模型的「思考」也计入这个额度——上下文一大就思考很久，
     * 8192 可能被思考吃光、一个字的正文都剩不下（表现就是「AI 返回内容为空」）。
     * 所以正文为空时会自动翻倍重试一次。
     */
    private static final int MAX_TOKENS_CEILING = 16384;

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

    /** 发起一轮对话，返回解析后的 JSON 对象（默认输出上限） */
    public JsonNode chatJson(String system, String user) {
        return chatJson(system, user, MAX_TOKENS);
    }

    /** 发起一轮对话，返回解析后的 JSON 对象；批量出题/批改等长输出场景可调大 maxTokens */
    public JsonNode chatJson(String system, String user, int maxTokens) {
        return chatJson(system, user, maxTokens, true);
    }

    /**
     * @param thinking 是否让模型先思考。
     *                 模型的思考**计入输出额度**——长输出任务里思考会把额度吃光，
     *                 导致正文被截断甚至一个字都没有（表现就是「AI 返回内容为空」）。
     *                 出题/出计划/写总结这类「要结果不要推理」的调用关掉它，又快又稳。
     */
    public JsonNode chatJson(String system, String user, int maxTokens, boolean thinking) {
        String content = chat(system, user, maxTokens, thinking);
        return extractJson(content);
    }

    private String chat(String system, String user, int maxTokens, boolean thinking) {
        var s = settings.get();
        String apiBase = String.valueOf(s.get("apiBase")).replaceAll("/+$", "");
        String apiKey = String.valueOf(s.get("apiKey"));
        String model = String.valueOf(s.get("model"));
        String format = String.valueOf(s.getOrDefault("apiFormat", "anthropic"));
        double temperature = s.get("temperature") instanceof Number n ? n.doubleValue() : 1.0;
        // Anthropic 要求 temperature ∈ [0.0, 1.0]，超界会 400
        if (temperature < 0.0) temperature = 0.0;
        if (temperature > 1.0) temperature = 1.0;

        try {
            HttpResponse<String> resp = post(apiBase, apiKey, format,
                    buildBody(format, model, temperature, maxTokens, system, user, thinking));
            // 模型不支持这么大的 max_tokens 时，退回默认值重试一次
            if ((resp.statusCode() < 200 || resp.statusCode() >= 300)
                    && maxTokens > MAX_TOKENS
                    && String.valueOf(resp.body()).toLowerCase().contains("max_tokens")) {
                System.err.println("[myreview] 模型拒绝 max_tokens=" + maxTokens + "，退回 " + MAX_TOKENS + " 重试");
                maxTokens = MAX_TOKENS;
                resp = post(apiBase, apiKey, format,
                        buildBody(format, model, temperature, maxTokens, system, user, thinking));
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new LlmException("AI 接口返回错误（HTTP " + resp.statusCode() + "）：" + truncate(resp.body(), 300)
                        + "。请检查系统设置中的 API Base / API Key / 模型 / 接口协议配置。");
            }
            JsonNode root = mapper.readTree(resp.body());
            String text = extractText(format, root);

            // 正文为空的常见原因：模型把全部输出额度花在「思考」上，正文一个字没写。
            // 这种情况自动翻倍额度重试一次，免得用户对着「返回内容为空」反复点续跑。
            if (text.isBlank()) {
                String stop = root.path("stop_reason").asText("");
                if ("max_tokens".equals(stop) && maxTokens < MAX_TOKENS_CEILING) {
                    int retryWith = Math.min(maxTokens * 2, MAX_TOKENS_CEILING);
                    System.err.println("[myreview] 输出额度被思考占满（stop_reason=max_tokens），提高到 "
                            + retryWith + " 重试一次");
                    JsonNode retry = mapper.readTree(post(apiBase, apiKey, format,
                            buildBody(format, model, temperature, retryWith, system, user, thinking)).body());
                    text = extractText(format, retry);
                    root = retry;
                }
            }
            if (text.isBlank()) {
                String stop = root.path("stop_reason").asText("未知");
                throw new LlmException("AI 返回内容为空（stop_reason=" + stop
                        + "，多半是思考占用了全部输出额度）。请重试。原始返回：" + truncate(root.toString(), 300));
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

    /* ================= 协议差异都收敛在下面三段 ================= */

    private HttpResponse<String> post(String apiBase, String apiKey, String format, String body) throws Exception {
        boolean openai = "openai".equalsIgnoreCase(format);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + (openai ? "/v1/chat/completions" : "/v1/messages")))
                // 出一整卷 / 批改整卷的输出很长，给足时间，避免长输出被掐断
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json");
        if (openai) {
            // OpenAI 兼容端点认 Bearer
            b.header("Authorization", "Bearer " + apiKey);
        } else {
            // Anthropic 认 x-api-key + 版本头，不要多塞 Authorization
            b.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
        }
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String buildBody(String format, String model, double temperature, int maxTokens,
                             String system, String user, boolean thinking) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        if ("openai".equalsIgnoreCase(format)) {
            // OpenAI 协议：system 放进 messages，上限字段叫 max_tokens
            body.put("max_tokens", maxTokens);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", system);
            messages.addObject().put("role", "user").put("content", user);
        } else {
            // Anthropic 协议：system 是顶层字段，上限字段叫 max_tokens
            body.put("max_tokens", maxTokens);
            body.put("system", system);
            // 关掉思考：它计入输出额度，长输出任务里会把正文挤到截断甚至为空
            if (!thinking) body.putObject("thinking").put("type", "disabled");
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "user").put("content", user);
        }
        return mapper.writeValueAsString(body);
    }

    /** 从响应里取正文：Anthropic 在 content[] 的 text 块，OpenAI 在 choices[].message.content */
    private String extractText(String format, JsonNode root) {
        StringBuilder sb = new StringBuilder();
        if ("openai".equalsIgnoreCase(format)) {
            for (JsonNode choice : root.path("choices")) {
                sb.append(choice.path("message").path("content").asText(""));
            }
        } else {
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText(""));
                }
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
        boolean looksTruncated = !text.endsWith("}") && !text.endsWith("}}");
        String diag = "（共 " + text.length() + " 字，"
                + (looksTruncated ? "末尾不是 } —— 多半是输出被截断" : "末尾是 } —— 多半是 JSON 本身非法") + "）";
        if (start < 0 || end <= start) {
            throw new LlmException("AI 返回的不是合法 JSON，" + diag + "。返回内容：" + truncate(content, 300));
        }
        try {
            return mapper.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            throw new LlmException("AI 返回的 JSON 解析失败，" + diag + "，请重试。返回内容：" + truncate(content, 300));
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
