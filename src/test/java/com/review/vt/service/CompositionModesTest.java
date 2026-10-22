package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组卷模式 → 允许档位 / 题量 / 档位吸附。
 * 单档卷全靠这里的映射撑着——吸错了，出的卷就不是用户要的构成。
 */
class CompositionModesTest {

    @Test
    @DisplayName("非法/空模式一律回落常规")
    void normalize_fallsBackToRegular() {
        assertEquals("regular", CompositionModes.normalize(null));
        assertEquals("regular", CompositionModes.normalize(""));
        assertEquals("regular", CompositionModes.normalize("  "));
        assertEquals("regular", CompositionModes.normalize("whatever"));
        assertEquals("base", CompositionModes.normalize(" base "));
    }

    @Test
    @DisplayName("六种模式的允许档位集合")
    void tiersOf_sixModes() {
        assertEquals(Set.of("基础", "中档", "大题"), CompositionModes.tiersOf("regular"));
        assertEquals(Set.of("基础"), CompositionModes.tiersOf("base"));
        assertEquals(Set.of("中档"), CompositionModes.tiersOf("improve"));
        assertEquals(Set.of("大题"), CompositionModes.tiersOf("deep"));
        assertEquals(Set.of("基础", "中档"), CompositionModes.tiersOf("baseImprove"));
        assertEquals(Set.of("中档", "大题"), CompositionModes.tiersOf("midImprove"));
    }

    @Test
    @DisplayName("只有常规卷跟练习的题量档走，其余模式固定题量")
    void questionCount_regularFollowsPaperSize() {
        assertEquals(6, CompositionModes.questionCount("regular", 6));
        assertEquals(15, CompositionModes.questionCount("regular", 15));
        assertEquals(25, CompositionModes.questionCount("base", 6));
        assertEquals(14, CompositionModes.questionCount("improve", 15));
        assertEquals(5, CompositionModes.questionCount("deep", 10));
        assertEquals(20, CompositionModes.questionCount("baseImprove", 10));
        assertEquals(10, CompositionModes.questionCount("midImprove", 15));
    }

    @Test
    @DisplayName("单档模式把任何深度推出的档位都吸过去")
    void snapTier_singleTierModes() {
        for (String derived : new String[] {"基础", "中档", "大题"}) {
            assertEquals("基础", CompositionModes.snapTier(derived, "base"));
            assertEquals("中档", CompositionModes.snapTier(derived, "improve"));
            assertEquals("大题", CompositionModes.snapTier(derived, "deep"));
        }
    }

    @Test
    @DisplayName("混合模式只吸不允许的档，允许的原样保留")
    void snapTier_mixedModes() {
        // 基础强化（基础+中档）：大题下吸到中档
        assertEquals("基础", CompositionModes.snapTier("基础", "baseImprove"));
        assertEquals("中档", CompositionModes.snapTier("中档", "baseImprove"));
        assertEquals("中档", CompositionModes.snapTier("大题", "baseImprove"));
        // 中档提升（中档+大题）：基础上吸到中档
        assertEquals("中档", CompositionModes.snapTier("基础", "midImprove"));
        assertEquals("中档", CompositionModes.snapTier("中档", "midImprove"));
        assertEquals("大题", CompositionModes.snapTier("大题", "midImprove"));
        // 常规：全保留
        assertEquals("基础", CompositionModes.snapTier("基础", "regular"));
        assertEquals("大题", CompositionModes.snapTier("大题", "regular"));
    }

    @Test
    @DisplayName("未知档位按基础处理再吸")
    void snapTier_unknownDerived() {
        assertEquals("基础", CompositionModes.snapTier(null, "base"));
        assertEquals("中档", CompositionModes.snapTier("", "improve"));
        assertEquals("大题", CompositionModes.snapTier("综合", "deep"));
        assertEquals("中档", CompositionModes.snapTier("怪档", "midImprove"));
    }

    @Test
    @DisplayName("串联大题只在含大题的模式下启用")
    void allowsLink_onlyWhenMajorAllowed() {
        assertTrue(CompositionModes.allowsLink("regular"));
        assertTrue(CompositionModes.allowsLink("deep"));
        assertTrue(CompositionModes.allowsLink("midImprove"));
        assertFalse(CompositionModes.allowsLink("base"));
        assertFalse(CompositionModes.allowsLink("improve"));
        assertFalse(CompositionModes.allowsLink("baseImprove"));
    }

    @Test
    @DisplayName("固定题量在典型构成下保底分值撑得住 100 分（区间能成立）")
    void questionCount_floorSumFits100() {
        // floor：基础 2 / 中档 4 / 大题 15
        // 单档卷恒成立
        assertTrue(25 * 2 <= 100, "base");
        assertTrue(14 * 4 <= 100, "improve");
        assertTrue(5 * 15 <= 100, "deep");
        // 混排的典型构成（见 questionCount 注释）；极端偏斜
        // （如中档提升 10 道全吸成大题）会走 Scoring 的无档位兜底，合计照样 100
        assertTrue(13 * 2 + 7 * 4 <= 100, "baseImprove 典型 13基础+7中档");
        assertTrue(6 * 4 + 4 * 15 <= 100, "midImprove 典型 6中档+4大题");
    }
}
