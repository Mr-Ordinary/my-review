package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 截断 JSON 拯救：模型输出被 max_tokens 截断时，把数组里完整的元素捞回来，
 * 别让整批题作废。这是出题失败率最高的一类故障。
 */
class LlmSalvageTest {

    @Test
    @DisplayName("截断的 questions 数组：救回前面完整的题")
    void salvagesCompleteQuestions() {
        String raw = """
            {"questions":[
              {"prompt":"第一题","referenceAnswer":"答案一","knowledgePoints":["A"]},
              {"prompt":"第二题","referenceAnswer":"答案二","knowledgePoints":["B"]},
              {"prompt":"第三题被截断，参考答""";
        JsonNode out = LlmService.salvageFirstArray(raw);
        assertNotNull(out);
        JsonNode qs = out.path("questions");
        assertEquals(2, qs.size(), "只救完整的两题");
        assertEquals("第一题", qs.get(0).path("prompt").asText());
        assertEquals("第二题", qs.get(1).path("prompt").asText());
    }

    @Test
    @DisplayName("字符串里的括号/引号不会打断配平")
    void ignoresBracesInsideStrings() {
        String raw = "{\"questions\":[{\"prompt\":\"他说\\\"用 { 和 } 表示集合\\\"\",\"x\":1},";
        JsonNode out = LlmService.salvageFirstArray(raw);
        assertNotNull(out);
        assertEquals(1, out.path("questions").size());
        assertTrue(out.path("questions").get(0).path("prompt").asText().contains("{ 和 }"));
    }

    @Test
    @DisplayName("evaluations 数组同样能救")
    void salvagesEvaluations() {
        String raw = "{\"evaluations\":[{\"sequence\":1,\"score\":8,\"feedback\":\"不错\"},{\"sequence\":2,\"sco";
        JsonNode out = LlmService.salvageFirstArray(raw);
        assertNotNull(out);
        assertEquals(1, out.path("evaluations").size());
        assertEquals(8, out.path("evaluations").get(0).path("score").asInt());
    }

    @Test
    @DisplayName("模型多写一个闭合括号：配平修复后能解析（复习卡实测坏法）")
    void repairsSurplusCloser() throws Exception {
        // 与真实坏样本同形：selfCheck 嵌套对象后多写了一个 }
        String bad = "{\"points\":[{\"name\":\"a\",\"summary\":\"s\","
                + "\"selfCheck\":{\"q\":\"q\",\"a\":\"a\"}}}]}";
        int[] removed = {0};
        String fixed = LlmService.dropSurplusClosers(bad, removed);
        assertEquals(1, removed[0], "应删掉 1 个多余括号");
        assertEquals(1, Mappings.MAPPER.readTree(fixed).path("points").size());
    }

    @Test
    @DisplayName("合法 JSON 原样返回，不多删")
    void balancedJsonUnchanged() {
        String good = "{\"points\":[{\"a\":{\"b\":[1,2]}}]}";
        int[] removed = {0};
        assertEquals(good, LlmService.dropSurplusClosers(good, removed));
        assertEquals(0, removed[0]);
    }

    @Test
    @DisplayName("字符串里裸双引号：修引号后可解析")
    void repairsUnescapedQuotes() throws Exception {
        String bad = "{\"points\":[{\"name\":\"a\",\"summary\":\"适合\\\"写入即对他人可见\\\"的场景\"}]}"
                .replace("\\\"写入", "\"写入").replace("可见\\\"", "可见\"");
        // 上面构造的是裸引号；修完后应能解析
        String fixed = LlmService.repairUnescapedQuotes(bad);
        assertEquals(1, Mappings.MAPPER.readTree(fixed).path("points").size());
    }

    @Test
    @DisplayName("裸引号 + 多余括号同时出现：先修引号再配平")
    void repairsQuotesAndSurplusTogether() throws Exception {
        String bad = "{\"points\":[{\"name\":\"a\",\"summary\":\"他说\"你好\"的场景\","
                + "\"selfCheckQ\":\"q\",\"selfCheckA\":\"a\"}}]}";
        String quoted = LlmService.repairUnescapedQuotes(bad);
        int[] removed = {0};
        String fixed = LlmService.dropSurplusClosers(quoted, removed);
        assertEquals(1, removed[0], "应删掉 1 个多余括号");
        assertEquals(1, Mappings.MAPPER.readTree(fixed).path("points").size());
    }

    @Test
    @DisplayName("没有可救的完整对象 → null（外层照常重试）")
    void noCompleteObjects() {
        assertNull(LlmService.salvageFirstArray("{\"questions\":[{\"prompt\":\"只有半"));
        assertNull(LlmService.salvageFirstArray("不是 JSON"));
        assertNull(LlmService.salvageFirstArray(""));
        assertNull(LlmService.salvageFirstArray(null));
    }

    @Test
    @DisplayName("数组已闭合但解析失败时，也能捞出其中合法对象")
    void salvagesFromMalformedButClosedArray() {
        String raw = "{\"questions\":[{\"prompt\":\"好题\"},这是坏对象,{\"prompt\":\"另一好题\"}]}";
        JsonNode out = LlmService.salvageFirstArray(raw);
        assertNotNull(out);
        assertEquals(2, out.path("questions").size());
    }
}
