package com.review.vt.service;

import com.review.vt.domain.MemoryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆点是「可直接背诵的一句话」——以前只拼进点评文本、看完就散。
 * 这里卡三件事：清洗、幂等（批改续跑不翻倍）、归属到知识点。
 */
class MemoryPointServiceTest {

    private static Set<String> set(String... ks) {
        return new LinkedHashSet<>(List.of(ks));
    }

    @Test
    @DisplayName("清洗：去首尾空白、砍超长、空的直接丢")
    void normalizeText_cleans() {
        assertEquals("HashMap 默认容量 16", MemoryPointService.normalizeText("  HashMap 默认容量 16  "));
        assertEquals("", MemoryPointService.normalizeText(null));
        assertEquals("", MemoryPointService.normalizeText("   "));
        String longText = "x".repeat(300);
        assertEquals(200, MemoryPointService.normalizeText(longText).length(), "超长砍到 200");
    }

    @Test
    @DisplayName("批改续跑不翻倍：同一道题同一句话只落一条")
    void build_isIdempotent() {
        List<String> texts = List.of("equals 与 hashCode 必须一致", "默认容量 16、负载因子 0.75");
        List<MemoryPoint> first = MemoryPointService.build("p1", "q1", List.of("点A"), texts, set(), 1);
        assertEquals(2, first.size());

        // 第二次批改同样输出 → 全部去重
        Set<String> seen = set();
        first.forEach((m) -> seen.add(MemoryPointService.keyOf(m.getQuestionId(), m.getText())));
        List<MemoryPoint> second = MemoryPointService.build("p1", "q1", List.of("点A"), texts, seen, 2);
        assertEquals(0, second.size(), "重复的不该再落库");

        // 不同的题同样的句子 → 是两条（各自的批改结果）
        List<MemoryPoint> other = MemoryPointService.build("p1", "q2", List.of("点A"), texts, seen, 3);
        assertEquals(2, other.size(), "换一道题就该重新记");
    }

    @Test
    @DisplayName("记忆点挂到这道题的知识点名下；综合题挂多个点")
    void build_attachesToPoints() {
        List<MemoryPoint> out = MemoryPointService.build("p1", "q1",
                List.of("HashMap · 哈希", "HashMap · 树化"), List.of("容量取 2 的幂"), set(), 1);
        assertEquals(1, out.size());
        String json = out.get(0).getPointNamesJson();
        assertTrue(json.contains("HashMap · 哈希") && json.contains("HashMap · 树化"),
                "两个点都要挂上，实际 " + json);
    }

    @Test
    @DisplayName("空句子不落库，不占条目")
    void build_skipsBlank() {
        // Arrays.asList 而不是 List.of——List.of 不接受 null，而模型偶尔会回 null
        List<String> texts = java.util.Arrays.asList("", "   ", null, "有效句子");
        List<MemoryPoint> out = MemoryPointService.build("p1", "q1", List.of("点A"), texts, set(), 1);
        assertEquals(1, out.size());
        assertEquals("有效句子", out.get(0).getText());
    }
}
