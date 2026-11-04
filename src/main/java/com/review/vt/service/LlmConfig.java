package com.review.vt.service;

/**
 * 一次 LLM 调用的完整配置快照（不可变）。
 *
 * <p>apiKey 是**明文**：只存在于请求线程或后台任务线程的堆上（UserLlmService.snapshotFor
 * 解密后即用即弃），绝不落库、绝不进日志、绝不出后端。
 */
public record LlmConfig(String apiBase, String apiKey, String model, String apiFormat, double temperature) {

    /** API 根地址（去掉末尾斜杠） */
    public String base() {
        return apiBase == null ? "" : apiBase.replaceAll("/+$", "");
    }

    /** 是否 OpenAI 兼容协议（openai / anthropic 两态） */
    public boolean openai() {
        return "openai".equalsIgnoreCase(apiFormat);
    }
}
