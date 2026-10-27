package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一条 AI 评价必须「有数值分 + 非空点评」才写库。
 * 少一样都留给补批——绝不因为模型漏题/空点评就给用户写 0 分或空点评。
 */
class GradingValidityTest {

    private static JsonNode json(String s) throws Exception {
        return Mappings.MAPPER.readTree(s);
    }

    @Test
    @DisplayName("有分有点评 = 有效")
    void valid_full() throws Exception {
        assertTrue(PracticeService.validEvaluation(json(
                "{\"sequence\":1,\"score\":8.5,\"feedback\":\"正确：讲清了主干。\"}")));
    }

    @Test
    @DisplayName("有分但点评为空 = 无效（留给补批，避免「有分无点评」）")
    void invalid_blankFeedback() throws Exception {
        assertFalse(PracticeService.validEvaluation(json(
                "{\"sequence\":1,\"score\":8.5,\"feedback\":\"\"}")));
        assertFalse(PracticeService.validEvaluation(json(
                "{\"sequence\":1,\"score\":8.5,\"feedback\":\"   \"}")));
        assertFalse(PracticeService.validEvaluation(json(
                "{\"sequence\":1,\"score\":8.5}")));
    }

    @Test
    @DisplayName("缺分 = 无效（不能按 0 分兜底）")
    void invalid_missingScore() throws Exception {
        assertFalse(PracticeService.validEvaluation(json(
                "{\"sequence\":1,\"feedback\":\"讲了主干\"}")));
        assertFalse(PracticeService.validEvaluation(json(
                "{\"sequence\":1,\"score\":\"8\",\"feedback\":\"讲了主干\"}")), "字符串分不算数值分");
        assertFalse(PracticeService.validEvaluation(json(
                "{\"sequence\":1,\"score\":null,\"feedback\":\"讲了主干\"}")));
        assertFalse(PracticeService.validEvaluation(null));
    }
}
