package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record CategoryScoreDto(String category, int asked, int points,
                               double earned, double rate) {}
