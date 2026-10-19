package com.review.vt.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** REST 返回结构（与前端 API 契约一一对应）。 */
public final class Dtos {

    private Dtos() {}

    public record Stats(long practiceCount, long questionCount, long attemptCount,
                        long evaluatedCount, Double averageScore) {}

    public record StageDto(String id, String name, String goal, String status,
                           long createdAt, long updatedAt, Long completedAt,
                           JsonNode summary, Stats stats) {}

    public record StageDetailDto(String id, String name, String goal, String status,
                                 long createdAt, long updatedAt, Long completedAt,
                                 JsonNode summary, Stats stats, List<PracticeDto> practices) {}

    public record PracticeDto(String id, String stageId, String mode, String topic, String status,
                              long createdAt, long updatedAt, Long completedAt,
                              JsonNode summary, Stats stats) {}

    public record PracticeDetailDto(String id, String stageId, String mode, String topic, String status,
                                    long createdAt, long updatedAt, Long completedAt,
                                    JsonNode summary, Stats stats, List<QuestionDto> questions) {}

    public record ExplanationDto(String detail, String memorizationPoints, Long explainedAt) {}

    public record EvaluationDto(Double score, String feedback, Map<String, Double> dimensions,
                                String detail, String memorizationPoints, Long evaluatedAt) {}

    public record AttemptDto(String id, int sequence, String answer, long submittedAt,
                             EvaluationDto evaluation) {}

    public record QuestionDto(String id, String practiceId, int sequence, String prompt,
                              long createdAt, ExplanationDto explanation, List<AttemptDto> attempts) {}
}
