package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文预算：出题 prompt 的长度必须永远有顶。
 * LLM 调用无状态，所以「上下文过长」只可能来自 prompt 里各段膨胀——这里就是那道闸。
 */
class PromptBudgetTest {

    private static List<String> lines(int n, int len) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add("第" + i + "行 " + "x".repeat(Math.max(0, len - 6)));
        }
        return out;
    }

    @Test
    @DisplayName("预算充足时全保留，顺序不变")
    void fitRecent_keepsAllWhenBudgetAllows() {
        List<String> src = lines(5, 50);
        List<String> out = PromptBudget.fitRecent(src, 0);
        assertEquals(src, out);
    }

    @Test
    @DisplayName("条数上限：最多保留 MAX_RECENT_ITEMS 条")
    void fitRecent_capsItemCount() {
        List<String> out = PromptBudget.fitRecent(lines(50, 20), 0);
        assertEquals(PromptBudget.MAX_RECENT_ITEMS, out.size());
    }

    @Test
    @DisplayName("预算不够时从最旧一端砍掉，最近的优先保留")
    void fitRecent_dropsOldestFirst() {
        List<String> src = lines(15, 200); // 每条会被截断成 MAX_RECENT_ITEM
        int used = PromptBudget.TOTAL - 300; // 只剩 300 字预算
        List<String> out = PromptBudget.fitRecent(src, used);
        assertTrue(out.size() >= 1 && out.size() <= 3, "应只保留最近几条，实际保留 " + out.size());
        // 保留的必须是「最新的在前」的前若干条
        for (int i = 0; i < out.size(); i++) {
            assertEquals(MemoryRules.cut(src.get(i), PromptBudget.MAX_RECENT_ITEM), out.get(i));
        }
    }

    @Test
    @DisplayName("每条超过 MAX_RECENT_ITEM 会被截断")
    void fitRecent_truncatesEachLine() {
        List<String> out = PromptBudget.fitRecent(lines(3, 500), 0);
        for (String s : out) {
            assertTrue(s.length() <= PromptBudget.MAX_RECENT_ITEM, "单条超长：" + s.length());
        }
    }

    @Test
    @DisplayName("预算已经超了就一条都不放，不会把 prompt 撑得更大")
    void fitRecent_nothingWhenOverBudget() {
        List<String> out = PromptBudget.fitRecent(lines(10, 100), PromptBudget.TOTAL + 1000);
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("空输入不炸")
    void fitRecent_nullSafe() {
        assertTrue(PromptBudget.fitRecent(null, 0).isEmpty());
        assertTrue(PromptBudget.fitRecent(List.of(), 0).isEmpty());
    }

    @Test
    @DisplayName("无论各段多长，上下文总量永远不超过 TOTAL（这就是「不会上下文过长」的保证）")
    void totalNeverExceedsBudget() {
        for (int used : new int[] {0, 500, 2000, 3800, 11999, 20000}) {
            List<String> out = PromptBudget.fitRecent(lines(30, 120), used);
            long recentChars = out.stream().mapToLong(String::length).sum();
            if (used <= PromptBudget.TOTAL) {
                assertTrue(used + recentChars <= PromptBudget.TOTAL,
                        "used=" + used + " 加 recent 后超预算：" + (used + recentChars));
            } else {
                assertEquals(0, out.size(), "预算已超就必须一条都不放");
            }
        }
    }

    @Test
    @DisplayName("各段上限之和就是单次 prompt 的硬上限")
    void sectionCapsBoundThePrompt() {
        int worstCase = PromptBudget.MAX_SCOPE + PromptBudget.MAX_DIGEST + PromptBudget.MAX_MEMORY
                + PromptBudget.MAX_RECENT_ITEMS * PromptBudget.MAX_RECENT_ITEM;
        // 任一段都不可能超过自己的上限，所以整体一定有顶
        assertTrue(worstCase > 0 && worstCase <= PromptBudget.TOTAL);
    }
}
