package com.review.vt.domain;

/**
 * 一道题（一次会话内按 sequence 递增）。
 * 字段与 data/store/questions.json 行（及后续数据库表列）1:1 对应。
 */
public class Question {

    private String id;

    private String practiceId;

    private int sequence;

    private String prompt;

    private long createdAt;

    /** 看答案（不创建作答、不评分） */
    private String explanationDetail;

    private String explanationMemo;

    private Long explainedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPracticeId() { return practiceId; }
    public void setPracticeId(String practiceId) { this.practiceId = practiceId; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public String getExplanationDetail() { return explanationDetail; }
    public void setExplanationDetail(String explanationDetail) { this.explanationDetail = explanationDetail; }
    public String getExplanationMemo() { return explanationMemo; }
    public void setExplanationMemo(String explanationMemo) { this.explanationMemo = explanationMemo; }
    public Long getExplainedAt() { return explainedAt; }
    public void setExplainedAt(Long explainedAt) { this.explainedAt = explainedAt; }
}
