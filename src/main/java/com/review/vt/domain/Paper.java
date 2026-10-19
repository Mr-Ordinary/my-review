package com.review.vt.domain;

/**
 * 一张卷：AI 一次出的一批题（15-30 道，满分 100，前易后难）。
 * 出题与批改都做成后台任务，progressJson 记进度，中断后可从断点续跑。
 * 字段与 data/store/papers.json 行（及后续数据库表列）1:1 对应。
 */
public class Paper {

    private String id;

    private String practiceId;

    /** 练习内第几张卷（从 1 开始，出卷时递增） */
    private int sequence;

    /** generating（出题中） / answering（答题中） / grading（批改中） / graded（已交卷） */
    private String status;

    private long createdAt;

    private Long submittedAt;

    /** 本卷总结 JSON：{overall, strengths[], improvements[], gradedAt} */
    private String summaryJson;

    /** 后台任务进度 JSON：{stage:"出题"|"批改", planned:N, done:N, failed:[批次号]} */
    private String progressJson;

    /** 出卷计划 JSON：[{angle,tier,type,points}...]。落库是为了中断后能按原计划断点续跑 */
    private String planJson;

    /** 满分，恒为 100（一张卷就是一张百分卷） */
    private int maxScore;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPracticeId() { return practiceId; }
    public void setPracticeId(String practiceId) { this.practiceId = practiceId; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public Long getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Long submittedAt) { this.submittedAt = submittedAt; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public String getProgressJson() { return progressJson; }
    public void setProgressJson(String progressJson) { this.progressJson = progressJson; }
    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
    public int getMaxScore() { return maxScore; }
    public void setMaxScore(int maxScore) { this.maxScore = maxScore; }
}
