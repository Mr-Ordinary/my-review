package com.review.vt.pojo.entity;
import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;


/**
 * 记忆点：批改时 AI 给的「能直接背诵的一句话」，挂在知识点名下。
 *
 * 以前它只是拼进点评文本里、看完就散——一个复习系统把最该攒的东西扔了。
 * 现在结构化存下来，复习某个知识点时能直接看到「这个点该背什么」。
 *
 * 一条记忆点来自一道题，可能归属多个知识点（综合题/串联题），
 * 所以 pointNamesJson 是数组而不是单值。
 *
 * 字段与 data/store/memory_points.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("memory_point")
@Getter
@Setter
public class MemoryPoint {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属练习
    private Long practiceId;

    // 所属题目
    private Long questionId;

    // 知识点名
    private String pointNamesJson;

    // 记忆内容
    private String text;

    // 创建时间
    private long createdAt;

}
