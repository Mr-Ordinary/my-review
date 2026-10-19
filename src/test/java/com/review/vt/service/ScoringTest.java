package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 百分制分值计算：一张卷必须正好 100 分，实得分换算要稳。
 * 这里是最容易「悄悄坏」的地方——AI 给的分值经常算歪。
 */
class ScoringTest {

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    @Test
    @DisplayName("分值合计已经是 100 时原样保留")
    void normalize_keepsExact100() {
        int[] out = Scoring.normalizePoints(new int[] {3, 3, 4, 40, 50});
        assertEquals(100, sum(out));
        assertEquals(3, out[0]);
    }

    @Test
    @DisplayName("AI 给少了（合计 95）也能归一化到 100")
    void normalize_scalesUp() {
        int[] out = Scoring.normalizePoints(new int[] {5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5});
        assertEquals(100, sum(out));
    }

    @Test
    @DisplayName("AI 给多了（合计 120）也能归一化到 100")
    void normalize_scalesDown() {
        int[] out = Scoring.normalizePoints(new int[] {30, 30, 30, 30});
        assertEquals(100, sum(out));
    }

    @Test
    @DisplayName("每题至少 1 分，不能被归一化成 0 分")
    void normalize_minOnePointEach() {
        int[] out = Scoring.normalizePoints(new int[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 91});
        assertEquals(100, sum(out));
        for (int v : out) {
            assertEquals(true, v >= 1, "每题分值都必须 >= 1，实际：" + v);
        }
    }

    @Test
    @DisplayName("极端不均（1 道大题 100 分 + 一堆小题）仍合计 100")
    void normalize_extremeSkew() {
        int[] raw = new int[30];
        raw[0] = 100;
        for (int i = 1; i < 30; i++) raw[i] = 1;
        int[] out = Scoring.normalizePoints(raw);
        assertEquals(30, out.length);
        assertEquals(100, sum(out));
    }

    @Test
    @DisplayName("AI 一个分值都没给：均分，合计仍是 100")
    void normalize_allZero() {
        int[] out = Scoring.normalizePoints(new int[] {0, 0, 0, 0});
        assertEquals(4, out.length);
        assertEquals(100, sum(out));
    }

    @Test
    @DisplayName("空输入返回空数组，不炸")
    void normalize_empty() {
        assertEquals(0, Scoring.normalizePoints(new int[0]).length);
        assertEquals(0, Scoring.normalizePoints(null).length);
    }

    @Test
    @DisplayName("实得分 = 表现分/10 × 题目分值，保留 1 位小数")
    void earnedPoints_basic() {
        assertEquals(3.5, Scoring.earnedPoints(7.0, 5));
        assertEquals(0.0, Scoring.earnedPoints(0.0, 12));
        assertEquals(12.0, Scoring.earnedPoints(10.0, 12));
    }

    @Test
    @DisplayName("表现分越界要夹到 0-10；未评分给 null")
    void earnedPoints_clampsAndNull() {
        assertEquals(5.0, Scoring.earnedPoints(20.0, 5));
        assertEquals(0.0, Scoring.earnedPoints(-3.0, 5));
        assertNull(Scoring.earnedPoints(null, 5));
    }

    @Test
    @DisplayName("卷得分 = 各题实得分之和；未作答的题按 0 计")
    void paperScore_sumsEarned() {
        List<Double> earned = java.util.Arrays.asList(3.5, 10.0, null, 24.0);
        assertEquals(37.5, Scoring.paperScore(earned));
        assertEquals(0.0, Scoring.paperScore(List.of()));
    }
}
