package com.review.vt.pojo.param;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CreateReviewParam(@Schema(description = "岗位难度 junior/mid/senior") String level, @Schema(description = "复习强度 quick/standard/deep") String intensity) {}
