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

    /**
     * 岗位难度定位：junior / mid / senior。
     * 决定出题的题型取舍——初级不考复杂场景，中级重点是原理与落地，高级才碰方案取舍。
     */
    private String level;

    /**
     * 复习强度：quick（速通）/ standard（标准）/ deep（深挖）。
     *
     * 决定两件事：① 出题按哪一档**内容规格**出（点上的 boundQuick/boundStd/boundDeep）；
     * ② 进题池的点（速通只出 high，标准 high+medium，深挖全量）。
     *
     * 一刷/二刷/三刷就是同一范围建多个练习、强度递进。
     * 这是**学习阶段属性**，不是单卷属性——深度在你手里，不靠 timesSeen 自动加深。
     */
    private String intensity;

    /**
     * 一卷出几道题。标准是「一次练习的时长」而不是清单规模——
     * 口述一道题约 3-4 分钟，10 题 ≈ 30-40 分钟是一次舒服的练习。
     * 快练 6 / 标准 10 / 深练 15。
     */
    private int paperSize;

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
    /** 长期记忆 JSON（AI 出题上下文） */
    private String memoryJson;

    /**
     * 记事本全文。就是**一整块可编辑文本**，随手记、随手改，不做条目结构。
     *
     * 刻意不用「一条一条」的记录：条目模型没法自由编辑——你在中间改一段，
     * 该算哪条的修改时间？所以日期只有整篇一份，悬停可见。
     * 追加就是末尾换行（{@code \n}），不掺题号——用户明确不要。
     */
    private String notesText;

    /** 记事本最后修改时间。悬停显示用——整篇只有这一个日期（条目式日期跟自由编辑冲突） */
    private Long notesUpdatedAt;

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
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getIntensity() { return intensity; }
    public void setIntensity(String intensity) { this.intensity = intensity; }
    public int getPaperSize() { return paperSize; }
    public void setPaperSize(int paperSize) { this.paperSize = paperSize; }
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
    public String getNotesText() { return notesText; }
    public void setNotesText(String notesText) { this.notesText = notesText; }
    public Long getNotesUpdatedAt() { return notesUpdatedAt; }
    public void setNotesUpdatedAt(Long notesUpdatedAt) { this.notesUpdatedAt = notesUpdatedAt; }
    public String getDigestJson() { return digestJson; }
    public void setDigestJson(String digestJson) { this.digestJson = digestJson; }
}
