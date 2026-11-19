package com.review.vt.pojo.entity;
import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;


/**
 * 一张卷：AI 一次出的一批题（15-30 道，满分 100，前易后难）。
 * 出题与批改都做成后台任务，progressJson 记进度，中断后可从断点续跑。
 * 字段与 data/store/papers.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("paper")
@Getter
@Setter
public class Paper {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属练习
    private Long practiceId;

    // 卷序号
    private int sequence;

    // 卷类型
    private String kind;

    // 重点知识点
    private String focusPointsJson;

    // 题型配比
    private String examMixJson;

    // 组卷模式
    private String compositionMode;

    // 状态
    private String status;

    // 创建时间
    private long createdAt;

    // 交卷时间
    private Long submittedAt;

    // 卷总结
    private String summaryJson;

    // 任务进度
    private String progressJson;

    // 出题计划
    private String planJson;

    // 满分
    private int maxScore;

}
