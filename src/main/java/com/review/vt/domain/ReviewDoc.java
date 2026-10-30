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

    private String id;

    private String practiceId;

    /** generating（生成中） / ready（就绪） / failed（部分失败，可续跑） */
    private String status;

    /** 学习卡 JSON：{"points":[{name,category,priority,summary,interview,principle,params[],pitfalls[],selfCheck{q,a}}]} */
    private String contentJson;

    /** 后台任务进度：{planned, done, failed[], running, error} */
    private String progressJson;

    private long createdAt;

    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPracticeId() { return practiceId; }
    public void setPracticeId(String practiceId) { this.practiceId = practiceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public String getProgressJson() { return progressJson; }
    public void setProgressJson(String progressJson) { this.progressJson = progressJson; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
