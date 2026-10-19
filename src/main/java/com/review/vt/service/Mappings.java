package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.vt.domain.Attempt;
import com.review.vt.domain.Practice;
import com.review.vt.domain.Question;
import com.review.vt.domain.Stage;
import com.review.vt.repo.AttemptRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.web.Dtos.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实体 → DTO 映射与统计计算（个人数据规模，直接循环统计）。 */
final class Mappings {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Mappings() {}

    static JsonNode parseJson(String s) {
        if (s == null || s.isBlank()) return null;
        try { return MAPPER.readTree(s); } catch (Exception e) { return null; }
    }

    static Map<String, Double> parseDimensions(String s) {
        JsonNode node = parseJson(s);
        if (node == null || !node.isObject()) return Map.of();
        Map<String, Double> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asDouble()));
        return map;
    }

    static List<String> stringList(JsonNode node, String field) {
        List<String> list = new ArrayList<>();
        JsonNode arr = node.path(field);
        if (arr.isArray()) arr.forEach(x -> list.add(x.asText()));
        return list;
    }

    static StageDto stage(Stage s, Stats stats) {
        return new StageDto(s.getId(), s.getName(), s.getGoal(), s.getStatus(),
                s.getCreatedAt(), s.getUpdatedAt(), s.getCompletedAt(),
                parseJson(s.getSummaryJson()), stats);
    }

    static StageDetailDto stageDetail(Stage s, Stats stats, List<PracticeDto> practices) {
        return new StageDetailDto(s.getId(), s.getName(), s.getGoal(), s.getStatus(),
                s.getCreatedAt(), s.getUpdatedAt(), s.getCompletedAt(),
                parseJson(s.getSummaryJson()), stats, practices);
    }

    static PracticeDto practice(Practice p, Stats stats) {
        return new PracticeDto(p.getId(), p.getStageId(), p.getMode(), p.getTopic(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt(),
                parseJson(p.getSummaryJson()), stats);
    }

    static QuestionDto question(Question q, List<Attempt> attempts) {
        ExplanationDto explanation = q.getExplainedAt() == null ? null
                : new ExplanationDto(q.getExplanationDetail(), q.getExplanationMemo(), q.getExplainedAt());
        List<AttemptDto> attemptDtos = attempts.stream().map(Mappings::attempt).toList();
        return new QuestionDto(q.getId(), q.getPracticeId(), q.getSequence(), q.getPrompt(),
                q.getCreatedAt(), explanation, attemptDtos);
    }

    static AttemptDto attempt(Attempt a) {
        EvaluationDto evaluation = a.getEvaluatedAt() == null ? null
                : new EvaluationDto(a.getScore(), a.getFeedback(), parseDimensions(a.getDimensionsJson()),
                        a.getEvaluationDetail(), a.getEvaluationMemo(), a.getEvaluatedAt());
        return new AttemptDto(a.getId(), a.getSequence(), a.getAnswer(), a.getSubmittedAt(), evaluation);
    }

    /** 一次练习的统计 */
    static Stats practiceStats(Practice p, QuestionRepository qRepo, AttemptRepository aRepo) {
        List<Question> questions = qRepo.findByPracticeIdOrderBySequenceAsc(p.getId());
        return stats(1, questions, aRepo);
    }

    /** 一个阶段的统计 */
    static Stats stageStats(List<Practice> practices, QuestionRepository qRepo, AttemptRepository aRepo) {
        List<Question> all = new ArrayList<>();
        for (Practice p : practices) all.addAll(qRepo.findByPracticeIdOrderBySequenceAsc(p.getId()));
        return stats(practices.size(), all, aRepo);
    }

    private static Stats stats(long practiceCount, List<Question> questions, AttemptRepository aRepo) {
        long attemptCount = 0, evaluatedCount = 0;
        double scoreSum = 0;
        for (Question q : questions) {
            for (Attempt a : aRepo.findByQuestionIdOrderBySequenceAsc(q.getId())) {
                attemptCount++;
                if (a.getEvaluatedAt() != null && a.getScore() != null) {
                    evaluatedCount++;
                    scoreSum += a.getScore();
                }
            }
        }
        Double averageScore = evaluatedCount == 0 ? null
                : Math.round(scoreSum / evaluatedCount * 10) / 10.0;
        return new Stats(practiceCount, questions.size(), attemptCount, evaluatedCount, averageScore);
    }
}
