package com.review.vt.domain;

/**
 * 考前复习提纲：一个练习一份，按知识点生成「必学 / 理解 / 选学」三层内容。
 *
 * <p>内容与考试共用同一份知识点清单（学考同源），**不受复习强度限制**——
 * 速通也能看到全部内容，只是前端按深度分层展示。
 *
 * 字段与 data/store/reviews.json 行（及后续数据库表列）1:1 对应。
 */
public class ReviewDoc {

    private Long id;

    /** 归属用户（多用户隔离；旧 JSON 数据迁移时统一补 admin） */
    private Long userId;

    private Long practiceId;

    /** generating（生成中） / ready（就绪） / failed（部分失败，可续跑） */
    private String status;

    /**
     * 后台任务进度：{planned, done, failed[], running, error}。
     *
     * <p>学习卡已拆到 {@link ReviewCard}（一行一卡）——原来整份 contentJson 约 30KB，
     * 每 3 个点一批要整块重写，TOAST 写放大严重。
     */
    private String progressJson;

    private long createdAt;

    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getPracticeId() { return practiceId; }
    public void setPracticeId(Long practiceId) { this.practiceId = practiceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProgressJson() { return progressJson; }
    public void setProgressJson(String progressJson) { this.progressJson = progressJson; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
