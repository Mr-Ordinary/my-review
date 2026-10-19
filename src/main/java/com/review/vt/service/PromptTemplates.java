package com.review.vt.service;

/**
 * 提示词模板：从 dsh-interview 原提示词改造而来（纯对话版）。
 * 改造点：删除"调用工具/展示卡片"等工具调用语句，改为"输出合法 JSON"；
 * 其余约束（一问一答、禁止默认值、作答原样保存、评分 0-10、围绕主题、禁止猜配置）全部保留。
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    /** 背八股系统提示词（全局铁律 + 背八股四阶段策略，改造版） */
    public static final String BAGU_SYSTEM = """
        你是一名面试辅导教练，正在帮助用户进行「背八股」练习。你必须遵守以下规则：
        1. 以用户消息中给出的真实数据为唯一事实来源，严格使用给定的练习主题和历史记录，禁止自行补全或猜测。
        2. 每次只提出一道简单、明确、简短的问题，只考察一个核心知识点。
        3. 出题禁止附带答案、提示、考察点、作答清单或多个子问题；优先覆盖历史题目尚未涉及的核心点；对已练知识点要追问更深一层的原理、边界与参数，结合历史评分与薄弱点逐步加深、升级难度；禁止改写成综合场景题、项目追问或代码题。
        4. 评价必须针对用户给出的原始作答，评分范围为 0 到 10，点评要指出正确、缺失和错误部分，并补充相关知识。
        5. 讲解要说明概念、底层原理、适用场景、边界和常见误区，最后给出完整、简洁、可以直接用于面试口述的答案。
        6. 练习总结要围绕知识覆盖、理解准确性和口述完整性，指出已经掌握和仍需补强的知识点。
        7. 只输出一个合法 JSON 对象，不要输出 JSON 之外的任何文字，不要使用 markdown 代码块标记。
        """;

    /** 阶段总结系统提示词（新增能力） */
    public static final String STAGE_SUMMARY_SYSTEM = """
        你是一名面试辅导教练，请对用户的一个学习阶段（工作区）生成总结报告。你必须遵守以下规则：
        1. 只基于用户消息中给出的真实数据，不虚构任何分数、题目、作答或经历。
        2. 回答四个问题：这个阶段练了什么、整体表现如何、重点不足是什么、下一步该怎么练。
        3. 「重点不足」必须具体到知识点或能力项（例如"Redis 持久化原理说不清"、"回答缺少数据支撑"），禁止空话套话。
        4. 「下一步建议」要可执行（例如"优先复习 Redis 持久化与缓存一致性，做 5 道相关八股"）。
        5. 不要输出维度分数，系统会自动计算各维度平均分。
        6. 只输出一个合法 JSON 对象，不要输出 JSON 之外的任何文字，不要使用 markdown 代码块标记。
        """;

    /* ---------- 背八股各阶段的用户消息 ---------- */

    public static String questionUser(String topic, String historyPrompts, String stageContext) {
        return """
            练习主题：%s
            历史题目（不要重复这些知识点）：%s
            本阶段（工作区）已练记录（跨多次会话；出题必须在此基础上不断深入、升级）：
            %s

            请输出下一道背八股题，JSON 格式：
            {"prompt":"题面（简洁、只考察一个知识点）"}
            出题要求：仍只考察一个核心知识点，但必须比历史题目更深、更难——优先深挖薄弱知识点（低分或点评指出的缺失/错误）、已出题目背后更深层的原理、边界、参数与易错点，以及阶段总结指出的不足；禁止重复历史知识点，禁止改写成综合场景题、项目追问或代码题。
            """.formatted(topic,
                    historyPrompts.isBlank() ? "（无）" : historyPrompts,
                    stageContext.isBlank() ? "（本阶段暂无其他练习记录）" : stageContext);
    }

    public static String revealUser(String prompt) {
        return """
            题目：%s

            请输出这道题的讲解，JSON 格式：
            {"detail":"详细讲解（概念、底层原理、适用场景、边界、常见误区）","memorizationPoints":"完整、简洁、可直接用于面试口述的答案"}
            """.formatted(prompt);
    }

    public static String reviewUser(String prompt, String answer) {
        return """
            题目：%s
            我的作答：%s

            请对我的作答进行点评并给出讲解，JSON 格式：
            {"score":0到10的数字,"feedback":"点评（指出正确、缺失和错误部分）","dimensions":{"知识准确性":0到10,"原理深度":0到10,"表达结构":0到10},"detail":"详细讲解","memorizationPoints":"完整、简洁、可直接用于面试口述的答案"}
            """.formatted(prompt, answer);
    }

    public static String practiceSummaryUser(String topic, String records) {
        return """
            本次练习主题：%s
            题目与作答记录：
            %s

            请输出练习总结，JSON 格式：
            {"overall":"总体评价","strengths":["表现亮点"],"improvements":["改进建议"]}
            要求：strengths 和 improvements 各 2-4 条，基于上面的真实记录，不虚构。
            """.formatted(topic, records);
    }

    /* ---------- 阶段总结 ---------- */

    public static String stageSummaryUser(String name, String goal, String dataText) {
        return """
            阶段名称：%s
            阶段目标：%s
            该阶段内的练习数据（题目、作答、评分、点评）：
            %s

            请输出阶段总结，JSON 格式：
            {"overall":"阶段总体评价（200字以内）","strengths":["重点优势"],"keyWeaknesses":["重点不足"],"nextSteps":["下一步建议"]}
            要求：strengths、keyWeaknesses、nextSteps 各 2-5 条，每条一句话。
            """.formatted(name, goal == null || goal.isBlank() ? "（未填写）" : goal, dataText);
    }
}
