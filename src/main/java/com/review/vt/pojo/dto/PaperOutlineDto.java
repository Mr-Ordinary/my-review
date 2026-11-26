package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 卷大纲 VO：卷行 meta + 题目存根（答题页下拉/题号导航用），也是 paper:{id} 缓存的值。
 * userId 用于缓存命中归属校验（全局键跨用户安全），响应里一并返回无害。
 * 卷得分是统计，不进这个缓存——由 brief 现算（统计永不缓存）。
 */
public record PaperOutlineDto(Long userId,
                              String id, String practiceId,
                              int sequence, String kind, String status, String compositionMode,
                              Integer maxScore, Long createdAt, Long submittedAt,
                              JsonNode summary, JsonNode progress,
                              List<QuestionStubDto> questions) {}
