package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record MistakeDto(String id, String practiceId, String questionId,
                         String source, String reason, Double score, String status,
                         long createdAt, long updatedAt, Long resolvedAt,
                         String prompt, String answer, String feedback) {}
