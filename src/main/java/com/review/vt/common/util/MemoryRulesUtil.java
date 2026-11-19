package com.review.vt.common.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 记忆的容量与合并规则。目标是「不丢失 + 精简」：
 * coverage（已覆盖知识点）用并集累积，靠服务端保证不丢；
 * 其余条目由 AI 蒸馏措辞，这里只负责去重、截断到字数与条数上限，防止记忆撑爆上下文。
 */
public final class MemoryRulesUtil {

    /** 记忆里 mastered/weakPoints/misconceptions/nextFocus 每类最多条数 */
    public static final int MAX_ITEMS = 8;
    /** 每条最多字数 */
    public static final int MAX_ITEM_CHARS = 30;
    /** 已覆盖知识点最多条数（比别的列表宽松，因为它才是「不重复出题」的依据） */
    public static final int MAX_COVERAGE = 40;
    /** 已覆盖知识点每条最多字数 */
    public static final int MAX_COVERAGE_CHARS = 24;

    private MemoryRulesUtil() {}

    /** 去重（保序）→ 截断字数 → 限量 */
    public static List<String> tidy(List<String> items, int maxItems, int maxChars) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (items != null) {
            for (String s : items) {
                String v = s == null ? "" : s.trim();
                if (v.isEmpty()) continue;
                v = cut(v, maxChars);
                seen.add(v);
                if (seen.size() >= maxItems) break;
            }
        }
        return new ArrayList<>(seen);
    }

    /** 两份列表求并集（老的在前，新的在后），再套 tidy 规则 */
    public static List<String> mergeUnion(List<String> older, List<String> newer, int maxItems, int maxChars) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (older != null) for (String s : older) if (s != null && !s.isBlank()) all.add(s.trim());
        if (newer != null) for (String s : newer) if (s != null && !s.isBlank()) all.add(s.trim());
        return tidy(new ArrayList<>(all), maxItems, maxChars);
    }

    public static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
