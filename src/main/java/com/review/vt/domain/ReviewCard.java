package com.review.vt.domain;

/**
 * 复习提纲中的**一张学习卡**（按知识点一行），替代原来整份 contentJson 大字段。
 *
 * <p>拆表原因：一份提纲约 30 个点、整块 JSON 约 30KB，生成时每 3 个点一批
 * 会整块重写 10 次（TOAST 写放大）。拆成一行一卡后每批只写 3 行（约 1.5KB）。
 *
 * <p>唯一键 (reviewDocId, pointName)，同一份提纲同一个点只有一张卡。
 */
public class ReviewCard {

    private Long id;

    /** 归属用户（多用户隔离） */
    private Long userId;

    private Long reviewDocId;

    /** 知识点名（与 knowledge_point.name 对应） */
    private String pointName;

    /** 大类，展示分组用 */
    private String category;

    /** high / medium / low */
    private String priority;

    /** 单卡内容 JSON：{name,category,priority,summary,interview,principle,params[],pitfalls[],selfCheck} */
    private String contentJson;

    /** 展示顺序（与知识点清单一致） */
    private int sequence;

    private long createdAt;

    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getReviewDocId() { return reviewDocId; }
    public void setReviewDocId(Long reviewDocId) { this.reviewDocId = reviewDocId; }
    public String getPointName() { return pointName; }
    public void setPointName(String pointName) { this.pointName = pointName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
