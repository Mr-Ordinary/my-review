package com.review.vt.service;

import com.review.vt.domain.KnowledgePoint;

import java.util.Locale;
import java.util.Set;

/**
 * 复习强度：决定①出题按哪一档内容规格出②哪些优先级的点进题池。
 *
 * <p>一刷=速通、二刷=标准、三刷=深挖——深度在用户手里，
 * 不靠 timesSeen 自动加深（那是上一版失控的根源）。
 *
 * <p>三档内容规格是**点上预先声明的**（boundQuick/boundStd/boundDeep），
 * 出题 AI 只能在对应档的边界内出题，不能自作主张往源码里挖。
 */
final class Intensities {

    /** 速通：覆盖高频+常见，只考概念与用法。一刷、当天铺面 */
    static final String QUICK = "quick";
    /** 标准：同样覆盖高频+常见，但要理解原理与主干参数。二刷、日常（默认） */
    static final String STANDARD = "standard";
    /** 深挖：全量（含偏门）+ 取舍边界。三刷、查漏、高级岗 */
    static final String DEEP = "deep";

    private Intensities() {}

    /** 非法/空值一律回落标准档。旧数据没有该字段也是标准。 */
    static String normalize(String intensity) {
        if (intensity == null) return STANDARD;
        String s = intensity.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case QUICK, "速通" -> QUICK;
            case DEEP, "深挖" -> DEEP;
            default -> STANDARD;
        };
    }

    /**
     * 该强度允许进题池的知识点优先级。
     *
     * <p>**优先级只决定覆盖顺序（high → medium → low），不再决定能不能进池**：
     * 三种强度都覆盖全部点，未考点按优先级排序，高频先考、偏门最后。
     * 速通/标准/深挖的区别回到**内容规格**（每个点考多深），而不是“哪些点永远不考”。
     *
     * <p>为什么这样改：只出 high 会让题池小到每张卷反复考同几个点
     * （实测 48 个点里只有 7 个 high，80 道题砸在 12 个点上，其余 36 个永远是「未学」）。
     */
    static Set<String> allowedPriorities(String intensity) {
        return Set.of("high", "medium", "low");
    }

    /** 这个点在当前强度下是否进题池 */
    static boolean allows(KnowledgePoint kp, String intensity) {
        if (kp == null) return false;
        String priority = kp.getPriority();
        if (priority == null || priority.isBlank()) {
            // 旧数据/发散点没标优先级：按 medium 处理（速通/标准/深挖都可见）
            priority = "medium";
        }
        return allowedPriorities(intensity).contains(priority.trim().toLowerCase(Locale.ROOT));
    }

    /** 该强度取哪一档内容规格。null 表示点上没写这档（出题时降级到更浅的档） */
    static String boundOf(KnowledgePoint kp, String intensity) {
        if (kp == null) return null;
        return switch (normalize(intensity)) {
            case QUICK -> firstNonBlank(kp.getBoundQuick(), kp.getBoundStd(), kp.getBoundDeep());
            case DEEP -> firstNonBlank(kp.getBoundDeep(), kp.getBoundStd(), kp.getBoundQuick());
            default -> firstNonBlank(kp.getBoundStd(), kp.getBoundDeep(), kp.getBoundQuick());
        };
    }

    /**
     * 该强度给「常规」组卷模式的档位配比权重。
     * 组卷模式显式指定档位集合时以模式为准；常规模式才用强度定配比。
     *
     * <pre>
     * 速通   基础为主（概念快刷）
     * 标准   基础+中档+少量大题（真考卷结构）
     * 深挖   中档+大题为主（场景与取舍）
     * </pre>
     */
    static int[] tierWeights(String intensity) {
        // [基础, 中档, 大题]
        return switch (normalize(intensity)) {
            case QUICK -> new int[] {8, 2, 0};
            case DEEP -> new int[] {2, 4, 4};
            default -> new int[] {5, 3, 2};
        };
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
