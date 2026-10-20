package com.review.vt.service;

import java.util.List;

/**
 * 语音转文字。实现从「音频采样」到「文本」，不关心录音与上传。
 *
 * 设计成接口是为了让识别后端可换（sherpa-onnx / Vosk / whisper.cpp …），
 * 也为了模型没装、平台不支持时能优雅降级，而不是拖垮整个应用。
 */
public interface SpeechTranscriber {

    /** 模型与 native 库都就绪、可以识别 */
    boolean isReady();

    /** 不可用的原因；可用时返回 null。前端据此提示用户该做什么 */
    String unavailableReason();

    /**
     * 识别一段音频。
     *
     * @param samples    归一化到 [-1, 1] 的单声道采样
     * @param sampleRate 采样率
     * @return 识别出的文本（可能为空字符串）
     */
    default String transcribe(float[] samples, int sampleRate) {
        return transcribe(samples, sampleRate, List.of());
    }

    /**
     * 识别一段音频，并对给定的「热词」做偏置。
     *
     * 中文模型的英文词表有限，equals / volatile 这类技术术语很容易被认成同音词。
     * 传入当前题目的考察点与常见术语，能明显把它们救回来。
     *
     * @param hotwords 希望被正确识别的词（会与内置技术术语表合并去重）
     */
    String transcribe(float[] samples, int sampleRate, List<String> hotwords);
}
