package com.review.vt.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 出题上下文的硬预算。
 *
 * LLM 调用是无状态的（每次独立 prompt），所以「上下文过长」其实只有一个来源：
 * prompt 里各段内容越攒越多。这里给每段硬上限 + 总预算，超了就从「最近历史」往回砍，
 * 保证单次 prompt 长度永远有顶，不会出现上下文超长。
 */
final class PromptBudget {

    /** 范围描述（用户原话）最多取多少字 */
    static final int MAX_SCOPE = 2000;
    /** 从范围里 URL 抓下来的网上资料正文最多多少字 */
    static final int MAX_REFS = 6000;
    /** L2 会话摘要最多多少字 */
    static final int MAX_DIGEST = 600;
    /** L1 记忆最多多少字 */
    static final int MAX_MEMORY = 1200;
    /** 最近历史最多保留几条 */
    static final int MAX_RECENT_ITEMS = 15;
    /** 最近历史每条最多多少字 */
    static final int MAX_RECENT_ITEM = 120;
    /** 上下文总预算（不含固定 system 提示词） */
    static final int TOTAL = 12000;

    private PromptBudget() {}

    /**
     * 在剩余预算内保留尽可能多的历史行。
     *
     * @param newestFirst 历史行，最新的在前
     * @param usedChars   已经占用预算的字符数（范围 + 摘要 + 记忆）
     * @return 实际能放进 prompt 的历史行（顺序不变）
     */
    static List<String> fitRecent(List<String> newestFirst, int usedChars) {
        List<String> out = new ArrayList<>();
        if (newestFirst == null || newestFirst.isEmpty()) return out;
        int budget = TOTAL - Math.max(0, usedChars);
        for (String line : newestFirst) {
            if (out.size() >= MAX_RECENT_ITEMS) break;
            String v = MemoryRules.cut(line, MAX_RECENT_ITEM);
            if (v.isEmpty()) continue;
            if (budget - v.length() < 0) break; // 预算不够：从最旧的一端砍掉，最近的优先保留
            out.add(v);
            budget -= v.length();
        }
        return out;
    }
}
