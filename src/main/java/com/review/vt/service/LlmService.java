package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 调用：把「system + user」发给模型，拿回一段 JSON。
 *
 * 支持两种接口协议（系统设置里的 apiFormat）：
 * - {@code anthropic}：Anthropic Messages API（POST {apiBase}/v1/messages，x-api-key）
 * - {@code openai}：OpenAI Chat Completions（POST {apiBase}/v1/chat/completions，Bearer），
 *   DeepSeek / 通义 / Ollama / vLLM 等兼容端点都能用
 *
 * 两条路径：
 * - {@link #chatJson}：单发单收，提示词要求模型只输出 JSON，稳健提取。批改/总结等调用走这里。
 * - {@link #chatJsonWithTools}：带工具调用的多轮循环，模型自己决定何时搜/抓网页，直到给出最终文本。
 *   目前只用于出题规划环节。
 *
 * 走 HTTP_PROXY/HTTPS_PROXY 环境变量代理（企业网关场景；没有代理变量就不走代理）。
 */
@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

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

    /**
     * 工具循环最多跑几轮。每轮模型可以并行发多个工具调用（比如一次抓 3 个 URL），
     * 但范围里 URL 多 + 搜索时还是会累积轮数，给足余量。
     */
    static final int MAX_TOOL_ROUNDS = 16;

    /** 连续工具失败熔断：出题是「有工具更好、没工具也行」的加分项，不要无限重试 */
    static final int MAX_CONSECUTIVE_TOOL_FAILS = 8;

    /** 单个工具结果给模型看的字符上限（WebFetcher 正文已限 6000） */
    static final int TOOL_RESULT_MAX = 8000;

    private final SettingsService settings;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public LlmService(SettingsService settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .proxy(HttpProxies.selector())
                .build();
    }

    /* ================= 工具调用类型 ================= */

    /** 一个工具：声明 + 执行 */
    public record Tool(String name, String description, JsonNode inputSchema, ToolHandler handler) {}

    @FunctionalInterface
    public interface ToolHandler {
        /** 返回给模型看的结果文本；抛出的异常会被转成 is_error 工具结果，不会中断循环 */
        String call(JsonNode input) throws Exception;
    }

    private record ToolCall(String id, String name, JsonNode input) {}
    private record ToolResult(String callId, String name, String output, boolean error) {}
    private record Turn(String text, List<ToolCall> toolCalls, String stopReason) {}

    /* ================= 对外 API ================= */

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

    /**
     * 带工具调用的对话：模型可以多轮调用 tools 里的工具，直到给出最终文本，
     * 再按 chatJson 的方式提取 JSON。仅用于出题规划环节；其它调用继续走 chatJson。
     */
    public JsonNode chatJsonWithTools(String system, String user, int maxTokens, boolean thinking,
                                      List<Tool> tools) {
        String content = chatWithTools(system, user, maxTokens, thinking, tools);
        return extractJson(content);
    }

    /* ================= 无工具单发路径（现状） ================= */

    private String chat(String system, String user, int maxTokens, boolean thinking) {
        var s = settings.get();
        String apiBase = String.valueOf(s.get("apiBase")).replaceAll("/+$", "");
        String apiKey = String.valueOf(s.get("apiKey"));
        String model = String.valueOf(s.get("model"));
        String format = String.valueOf(s.getOrDefault("apiFormat", "anthropic"));
        double temperature = clampTemperature(s);

        try {
            HttpResponse<String> resp = post(apiBase, apiKey, format,
                    buildBody(format, model, temperature, maxTokens, system, user, thinking));
            // 模型不支持这么大的 max_tokens 时，退回默认值重试一次
            if ((resp.statusCode() < 200 || resp.statusCode() >= 300)
                    && maxTokens > MAX_TOKENS
                    && String.valueOf(resp.body()).toLowerCase().contains("max_tokens")) {
                log.warn("模型拒绝 max_tokens={}，退回 {} 重试", maxTokens, MAX_TOKENS);
                maxTokens = MAX_TOKENS;
                resp = post(apiBase, apiKey, format,
                        buildBody(format, model, temperature, maxTokens, system, user, thinking));
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw httpError(resp);
            }
            JsonNode root = mapper.readTree(resp.body());
            String text = extractText(format, root);
            logCache(root);

            // 模型偶尔会幻觉出工具调用标记（本轮没给工具），提取时会剥掉，这里记个告警方便排查
            if (text.contains("<tool_call>") || text.contains("<function")) {
                log.warn("模型在无工具轮幻觉出工具调用标记（stop={}），已自动剥离后提取 JSON",
                        root.path("stop_reason").asText(root.path("choices").path(0).path("finish_reason").asText("?")));
            }

            // 正文为空的常见原因：模型把全部输出额度花在「思考」上，正文一个字没写。
            // 这种情况自动翻倍额度重试一次，免得用户对着「返回内容为空」反复点续跑。
            if (text.isBlank()) {
                String stop = root.path("stop_reason").asText("");
                if ("max_tokens".equals(stop) && maxTokens < MAX_TOKENS_CEILING) {
                    int retryWith = Math.min(maxTokens * 2, MAX_TOKENS_CEILING);
                    log.warn("输出额度被思考占满（stop_reason=max_tokens），提高到 {} 重试一次", retryWith);
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

    /* ================= 工具循环路径 ================= */

    private String chatWithTools(String system, String user, int maxTokens, boolean thinking, List<Tool> tools) {
        var s = settings.get();
        String apiBase = String.valueOf(s.get("apiBase")).replaceAll("/+$", "");
        String apiKey = String.valueOf(s.get("apiKey"));
        String model = String.valueOf(s.get("model"));
        String format = String.valueOf(s.getOrDefault("apiFormat", "anthropic"));
        double temperature = clampTemperature(s);
        boolean openai = "openai".equalsIgnoreCase(format);

        // history：已按目标协议塑形的消息列表（anthropic 的 content 是块数组；openai 是字符串）
        List<ObjectNode> history = new ArrayList<>();
        history.add(firstUserMessage(openai, user));

        int consecFails = 0;
        try {
            for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
                HttpResponse<String> resp = post(apiBase, apiKey, format,
                        buildBodyWithTools(format, model, temperature, maxTokens, system, history, tools, thinking));
                if ((resp.statusCode() < 200 || resp.statusCode() >= 300)
                        && maxTokens > MAX_TOKENS
                        && String.valueOf(resp.body()).toLowerCase().contains("max_tokens")) {
                    log.warn("模型拒绝 max_tokens={}，退回 {} 重试", maxTokens, MAX_TOKENS);
                    maxTokens = MAX_TOKENS;
                    resp = post(apiBase, apiKey, format,
                            buildBodyWithTools(format, model, temperature, maxTokens, system, history, tools, thinking));
                }
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    throw httpError(resp);
                }
                JsonNode root = mapper.readTree(resp.body());
                logCache(root);
                Turn turn = extractTurn(format, root);

                if (!turn.toolCalls().isEmpty()) {
                    log.debug("工具循环第 {} 轮：模型请求 {} 个工具调用（stop={}）",
                            round, turn.toolCalls().size(), turn.stopReason());
                }

                if (turn.toolCalls().isEmpty()) {
                    // 终局轮：空白兜底与 chat() 一致
                    String text = turn.text();
                    if (text.isBlank()) {
                        String stop = turn.stopReason();
                        if (("max_tokens".equals(stop) || "length".equals(stop)) && maxTokens < MAX_TOKENS_CEILING) {
                            int retryWith = Math.min(maxTokens * 2, MAX_TOKENS_CEILING);
                            log.warn("工具循环终局轮空白（stop={}），提高到 {} 重试", stop, retryWith);
                            JsonNode retry = mapper.readTree(post(apiBase, apiKey, format,
                                    buildBodyWithTools(format, model, temperature, retryWith, system, history, tools, thinking)).body());
                            Turn retryTurn = extractTurn(format, retry);
                            if (!retryTurn.toolCalls().isEmpty()) {
                                // 重试又带出工具调用：把这轮并回去继续循环
                                appendAssistant(history, openai, retryTurn.text(), retryTurn.toolCalls());
                                List<ToolResult> results = runTools(retryTurn.toolCalls(), tools, new int[]{consecFails});
                                consecFails = results.stream().filter(ToolResult::error).count() >= results.size()
                                        ? consecFails + results.size() : 0;
                                appendToolResults(history, openai, results);
                                continue;
                            }
                            text = retryTurn.text();
                        }
                    }
                    if (text.isBlank()) {
                        throw new LlmException("AI 返回内容为空（stop_reason=" + turn.stopReason()
                                + "）。请重试。原始返回：" + truncate(root.toString(), 300));
                    }
                    return text;
                }

                // 有工具调用：执行并把结果塞回历史
                appendAssistant(history, openai, turn.text(), turn.toolCalls());
                List<ToolResult> results = runTools(turn.toolCalls(), tools, new int[]{consecFails});
                long fails = results.stream().filter(ToolResult::error).count();
                consecFails = fails > 0 ? consecFails + (int) fails : 0;
                if (consecFails >= MAX_CONSECUTIVE_TOOL_FAILS) {
                    throw new LlmException("工具连续失败 " + consecFails + " 次，出题计划中止，请重试");
                }
                appendToolResults(history, openai, results);
                // stop_reason 是 tool_use / end_turn / max_tokens 都不影响——有 tool_calls 就继续
            }
            throw new LlmException("AI 在 " + MAX_TOOL_ROUNDS + " 轮工具调用后仍未给出出题计划，请重试");
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("AI 请求被中断，请重试。");
        } catch (Exception e) {
            throw new LlmException("AI 请求失败：" + e.getMessage() + "。请检查网络和系统设置。");
        }
    }

    /** 执行一批工具调用；单个失败转 is_error 结果，不中断 */
    private List<ToolResult> runTools(List<ToolCall> calls, List<Tool> tools, int[] consecFailsRef) {
        List<ToolResult> results = new ArrayList<>();
        for (ToolCall call : calls) {
            Tool tool = tools.stream().filter(t -> t.name().equals(call.name())).findFirst().orElse(null);
            if (tool == null) {
                results.add(new ToolResult(call.id(), call.name(), "没有名为 " + call.name() + " 的工具", true));
                consecFailsRef[0]++;
                continue;
            }
            try {
                String out = tool.handler().call(call.input());
                out = MemoryRules.cut(out == null ? "" : out, TOOL_RESULT_MAX);
                log.info("工具 {} 调用成功（{} 字）→ {}", call.name(), out.length(),
                        MemoryRules.cut(call.input().toString(), 80));
                results.add(new ToolResult(call.id(), call.name(), out.isBlank() ? "（工具没有返回内容）" : out, false));
                consecFailsRef[0] = 0;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("工具 {} 调用失败：{}", call.name(), msg);
                results.add(new ToolResult(call.id(), call.name(), "工具调用失败：" + msg, true));
                consecFailsRef[0]++;
            }
        }
        return results;
    }

    private ObjectNode firstUserMessage(boolean openai, String user) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", user);
        return msg;
    }

    private void appendAssistant(List<ObjectNode> history, boolean openai, String text, List<ToolCall> calls) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "assistant");
        if (openai) {
            if (text != null && !text.isBlank()) msg.put("content", text);
            ArrayNode toolCalls = msg.putArray("tool_calls");
            for (ToolCall c : calls) {
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", c.id());
                tc.put("type", "function");
                ObjectNode fn = tc.putObject("function");
                fn.put("name", c.name());
                fn.put("arguments", c.input() == null || c.input().isNull() ? "{}" : c.input().toString());
            }
        } else {
            ArrayNode content = msg.putArray("content");
            if (text != null && !text.isBlank()) {
                content.addObject().put("type", "text").put("text", text);
            }
            for (ToolCall c : calls) {
                ObjectNode tu = content.addObject();
                tu.put("type", "tool_use");
                tu.put("id", c.id());
                tu.put("name", c.name());
                tu.set("input", c.input() == null ? mapper.createObjectNode() : c.input());
            }
            // Anthropic 要求 content 不能为空
            if (content.isEmpty()) {
                content.addObject().put("type", "text").put("text", "");
            }
        }
        history.add(msg);
    }

    private void appendToolResults(List<ObjectNode> history, boolean openai, List<ToolResult> results) {
        if (openai) {
            // OpenAI：每个工具结果是独立的 role:tool 消息
            for (ToolResult r : results) {
                ObjectNode msg = mapper.createObjectNode();
                msg.put("role", "tool");
                msg.put("tool_call_id", r.callId());
                msg.put("content", r.output());
                history.add(msg);
            }
        } else {
            // Anthropic：一轮的全部 tool_result 放进紧随其后的一条 user 消息，按 tool_use_id 配对
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", "user");
            ArrayNode content = msg.putArray("content");
            for (ToolResult r : results) {
                ObjectNode tr = content.addObject();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", r.callId());
                tr.put("content", r.output());
                if (r.error()) tr.put("is_error", true);
            }
            history.add(msg);
        }
    }

    private String buildBodyWithTools(String format, String model, double temperature, int maxTokens,
                                      String system, List<ObjectNode> history, List<Tool> tools,
                                      boolean thinking) throws Exception {
        boolean openai = "openai".equalsIgnoreCase(format);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (!openai) {
            putCachedSystem(body, system);
            if (!thinking) body.putObject("thinking").put("type", "disabled");
        }

        ArrayNode toolsArr = body.putArray("tools");
        for (Tool t : tools) {
            if (openai) {
                ObjectNode fn = toolsArr.addObject();
                fn.put("type", "function");
                ObjectNode f = fn.putObject("function");
                f.put("name", t.name());
                f.put("description", t.description());
                f.set("parameters", t.inputSchema());
            } else {
                ObjectNode tool = toolsArr.addObject();
                tool.put("name", t.name());
                tool.put("description", t.description());
                tool.set("input_schema", t.inputSchema());
            }
        }

        ArrayNode messages = body.putArray("messages");
        if (openai) {
            messages.addObject().put("role", "system").put("content", system);
        }
        for (ObjectNode m : history) {
            messages.add(m);
        }
        return mapper.writeValueAsString(body);
    }

    /** 解析一轮响应：正文文本 + 工具调用 + stop 原因 */
    private Turn extractTurn(String format, JsonNode root) {
        boolean openai = "openai".equalsIgnoreCase(format);
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        String stop;
        if (openai) {
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            text.append(message.path("content").asText(""));
            for (JsonNode tc : message.path("tool_calls")) {
                String name = tc.path("function").path("name").asText("");
                JsonNode args;
                try {
                    args = mapper.readTree(tc.path("function").path("arguments").asText("{}"));
                } catch (Exception e) {
                    args = mapper.nullNode();
                }
                calls.add(new ToolCall(tc.path("id").asText(""), name, args));
            }
            stop = choice.path("finish_reason").asText("");
        } else {
            for (JsonNode block : root.path("content")) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    text.append(block.path("text").asText(""));
                } else if ("tool_use".equals(type)) {
                    calls.add(new ToolCall(block.path("id").asText(""), block.path("name").asText(""),
                            block.path("input").isNull() ? mapper.createObjectNode() : block.path("input")));
                }
            }
            stop = root.path("stop_reason").asText("");
        }
        return new Turn(text.toString(), calls, stop);
    }

    /* ================= 协议差异都收敛在下面几段 ================= */

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

    /**
     * 系统提示词打上 prompt caching 标记。
     *
     * 系统提示词是**纯常量**（几千字的规则），但每张卷要调十来次 LLM——
     * 不打缓存就是每次原样重发、每次全价。实测这个代理支持：
     * 第 2 次起 {@code cache_read_input_tokens} 非零，实际输入只剩增量。
     *
     * 注意：Anthropic 的缓存是**前缀匹配**的，所以可缓存的必须是稳定前缀。
     * 系统提示词在最前面、不含任何变量，正好是理想的缓存边界；
     * 会变的内容（本次角度、最近历史）都在 user 消息里，不污染前缀。
     */
    private void putCachedSystem(ObjectNode body, String system) {
        ArrayNode arr = body.putArray("system");
        ObjectNode blk = arr.addObject();
        blk.put("type", "text");
        blk.put("text", system);
        blk.putObject("cache_control").put("type", "ephemeral");
    }

    /** 缓存命中情况打到日志里——不看这个就等于没验证过缓存到底生效没有 */
    private void logCache(JsonNode root) {
        JsonNode u = root.path("usage");
        if (u.isMissingNode() || u.isNull()) return;
        long read = u.path("cache_read_input_tokens").asLong(0);
        long created = u.path("cache_creation_input_tokens").asLong(0);
        long input = u.path("input_tokens").asLong(0);
        if (read > 0 || created > 0) {
            log.info("prompt cache：读 {} / 写 {} / 新输入 {}", read, created, input);
        }
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
            putCachedSystem(body, system);
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

    /**
     * 从模型输出中稳健提取 JSON 对象。
     * 容忍：```json 代码块、前后缀文字、以及残留的 <tool_call><function=...> 标记
     * （模型在无工具调用时偶尔会幻觉出工具调用标记，先把它们剥掉再找 JSON）。
     */
    private JsonNode extractJson(String content) {
        String text = content.trim();
        text = text.replaceAll("(?s)<tool_call>.*?</tool_call>", "").trim();
        text = text.replaceAll("(?s)<function[^>]*>.*?</function>", "").trim();
        text = text.replaceAll("(?s)<function[^>]*/>", "").trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LlmException("AI 返回的不是合法 JSON（找不到 {} 边界）。返回内容：" + truncate(content, 500));
        }
        String json = text.substring(start, end + 1);

        // 策略 1：直接解析
        try {
            return mapper.readTree(json);
        } catch (Exception ignored) {}

        // 策略 2：修尾逗号 + 中文弯引号
        String repaired = json
                .replaceAll(",\\s*([}\\]])", "$1")
                .replaceAll("[\\u201c\\u201d]", "\"")
                .replaceAll("[\\u2018\\u2019]", "'");
        try {
            return mapper.readTree(repaired);
        } catch (Exception ignored) {}

        // 策略 3：修字符串值里的裸双引号
        String escaped = repairUnescapedQuotes(json);
        try {
            return mapper.readTree(escaped);
        } catch (Exception e) {
            boolean looksTruncated = !json.endsWith("}") && !json.endsWith("}}");
            String diag = looksTruncated ? "输出被截断（末尾不是 }）" : "JSON 格式非法";
            throw new LlmException("AI 返回的 JSON 解析失败（" + diag + "），请重试。返回内容：" + truncate(content, 500));
        }
    }

    /** 修字符串值里的裸双引号（"他说"你好"" 这类），只在整体解析失败后使用 */
    private String repairUnescapedQuotes(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 64);
        boolean inString = false;
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < json.length()) {
                    sb.append(c).append(json.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    int j = i + 1;
                    while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                    if (j >= json.length() || ",:}]".indexOf(json.charAt(j)) >= 0) {
                        inString = false;
                        sb.append(c);
                    } else {
                        sb.append("\\\"");
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') inString = true;
                sb.append(c);
            }
            i++;
        }
        return sb.toString();
    }

    private LlmException httpError(HttpResponse<String> resp) {
        return new LlmException("AI 接口返回错误（HTTP " + resp.statusCode() + "）：" + truncate(resp.body(), 300)
                + "。请检查系统设置中的 API Base / API Key / 模型 / 接口协议配置。");
    }

    private static double clampTemperature(java.util.Map<String, Object> s) {
        double temperature = s.get("temperature") instanceof Number n ? n.doubleValue() : 1.0;
        if (temperature < 0.0) temperature = 0.0;
        if (temperature > 1.0) temperature = 1.0;
        return temperature;
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
