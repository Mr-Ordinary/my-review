package com.review.vt.pojo.param;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PasswordParam(@Schema(description = "原密码") String oldPassword, @Schema(description = "新密码") String newPassword) {}
