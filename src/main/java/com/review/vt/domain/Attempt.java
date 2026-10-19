package com.review.vt.domain;

/**
 * 一次作答（追加保存，不覆盖历史）。
 * 作答过程不调 AI；评价在「交卷」时由 AI 对整卷批改后写回这里。
 * score 是 0-10 的表现分（AI 直接给，不用换算），points 是换算后的实得分（0 到 该题满分）。
 * 字段与 data/store/attempts.json 行（及后续数据库表列）1:1 对应。
 */
public class Attempt {

    private String id;

    private String questionId;

    private int sequence;

    private String answer;

    private long submittedAt;

    /** 评价（交卷批改后写入）：表现分 0-10 */
    private Double score;

    /** 实得分 = score/10 × 该题分值（交卷批改后写入） */
    private Double points;

    private String feedback;

    /** 维度分 JSON：{"场景分析":8,"技术深度":7,"表达结构":9} */
    private String dimensionsJson;

    private Long evaluatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getQuestionId() { return questionId; }
    public void setQuestionId(String questionId) { this.questionId = questionId; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public long getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(long submittedAt) { this.submittedAt = submittedAt; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Double getPoints() { return points; }
    public void setPoints(Double points) { this.points = points; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public String getDimensionsJson() { return dimensionsJson; }
    public void setDimensionsJson(String dimensionsJson) { this.dimensionsJson = dimensionsJson; }
    public Long getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Long evaluatedAt) { this.evaluatedAt = evaluatedAt; }
}
