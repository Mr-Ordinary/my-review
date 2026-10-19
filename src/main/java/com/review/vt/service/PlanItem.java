package com.review.vt.service;

/**
 * 出卷计划里的一道题：考察角度 + 难度档 + 题型 + 分值。
 * 一张卷的 points 合计 100，由 {@link Scoring#normalizePoints} 校正。
 */
record PlanItem(String angle, String tier, String type, int points) {}
