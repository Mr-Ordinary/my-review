package com.review.vt.service;

import com.review.vt.domain.KnowledgePoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复习提纲的请求行：优先级标签、类别、点名、深挖上限都要带全。 */
class ReviewDocServiceTest {

    private static KnowledgePoint point(String name, String category, String priority, String boundDeep) {
        KnowledgePoint kp = new KnowledgePoint();
        kp.setName(name);
        kp.setCategory(category);
        kp.setPriority(priority);
        kp.setBoundDeep(boundDeep);
        return kp;
    }

    @Test
    @DisplayName("请求行包含点名/优先级/类别/深挖上限")
    void pointLines_full() {
        List<String> lines = ReviewDocService.pointLines(List.of(
                point("volatile · 可见性", "volatile", "high", "可见性与原子性边界，不考源码")));
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("点名：volatile · 可见性｜"), line);
        assertTrue(line.contains("优先级：高频"), line);
        assertTrue(line.contains("类别：volatile"), line);
        assertTrue(line.contains("深挖上限：可见性与原子性边界"), line);
    }

    @Test
    @DisplayName("优先级标签：medium=常见、low=偏门；缺省按常见")
    void pointLines_labels() {
        assertTrue(ReviewDocService.pointLines(List.of(
                point("a", "x", "medium", "b"))).get(0).contains("优先级：常见｜类别：x"));
        assertTrue(ReviewDocService.pointLines(List.of(
                point("a", "x", "low", "b"))).get(0).contains("优先级：偏门｜类别：x"));
        KnowledgePoint none = point("a", null, null, null);
        String line = ReviewDocService.pointLines(List.of(none)).get(0);
        assertTrue(line.contains("优先级：常见｜类别：综合"), line);
        assertTrue(line.contains("深挖上限：概念、原理"), "缺深挖上限时给默认天花板");
    }

    @Test
    @DisplayName("点名对齐：模型补全被截断的名字 / 带装饰，也能匹配回原名")
    void resolvePointName_handlesModelDrift() {
        String truncated = "volatile · 指令重排序与 happens-befo";
        assertEquals(truncated, ReviewDocService.resolvePointName(
                "volatile · 指令重排序与 happens-before", List.of(truncated)),
                "模型补全被截断的英文名");
        assertEquals("synchronized · 锁升级过程", ReviewDocService.resolvePointName(
                "【高频 / synchronized】synchronized · 锁升级过程｜深挖上限：升级条件",
                List.of("synchronized · 锁升级过程")), "模型把整行复制进 name");
        assertEquals("CAS · 原理与三大问题", ReviewDocService.resolvePointName(
                "CAS · 原理与三大问题", List.of("CAS · 原理与三大问题")));
        assertEquals(null, ReviewDocService.resolvePointName(
                "完全无关的点", List.of("CAS · 原理与三大问题")));
    }

    @Test
    @DisplayName("复习提示词要求每点三层齐全")
    void reviewSystem_requiresAllLayers() {
        String sys = PromptTemplates.REVIEW_SYSTEM;
        for (String field : new String[] {"summary", "interview", "principle", "params", "pitfalls", "selfCheckQ", "selfCheckA"}) {
            assertTrue(sys.contains(field), "缺少字段说明：" + field);
        }
        assertTrue(sys.contains("从零讲起"), "要假设用户没有背景");
    }

    @Test
    @DisplayName("复习请求模板可以 formatted")
    void reviewChunkUser_safe() {
        String out = PromptTemplates.reviewChunkUser("练习主题：集合",
                List.of("【高频 / 集合】HashMap · put过程｜深挖上限：主干流程"));
        assertTrue(out.contains("JSON"));
        assertTrue(out.contains("HashMap · put过程"));
        assertTrue(out.contains("一个都不能少"));
    }
}
