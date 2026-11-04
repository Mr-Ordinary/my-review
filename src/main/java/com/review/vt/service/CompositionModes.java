package com.review.vt.service;

import java.util.Set;

/**
 * 组卷模式：控制一卷里**出现哪些难度档**、出几道题。只保留三种（2026-11 精简）：
 *
 * <ul>
 *   <li><b>常规</b>（regular）：基础+中档+大题混排，按练习的题量档（6/10/15）——像真考卷</li>
 *   <li><b>概念题</b>（concept）：只出基础小题，约 25 道——先巩固概念</li>
 *   <li><b>场景题</b>（scene）：中档+大题，约 10 道——最后场景实战</li>
 * </ul>
 *
 * <p>模式只约束「这道题以什么形式考」，不碰知识点自己的强化链路——
 * 选点配比、深度阶梯、掌握度回写都照旧走 {@link KnowledgeService}。
 *
 * <p>旧模式串（base/improve/deep/baseImprove/midImprove）与非法值一律归 regular——
 * 全部历史数据本来就是 regular/null，无需映射表。
 */
final class CompositionModes {

    /** 常规：基础+中档+大题（混排，按题量） */
    static final String REGULAR = "regular";
    /** 概念题：只出基础小题 */
    static final String CONCEPT = "concept";
    /** 场景题：中档+大题 */
    static final String SCENE = "scene";

    private static final Set<String> ALL = Set.of(REGULAR, CONCEPT, SCENE);

    private CompositionModes() {}

    /** 非法/空值/旧模式串一律回落常规 */
    static String normalize(String mode) {
        if (mode == null) return REGULAR;
        String m = mode.trim();
        return ALL.contains(m) ? m : REGULAR;
    }

    /** 该模式允许出现的档位集合 */
    static Set<String> tiersOf(String mode) {
        return switch (normalize(mode)) {
            case CONCEPT -> Set.of("基础");
            case SCENE -> Set.of("中档", "大题");
            default -> Set.of("基础", "中档", "大题");
        };
    }

    /**
     * 一卷几道题。只有常规卷跟练习的题量档（6/10/15）走，
     * 其余模式用固定题量——题量是按「合计 100 分、档位分值区间能成立」倒推的：
     *
     * <pre>
     * concept  25 道 × 4 分（基础 2-4）
     * scene    10 道（中档+大题混排）
     * regular  按 paperSize（6/10/15）
     * </pre>
     */
    static int questionCount(String mode, int paperSize) {
        return switch (normalize(mode)) {
            case CONCEPT -> 25;
            case SCENE -> 10;
            default -> paperSize;
        };
    }

    /**
     * 把深度推出的档位吸到模式允许的集合里，按档位距离取最近
     * （基础 0 &lt; 中档 1 &lt; 大题 2，同距取较低的档——形式宁可稳一点）。
     */
    static String snapTier(String derived, String mode) {
        String tier = rankOf(derived) < 0 ? "基础" : derived.trim();
        Set<String> allowed = tiersOf(mode);
        if (allowed.contains(tier)) return tier;
        int rank = rankOf(tier);
        String best = "基础";
        int bestDist = Integer.MAX_VALUE;
        for (String t : allowed) {
            int dist = Math.abs(rankOf(t) - rank);
            if (dist < bestDist || (dist == bestDist && rankOf(t) < rankOf(best))) {
                bestDist = dist;
                best = t;
            }
        }
        return best;
    }

    /** 卷末串联大题只在允许大题的模式下启用（概念题里塞不下一整道场景题） */
    static boolean allowsLink(String mode) {
        return tiersOf(mode).contains("大题");
    }

    private static int rankOf(String tier) {
        return "大题".equals(tier) ? 2 : "中档".equals(tier) ? 1 : "基础".equals(tier) ? 0 : -1;
    }
}
