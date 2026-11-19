package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record PracticeDetailDto(String id, String stageId, String mode, String topic,
                                String scopeMode, String scopeText, String level, String intensity,
                                boolean review, String status,
                                long createdAt, long updatedAt, Long completedAt,
                                JsonNode memory, JsonNode digest,
                                Stats stats, String notesText, Long notesUpdatedAt,
                                List<MistakeDto> mistakes, List<PaperDto> papers) {}
