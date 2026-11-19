package com.review.vt.pojo.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 练习笔记：一条 = 一个场景下针对某题（可为空）随手记的一段话。
 *
 * 结构化条目而不是一篇长文——答题时/看批改时的快记天然带上下文（哪道题、什么场景），
 * 混在一篇文档里会搅成一锅粥。每条挂 questionId（手动新增可为空），前端按它联查题面。
 *
 * 字段与 data/store/notes.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("note")
@Getter
@Setter
public class Note {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属练习
    private Long practiceId;

    // 所属题目
    private Long questionId;

    // 场景
    private String scene;

    // 内容
    private String text;

    // 创建时间
    private long createdAt;

    // 更新时间
    private long updatedAt;

}
