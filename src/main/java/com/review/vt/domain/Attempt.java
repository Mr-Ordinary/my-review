package com.review.vt.domain;

/**
 * 一次作答（追加保存，不覆盖历史）；评价内联在作答行上，一答一评。
 * 字段与 data/store/attempts.json 行（及后续数据库表列）1:1 对应。
 */
public class Attempt {

    private String id;

    private String questionId;

    private int sequence;

    private String answer;

    private long submittedAt;

    /** 评价：score 0-10 */
    private Double score;

    private String feedback;

    /** 维度分 JSON：{"知识准确性":8,"原理深度":7,"表达结构":9} */
    private String dimensionsJson;

    /** 评价附带的详细讲解 / 可背诵答案 */
    private String evaluationDetail;

    private String evaluationMemo;

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
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public String getDimensionsJson() { return dimensionsJson; }
    public void setDimensionsJson(String dimensionsJson) { this.dimensionsJson = dimensionsJson; }
    public String getEvaluationDetail() { return evaluationDetail; }
    public void setEvaluationDetail(String evaluationDetail) { this.evaluationDetail = evaluationDetail; }
    public String getEvaluationMemo() { return evaluationMemo; }
    public void setEvaluationMemo(String evaluationMemo) { this.evaluationMemo = evaluationMemo; }
    public Long getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Long evaluatedAt) { this.evaluatedAt = evaluatedAt; }
}
