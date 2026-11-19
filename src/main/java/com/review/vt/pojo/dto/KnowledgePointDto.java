package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public record KnowledgePointDto(String id, String category, String name, String source,
                               int timesSeen, double mastery, String status,
                               java.util.List<Double> history,
                               java.util.List<String> angles,
                               String priority,
                               String boundQuick, String boundStd, String boundDeep) {}
