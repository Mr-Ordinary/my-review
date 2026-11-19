package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record CoverageDto(int totalPoints, int coveredPoints,
                          int totalCategories, int coveredCategories) {}
