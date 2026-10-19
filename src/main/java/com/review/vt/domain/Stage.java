package com.review.vt.domain;

/**
 * 阶段：只是用来区分空间的名字（分组），本身不承载业务逻辑。
 *
 * 阶段里的东西分两类，别混：
 * - **实时统计**（卷数/题数/平均分）：每次查询现算，不落库
 * - **阶段总结**（summaryJson）：AI 给的反馈，交卷后自动重新生成；「全复习」就是基于它做的
 *
 * 字段与 data/store/stages.json 行（及后续数据库表列）1:1 对应。
 */
public class Stage {

    private String id;

    private String name;

    /** active / completed */
    private String status;

    private long createdAt;

    private long updatedAt;

    private Long completedAt;

    /**
     * 阶段总结（AI 反馈，每次交卷后自动重新生成）JSON：
     * {"overall":"","strengths":[],"keyWeaknesses":[],"nextSteps":[],
     *  "reviewScope":"为全复习框定的范围，一长串文字","generatedAt":0}
     */
    private String summaryJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
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
