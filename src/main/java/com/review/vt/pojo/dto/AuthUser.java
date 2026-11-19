package com.review.vt.pojo.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 返回给前端的用户信息（无任何密文字段） */
public record AuthUser(String id, String username, String nickname, String avatarExt,
                       String apiKeyLast4, boolean hasApiKey, boolean hasSummary, Long summaryAt) {}
