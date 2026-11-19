package com.review.vt.pojo.dto;

import java.util.List;

/**
 * 题内容 VO（切题只查这一题）：题面等全内容 + 最近一次作答的 attempt id（有回答时前端再取 attempt:{id}）。
 * 也是 question:{id} 缓存的值；userId 用于缓存命中归属校验。
 */
public record QuestionDetailDto(Long userId,
                                String id, String paperId, int sequence, String prompt,
                                String referenceAnswer,
                                List<String> assignedPoints, String angle,
                                List<String> knowledgePoints, List<String> demands,
                                String tier, String questionType, Integer points,
                                String qtype, String optionsJson, String answerJson,
                                Long createdAt, Long revealedAt,
                                Long latestAttemptId) {}
