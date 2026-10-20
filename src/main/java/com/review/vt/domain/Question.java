package com.review.vt.domain;

/**
 * 一道题（属于某张卷，卷内按 sequence 递增）。
 * 一张卷是一张 100 分的真考卷：题目分「基础 / 中档 / 大题」三档，每题有分值，卷内分值合计 100。
 * 参考答案在出题时就随题面一起返回，答题过程不需要再调 AI。
 * 字段与 data/store/questions.json 行（及后续数据库表列）1:1 对应。
 */
public class Question {

    private String id;

    private String practiceId;

    private String paperId;

    /** 卷内题号（从 1 开始，前易后难） */
    private int sequence;

    /** 题面：基础题简短，大题是完整业务场景 */
    private String prompt;

    /** 参考答案（出题时生成，面试可直接口述） */
    private String referenceAnswer;

    /** 考察知识点 JSON 数组，例如 ["线程池","拒绝策略"]——AI 自己的叫法，仅供展示 */
    private String knowledgePointsJson;

    /**
     * 本题挂在清单里的哪几个点上（JSON 数组），交卷后按它们回写掌握度。
     * 常规题 1 个；卷末串联大题 2-3 个。
     */
    private String assignedPointsJson;

    /** 出题角度（后端选点时定的问法）。变种题要靠它避开上一次的角度 */
    private String angle;

    /** 基础 / 中档 / 大题 */
    private String tier;

    /** 概念辨析 / 简答 / 原理追问 / 场景应用 / 故障排查 / 方案设计 */
    private String questionType;

    /** 本题分值（卷内合计 100） */
    private int points;

    private long createdAt;

    /**
     * 用户点过「看参考答案」的时间。看过就按规则本题计 0 分——
     * 这是刻意的：参考答案是拿来复习的，看了就不能再拿这题的分。
     */
    private Long revealedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPracticeId() { return practiceId; }
    public void setPracticeId(String practiceId) { this.practiceId = practiceId; }
    public String getPaperId() { return paperId; }
    public void setPaperId(String paperId) { this.paperId = paperId; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getReferenceAnswer() { return referenceAnswer; }
    public void setReferenceAnswer(String referenceAnswer) { this.referenceAnswer = referenceAnswer; }
    public String getKnowledgePointsJson() { return knowledgePointsJson; }
    public void setKnowledgePointsJson(String knowledgePointsJson) { this.knowledgePointsJson = knowledgePointsJson; }
    public String getAssignedPointsJson() { return assignedPointsJson; }
    public void setAssignedPointsJson(String assignedPointsJson) { this.assignedPointsJson = assignedPointsJson; }
    public String getAngle() { return angle; }
    public void setAngle(String angle) { this.angle = angle; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }
    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public Long getRevealedAt() { return revealedAt; }
    public void setRevealedAt(Long revealedAt) { this.revealedAt = revealedAt; }
}
