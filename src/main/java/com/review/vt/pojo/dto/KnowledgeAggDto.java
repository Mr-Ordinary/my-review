package com.review.vt.pojo.dto;

/**
 * 知识点聚合行（首页 KPI 用）：一条 SQL 出全用户的覆盖/掌握/总数。
 * covered = times_seen &gt; 0；mastered = status='已掌握'；weak = covered - mastered。
 */
public record KnowledgeAggDto(long total, long covered, long mastered) {}
