package com.review.vt.pojo.param;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出卷请求。
 * @param mode 组卷模式：regular（常规）/ concept（概念题）/ scene（场景题）/ exam（考试模式）。
 *             不传按 regular。只约束这卷出哪些难度档（或哪些题型）、出几道题。
 * @param examMix 考试模式的题型数量（mode=exam 时必填）：单选/多选/填空/简答/代码，
 *                每种 0-30，可为 0，合计至少 1。
 */
public record StartPaperParam(@Schema(description = "组卷模式 regular/concept/scene/exam") String mode, @Schema(description = "考试题型数量（mode=exam 时必填）") ExamMixParam examMix) {}
