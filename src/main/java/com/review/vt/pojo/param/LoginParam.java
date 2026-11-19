package com.review.vt.pojo.param;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record LoginParam(@Schema(description = "用户名") String username, @Schema(description = "密码") String password) {}
