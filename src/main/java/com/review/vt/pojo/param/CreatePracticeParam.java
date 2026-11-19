package com.review.vt.pojo.param;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CreatePracticeParam(@Schema(description = "主题") String topic, @Schema(description = "范围模式 topic/range") String scopeMode, @Schema(description = "范围描述") String scopeText,
                                    @Schema(description = "岗位难度 junior/mid/senior") String level, @Schema(description = "复习强度 quick/standard/deep") String intensity, @Schema(description = "一卷题量 6/10/15") Integer paperSize) {}
