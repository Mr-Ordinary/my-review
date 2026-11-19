package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record FollowUpDto(String id, String role, String content, long createdAt) {}
