package com.review.vt.pojo.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 错题本条目：用户栽过的题，per-practice 一本（每个练习有自己的范围）。
 *
 * 关键设计：
 * - **一题一条**（practiceId + questionId 去重）——批改重入天然幂等，不会翻倍。
 * - **自动 + 手动**：交卷按规则自动收（低分/未作答/看过参考答案）；
 *   也可以手动加入「侥幸答对但想盯住」的题。手动行永不被自动收录覆盖或删除。
 * - status：open（待订正）/ resolved（已订正），用户自己维护。
 *
 * 字段与 data/store/mistakes.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("mistake")
@Getter
@Setter
public class Mistake {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属练习
    private Long practiceId;

    // 所属题目
    private Long questionId;

    // 来源
    private String source;

    // 原因
    private String reason;

    // 表现分
    private Double score;

    // 状态
    private String status;

    // 创建时间
    private long createdAt;

    // 更新时间
    private long updatedAt;

    // 订正时间
    private Long resolvedAt;

}
