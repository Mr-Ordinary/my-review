package com.review.vt.pojo.param;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一套 API 配置的写入请求。clearKey=true 显式清 key；否则 apiKey 留空/含 '*' 都不动密文 */
public record ApiConfigParam(@Schema(description = "名称") String name, @Schema(description = "API Base URL") String apiBase, @Schema(description = "API Key（留空或含 * 不改动）") String apiKey, @Schema(description = "模型名") String model,
                               @Schema(description = "接口协议 anthropic/openai") String apiFormat, @Schema(description = "温度 0-1") Double temperature, @Schema(description = "是否清除 Key") Boolean clearKey) {}
