package com.review.vt.domain;

/**
 * 练习笔记：一条 = 一个场景下针对某题（可为空）随手记的一段话。
 *
 * 结构化条目而不是一篇长文——答题时/看批改时的快记天然带上下文（哪道题、什么场景），
 * 混在一篇文档里会搅成一锅粥。每条挂 questionId（手动新增可为空），前端按它联查题面。
 *
 * 字段与 data/store/notes.json 行（及后续数据库表列）1:1 对应。
 */
public class Note {

    private String id;

    /** 归属练习——笔记本是 per-practice 的，跟着练习的范围走 */
    private String practiceId;

    /** 挂的题目 id；笔记页手动新增时可为空 */
    private String questionId;

    /** answering（答题时，含看完参考答案后）/ grading（看批改点评时）/ manual（笔记页手动） */
    private String scene;

    /** 笔记正文（字数上限见 Limits.MAX_NOTE_TEXT） */
    private String text;

    private long createdAt;

    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPracticeId() { return practiceId; }
    public void setPracticeId(String practiceId) { this.practiceId = practiceId; }
    public String getQuestionId() { return questionId; }
    public void setQuestionId(String questionId) { this.questionId = questionId; }
    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
