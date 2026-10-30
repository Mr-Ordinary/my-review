package com.review.vt.domain;

/**
 * 一道题的追问消息（聊天记录）。
 *
 * <p>**按 questionId 隔离**：每道题一段独立会话，问 A 题不会带上 B 题的上下文。
 * user 与 assistant 两种角色，按 createdAt 升序展示；同毫秒时保持插入顺序。
 *
 * 字段与 data/store/followups.json 行（及后续数据库表列）1:1 对应。
 */
public class FollowUp {

    private String id;

    private String practiceId;

    private String questionId;

    /** user（用户提问） / assistant（AI 回答） */
    private String role;

    private String content;

    private long createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPracticeId() { return practiceId; }
    public void setPracticeId(String practiceId) { this.practiceId = practiceId; }
    public String getQuestionId() { return questionId; }
    public void setQuestionId(String questionId) { this.questionId = questionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
