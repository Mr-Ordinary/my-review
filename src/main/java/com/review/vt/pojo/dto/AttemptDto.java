package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record AttemptDto(String id, int sequence, String answer, long submittedAt,
                         EvaluationDto evaluation) {}
