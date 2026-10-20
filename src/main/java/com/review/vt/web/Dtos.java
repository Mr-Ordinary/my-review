package com.review.vt.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** REST 返回结构（与前端 API 契约一一对应）。分数一律是百分制（0-100）。 */
public final class Dtos {

    private Dtos() {}

    /**
     * @param averageScore 百分制平均分：各卷得分（满分 100）的平均，1 位小数
     * @param lastScore    最近一卷得分（0-100），没有已批改的卷则为 null
     */
    public record Stats(long practiceCount, long paperCount, long questionCount,
                        long attemptCount, long evaluatedCount,
                        Double averageScore, Double lastScore) {}

    public record StageDto(String id, String name, String status,
                           long createdAt, long updatedAt, Long completedAt,
                           JsonNode summary, Stats stats) {}

    public record StageDetailDto(String id, String name, String status,
                                 long createdAt, long updatedAt, Long completedAt,
                                 JsonNode summary, Stats stats, List<PracticeDto> practices) {}

    public record PracticeDto(String id, String stageId, String mode, String topic,
                              String scopeMode, String scopeText, String level, boolean review, String status,
                              long createdAt, long updatedAt, Long completedAt,
                              JsonNode memory, JsonNode digest, Stats stats) {}

    public record PracticeDetailDto(String id, String stageId, String mode, String topic,
                                    String scopeMode, String scopeText, String level, boolean review, String status,
                                    long createdAt, long updatedAt, Long completedAt,
                                    JsonNode memory, JsonNode digest,
                                    Stats stats, List<PaperDto> papers) {}

    /** 一张卷：满分 maxScore（恒为 100），score 是已批改后的实际得分 */
    public record PaperDto(String id, int sequence, String status,
                           long createdAt, Long submittedAt,
                           int maxScore, Double score,
                           JsonNode summary, JsonNode progress, Stats stats,
                           List<QuestionDto> questions) {}

    /** @param points 实得分 = score/10 × 该题分值 */
    public record EvaluationDto(Double score, Double points, String feedback,
                                Map<String, Double> dimensions, Long evaluatedAt) {}

    public record AttemptDto(String id, int sequence, String answer, long submittedAt,
                             EvaluationDto evaluation) {}

    /**
     * 知识点：掌握度 0-100，status 未学/生疏/已掌握。
     *
     * @param history 历次表现分（串联大题的分已按弱证据降权前的原分展示）
     * @param angles  历次出题角度，旧→新；出题要避开它们（变种的依据）
     */
    public record KnowledgePointDto(String id, String category, String name, String source,
                                   int timesSeen, double mastery, String status,
                                   java.util.List<Double> history,
                                   java.util.List<String> angles) {}

    /**
     * @param assignedPoints  后端从清单里挑中的点名——掌握度按它们回写，也是「这道题考的是哪个点」
     *                        的唯一可信来源。常规题 1 个，卷末串联大题 2-3 个。
     * @param angle           出题角度（问法），变种题要避开它
     * @param knowledgePoints AI 自己标注的考点叫法，仅供展示，不参与掌握度计算
     */
    public record QuestionDto(String id, String paperId, int sequence, String prompt,
                              String referenceAnswer, List<String> assignedPoints, String angle,
                              List<String> knowledgePoints,
                              String tier, String questionType, int points,
                              long createdAt, Long revealedAt, List<AttemptDto> attempts) {}
}
