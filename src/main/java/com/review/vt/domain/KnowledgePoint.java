package com.review.vt.domain;

/**
 * 知识点：练习范围拆出来的一个个具体考点，出题与掌握度都挂在它上面。
 *
 * 关键设计（2026-10 改版）：
 * - **一个点 = 一个固定内容边界**，拆清单时就声明三档各考到哪为止
 *   （{@link #boundQuick}/{@link #boundStd}/{@link #boundDeep}），出题只能在对应档内出。
 *   不再有「timesSeen 越多挖越深」的自动加深——深度由用户选的**复习强度**决定，
 *   强化=换角度变种，不是往源码里钻。
 * - **重要度靠搜索取证**（{@link #priority} + {@link #evidenceJson}），
 *   不是 AI 空想。速通只出 high，标准出 high+medium，深挖才碰 low。
 * - **掌握度看变种题**：只有同一个点换个角度还答得不错才算掌握，避免蒙对一次就跳过。
 * - **来源两种**：范围拆解（scope）/ 用户作答里发散出来的（answer），后者必须 inScope 才收。
 *
 * 字段与 data/store/knowledge_points.json 行（及后续数据库表列）1:1 对应。
 */
public class KnowledgePoint {

    private String id;

    private String practiceId;

    /** 大类，便于分组展示，例如「面向对象」 */
    private String category;

    /** 知识点名，例如「HashMap · put过程」。一个点=一个固定内容边界，不再向下分层 */
    private String name;

    /** scope（范围拆解） / answer（用户作答发散） */
    private String source;

    /** 是否在练习范围内。发散来的点必须为 true 才收，否则宁可不要 */
    private boolean inScope;

    /**
     * 面试重要度：high（高频必考）/ medium（常见加分）/ low（少见偏门，仅深挖才出）。
     * 由 scaffold 阶段的联网取证分级，证据存 {@link #evidenceJson}。
     */
    private String priority;

    /**
     * 重要度证据 JSON：{"evidence":"…","hits":["…"],"source":"search|fallback"}。
     * 落库是为了可查——二刷觉得分级不对时能翻出当时凭什么这么定。
     */
    private String evidenceJson;

    /** 速通档考到哪为止（一句话内容规格）。例：「是什么、怎么用、什么场景选」 */
    private String boundQuick;

    /** 标准档考到哪为止。例：「主干流程、关键参数、为什么这么设计」 */
    private String boundStd;

    /** 深挖档考到哪为止（天花板，出题禁止越过）。例：「并发表现、与 ConcurrentHashMap 的取舍」 */
    private String boundDeep;

    /** 被考过几次（统计/展示用，不再驱动深度） */
    private int timesSeen;

    /**
     * 历次作答证据：[{"paperSeq":N,"score":0-10,"link":bool}]。
     * link=true 表示这分来自卷末串联大题——对单点掌握度是弱证据，算 mastery 时降权。
     */
    private String historyJson;

    /**
     * 历次出题角度：[{"paperSeq":N,"angle":"…"}]。
     * 变种题的依据——下次出题必须告诉模型这个点**已经这么问过了**，才能真的换问法，
     * 否则「不要重复上一次的角度」只是一句空话。
     */
    private String anglesJson;

    /** 掌握度 0-100（由 history 算出，存一份方便查询/展示） */
    private double mastery;

    /** 未学 / 学习中 / 待巩固 / 已掌握 */
    private String status;

    private long createdAt;

    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPracticeId() { return practiceId; }
    public void setPracticeId(String practiceId) { this.practiceId = practiceId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public boolean isInScope() { return inScope; }
    public void setInScope(boolean inScope) { this.inScope = inScope; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public String getBoundQuick() { return boundQuick; }
    public void setBoundQuick(String boundQuick) { this.boundQuick = boundQuick; }
    public String getBoundStd() { return boundStd; }
    public void setBoundStd(String boundStd) { this.boundStd = boundStd; }
    public String getBoundDeep() { return boundDeep; }
    public void setBoundDeep(String boundDeep) { this.boundDeep = boundDeep; }
    public int getTimesSeen() { return timesSeen; }
    public void setTimesSeen(int timesSeen) { this.timesSeen = timesSeen; }
    public String getHistoryJson() { return historyJson; }
    public void setHistoryJson(String historyJson) { this.historyJson = historyJson; }
    public String getAnglesJson() { return anglesJson; }
    public void setAnglesJson(String anglesJson) { this.anglesJson = anglesJson; }
    public double getMastery() { return mastery; }
    public void setMastery(double mastery) { this.mastery = mastery; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
