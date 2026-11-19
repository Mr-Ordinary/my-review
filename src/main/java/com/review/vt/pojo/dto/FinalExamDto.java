package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record FinalExamDto(String id, int sequence, String status, long createdAt,
                           Long submittedAt, int maxScore, Double score,
                           CoverageDto coverage, List<CategoryScoreDto> categories,
                           List<QuestionDto> questions) {}
