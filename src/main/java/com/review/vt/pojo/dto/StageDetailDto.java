package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 阶段详情 VO（/api/stages/{id} 专用）：平铺统计，无 stats 嵌套。
 * 均分 = Σ每题实得分（最近一次已批改）/ 题目数（题目加权），卷/题数全部计入（含刷题卷）。
 */
public record StageDetailDto(String id, String name, String status,
                             long createdAt, long updatedAt, Long completedAt,
                             JsonNode summary,
                             Double averageScore,
                             long practiceCount, long paperCount, long questionCount,
                             List<PracticeCardDto> practices) {}
