package com.review.vt.service;

import com.review.vt.domain.Attempt;
import com.review.vt.domain.FollowUp;
import com.review.vt.domain.Question;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追问上下文：**只带这一道题的信息**，历史只带最近几轮且总长有顶。
 * 这两点是「不串题」和「上下文不爆」的底线。
 */
class FollowUpServiceTest {

    private static Question question() {
        Question q = new Question();
        q.setPrompt("关于 ArrayList：① 尾部 add 与中间插入的时间复杂度分别是多少？② 扩容时容量大约扩大多少倍？");
        q.setReferenceAnswer("**尾部 add 平均 O(1)，中间插入 O(n)**；扩容约 **1.5 倍**。");
        q.setKnowledgePointsJson("[\"ArrayList · 扩容机制\",\"ArrayList · 复杂度\"]");
        q.setPoints(8);
        return q;
    }

    private static Attempt attempt() {
        Attempt a = new Attempt();
        a.setAnswer("尾部 O(1)，中间 O(n)；扩容 1.5 倍。");
        a.setScore(8.0);
        a.setPoints(6.4);
        a.setFeedback("正确：两个问点都答到了。\n不足：扩容代价可以再讲透。");
        a.setEvaluatedAt(1L);
        return a;
    }

    @Test
    @DisplayName("上下文只含本题的题面/作答/批改/答案/问点/考察点")
    void contextContainsOnlyThisQuestion() {
        String ctx = FollowUpService.buildContext(question(), attempt());
        assertTrue(ctx.contains("【题面】"));
        assertTrue(ctx.contains("【我的作答】"));
        assertTrue(ctx.contains("【批改】"));
        assertTrue(ctx.contains("表现分 8.0/10"));
        assertTrue(ctx.contains("【参考答案】"));
        assertTrue(ctx.contains("【题面问点】"));
        assertTrue(ctx.contains("时间复杂度"));
        assertTrue(ctx.contains("【考察点】"));
        assertTrue(ctx.contains("ArrayList · 扩容机制"));
        // 不能把其他题或练习级记忆带进来
        assertFalse(ctx.contains("HashMap"));
        assertFalse(ctx.contains("长期记忆"));
        assertFalse(ctx.contains("会话摘要"));
    }

    @Test
    @DisplayName("没作答的题也能追问：上下文标注未作答，仍带题面与参考答案")
    void contextSupportsUnansweredQuestion() {
        String ctx = FollowUpService.buildContext(question(), null);
        assertTrue(ctx.contains("【题面】"));
        assertTrue(ctx.contains("（未作答）"));
        assertTrue(ctx.contains("未作答，本题按 0 分计"));
        assertTrue(ctx.contains("【参考答案】"));
        assertTrue(ctx.contains("【题面问点】"));
        assertFalse(ctx.contains("【我的作答】\n尾部"), "不应该有作答内容");
    }

    @Test
    @DisplayName("历史只带最近 10 条，且总长有顶")
    void historyIsBounded() {
        List<FollowUp> history = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            FollowUp f = new FollowUp();
            f.setRole(i % 2 == 0 ? "user" : "assistant");
            f.setContent("第 " + i + " 条消息" + "内容".repeat(100));
            f.setCreatedAt(i);
            history.add(f);
        }
        String out = FollowUpService.fitHistory(history);
        long lines = out.lines().count();
        assertTrue(lines <= FollowUpService.HISTORY_MESSAGES, "条数应 ≤ " + FollowUpService.HISTORY_MESSAGES + "，实际 " + lines);
        assertTrue(out.length() <= FollowUpService.HISTORY_CHARS + 20, "总长超预算：" + out.length());
        assertFalse(out.contains("第 0 条消息"), "最旧的消息应被丢掉");
        assertTrue(out.contains("第 29 条消息"), "最新的消息必须保留");
    }

    @Test
    @DisplayName("历史顺序保持旧→新，角色前缀正确")
    void historyKeepsOrder() {
        List<FollowUp> history = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            FollowUp f = new FollowUp();
            f.setRole(i % 2 == 0 ? "user" : "assistant");
            f.setContent("消息" + i);
            f.setCreatedAt(i);
            history.add(f);
        }
        String out = FollowUpService.fitHistory(history);
        assertTrue(out.indexOf("消息0") < out.indexOf("消息1"));
        assertTrue(out.indexOf("消息1") < out.indexOf("消息2"));
        assertTrue(out.startsWith("用户：消息0"));
    }

    @Test
    @DisplayName("空历史返回空串")
    void emptyHistory() {
        assertEquals("", FollowUpService.fitHistory(null));
        assertEquals("", FollowUpService.fitHistory(List.of()));
    }
}
