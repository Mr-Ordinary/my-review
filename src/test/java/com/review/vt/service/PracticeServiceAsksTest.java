package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批改清单必须从题面机械抽取——题面问几点，要求就是几点。
 * 这是防「要求发散/漏项」的最后一道闸：AI 单独写 demands 必然加内容或漏问点。
 */
class PracticeServiceAsksTest {

    @Test
    @DisplayName("①② 编号题：要求就是题面的两个问点，不多出「说明差异」")
    void extractAsks_numberedTwo() {
        String prompt = "关于 ArrayList：① 尾部 add 与中间位置插入的平均时间复杂度分别是多少？"
                + "② 每次扩容时容量大约扩大多少倍？";
        List<String> asks = PracticeService.extractAsks(prompt);
        assertEquals(2, asks.size());
        assertTrue(asks.get(0).contains("时间复杂度"));
        assertTrue(asks.get(1).contains("扩大多少倍"));
        for (String a : asks) assertFalse(a.contains("差异"), "题面没问差异，要求里不能有");
    }

    @Test
    @DisplayName("①②③ 三个问点全保留，不能漏（之前漏过线程安全）")
    void extractAsks_numberedThree() {
        String prompt = "关于 HashMap，请回答：① JDK 8 中它的底层结构是什么、在什么条件下会从链表转成红黑树？"
                + "② 它允许 null 键和 null 值吗？"
                + "③ 它是线程安全的吗，多线程并发写入会有什么问题？";
        List<String> asks = PracticeService.extractAsks(prompt);
        assertEquals(3, asks.size());
        assertTrue(asks.get(0).contains("底层结构"));
        assertTrue(asks.get(1).contains("null"));
        assertTrue(asks.get(2).contains("线程安全"));
    }

    @Test
    @DisplayName("无编号多问句：按问号切分")
    void extractAsks_questionMarks() {
        String prompt = "HashMap 什么时候会触发扩容？扩容后元素的下标是怎样重新确定的？";
        List<String> asks = PracticeService.extractAsks(prompt);
        assertEquals(2, asks.size());
        assertTrue(asks.get(0).contains("触发扩容"));
        assertTrue(asks.get(1).contains("重新确定"));
    }

    @Test
    @DisplayName("单问点题：整题就是一个问点")
    void extractAsks_singleAsk() {
        List<String> asks = PracticeService.extractAsks("HashMap 的默认容量是多少？");
        assertEquals(1, asks.size());
        assertTrue(asks.get(0).contains("默认容量"));
    }

    @Test
    @DisplayName("空题面不炸")
    void extractAsks_nullSafe() {
        assertEquals(List.of(), PracticeService.extractAsks(null));
        assertEquals(List.of(), PracticeService.extractAsks("  "));
    }

    @Test
    @DisplayName("问点超长会截断，避免批改清单难读")
    void extractAsks_truncates() {
        String longAsk = "请详细说明".repeat(60);
        List<String> asks = PracticeService.extractAsks(longAsk);
        assertTrue(asks.get(0).length() <= 161, "实际 " + asks.get(0).length());
    }
}
