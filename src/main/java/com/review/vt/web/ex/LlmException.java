package com.review.vt.web.ex;

/** AI 调用失败（网络/空返回/工具循环中止等），前端按 message 提示用户重试或检查设置。 */
public class LlmException extends RuntimeException {
    public LlmException(String message) { super(message); }
}
