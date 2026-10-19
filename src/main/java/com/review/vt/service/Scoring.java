package com.review.vt.service;

import java.util.Arrays;
import java.util.List;

/**
 * 百分制分值计算。一张卷 = 100 分，题目分「基础 / 中档 / 大题」三档，每题有分值。
 * AI 只给 0-10 的表现分，实得分与卷得分都在这里换算，保证口径唯一。
 */
final class Scoring {

    /** 一张卷的满分 */
    static final int PAPER_MAX = 100;

    /** 每题最低分值（保证小题也有分） */
    private static final int MIN_POINTS = 1;

    private Scoring() {}

    /**
     * 把 AI 给的每题分值归一化到合计正好 {@link #PAPER_MAX}。
     * AI 常把总分算歪（95、105、全是 5 分…），这里统一校正：
     * 先按比例缩放取整（每题至少 1 分），再把残差逐个摊到最大的题上。
     */
    static int[] normalizePoints(int[] raw) {
        int n = raw == null ? 0 : raw.length;
        if (n == 0) return new int[0];

        int sum = 0;
        for (int v : raw) sum += Math.max(0, v);
        if (sum <= 0) {
            // 一个有效分值都没有：均分
            int[] even = new int[n];
            Arrays.fill(even, MIN_POINTS);
            even[0] += PAPER_MAX - MIN_POINTS * n;
            return even;
        }

        int[] out = new int[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            int v = (int) Math.round(raw[i] * (PAPER_MAX / (double) sum));
            out[i] = Math.max(MIN_POINTS, v);
            assigned += out[i];
        }

        // 残差逐个摊派到当前最大的题上，保证最终合计正好 PAPER_MAX
        int diff = PAPER_MAX - assigned;
        int guard = PAPER_MAX * 2 + n; // 防死循环
        while (diff != 0 && guard-- > 0) {
            int idx = 0;
            for (int i = 1; i < n; i++) if (out[i] > out[idx]) idx = i;
            if (diff > 0) {
                out[idx]++;
                diff--;
            } else if (out[idx] > MIN_POINTS) {
                out[idx]--;
                diff++;
            } else {
                break;
            }
        }
        return out;
    }

    /** 实得分 = 表现分 / 10 × 该题分值，保留 1 位小数；未评分给 null */
    static Double earnedPoints(Double score, int questionPoints) {
        if (score == null) return null;
        double s = Math.max(0, Math.min(10, score));
        return round1(s / 10.0 * Math.max(0, questionPoints));
    }

    /** 卷得分 = Σ 实得分，保留 1 位小数 */
    static double paperScore(List<Double> earned) {
        double sum = 0;
        for (Double e : earned) if (e != null) sum += e;
        return round1(sum);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
