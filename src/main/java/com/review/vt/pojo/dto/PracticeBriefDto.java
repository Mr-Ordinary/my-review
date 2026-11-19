package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 练习简报 VO（答题页 init）：练习 meta + 全部卷（下拉选项）+ 最新卷 id。meta 部分走 practice:{id} 缓存。 */
public record PracticeBriefDto(String id, String stageId, String mode, String topic,
                               String scopeMode, String scopeText, String level, String intensity,
                               boolean review, String status,
                               long createdAt, long updatedAt, Long completedAt,
                               JsonNode memory, JsonNode digest,
                               String notesText, Long notesUpdatedAt,
                               String latestPaperId,
                               List<PaperListItemDto> papers) {}
