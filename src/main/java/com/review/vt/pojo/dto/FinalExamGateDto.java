package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record FinalExamGateDto(boolean unlocked, int papersDone, int totalPoints,
                               int coveredPoints, double coveragePercent,
                               double avgMastery, String hint) {}
