package com.review.vt.pojo.dto;

/** 阶段详情里的练习卡片 VO：平铺统计（均分=Σ实得分/题数），卷/题数含刷题。 */
public record PracticeCardDto(String id, String stageId, String mode, String topic,
                              String scopeMode, String scopeText, String level, String intensity,
                              boolean review, String status,
                              long createdAt, long updatedAt, Long completedAt,
                              Double averageScore,
                              long paperCount, long questionCount,
                              int noteCount, int openMistakeCount) {}
