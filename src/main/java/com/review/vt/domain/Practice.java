package com.review.vt.domain;

/**
 * 一次练习 = 一个会话（一个范围练到底，可反复出卷）。
 * 字段与 data/store/practices.json 行（及后续数据库表列）1:1 对应。
 */
public class Practice {

    private String id;

    private String stageId;

    /** interview（真考卷式练习）；预留更多模式 */
    private String mode;

    /** 短标题（列表展示用） */
    private String topic;

    /** topic（主题模式） / range（范围模式） */
    private String scopeMode;

    /** 范围模式下用户原话，例如「范围是 javaguide 基础篇上中下，参照 xxx」；主题模式为 null */
    private String scopeText;

    /** 系统自动发起的全复盘练习 = true，用户创建的 = false */
    private boolean review;

    /** active / completed */
    private String status;

    private long createdAt;

    private long updatedAt;

    private Long completedAt;

    /**
     * L1 长期记忆（跨卷累积，服务端硬上限）JSON：
     * {"mastered":[],"weakPoints":[],"misconceptions":[],"nextFocus":[],"coverage":[],"updatedAt":0}
     */
    private String memoryJson;

    /**
     * L2 会话摘要（每次交卷后重写，长度固定，用来替代「全部历史」进上下文）JSON：
     * {"overview":"","coveredAreas":[],"uncoveredAreas":[],"level":"","updatedAt":0}
     */
    private String digestJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStageId() { return stageId; }
    public void setStageId(String stageId) { this.stageId = stageId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getScopeMode() { return scopeMode; }
    public void setScopeMode(String scopeMode) { this.scopeMode = scopeMode; }
    public String getScopeText() { return scopeText; }
    public void setScopeText(String scopeText) { this.scopeText = scopeText; }
    public boolean isReview() { return review; }
    public void setReview(boolean review) { this.review = review; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long completedAt) { this.completedAt = completedAt; }
    public String getMemoryJson() { return memoryJson; }
    public void setMemoryJson(String memoryJson) { this.memoryJson = memoryJson; }
    public String getDigestJson() { return digestJson; }
    public void setDigestJson(String digestJson) { this.digestJson = digestJson; }
}
