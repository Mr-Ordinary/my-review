package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record NoteDto(String id, String practiceId, String questionId, String scene,
                      String text, long createdAt, long updatedAt) {}
