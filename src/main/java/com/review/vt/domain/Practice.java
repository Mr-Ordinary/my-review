package com.review.vt.domain;

/**
 * 一次练习 = 一个会话（当前支持背八股，后续可扩展场景题/刷力扣/押题/模拟面试）。
 * 字段与 data/store/practices.json 行（及后续数据库表列）1:1 对应。
 */
public class Practice {

    private String id;

    private String stageId;

    private String mode;

    private String topic;

    /** active / completed */
    private String status;

    private long createdAt;

    private long updatedAt;

    private Long completedAt;

    /** 练习总结 JSON：{kind:'interview', overall, strengths[], improvements[], createdAt} */
    private String summaryJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStageId() { return stageId; }
    public void setStageId(String stageId) { this.stageId = stageId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
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
}
