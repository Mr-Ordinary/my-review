package com.review.vt.pojo.entity;
import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;


/**
 * 一次练习 = 一个会话（一个范围练到底，可反复出卷）。
 * 字段与 data/store/practices.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("practice")
@Getter
@Setter
public class Practice {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属阶段
    private Long stageId;

    // 模式
    private String mode;

    // 主题
    private String topic;

    // 范围模式
    private String scopeMode;

    // 范围描述
    private String scopeText;

    // 岗位难度
    private String level;

    // 复习强度
    private String intensity;

    // 一卷题量
    private int paperSize;

    // 是否全复习
    private boolean review;

    // 状态
    private String status;

    // 创建时间
    private long createdAt;

    // 更新时间
    private long updatedAt;

    // 完成时间
    private Long completedAt;

    // 长期记忆
    private String memoryJson;

    // 记事本
    private String notesText;

    // 记事本更新时间
    private Long notesUpdatedAt;

    // 会话摘要
    private String digestJson;

}
