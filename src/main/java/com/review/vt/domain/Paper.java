package com.review.vt.domain;

/**
 * 一张卷：AI 一次出的一批题（15-30 道，满分 100，前易后难）。
 * 出题与批改都做成后台任务，progressJson 记进度，中断后可从断点续跑。
 * 字段与 data/store/papers.json 行（及后续数据库表列）1:1 对应。
 */
public class Paper {

    private String id;

    private String practiceId;

    /** 练习内第几张卷（从 1 开始，出卷时递增） */
    private int sequence;

    /**
     * normal（普通练习卷） / final（期末综合考） / mistake（错题重做卷）。
     *
     * 期末考是**总结性测量**，普通卷是**形成性学习**，两者的区别在三处：
     * ① 出题上下文不带记忆/摘要/最近历史（免得"最近在考什么"污染测量）；
     * ② 选点不按补足/强化/深入配比，而是全范围分层抽样；
     * ③ **不回写掌握度**——成绩单单独留，不干扰学习轨道。
     * 唯一共用的是角度历史：保证第二次期末考不会重复第一次的问法。
     *
     * 错题重做卷则是普通卷的**选点特例**：只从待订正错题反查出的知识点里选，
     * 其余（上下文、批改、回写掌握度）与普通卷完全一致。
     */
    private String kind;

    /** kind=mistake 时锁定的知识点名（JSON 数组）。出卷时只从这里选，免得中途错题状态变了 */
    private String focusPointsJson;

    /**
     * 组卷模式：regular（常规）/ base（基础）/ improve（提升）/ deep（深入）
     * / baseImprove（基础强化）/ midImprove（中档提升）。
     *
     * 控制一卷里出现哪些难度档、出几道题，不碰知识点的强化链路
     * （选点配比、深度阶梯、掌握度回写都照旧）。null=regular（旧数据）。
     * 期末考/错题卷不走这个字段——它们有自己的结构。
     */
    private String compositionMode;

    /** generating（出题中） / answering（答题中） / grading（批改中） / graded（已交卷） */
    private String status;

    private long createdAt;

    private Long submittedAt;

    /** 本卷总结 JSON：{overall, strengths[], improvements[], gradedAt} */
    private String summaryJson;

    /** 后台任务进度 JSON：{stage:"出题"|"批改", planned:N, done:N, failed:[批次号]} */
    private String progressJson;

    /** 出卷计划 JSON：[{angle,tier,type,points}...]。落库是为了中断后能按原计划断点续跑 */
    private String planJson;

    /** 满分，恒为 100（一张卷就是一张百分卷） */
    private int maxScore;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPracticeId() { return practiceId; }
    public void setPracticeId(String practiceId) { this.practiceId = practiceId; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFocusPointsJson() { return focusPointsJson; }
    public void setFocusPointsJson(String focusPointsJson) { this.focusPointsJson = focusPointsJson; }
    public String getCompositionMode() { return compositionMode; }
    public void setCompositionMode(String compositionMode) { this.compositionMode = compositionMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public Long getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Long submittedAt) { this.submittedAt = submittedAt; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public String getProgressJson() { return progressJson; }
    public void setProgressJson(String progressJson) { this.progressJson = progressJson; }
    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
    public int getMaxScore() { return maxScore; }
    public void setMaxScore(int maxScore) { this.maxScore = maxScore; }
}
