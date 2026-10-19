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
    void paperPlanUser_safe() {
        String out = PromptTemplates.paperPlanUser("练习主题：Java 并发编程\n" + SAMPLE, 2);
        assertSafe("paperPlanUser", out);
        assertTrue(out.contains("第 2 张卷"), "卷号应被填进去");
        assertTrue(out.contains("45%"), "百分比应原样输出，而不是被当成转换符");
    }

    @Test
    @DisplayName("出题批次模板可以 formatted，角度与分值被填进去")
    void questionChunkUser_safe() {
        List<PlanItem> slice = List.of(
                new PlanItem("HashMap 与 Hashtable 差异", "基础", "概念辨析", 3),
                new PlanItem("大促扣减超卖定位", "大题", "故障排查", 12));
        String out = PromptTemplates.questionChunkUser("练习主题：集合", slice, 1);
        assertSafe("questionChunkUser", out);
        assertTrue(out.contains("概念辨析") && out.contains("故障排查"), "题型应被填进去");
        assertTrue(out.contains("12 分") || out.contains("12"), "分值应被填进去");
    }

    @Test
    @DisplayName("批改模板可以 formatted")
    void gradeUser_safe() {
        String out = PromptTemplates.gradeUser("练习主题：集合", 1, 3, SAMPLE);
        assertSafe("gradeUser", out);
        assertTrue(out.contains("第 1 批 / 共 3 批"), "批次信息应被填进去");
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
