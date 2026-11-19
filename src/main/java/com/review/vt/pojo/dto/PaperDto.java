package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record PaperDto(String id, int sequence, String kind, String status,
                       String compositionMode,
                       long createdAt, Long submittedAt,
                       int maxScore, Double score,
                       JsonNode summary, JsonNode progress, Stats stats,
                       List<QuestionDto> questions) {}
