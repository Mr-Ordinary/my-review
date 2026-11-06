package com.review.vt.domain;

/**
 * 记忆点：批改时 AI 给的「能直接背诵的一句话」，挂在知识点名下。
 *
 * 以前它只是拼进点评文本里、看完就散——一个复习系统把最该攒的东西扔了。
 * 现在结构化存下来，复习某个知识点时能直接看到「这个点该背什么」。
 *
 * 一条记忆点来自一道题，可能归属多个知识点（综合题/串联题），
 * 所以 pointNamesJson 是数组而不是单值。
 *
 * 字段与 data/store/memory_points.json 行（及后续数据库表列）1:1 对应。
 */
public class MemoryPoint {

    private Long id;

    /** 归属用户（多用户隔离；旧 JSON 数据迁移时统一补 admin） */
    private Long userId;

    private Long practiceId;

    /** 来自哪道题；点进去能看原题 */
    private Long questionId;

    /** 挂在哪些知识点下，JSON 数组。通常等于该题的 assignedPoints */
    private String pointNamesJson;

    /** 可直接背诵的一句话（浓缩核心结论/关键参数/易错点） */
    private String text;

    private long createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getPracticeId() { return practiceId; }
    public void setPracticeId(Long practiceId) { this.practiceId = practiceId; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getPointNamesJson() { return pointNamesJson; }
    public void setPointNamesJson(String pointNamesJson) { this.pointNamesJson = pointNamesJson; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
