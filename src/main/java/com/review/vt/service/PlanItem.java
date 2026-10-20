package com.review.vt.service;

import java.util.List;

/**
 * 出卷计划里的一道题：知识点 + 出题角度 + 难度档 + 题型 + 分值。
 * 一张卷的 points 合计 100，由 {@link Scoring#normalizePoints} 校正。
 *
 * @param pointNames 本题挂在清单里的哪几个点。常规题 1 个；卷末串联大题 2-3 个。
 *                   交卷后按这些名字回写掌握度。
 * @param angle      出题角度（打算怎么问）。会存进 {@code Question.angle}，
 *                   下次给同一个点出题时要避开它，这就是「变种」的落点。
 * @param points     本题分值（不是知识点个数）
 */
record PlanItem(List<String> pointNames, String angle, String tier, String type, int points) {

    /** 展示用：单点写名字，多点用「、」连起来 */
    String pointLabel() {
        return String.join("、", pointNames);
    }

    /** 是否卷末串联大题（跨多个点） */
    boolean isLink() {
        return pointNames.size() > 1;
    }
}
