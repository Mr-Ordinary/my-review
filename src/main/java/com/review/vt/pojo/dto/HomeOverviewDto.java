package com.review.vt.pojo.dto;

import java.util.List;

/**
 * 工作台首页总览 VO（/api/home/overview）：KPI 数字 + 阶段卡 + 最近的卷。
 * 全部统计现算（统计永不缓存）；均分 = 已批改卷的卷面均分（Σ每题最近一次已批改实得分，0-100）。
 */
public record HomeOverviewDto(HomeKpisDto kpis, List<HomeStageDto> stages, List<HomePaperDto> recentPapers) {

    /** KPI 行：本周卷量（带上周对比）/ 均分 / 待巩固 / 掌握率 */
    public record HomeKpisDto(long papersThisWeek, long papersLastWeek,
                              Double averageScore, long gradedPaperCount,
                              long weakCount, long coveredCount, long masteredCount, long knowledgeTotal) {}

    /** 阶段卡：数量（练习/卷/题，含刷题）+ 该阶段已批改卷的卷面均分 */
    public record HomeStageDto(String id, String name,
                               long practiceCount, long paperCount, long questionCount,
                               Double averageScore, long updatedAt, boolean hasSummary) {}

    /** 「最近的卷」行：题量与档位构成来自题存根；得分仅已批改卷有 */
    public record HomePaperDto(String id, String practiceId, String topic,
                               int sequence, String compositionMode, String status,
                               int questionCount, int tierBase, int tierMid, int tierMajor,
                               Double score, long updatedAt) {}
}
