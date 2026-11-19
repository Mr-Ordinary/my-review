package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record QuestionDto(String id, String paperId, int sequence, String prompt,
                          String referenceAnswer, List<String> assignedPoints, String angle,
                          List<String> knowledgePoints, List<String> demands,
                          String tier, String questionType, int points,
                          String qtype, String optionsJson, String answerJson,
                          long createdAt, Long revealedAt, List<AttemptDto> attempts) {}
