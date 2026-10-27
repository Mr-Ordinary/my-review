package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨卷题目去重：归一化 + 三元组 Jaccard ≥ 0.8 判重。
 * 太短不判，避免「HashMap 是什么」这类短题被误杀。
 */
class PromptDedupTest {

    @Test
    @DisplayName("完全相同的题（仅标点/空白不同）判重")
    void identicalAfterNormalize() {
        String a = "在遍历 ArrayList 过程中直接调用 list.remove(Object)，会有什么后果？";
        String b = "在遍历ArrayList过程中直接调用list.remove(Object) 会有什么后果";
        assertTrue(PracticeService.isDuplicatePrompt(a, b));
    }

    @Test
    @DisplayName("同一道题加了一句话：仍判重")
    void nearIdentical() {
        String a = "在遍历 ArrayList 过程中直接调用 list.remove(Object)，会有什么后果？请说明原因。";
        String b = "在遍历 ArrayList 过程中直接调用 list.remove(Object)，会有什么后果？";
        assertTrue(PracticeService.isDuplicatePrompt(a, b),
                "Jaccard=" + PracticeService.trigramJaccard(
                        PracticeService.normalizePrompt(a), PracticeService.normalizePrompt(b)));
    }

    @Test
    @DisplayName("同主题不同问法：不能误杀（插入性能 vs 查询性能）")
    void differentQuestionNotDuplicate() {
        String a = "ArrayList 和 LinkedList 插入性能有什么区别？";
        String b = "ArrayList 和 LinkedList 查询性能有什么区别？";
        assertFalse(PracticeService.isDuplicatePrompt(a, b),
                "这两个是不同题，Jaccard=" + PracticeService.trigramJaccard(
                        PracticeService.normalizePrompt(a), PracticeService.normalizePrompt(b)));
    }

    @Test
    @DisplayName("太短的题只认完全相同，不做模糊判重")
    void shortPromptsSkipped() {
        assertTrue(PracticeService.isDuplicatePrompt("HashMap 是什么？", "HashMap 是什么"));
        assertFalse(PracticeService.isDuplicatePrompt("HashMap 是什么？", "ArrayList 是什么？"));
        assertFalse(PracticeService.isDuplicatePrompt("", "任意题目内容足够长足够长"));
    }

    @Test
    @DisplayName("中英文标点都归一化掉")
    void normalizePunctuation() {
        String norm = PracticeService.normalizePrompt("「HashMap」的·底层结构，是什么？");
        assertFalse(norm.contains("？"));
        assertFalse(norm.contains("「"));
        assertTrue(norm.contains("hashmap"));
    }
}
