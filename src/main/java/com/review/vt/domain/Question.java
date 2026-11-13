package com.review.vt.domain;

/**
 * 一道题（属于某张卷，卷内按 sequence 递增）。
 * 一张卷是一张 100 分的真考卷：题目分「基础 / 中档 / 大题」三档，每题有分值，卷内分值合计 100。
 * 参考答案在出题时就随题面一起返回，答题过程不需要再调 AI。
 * 字段与 data/store/questions.json 行（及后续数据库表列）1:1 对应。
 */
public class Question {

    private Long id;

    /** 归属用户（多用户隔离；旧 JSON 数据迁移时统一补 admin） */
    private Long userId;

    private Long practiceId;

    private Long paperId;

    /** 卷内题号（从 1 开始，前易后难） */
    private int sequence;

    /** 题面：基础题简短，大题是完整业务场景 */
    private String prompt;

    /** 参考答案（出题时生成，面试可直接口述） */
    private String referenceAnswer;

    /** 考察知识点 JSON 数组，例如 ["线程池","拒绝策略"]——AI 自己的叫法，仅供展示 */
    private String knowledgePointsJson;

    /**
     * 本题要求 JSON 数组（批改清单），例如 ["说明约束的是引用还是对象","说明为什么"]。
     * 基础/中档 1-2 条，大题 3 条对应 ①②③。批改只按它逐条核对，参考答案多写的内容不判缺失。
     * 旧数据缺此字段 → 批改退回「以题干为准」。
     */
    private String demandsJson;

    /**
     * 本题挂在清单里的哪几个点上（JSON 数组），交卷后按它们回写掌握度。
     * 常规题 1 个；卷末串联大题 2-3 个。
     */
    private String assignedPointsJson;

    /** 出题角度（后端选点时定的问法）。变种题要靠它避开上一次的角度 */
    private String angle;

    /** 基础 / 中档 / 大题 */
    private String tier;

    /** 概念辨析 / 简答 / 原理追问 / 场景应用 / 故障排查 / 方案设计 */
    private String questionType;

    /**
     * 考试模式的题型：single（单选）/ multi（多选）/ blank（填空）/ short（简答）/ code（代码）。
     * null = 常规文字题（沿用 referenceAnswer + demandsJson 判卷）。
     */
    private String qtype;

    /**
     * 客观题选项 JSON，例如 [{"key":"A","text":"..."},...]（single/multi 用）。
     * 选项顺序即展示顺序，key 固定 A-D。
     */
    private String optionsJson;

    /**
     * 结构化标准答案 JSON（考试模式用）：
     * single:   {"correct":["A"]}
     * multi:    {"correct":["A","C"]}
     * blank:    {"blanks":[{"accepted":["答案1","别名1"]},{"accepted":["..."]}]}
     * short/code: {"demands":["要点1","要点2"]}（批改清单，同常规题 demandsJson 语义）
     */
    private String answerJson;

    /** 本题分值（卷内合计 100） */
    private int points;

    private long createdAt;

    /**
     * 用户点过「看参考答案」的时间。看过就按规则本题计 0 分——
     * 这是刻意的：参考答案是拿来复习的，看了就不能再拿这题的分。
     */
    private Long revealedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getPracticeId() { return practiceId; }
    public void setPracticeId(Long practiceId) { this.practiceId = practiceId; }
    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getReferenceAnswer() { return referenceAnswer; }
    public void setReferenceAnswer(String referenceAnswer) { this.referenceAnswer = referenceAnswer; }
    public String getKnowledgePointsJson() { return knowledgePointsJson; }
    public void setKnowledgePointsJson(String knowledgePointsJson) { this.knowledgePointsJson = knowledgePointsJson; }
    public String getDemandsJson() { return demandsJson; }
    public void setDemandsJson(String demandsJson) { this.demandsJson = demandsJson; }
    public String getAssignedPointsJson() { return assignedPointsJson; }
    public void setAssignedPointsJson(String assignedPointsJson) { this.assignedPointsJson = assignedPointsJson; }
    public String getAngle() { return angle; }
    public void setAngle(String angle) { this.angle = angle; }
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }
    public String getQtype() { return qtype; }
    public void setQtype(String qtype) { this.qtype = qtype; }
    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }
    public String getAnswerJson() { return answerJson; }
    public void setAnswerJson(String answerJson) { this.answerJson = answerJson; }
    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public Long getRevealedAt() { return revealedAt; }
    public void setRevealedAt(Long revealedAt) { this.revealedAt = revealedAt; }
}
