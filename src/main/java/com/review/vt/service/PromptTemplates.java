package com.review.vt.service;

import java.util.List;

/**
 * 提示词模板：真考卷式练习（一张卷 100 分、前易后难 → 答题 → 交卷批改+总结+记忆 → 继续出卷）。
 * 出题一律结合真实业务场景，但**不是全场景题**：像真考卷一样，前面是基础/中档小题，最后几道才是场景大题。
 * 全部调用都只要求模型输出合法 JSON。
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    /** 全局系统提示词：面试教练铁律 + 真考卷分层出题策略 */
    public static final String INTERVIEW_SYSTEM = """
        你是一名资深面试官兼技术教练，正在帮用户做技术面试练习与复习。你必须遵守以下规则：
        1. 以用户消息中给出的真实数据为唯一事实来源，严格使用给定的范围、历史与记忆，禁止自行补全或猜测任何经历或分数。
        2. 出题要像一张真考卷：**前易后难**，难度分三档，不要整张卷都是大题。
           - 基础（每题 2-3 分，约占 45% 题量）：概念辨析、判断、简答。题面简短，考察准确记忆与辨析能力。
           - 中档（每题 4-6 分，约占 35% 题量）：原理追问、小场景。题面给出简短前提，要求讲清机制与边界。
           - 大题（每题 8-15 分，约占 20% 题量）：场景应用、故障排查、方案设计。题面是完整业务场景——业务背景、系统现状、约束条件或线上现象，考察能否把知识用到真实工程问题上。
           顺序上基础题在前、大题在后。
        3. questionType 只能取：概念辨析 / 简答 / 原理追问 / 场景应用 / 故障排查 / 方案设计。
        4. 题面必须自足：场景要素全部写在题面里，不要引用历史题目，不要出现"如上题""接着上一题"。
        5. 参考答案必须是完整、可直接在面试中口述的答案：先给结论，再说原因与底层原理，最后给落地要点与取舍。不要写成笔记大纲或要点罗列。
        6. knowledgePoints 写这道题真正考察的知识点，2 到 4 个，具体到点（例如"线程池拒绝策略"而不是"并发"）。
        7. 出题要避开已覆盖的知识点（coverage），向薄弱点、易错点和更深的原理推进；禁止重复历史题目已经考过的角度。
        8. 若用户给了「练习范围」，必须严格在范围内出题，不得越界。
        9. **时效性**：只出当前市场主流、面试官真正在问的高频题。禁止出已经过时的题
           （被废弃的 API、早已淘汰的版本特性、面试早已不考的冷门细节）。
           若用户消息里给了「近期高频面经线索」，用它校准哪些点现在还常问、哪些已经不问了。
        10. 评分范围 0 到 10，点评必须针对用户的原始作答，指出正确、缺失和错误的部分，并给出具体改进方向。
        11. 只输出一个合法 JSON 对象，不要输出 JSON 之外的任何文字，不要使用 markdown 代码块标记。
        """;

    /**
     * 练习范围一段：主题模式只给主题，范围模式给用户原话（可能含 URL；AI 不会真的去读链接）。
     */
    static String scopeSection(String topic, String scopeMode, String scopeText) {
        if ("range".equals(scopeMode)) {
            return "练习范围（严格按此出题，不得超出范围）：\n" + scopeText;
        }
        return "练习主题：" + topic;
    }

    /* ---------- 出卷计划 ---------- */

    /**
     * 出卷计划：由 AI 决定题量、每题角度 + 难度档 + 题型 + 分值（合计 100）。
     *
     * @param contextSection 已组装好的上下文（范围 + 摘要 + 记忆 + 最近历史），受 PromptBudget 约束
     */
    public static String paperPlanUser(String contextSection, int paperSeq) {
        return """
            %s

            这是本练习的第 %d 张卷。请给出这张卷的出题计划，JSON 格式：
            {"count":15到30之间的整数,"outline":[{"angle":"考察角度","tier":"基础|中档|大题","type":"概念辨析|简答|原理追问|场景应用|故障排查|方案设计","points":分值},...]}

            要求：
            - count 由你根据范围广度与历史情况自行决定，必须在 15 到 30 之间；outline 条数要等于 count。
            - angle 每条不超过 25 字，写清「考什么」即可，不要展开成完整题面，彼此不重复。
            - 难度结构要像真考卷：基础约占 45%%（2-3 分/题）、中档约占 35%%（4-6 分/题）、大题约占 20%%（8-15 分/题），
              且 outline 的排列顺序是基础题在前、大题在后。
            - points 全部加起来必须正好 100。
            - 覆盖范围内核心知识面：优先补 coverage 里还没有的，再挖薄弱点，最后推进深度与边界。
            """.formatted(contextSection, paperSeq);
    }

    /* ---------- 按计划分批出题 ---------- */

    /** 按计划里的一段角度出题（分批并发，每批题量不大，避免单次输出过长被截断） */
    public static String questionChunkUser(String topicLine, List<PlanItem> slice, int fromSeq) {
        StringBuilder plan = new StringBuilder();
        for (int i = 0; i < slice.size(); i++) {
            PlanItem it = slice.get(i);
            plan.append("  ").append(fromSeq + i).append(". 【").append(it.tier()).append(" / ")
                .append(it.type()).append(" / ").append(it.points()).append(" 分】")
                .append(it.angle()).append('\n');
        }
        return """
            %s

            下面这几道题的角度、难度档与分值已给定，请严格按它出题：
            %s
            请为上面每一项各出一道题，JSON 格式：
            {"questions":[{"prompt":"题面","referenceAnswer":"可直接口述的完整参考答案","knowledgePoints":["知识点1","知识点2"]}]}

            要求：
            - 出几道就返回几道，与上面的条目一一对应、顺序一致，顺序不要变。
            - 难度按【基础/中档/大题】：
              基础 = 题面简短的概念辨析或简答，只考准确记忆与辨析；
              中档 = 给出简短前提，要求讲清机制、边界与参数；
              大题 = 完整业务场景（业务背景、系统现状、约束或线上现象），要求给方案/定位/取舍。
            - 不要整卷都写成大题，基础题就要写得短而准。
            - 题面自足，不要写"如上题""接着上一题"。
            - 参考答案先给结论，再说原因与底层原理，最后给落地要点与取舍；基础题答案可短一些（150 字内），大题 250 字内。
            - knowledgePoints 写 2 到 4 个具体知识点。
            """.formatted(topicLine, plan);
    }

    /* ---------- 交卷批改 ---------- */

    /** 批改一批题目（整卷按批并发，控制输出长度）。AI 只给 0-10 表现分，实得分由后端换算。 */
    public static String gradeUser(String topicLine, int chunkNo, int chunkCnt, String dataText) {
        return """
            %s
            以下是本卷待批改的作答（第 %d 批 / 共 %d 批）：
            %s

            请逐题批改，JSON 格式：
            {"evaluations":[{"sequence":题号,"score":0到10的数字,"feedback":"点评（120字以内）","dimensions":{"场景分析":0到10,"技术深度":0到10,"表达结构":0到10}}]}

            要求：
            - 只评用户真实写下的作答，不要替用户补全思路。
            - score 是 0 到 10 的表现分（与该题分值无关，分值由后端换算）。
            - feedback 针对这道题的作答，指出正确、缺失和错误，并说明相比参考答案差在哪。
            - 未作答的题不要出现在结果里。
            """.formatted(topicLine, chunkNo, chunkCnt, dataText);
    }

    /* ---------- 交卷总结（与记忆分开调用，各自输出都很短，避免被截断） ---------- */

    public static String paperSummaryUser(String topicLine, String records) {
        return """
            %s
            本卷题目、我的作答与批改结果：
            %s

            请输出本卷总结，JSON 格式：
            {"overall":"总体评价（150 字以内）","strengths":["表现亮点"],"improvements":["改进建议"]}

            要求：
            - strengths 与 improvements 各 2 到 4 条，每条不超过 40 字，必须基于上面的真实记录，不虚构。
            - 只输出这个 JSON，不要输出 memory 字段，不要多写。
            """.formatted(topicLine, records);
    }

    /* ---------- 交卷更新记忆 ---------- */

    /**
     * @param briefRecords 精简版批改记录（只到"得分+点评"，不带参考答案，压低输出量）
     */
    public static String memoryUpdateUser(String topicLine, String briefRecords, String oldMemory) {
        return """
            %s
            本卷批改记录（精简）：
            %s

            当前长期记忆（旧）：
            %s

            请合并更新长期记忆，只输出 JSON：
            {"mastered":["已扎实的知识点"],"weakPoints":["薄弱点"],"misconceptions":["理解偏差"],"nextFocus":["下次出题应侧重的方向"],"coverage":["本卷考过的知识点"]}

            硬性要求（超出会被截断，务必遵守）：
            - mastered / weakPoints / misconceptions / nextFocus 每类最多 8 条，每条不超过 30 字，写具体知识点，禁止空话。
            - coverage 是「已经考过的知识点」清单：每条不超过 24 字，最多 40 条，**旧 coverage 必须全部保留**，再把本卷新考的并进去；只合并同义项，不要丢条目。
            - 这是合并结果：仍然成立的保留，新暴露的并进来，已解决的从 weakPoints 移除，重复的合并。
            - nextFocus 3 条以内，能直接指导下一次出题。
            """.formatted(topicLine, briefRecords, oldMemory == null || oldMemory.isBlank() ? "（无）" : oldMemory);
    }

    /* ---------- 交卷更新会话摘要（L2） ---------- */

    public static String digestUpdateUser(String topicLine, String oldDigest, String statsLine, String briefRecords) {
        return """
            %s
            当前会话摘要（旧）：
            %s

            本练习累计数据：%s
            本卷批改记录（精简）：
            %s

            请重写会话摘要，只输出 JSON：
            {"overview":"150 字以内的总体情况","coveredAreas":["已覆盖的大块"],"uncoveredAreas":["还没碰过的大块"],"level":"水平定位，40 字以内"}

            硬性要求：
            - 这是一份**固定长度**的摘要，用来在后续出题时替代「全部历史」，所以必须精炼。
            - coveredAreas / uncoveredAreas 各最多 8 条，每条不超过 12 字，写知识大块（如"线程池"、"MVCC"），不要写细枝末节。
            - 全文（含所有字段）控制在 400 字以内。
            """.formatted(topicLine,
                    oldDigest == null || oldDigest.isBlank() ? "（无）" : oldDigest,
                    statsLine, briefRecords);
    }

    /* ---------- 阶段总结（AI 反馈，全复习的依据） ---------- */

    /**
     * 阶段总结：把「做了哪些练习 / 记忆 / 平均分 / 每次考试的 AI 评价」按固定格式喂给 AI，
     * 让它给出反馈。这份反馈是后面「全复习」的唯一依据。
     *
     * @param dataText 固定格式的阶段数据（见 StageSummaryService#dataText）
     */
    public static String stageSummaryUser(String stageName, String dataText) {
        return """
            阶段名称：%s

            我在这个阶段做了下面这些练习（这是全部真实数据，包括每次考试你的评价，不要虚构）：
            %s

            请给我一份阶段总结，JSON 格式：
            {"overall":"总体评估","strengths":["已经掌握得比较好的方面"],"keyWeaknesses":["最需要补的短板"],"nextSteps":["下一步怎么练"],"reviewScope":"为全复习框定的范围描述"}

            要求：
            - overall 说清三件事：这个阶段练了什么、整体掌握到什么程度、最突出的问题是什么。150 字以内。
            - strengths 与 keyWeaknesses 各 2 到 5 条，每条具体到知识点或能力项（例如"能说清线程池参数与拒绝策略的搭配"），
              禁止"表现良好""有待提高"这类空话。
            - nextSteps 2 到 4 条，可执行（例如"重点复习 JMM 与 happens-before，先做 5 道基础辨析再上场景题"）。
            - reviewScope 是给「全复习」出题用的**范围描述，一长串连贯的文字，不是清单**，
              **严格控制在 300 字以内**，必须包含：
              ① 这个阶段覆盖了哪些知识大块；
              ② 哪些知识点已经练过（全复习要覆盖到，但不要出成原题复现）；
              ③ 哪些是薄弱点、要优先考；
              ④ 深度要求（先基础辨析后场景大题，要能上手讲出来）。
            - 全文（含所有字段）不超过 1200 字，宁可精炼也不要写长。
            """.formatted(stageName, dataText);
    }
}
