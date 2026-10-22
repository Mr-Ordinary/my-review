package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("按档位保底：大题至少 15 分、中档 4 分、基础 2 分")
    void normalizePoints_tierFloors() {
        // AI 把大题给成 13 分，后端要抬到 ≥15
        int[] raw = {3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 13, 13};
        String[] tiers = new String[raw.length];
        for (int i = 0; i < tiers.length; i++) tiers[i] = i >= 17 ? "大题" : "基础";
        int[] out = Scoring.normalizePoints(raw, tiers);
        assertEquals(100, sum(out));
        assertTrue(out[17] >= 15 && out[17] <= 20, "大题应在 15-20，实际 " + out[17]);
        assertTrue(out[18] >= 15 && out[18] <= 20, "大题应在 15-20，实际 " + out[18]);
        for (int i = 0; i < 17; i++) assertTrue(out[i] >= 2, "基础题至少 2 分");
    }

    /** 真实题量下（15-30 题）的档位分布：15 基础 + 4 中档 + 1 大题 */
    private static String[] realisticTiers() {
        String[] t = new String[20];
        for (int i = 0; i < 20; i++) t[i] = i < 15 ? "基础" : i < 19 ? "中档" : "大题";
        return t;
    }

    @Test
    @DisplayName("档位归一化后总分必须是 100，且每题不小于保底")
    void normalizePoints_tierAlwaysSumsTo100() {
        String[] tiers = realisticTiers();
        for (int[] raw : new int[][] {
                new int[20],                                    // AI 一个分值都没给
                java.util.stream.IntStream.range(0, 20).map(i -> 5).toArray(),
                java.util.stream.IntStream.range(0, 20).map(i -> i == 19 ? 28 : 2).toArray() // 大题给得太重
        }) {
            int[] out = Scoring.normalizePoints(raw, tiers);
            assertEquals(100, sum(out), "总分必须 100");
            for (int i = 0; i < 20; i++) {
                int floor = i == 19 ? 15 : i >= 15 ? 4 : 2;
                assertTrue(out[i] >= floor, "第 " + i + " 题低于保底：" + out[i]);
            }
        }
    }

    @Test
    @DisplayName("AI 把大题写成 28 分要压到 20，多出的分摊给小题")
    void normalizePoints_capsMajorAt20() {
        int[] raw = java.util.stream.IntStream.range(0, 20).map(i -> i == 19 ? 28 : 2).toArray();
        int[] out = Scoring.normalizePoints(raw, realisticTiers());
        assertEquals(100, sum(out));
        assertTrue(out[19] >= 15 && out[19] <= 20, "大题应夹在 15-20，实际 " + out[19]);
        for (int i = 0; i < 15; i++) assertTrue(out[i] >= 2 && out[i] <= 4, "基础题应夹在 2-4，实际 " + out[i]);
        for (int i = 15; i < 19; i++) assertTrue(out[i] >= 4 && out[i] <= 8, "中档应夹在 4-8，实际 " + out[i]);
    }

    @Test
    @DisplayName("题量太少、夹着上限也凑不满 100 时，放开上限也要凑够 100")
    void normalizePoints_relaxesCeilingWhenShort() {
        int[] raw = {5, 5, 5, 5, 28};
        String[] tiers = {"基础", "基础", "基础", "基础", "大题"};
        int[] out = Scoring.normalizePoints(raw, tiers);
        assertEquals(100, sum(out), "总分必须 100，宁可突破上限");
    }

    @Test
    @DisplayName("题太多导致保底就超 100 时，退回普通归一化，不炸")
    void normalizePoints_floorOverflowFallsBack() {
        int n = 60;
        int[] raw = new int[n];
        String[] tiers = new String[n];
        for (int i = 0; i < n; i++) { raw[i] = 1; tiers[i] = "大题"; }
        int[] out = Scoring.normalizePoints(raw, tiers);
        assertEquals(n, out.length);
        assertEquals(100, sum(out));
    }

    /* ---------- 组卷模式的固定题量：单档分布要能落在档位区间里 ---------- */

    @Test
    @DisplayName("基础卷 25 道全基础：每题 4 分，正好 100")
    void normalizePoints_allBase25() {
        int n = 25;
        int[] raw = new int[n];
        String[] tiers = new String[n];
        for (int i = 0; i < n; i++) { raw[i] = 3; tiers[i] = "基础"; }
        int[] out = Scoring.normalizePoints(raw, tiers);
        assertEquals(100, sum(out));
        for (int v : out) assertTrue(v >= 2 && v <= 4, "基础题应夹在 2-4，实际 " + v);
    }

    @Test
    @DisplayName("深入卷 5 道全大题：每题 20 分，正好 100")
    void normalizePoints_allMajor5() {
        int n = 5;
        int[] raw = new int[n];
        String[] tiers = new String[n];
        for (int i = 0; i < n; i++) { raw[i] = 16; tiers[i] = "大题"; }
        int[] out = Scoring.normalizePoints(raw, tiers);
        assertEquals(100, sum(out));
        for (int v : out) assertTrue(v >= 15 && v <= 20, "大题应夹在 15-20，实际 " + v);
    }

    @Test
    @DisplayName("提升卷 14 道全中档：每题 4-8 分，合计 100")
    void normalizePoints_allMid14() {
        int n = 14;
        int[] raw = new int[n];
        String[] tiers = new String[n];
        for (int i = 0; i < n; i++) { raw[i] = 6; tiers[i] = "中档"; }
        int[] out = Scoring.normalizePoints(raw, tiers);
        assertEquals(100, sum(out));
        for (int v : out) assertTrue(v >= 4 && v <= 8, "中档应夹在 4-8，实际 " + v);
    }

    @Test
    @DisplayName("基础强化 20 道（13 基础 + 7 中档）：合计 100 且不破区间")
    void normalizePoints_baseImprove20() {
        int n = 20;
        int[] raw = new int[n];
        String[] tiers = new String[n];
        for (int i = 0; i < n; i++) { raw[i] = i < 13 ? 3 : 6; tiers[i] = i < 13 ? "基础" : "中档"; }
        int[] out = Scoring.normalizePoints(raw, tiers);
        assertEquals(100, sum(out));
        for (int i = 0; i < 20; i++) {
            if (i < 13) assertTrue(out[i] >= 2 && out[i] <= 4, "基础应夹在 2-4，实际 " + out[i]);
            else assertTrue(out[i] >= 4 && out[i] <= 8, "中档应夹在 4-8，实际 " + out[i]);
        }
    }

    @Test
    @DisplayName("中档提升 10 道（6 中档 + 4 大题）：合计 100 且不破区间")
    void normalizePoints_midImprove10() {
        int n = 10;
        int[] raw = new int[n];
        String[] tiers = new String[n];
        for (int i = 0; i < n; i++) { raw[i] = i < 6 ? 5 : 16; tiers[i] = i < 6 ? "中档" : "大题"; }
        int[] out = Scoring.normalizePoints(raw, tiers);
        assertEquals(100, sum(out));
        for (int i = 0; i < 10; i++) {
            if (i < 6) assertTrue(out[i] >= 4 && out[i] <= 8, "中档应夹在 4-8，实际 " + out[i]);
            else assertTrue(out[i] >= 15 && out[i] <= 20, "大题应夹在 15-20，实际 " + out[i]);
        }
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
