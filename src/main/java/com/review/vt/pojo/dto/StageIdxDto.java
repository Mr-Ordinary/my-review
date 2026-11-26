package com.review.vt.pojo.dto;

import lombok.Data;

@Data
public class StageIdxDto {
    private Long id;
    private String name;
    /** active=进行中 / completed=已归档：新建练习弹窗只列进行中的阶段 */
    private String status;
    private long practiceCount;
    private long paperCount;
    private long questionCount;
    private long updatedAt;
    private boolean hasSummary;
}
