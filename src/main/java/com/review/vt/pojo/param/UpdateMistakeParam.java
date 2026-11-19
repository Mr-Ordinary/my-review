package com.review.vt.pojo.param;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record UpdateMistakeParam(@Schema(description = "状态 open/resolved") String status) {}
