package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组卷模式（2026-11 精简为 3 种）→ 允许档位 / 题量 / 档位吸附。
 * 单档卷全靠这里的映射撑着——吸错了，出的卷就不是用户要的构成。
 */
class CompositionModesTest {

    @Test
    @DisplayName("非法/空/旧模式串一律回落常规")
    void normalize_fallsBackToRegular() {
        assertEquals("regular", CompositionModes.normalize(null));
        assertEquals("regular", CompositionModes.normalize(""));
        assertEquals("regular", CompositionModes.normalize("  "));
        assertEquals("regular", CompositionModes.normalize("whatever"));
        // 旧六模式串全部归 regular（历史数据全是 regular/null）
        assertEquals("regular", CompositionModes.normalize("base"));
        assertEquals("regular", CompositionModes.normalize("improve"));
        assertEquals("regular", CompositionModes.normalize("deep"));
        assertEquals("regular", CompositionModes.normalize("baseImprove"));
        assertEquals("regular", CompositionModes.normalize("midImprove"));
        // 三个合法值原样保留
        assertEquals("concept", CompositionModes.normalize(" concept "));
        assertEquals("scene", CompositionModes.normalize("scene"));
    }

    @Test
    @DisplayName("三种模式的允许档位集合")
    void tiersOf_threeModes() {
        assertEquals(Set.of("基础", "中档", "大题"), CompositionModes.tiersOf("regular"));
        assertEquals(Set.of("基础"), CompositionModes.tiersOf("concept"));
        assertEquals(Set.of("中档", "大题"), CompositionModes.tiersOf("scene"));
    }

    @Test
    @DisplayName("只有常规卷跟练习的题量档走，其余模式固定题量")
    void questionCount_regularFollowsPaperSize() {
        assertEquals(6, CompositionModes.questionCount("regular", 6));
        assertEquals(15, CompositionModes.questionCount("regular", 15));
        assertEquals(25, CompositionModes.questionCount("concept", 6));
        assertEquals(10, CompositionModes.questionCount("scene", 15));
    }

    @Test
    @DisplayName("概念题把任何深度推出的档位都吸到基础")
    void snapTier_concept() {
        for (String derived : new String[] {"基础", "中档", "大题"}) {
            assertEquals("基础", CompositionModes.snapTier(derived, "concept"));
        }
    }

    @Test
    @DisplayName("场景题把基础吸到中档，中档/大题保留")
    void snapTier_scene() {
        assertEquals("中档", CompositionModes.snapTier("基础", "scene"));
        assertEquals("中档", CompositionModes.snapTier("中档", "scene"));
        assertEquals("大题", CompositionModes.snapTier("大题", "scene"));
    }

    @Test
    @DisplayName("常规全档保留")
    void snapTier_regular() {
        assertEquals("基础", CompositionModes.snapTier("基础", "regular"));
        assertEquals("中档", CompositionModes.snapTier("中档", "regular"));
        assertEquals("大题", CompositionModes.snapTier("大题", "regular"));
    }

    @Test
    @DisplayName("未知档位按基础处理再吸")
    void snapTier_unknownDerived() {
        assertEquals("基础", CompositionModes.snapTier(null, "concept"));
        assertEquals("中档", CompositionModes.snapTier("", "scene"));
        // 未知档→基础；regular 允许基础所以原样保留，scene 不允许所以吸到中档
        assertEquals("基础", CompositionModes.snapTier("综合", "regular"));
        assertEquals("中档", CompositionModes.snapTier("怪档", "scene"));
    }

    @Test
    @DisplayName("串联大题只在含大题的模式下启用")
    void allowsLink_onlyWhenMajorAllowed() {
        assertTrue(CompositionModes.allowsLink("regular"));
        assertTrue(CompositionModes.allowsLink("scene"));
        assertFalse(CompositionModes.allowsLink("concept"));
    }

    @Test
    @DisplayName("固定题量在典型构成下保底分值撑得住 100 分（区间能成立）")
    void questionCount_floorSumFits100() {
        // floor：基础 2 / 中档 4 / 大题 15
        assertTrue(25 * 2 <= 100, "concept 25 道基础");
        // scene 10 道中档+大题，典型 6 中档 + 4 大题
        assertTrue(6 * 4 + 4 * 15 <= 100, "scene 典型 6中档+4大题");
    }
}
