package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record Stats(long practiceCount, long paperCount, long questionCount,
                    long attemptCount, long evaluatedCount,
                    Double averageScore, Double lastScore,
                    java.util.List<Double> recentScores,
                    long drillCount, Double drillAvgScore, Double drillLastScore) {}
