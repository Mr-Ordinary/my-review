package com.review.vt.pojo.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * API 配置共享：所有者把自己的配置共享给目标用户使用。
 * 目标用户可在配置列表里选用（标注来源），但不能编辑/删除；所有者可随时取消。
 */
@TableName("llm_share")
@Getter
@Setter
public class ApiShare {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;
    // 共享配置
    private Long configId;
    // 所有者
    private Long ownerUserId;
    // 被共享用户
    private Long targetUserId;
    // 共享时间
    private long createdAt;





}
