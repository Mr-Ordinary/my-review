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

    /** 各档的分值区间：大题要够重但不能吃掉半张卷 */
    private static final int MIN_MAJOR = 15;
    private static final int MAX_MAJOR = 20;
    private static final int MIN_MID = 4;
    private static final int MAX_MID = 8;
    private static final int MIN_BASE = 2;
    private static final int MAX_BASE = 4;

    private Scoring() {}

    /** 各档的保底分值 */
    private static int floorOf(String tier) {
        return "大题".equals(tier) ? MIN_MAJOR : "中档".equals(tier) ? MIN_MID : MIN_BASE;
    }

    /** 各档的上限：AI 把大题写成 28 分时要压下来 */
    private static int ceilOf(String tier) {
        return "大题".equals(tier) ? MAX_MAJOR : "中档".equals(tier) ? MAX_MID : MAX_BASE;
    }

    /**
     * 按难度档归一化：先给每题保底分值，剩下的按 AI 给的比例分，
     * 但每题不超过本档上限，最后凑整到正好 100。
     *
     * 卷面结构不能指望模型自觉——AI 有时把大题写成 13 分，有时写成 28 分，这里统一校正。
     */
    static int[] normalizePoints(int[] raw, String[] tiers) {
        if (raw == null || raw.length == 0) return new int[0];
        int n = raw.length;
        int[] floor = new int[n];
        int[] ceil = new int[n];
        for (int i = 0; i < n; i++) {
            String t = tiers == null || i >= tiers.length ? "" : tiers[i];
            floor[i] = floorOf(t);
            ceil[i] = ceilOf(t);
        }
        int floorSum = 0;
        for (int f : floor) floorSum += f;
        if (floorSum > PAPER_MAX) {
            // 极端情况（题太多导致保底就超 100）：退回不带档位的归一化
            return normalizePoints(raw);
        }

        int[] out = floor.clone();
        int rest = PAPER_MAX - floorSum;

        // 按 AI 给的比例分配剩余，尽量夹在 [floor, ceil] 之间
        int rawSum = 0;
        for (int v : raw) rawSum += Math.max(0, v);
        if (rawSum > 0) {
            for (int i = 0; i < n && rest > 0; i++) {
                int want = (int) Math.round(rest * (Math.max(0, raw[i]) / (double) rawSum));
                int canTake = Math.min(want, ceil[i] - out[i]);
                if (canTake > 0) {
                    out[i] += canTake;
                    rest -= canTake;
                }
            }
        }

        // 上限是**软的**：题少时所有题都顶到上限也凑不满 100，
        // 这时候必须放开上限把分发完，否则总分对不上，均分就全错了。
        int guard = n * 8 + 1;
        while (rest > 0 && guard-- > 0) {
            boolean any = false;
            for (int i = 0; i < n && rest > 0; i++) {
                if (out[i] < ceil[i]) { out[i]++; rest--; any = true; }
            }
            if (!any) {
                for (int i = 0; i < n && rest > 0; i++) { out[i]++; rest--; }
            }
        }
        // 超了就从最大的往下压（不低于保底）
        while (rest < 0) {
            int idx = -1;
            for (int i = 0; i < n; i++) if (out[i] > floor[i] && (idx < 0 || out[i] > out[idx])) idx = i;
            if (idx < 0) break;
            out[idx]--;
            rest++;
        }
        return out;
    }

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
