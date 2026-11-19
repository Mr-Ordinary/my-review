package com.review.vt.pojo.param;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ExamMixParam(@Schema(description = "单选题数量") Integer single, @Schema(description = "多选题数量") Integer multi, @Schema(description = "填空题数量") Integer blank, @Schema(description = "简答题数量") Integer shortAnswer, @Schema(description = "代码题数量") Integer code) {

    public java.util.Map<String, Integer> toMap() {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        m.put("single", single == null ? 0 : single);
        m.put("multi", multi == null ? 0 : multi);
        m.put("blank", blank == null ? 0 : blank);
        m.put("shortAnswer", shortAnswer == null ? 0 : shortAnswer);
        m.put("code", code == null ? 0 : code);
        return m;
    }
}
