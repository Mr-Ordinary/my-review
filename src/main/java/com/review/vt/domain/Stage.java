package com.review.vt.domain;

/**
 * 学习阶段（工作区）：练习/押题/模拟面试都归属某个阶段，阶段可生成总结报告。
 * 字段与 data/store/stages.json 行（及后续数据库表列）1:1 对应。
 */
public class Stage {

    private String id;

    private String name;

    private String goal;

    /** active / completed */
    private String status;

    private long createdAt;

    private long updatedAt;

    private Long completedAt;

    /** 阶段总结 JSON（dimensionScores 由后端计算，文字部分由 AI 生成） */
    private String summaryJson;

    private Long summaryAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long completedAt) { this.completedAt = completedAt; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public Long getSummaryAt() { return summaryAt; }
    public void setSummaryAt(Long summaryAt) { this.summaryAt = summaryAt; }
}
