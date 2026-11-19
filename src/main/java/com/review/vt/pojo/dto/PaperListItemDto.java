package com.review.vt.pojo.dto;

/** 卷下拉列表项（答题页切卷用）：只给定位与状态，不含题目。 */
public record PaperListItemDto(String id, int sequence, String kind, String status,
                               String compositionMode, Integer maxScore,
                               Long createdAt, Long submittedAt) {}
