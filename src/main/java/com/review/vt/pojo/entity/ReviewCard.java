package com.review.vt.pojo.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 复习提纲中的**一张学习卡**（按知识点一行），替代原来整份 contentJson 大字段。
 *
 * <p>拆表原因：一份提纲约 30 个点、整块 JSON 约 30KB，生成时每 3 个点一批
 * 会整块重写 10 次（TOAST 写放大）。拆成一行一卡后每批只写 3 行（约 1.5KB）。
 *
 * <p>唯一键 (reviewDocId, pointName)，同一份提纲同一个点只有一张卡。
 */
@TableName("review_card")
@Getter
@Setter
public class ReviewCard {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属提纲
    private Long reviewDocId;

    // 知识点名
    private String pointName;

    // 大类
    private String category;

    // 优先级
    private String priority;

    // 卡片内容
    private String contentJson;

    // 顺序
    private int sequence;

    // 创建时间
    private long createdAt;

    // 更新时间
    private long updatedAt;

}
