package com.review.vt.domain;

/**
 * 错题本条目：用户栽过的题，per-practice 一本（每个练习有自己的范围）。
 *
 * 关键设计：
 * - **一题一条**（practiceId + questionId 去重）——批改重入天然幂等，不会翻倍。
 * - **自动 + 手动**：交卷按规则自动收（低分/未作答/看过参考答案）；
 *   也可以手动加入「侥幸答对但想盯住」的题。手动行永不被自动收录覆盖或删除。
 * - status：open（待订正）/ resolved（已订正），用户自己维护。
 *
 * 字段与 data/store/mistakes.json 行（及后续数据库表列）1:1 对应。
 */
public class Mistake {

    private String id;

    private String practiceId;

    /** 一题一条，(practiceId, questionId) 天然去重 */
    private String questionId;

    /** auto（批改收录）/ manual（手动加入） */
    private String source;

    /** low-score / unanswered / revealed / manual */
    private String reason;

    /** 收录时的表现分快照（未作答/未批改为 null） */
    private Double score;

    /** open（待订正）/ resolved（已订正） */
    private String status;

    private long createdAt;

    private long updatedAt;

    /** 标记已订正的时间；重新打开时清空 */
    private Long resolvedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPracticeId() { return practiceId; }
    public void setPracticeId(String practiceId) { this.practiceId = practiceId; }
    public String getQuestionId() { return questionId; }
    public void setQuestionId(String questionId) { this.questionId = questionId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public Long getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Long resolvedAt) { this.resolvedAt = resolvedAt; }
}
