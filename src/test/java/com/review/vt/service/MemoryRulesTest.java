package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆合并：目标是「不丢失 + 精简」。
 * coverage 走并集只增不减，其余条目去重/限字数/限量，防止记忆撑爆上下文。
 */
class MemoryRulesTest {

    @Test
    @DisplayName("去重时保序，重复条目只留第一个")
    void tidy_dedupesKeepingOrder() {
        List<String> out = MemoryRules.tidy(List.of("HashMap", "线程池", "HashMap", "  线程池  ", "锁"), 8, 30);
        assertEquals(List.of("HashMap", "线程池", "锁"), out);
    }

    @Test
    @DisplayName("每条超过字数上限会被截断")
    void tidy_truncatesLongItem() {
        String longItem = "这是一个特别特别长的知识点描述，远远超出了三十个字的条目上限，必须被截断掉";
        assertTrue(longItem.length() > MemoryRules.MAX_ITEM_CHARS, "测试数据必须真的超长");
        List<String> out = MemoryRules.tidy(List.of(longItem), 8, MemoryRules.MAX_ITEM_CHARS);
        assertEquals(1, out.size());
        assertEquals(MemoryRules.MAX_ITEM_CHARS, out.get(0).length());
    }

    @Test
    @DisplayName("超过条数上限只保留前若干条")
    void tidy_capsItemCount() {
        List<String> items = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "知识点" + i).toList();
        List<String> out = MemoryRules.tidy(items, MemoryRules.MAX_ITEMS, MemoryRules.MAX_ITEM_CHARS);
        assertEquals(MemoryRules.MAX_ITEMS, out.size());
        assertEquals("知识点1", out.get(0));
    }

    @Test
    @DisplayName("空白与 null 条目被丢弃，不计入条数")
    void tidy_skipsBlank() {
        List<String> out = MemoryRules.tidy(java.util.Arrays.asList("", "  ", null, "A"), 8, 30);
        assertEquals(List.of("A"), out);
    }

    @Test
    @DisplayName("mergeUnion 是并集：旧记忆的条目一条都不能丢")
    void mergeUnion_neverLosesOlder() {
        List<String> older = List.of("HashMap", "线程池", "锁");
        List<String> newer = List.of("线程池", "CAS", "AQS");
        List<String> out = MemoryRules.mergeUnion(older, newer, MemoryRules.MAX_COVERAGE, MemoryRules.MAX_COVERAGE_CHARS);
        assertTrue(out.containsAll(List.of("HashMap", "线程池", "锁", "CAS", "AQS")));
        // 旧的在前，顺序稳定
        assertEquals("HashMap", out.get(0));
    }

    @Test
    @DisplayName("mergeUnion 超量时按上限截断（保证上下文不膨胀）")
    void mergeUnion_capsSize() {
        List<String> older = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "老" + i).toList();
        List<String> newer = java.util.stream.IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "新" + i).toList();
        List<String> out = MemoryRules.mergeUnion(older, newer, 10, 30);
        assertEquals(10, out.size());
        // 截断时优先保留旧的（老的在前）
        assertEquals("老1", out.get(0));
    }

    @Test
    @DisplayName("coverage 用宽松上限，保证「考过的知识点」尽量不丢")
    void coverage_hasRoomyCap() {
        List<String> many = java.util.stream.IntStream.rangeClosed(1, 38)
                .mapToObj(i -> "考点" + i).toList();
        List<String> out = MemoryRules.mergeUnion(many, List.of("考点39", "考点40"), 10, 30);
        assertEquals(10, out.size());
        List<String> full = MemoryRules.mergeUnion(many, List.of("考点39", "考点40"),
                MemoryRules.MAX_COVERAGE, MemoryRules.MAX_COVERAGE_CHARS);
        assertEquals(40, full.size());
    }

    @Test
    @DisplayName("null 输入不炸")
    void mergeUnion_nullSafe() {
        assertEquals(List.of("A"), MemoryRules.mergeUnion(null, List.of("A"), 8, 30));
        assertEquals(List.of("A"), MemoryRules.mergeUnion(List.of("A"), null, 8, 30));
        assertTrue(MemoryRules.mergeUnion(null, null, 8, 30).isEmpty());
    }
}
