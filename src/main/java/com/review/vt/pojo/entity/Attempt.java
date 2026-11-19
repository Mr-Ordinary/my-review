package com.review.vt.pojo.entity;
import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;


/**
 * 一次作答（追加保存，不覆盖历史）。
 * 作答过程不调 AI；评价在「交卷」时由 AI 对整卷批改后写回这里。
 * score 是 0-10 的表现分（AI 直接给，不用换算），points 是换算后的实得分（0 到 该题满分）。
 * 字段与 data/store/attempts.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("attempt")
@Getter
@Setter
public class Attempt {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属题目
    private Long questionId;

    // 作答次数
    private int sequence;

    // 作答
    private String answer;

    // 作答时间
    private long submittedAt;

    // 表现分
    private Double score;

    // 实得分
    private Double points;

    // 点评
    private String feedback;

    // 维度分
    private String dimensionsJson;

    // 批改时间
    private Long evaluatedAt;

}
