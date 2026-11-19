package com.review.vt.pojo.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 一道题的追问消息（聊天记录）。
 *
 * <p>**按 questionId 隔离**：每道题一段独立会话，问 A 题不会带上 B 题的上下文。
 * user 与 assistant 两种角色，按 createdAt 升序展示；同毫秒时保持插入顺序。
 *
 * 字段与 data/store/followups.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("follow_up")
@Getter
@Setter
public class FollowUp {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属练习
    private Long practiceId;

    // 所属题目
    private Long questionId;

    // 角色
    private String role;

    // 内容
    private String content;

    // 创建时间
    private long createdAt;

}
