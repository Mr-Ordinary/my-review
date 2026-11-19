package com.review.vt.pojo.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 考前复习提纲。
 * @param status none（还没生成）/ generating / ready / failed
 * @param content {"points":[{name,category,priority,summary,interview,principle,params[],pitfalls[],selfCheck{q,a}}]}
 */
public record ReviewDocDto(String id, String practiceId, String status,
                           JsonNode content, JsonNode progress,
                           int planned, int done, long updatedAt) {}
