package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词模板必须能被安全地 formatted。
 *
 * 这里最常踩的坑是：格式串里写了字面的 %（比如"约占 45%（..."），
 * String.format 会当成转换符直接抛 UnknownFormatConversionException，
 * 后果是出题后台任务整个失败。所有模板都要过一遍这道闸。
 */
class PromptTemplatesTest {

    private static final String SAMPLE = "第 1 题：某电商下单接口 QPS 峰值 5 万，如何设计扣减？｜得分 7.0；点评：说清了条件更新";

    private static void assertSafe(String label, String rendered) {
        assertFalse(rendered == null || rendered.isBlank(), label + " 渲染结果为空");
        assertTrue(rendered.contains("JSON"), label + " 应当要求输出 JSON");
    }

    @Test
    @DisplayName("出卷计划模板可以 formatted，且不会被字面 % 弄崩")
    void paperPlanForPoints_safe() {
        String out = PromptTemplates.paperPlanForPoints("练习主题：Java 并发编程\n" + SAMPLE,
                List.of("[补足] 封装 · 概念 | 第 1 层 | 无（新点）",
                        "[强化] 封装 · 底层 | 第 2 层 | 已问过：封装的意义"));
        assertSafe("paperPlanForPoints", out);
        assertTrue(out.contains("封装 · 概念"), "挑好的知识点要传进去");
        assertTrue(out.contains("出题角度"), "要求 AI 给出出题角度");
        assertTrue(out.contains("强化"), "要点明强化的点要换角度问");
        assertTrue(out.contains("已问过"), "要告诉它这个点问过什么，变种才有依据");
    }

    @Test
    @DisplayName("变种是硬要求：提示词里必须明令避开已用过的角度")
    void paperPlanForPoints_requiresVariant() {
        String out = PromptTemplates.paperPlanForPoints("范围", List.of("[强化] 点 | 第 2 层 | 已问过：角度A"));
        assertTrue(out.contains("禁止"), "要明令禁止重复问法");
        assertTrue(out.contains("变种"), "要有「变种」这个词");
    }

    @Test
    @DisplayName("串联大题是唯一例外，且明确它不挖深任何点")
    void paperPlanForPoints_linkIsOnlyException() {
        String out = PromptTemplates.paperPlanForPoints("范围",
                List.of("[串联] 点A + 点B | 各按自己层级 | 已问过：…"));
        assertTrue(out.contains("串联"), "要有串联标签说明");
        assertTrue(out.contains("唯一例外"), "要讲清它是唯一的跨点出口");
        assertTrue(out.contains("不挖深") || out.contains("不提高"), "要强调它不加深任何点");

        // 系统提示词里「一题几个点」的规则必须自洽：只留一个例外
        String sys = PromptTemplates.INTERVIEW_SYSTEM;
        assertTrue(sys.contains("唯一例外"), "系统提示词里也要声明这是唯一例外");
        assertTrue(sys.contains("不跟着卷数走"), "深度不能再说跟着卷数走");
    }

    @Test
    @DisplayName("拆清单模板可以 formatted")
    void scaffoldUser_safe() {
        String out = PromptTemplates.scaffoldUser("Java 基础", "mid");
        assertSafe("scaffoldUser", out);
        assertTrue(out.contains("宁多勿少"), "清单要穷尽");
        assertTrue(out.contains("严格在范围内"), "不能把范围外的列进来");
    }

    @Test
    @DisplayName("发散模板必须严格判 inScope")
    void harvestUser_safe() {
        String out = PromptTemplates.harvestUser("Java 基础", "我觉得这个跟分布式锁有关",
                List.of("封装 · 概念"));
        assertSafe("harvestUser", out);
        assertTrue(out.contains("inScope"), "必须判定是否在范围内");
        assertTrue(out.contains("宁可漏发散"), "越界比漏发散更糟");
    }

    @Test
    @DisplayName("出题批次模板可以 formatted，角度与分值被填进去")
    void questionChunkUser_safe() {
        List<PlanItem> slice = List.of(
                new PlanItem(List.of("HashMap 与 Hashtable 差异"), "HashMap 与 Hashtable 差异", "基础", "概念辨析", 3),
                new PlanItem(List.of("大促扣减超卖定位"), "大促扣减超卖定位", "大题", "故障排查", 12),
                new PlanItem(List.of("volatile 语义", "CAS 与原子类"), "两者取舍", "大题", "场景应用", 16));
        String out = PromptTemplates.questionChunkUser("练习主题：集合", slice, 1);
        assertSafe("questionChunkUser", out);
        assertTrue(out.contains("概念辨析") && out.contains("故障排查"), "题型应被填进去");
        assertTrue(out.contains("12 分") || out.contains("12"), "分值应被填进去");
        assertTrue(out.contains("串联"), "串联大题的例外要讲清");
        assertTrue(out.contains("2 到 3 个"), "串联大题才允许跨点");
    }

    @Test
    @DisplayName("出题必须带 demands 批改清单，且禁止题干问 A 却把 B 列进要求")
    void questionChunkUser_requiresDemands() {
        List<PlanItem> slice = List.of(
                new PlanItem(List.of("final 引用语义"), "final 引用语义的作用边界", "中档", "概念辨析", 5));
        String out = PromptTemplates.questionChunkUser("练习主题：Java 基础", slice, 1);
        assertTrue(out.contains("\"demands\""), "出题 schema 必须包含 demands 字段");
        assertTrue(out.contains("批改清单"), "要讲明 demands 是批改清单");
        assertTrue(out.contains("禁止题干问 A"), "必须明令禁止题干与要求错位");
        assertTrue(out.contains("扩展："), "超出要求的深入内容要单独放扩展段");
    }

    @Test
    @DisplayName("参考答案只覆盖本题要求，落地要点仅在要求方案时才写")
    void systemAnswerCoversDemandsOnly() {
        String sys = PromptTemplates.INTERVIEW_SYSTEM;
        assertTrue(sys.contains("只覆盖本题要求"), "参考答案只覆盖本题要求");
        assertTrue(sys.contains("只在题面要求给方案"), "落地要点与取舍必须是条件式的");
        assertTrue(sys.contains("扩展："), "超出要求的内容要放扩展段");
    }

    @Test
    @DisplayName("批改模板可以 formatted")
    void gradeUser_safe() {
        String out = PromptTemplates.gradeUser("练习主题：集合", 1, 3, SAMPLE);
        assertSafe("gradeUser", out);
        assertTrue(out.contains("第 1 批 / 共 3 批"), "批次信息应被填进去");
    }

    @Test
    @DisplayName("批改标准是「本题要求」，不是参考答案——这条口径要锁死")
    void gradeUser_gradesAgainstDemandsNotReference() {
        String out = PromptTemplates.gradeUser("练习主题：集合", 1, 1, SAMPLE);
        assertTrue(out.contains("本题要求"), "批改标准必须写明是本题要求");
        assertTrue(out.contains("不得判「缺失」") || out.contains("不得判「缺失」、不得扣分"),
                "题干没问的内容不得判缺失");
        assertTrue(out.contains("扩展"), "超出要求的内容只能进扩展区");
        assertTrue(out.contains("不加分也不扣分"), "扩展既不加分也不扣分");
        assertTrue(!out.contains("相比参考答案差在哪"), "旧的「对照参考答案找差距」措辞不能残留");
    }

    @Test
    @DisplayName("批改保持严格：答浅、答错、漏项照常扣，禁止同情分——不能变成宽松大师")
    void gradeUser_staysStrict() {
        String out = PromptTemplates.gradeUser("练习主题：集合", 1, 1, SAMPLE);
        assertTrue(out.contains("分数锚点"), "要有分数锚点，不能普遍打高分");
        assertTrue(out.contains("只给结论"), "问「为什么」只给结论必须点名");
        assertTrue(out.contains("至少降 2 分"), "漏要求项要有明确扣分下限");
        assertTrue(out.contains("禁止"), "必须明令禁止同情分");
        assertTrue(out.contains("同情分"), "点名禁止同情分");
        assertTrue(out.contains("冷静核对"), "要像面试官冷静核对，不是鼓励式点评");
    }

    @Test
    @DisplayName("批改 feedback 分段口径：缺失仅限要求内，扩展单列")
    void gradeUser_feedbackSections() {
        String out = PromptTemplates.gradeUser("练习主题：集合", 1, 1, SAMPLE);
        assertTrue(out.contains("正确：") && out.contains("错误：") && out.contains("缺失：") && out.contains("扩展："),
                "feedback 四段标记都要写明");
        assertTrue(out.contains("仅限要求内"), "缺失必须限定在要求内");
        assertTrue(out.contains("正确性") && out.contains("完整性") && out.contains("表述"),
                "dimensions 要改成相对本题要求的三维度");
        assertTrue(!out.contains("技术深度"), "旧维度「技术深度」会诱导拔高，不能残留");
        assertTrue(!out.contains("场景分析"), "旧维度「场景分析」对基础题无意义，不能残留");
    }

    @Test
    @DisplayName("总结模板可以 formatted")
    void paperSummaryUser_safe() {
        assertSafe("paperSummaryUser", PromptTemplates.paperSummaryUser("练习主题：集合", SAMPLE));
    }

    @Test
    @DisplayName("记忆模板可以 formatted，旧记忆为空也能用")
    void memoryUpdateUser_safe() {
        assertSafe("memoryUpdateUser(空记忆)", PromptTemplates.memoryUpdateUser("练习主题：集合", SAMPLE, null));
        assertSafe("memoryUpdateUser(有记忆)", PromptTemplates.memoryUpdateUser("练习主题：集合", SAMPLE, "{}"));
    }

    @Test
    @DisplayName("摘要模板可以 formatted，旧摘要为空也能用")
    void digestUpdateUser_safe() {
        assertSafe("digestUpdateUser", PromptTemplates.digestUpdateUser("练习主题：集合", null, "共 1 张卷", SAMPLE));
    }

    @Test
    @DisplayName("阶段总结模板可以 formatted")
    void stageSummaryUser_safe() {
        String out = PromptTemplates.stageSummaryUser("秋招冲刺", SAMPLE);
        assertSafe("stageSummaryUser", out);
        assertTrue(out.contains("reviewScope"), "阶段总结必须产出全复习范围");
    }

    @Test
    @DisplayName("语音术语纠正模板可以 formatted")
    void polishTranscriptUser_safe() {
        String out = PromptTemplates.polishTranscriptUser("Java 并发",
                java.util.List.of("equals", "volatile"), "我觉得 ecos 方法和瓦太奥 关键字");
        assertSafe("polishTranscriptUser", out);
        assertTrue(out.contains("不要改写"), "必须强调不许改写口述内容");
        assertTrue(out.contains("ecos"), "原文要原样传入");
        assertTrue(out.contains("equals"), "热词要传进去");
    }

    @Test
    @DisplayName("深度跟着点走，不跟着卷走——这条铁律要写进系统提示词")
    void systemSaysDepthFollowsPointNotPaper() {
        String sys = PromptTemplates.INTERVIEW_SYSTEM;
        assertTrue(sys.contains("跟着每个知识点自己的出现次数走"), "要明说深度跟点的出现次数");
        assertTrue(sys.contains("不跟着卷数走"), "要明确否定「按卷数定深度」的旧说法");
        assertTrue(!sys.contains("深度必须循序渐进，跟着卷数走"), "旧措辞不能残留");
    }

    @Test
    @DisplayName("范围模式的范围说明原样传入，不被当成格式串")
    void scopeSection_rangeKeepsUserText() {
        String scope = "范围是 javaguide 基础篇上中下，参照 https://javaguide.cn/（占 50% 的篇幅）";
        String out = PromptTemplates.scopeSection("标题", "range", scope);
        assertTrue(out.contains("严格按此出题"), "范围模式要给出硬约束");
        assertTrue(out.contains(scope), "用户原话必须原样传给 AI，不能被改写或截断成格式串");
    }

    @Test
    @DisplayName("主题模式只给主题")
    void scopeSection_topic() {
        String out = PromptTemplates.scopeSection("集合、泛型", "topic", null);
        assertTrue(out.contains("练习主题：集合、泛型"));
    }
}
