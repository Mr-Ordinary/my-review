package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 卷下拉列表项（答题页切卷用）：定位 + 状态 + 成绩区 meta（score/summary/progress），不含题目。
 * score 是统计（Σ每题最近一次已批改实得分），brief 每次现算；summary/progress 是卷行列。
 */
public record PaperListItemDto(String id, int sequence, String kind, String status,
                               String compositionMode, Integer maxScore,
                               Long createdAt, Long submittedAt,
                               Double score, JsonNode summary, JsonNode progress) {}
