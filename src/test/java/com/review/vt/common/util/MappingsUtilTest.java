package com.review.vt.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.review.vt.common.util.MappingsUtil;

/**
 * 从 AI 返回里取字段必须足够皮实。
 *
 * 真实踩过的坑：模型偶尔把本该是字符串的字段给成对象/数组，
 * {@code asText("")} 会静默返回空串，后果是「会话摘要被写成空的」——
 * 后续出题就丢了上下文。所以这里对每种畸形输入都要有确定行为。
 */
class MappingsUtilTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode n(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("正常文本字段原样返回")
    void text_normal() {
        assertEquals("说不清锁升级", MappingsUtil.text(n("{\"overview\":\"说不清锁升级\"}"), "overview"));
    }

    @Test
    @DisplayName("字段被模型给成对象/数组时不能返回空串，要能拿到内容")
    void text_nonTextualStillYieldsContent() {
        assertTrue(!MappingsUtil.text(n("{\"overview\":{\"a\":1}}"), "overview").isBlank(),
                "非文本节点必须退回节点文本，不能是空串");
        assertTrue(!MappingsUtil.text(n("{\"overview\":[\"x\",\"y\"]}"), "overview").isBlank());
    }

    @Test
    @DisplayName("字段缺失或为 null 时返回空串，不抛异常")
    void text_missingAndNull() {
        assertEquals("", MappingsUtil.text(n("{\"a\":1}"), "overview"));
        assertEquals("", MappingsUtil.text(n("{\"overview\":null}"), "overview"));
        assertEquals("", MappingsUtil.text(null, "overview"));
    }

    @Test
    @DisplayName("正常数组返回列表，空串条目被丢弃")
    void stringList_normal() {
        assertEquals(List.of("HashMap", "线程池"),
                MappingsUtil.stringList(n("{\"coverage\":[\"HashMap\",\"\",\"线程池\"]}"), "coverage"));
    }

    @Test
    @DisplayName("模型把数组给成逗号分隔的字符串时也能拆开")
    void stringList_scalarString() {
        List<String> out = MappingsUtil.stringList(n("{\"coverage\":\"HashMap、线程池, CAS；AQS\"}"), "coverage");
        assertEquals(4, out.size());
        assertTrue(out.contains("HashMap") && out.contains("AQS"));
    }

    @Test
    @DisplayName("数组里混进对象时不能把整段吞掉，也不能抛异常")
    void stringList_mixedElements() {
        List<String> out = MappingsUtil.stringList(n("{\"coverage\":[\"HashMap\",{\"x\":1},\"线程池\"]}"), "coverage");
        assertEquals(3, out.size());
    }

    @Test
    @DisplayName("cap 把「一题只考 1-2 个点」在数据层卡死，不指望模型自觉")
    void cap_limitsListSize() {
        assertEquals(List.of("a", "b"), MappingsUtil.cap(List.of("a", "b", "c", "d"), 2));
        assertEquals(List.of("a", "b"), MappingsUtil.cap(List.of("a", "b"), 2), "没超就不动");
        assertEquals(List.of("a"), MappingsUtil.cap(List.of("a"), 2));
        assertTrue(MappingsUtil.cap(List.of(), 2).isEmpty());
        assertTrue(MappingsUtil.cap(null, 2).isEmpty(), "null 要返回空列表而不是炸");
    }

    @Test
    @DisplayName("字段缺失 / null / 不是数组都返回空列表")
    void stringList_badShapes() {
        assertTrue(MappingsUtil.stringList(n("{\"a\":1}"), "coverage").isEmpty());
        assertTrue(MappingsUtil.stringList(n("{\"coverage\":null}"), "coverage").isEmpty());
        assertTrue(MappingsUtil.stringList(n("{\"coverage\":3}"), "coverage").isEmpty());
        assertTrue(MappingsUtil.stringList(null, "coverage").isEmpty());
    }

}
