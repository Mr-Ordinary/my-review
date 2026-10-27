package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 出题质检：解析结果要稳，异常时放行（不能把出题卡死）。 */
class QuestionCheckTest {

    private static JsonNode json(String s) throws Exception {
        return Mappings.MAPPER.readTree(s);
    }

    @Test
    @DisplayName("不通过的题按 0-based index 返回，并带原因")
    void parsesRejections() throws Exception {
        Map<Integer, String> out = PracticeService.parseQuestionRejections(json("""
                {"results":[
                  {"index":1,"ok":false,"reason":"题面有歧义"},
                  {"index":2,"ok":true,"reason":""},
                  {"index":3,"ok":false,"reason":""}
                ]}"""), 3);
        assertEquals(2, out.size());
        assertEquals("题面有歧义", out.get(0));
        assertEquals("未通过质检", out.get(2), "空原因给默认文案");
        assertFalse(out.containsKey(1));
    }

    @Test
    @DisplayName("越界 index / 非数字 / 缺字段都忽略，不炸")
    void ignoresInvalid() throws Exception {
        Map<Integer, String> out = PracticeService.parseQuestionRejections(json("""
                {"results":[
                  {"index":0,"ok":false,"reason":"越界"},
                  {"index":99,"ok":false,"reason":"越界"},
                  {"index":"x","ok":false,"reason":"非数字"},
                  {"ok":false,"reason":"缺 index"},
                  {"index":2,"ok":true}
                ]}"""), 3);
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("空/畸形结果一律放行")
    void failOpen() throws Exception {
        assertTrue(PracticeService.parseQuestionRejections(null, 3).isEmpty());
        assertTrue(PracticeService.parseQuestionRejections(json("{\"results\":[]}"), 3).isEmpty());
        assertTrue(PracticeService.parseQuestionRejections(json("{}"), 3).isEmpty());
    }

    @Test
    @DisplayName("质检提示词可以 formatted，且要求每题都有结果")
    void promptSafe() {
        String out = PromptTemplates.questionCheckUser("练习主题：集合",
                "1. 【基础 / 概念辨析 / 3 分】角度：HashMap 结构\n题面：xxx\n参考答案：yyy\n");
        assertTrue(out.contains("JSON"));
        assertTrue(out.contains("每题都要有一条结果"));
        assertTrue(out.contains("HashMap 结构"));
        assertTrue(PromptTemplates.QUESTION_CHECK_SYSTEM.contains("不改写题目"));
    }
}
