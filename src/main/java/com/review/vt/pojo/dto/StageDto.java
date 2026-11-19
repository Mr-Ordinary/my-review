package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record StageDto(String id, String name, String status,
                       long createdAt, long updatedAt, Long completedAt,
                       JsonNode summary, Stats stats) {}
