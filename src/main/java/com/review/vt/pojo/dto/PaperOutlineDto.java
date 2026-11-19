package com.review.vt.pojo.dto;

import java.util.List;

/**
 * 卷大纲 VO：卷 meta + 题目存根（答题页下拉/题号导航用），也是 paper:{id} 缓存的值。
 * userId 用于缓存命中归属校验（全局键跨用户安全），响应里一并返回无害。
 */
public record PaperOutlineDto(Long userId,
                              String id, String practiceId,
                              int sequence, String kind, String status, String compositionMode,
                              Integer maxScore, Long createdAt, Long submittedAt,
                              List<QuestionStubDto> questions) {}
