package com.review.vt.pojo.dto;

import lombok.Data;

/**
 * GROUP BY 计数行：某个练习下的行数（卷数/题数共用）。
 * 对应 SQL：SELECT practice_id, COUNT(*) AS cnt FROM xxx WHERE ... GROUP BY practice_id
 */
@Data
public class PracticeCountDto {
    private Long practiceId;
    private long cnt;
}
