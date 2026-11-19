package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** @param points 实得分 = score/10 × 该题分值 */
public record EvaluationDto(Double score, Double points, String feedback,
                            Map<String, Double> dimensions, Long evaluatedAt) {}
