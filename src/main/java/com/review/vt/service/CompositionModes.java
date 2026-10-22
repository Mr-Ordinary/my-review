package com.review.vt.service;

import java.util.Set;

/**
 * 组卷模式：控制一卷里**出现哪些难度档**、出几道题。
 *
 * <p>模式只约束「这道题以什么形式考」（基础/中档/大题），
 * 不碰知识点自己的强化链路——选点配比（补足/强化/深入）、深度阶梯（第 N 层）、
 * 掌握度回写都照旧走 {@link KnowledgeService}。
 *
 * <p>档位是后端从深度推出来再**吸附**到模式允许的集合里的：
 * 初级/中级永远推不出大题（深度封顶 2/3 层），「只出大题」若靠过滤就一道题都凑不出来，
 * 所以必须覆盖映射而不是筛掉。
 */
final class CompositionModes {

    /** 常规：基础+中档+大题（混排，历史默认） */
    static final String REGULAR = "regular";
    /** 基础：只出基础小题 */
    static final String BASE = "base";
    /** 提升：只出中档 */
    static final String IMPROVE = "improve";
    /** 深入：只出大题 */
    static final String DEEP = "deep";
    /** 基础强化：基础+中档 */
    static final String BASE_IMPROVE = "baseImprove";
    /** 中档提升：中档+大题 */
    static final String MID_IMPROVE = "midImprove";

    private static final Set<String> ALL = Set.of(
            REGULAR, BASE, IMPROVE, DEEP, BASE_IMPROVE, MID_IMPROVE);

    private CompositionModes() {}

    /** 非法/空值一律回落常规。旧数据没有该字段也是常规。 */
    static String normalize(String mode) {
        if (mode == null) return REGULAR;
        String m = mode.trim();
        return ALL.contains(m) ? m : REGULAR;
    }

    /** 该模式允许出现的档位集合 */
    static Set<String> tiersOf(String mode) {
        return switch (normalize(mode)) {
            case BASE -> Set.of("基础");
            case IMPROVE -> Set.of("中档");
            case DEEP -> Set.of("大题");
            case BASE_IMPROVE -> Set.of("基础", "中档");
            case MID_IMPROVE -> Set.of("中档", "大题");
            default -> Set.of("基础", "中档", "大题");
        };
    }

    /**
     * 一卷几道题。只有常规卷跟练习的题量档（6/10/15）走，
     * 其余模式用固定题量——题量是按「合计 100 分、档位分值区间能成立」倒推的：
     *
     * <pre>
     * base        25 道 × 4 分（基础 2-4）
     * improve     14 道 × ~7 分（中档 4-8）
     * deep         5 道 × 20 分（大题 15-20）
     * baseImprove 20 道（基础+中档混排）
     * midImprove  10 道（中档+大题混排）
     * </pre>
     *
     * 混排模式的实际档位配比由知识点深度决定（模式只做约束），
     * 所以只锁总量，不锁各档几道。
     */
    static int questionCount(String mode, int paperSize) {
        return switch (normalize(mode)) {
            case BASE -> 25;
            case IMPROVE -> 14;
            case DEEP -> 5;
            case BASE_IMPROVE -> 20;
            case MID_IMPROVE -> 10;
            default -> paperSize;
        };
    }

    /**
     * 把深度推出的档位吸到模式允许的集合里，按档位距离取最近
     * （基础 0 &lt; 中档 1 &lt; 大题 2，同距取较低的档——形式宁可稳一点）。
     *
     * <p>注意：吸附的只是**题型形式**。知识点的深度阶梯不受影响——
     * 「第 4 层」的点在基础卷里出的仍是基础形式的题，角度深浅照第 4 层走。
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

    /** 卷末串联大题只在允许大题的模式下启用（基础/提升/基础强化里塞不下一整道场景题） */
    static boolean allowsLink(String mode) {
        return tiersOf(mode).contains("大题");
    }

    private static int rankOf(String tier) {
        return "大题".equals(tier) ? 2 : "中档".equals(tier) ? 1 : "基础".equals(tier) ? 0 : -1;
    }
}
