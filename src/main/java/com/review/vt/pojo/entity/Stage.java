package com.review.vt.pojo.entity;
import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;


/**
 * 阶段：只是用来区分空间的名字（分组），本身不承载业务逻辑。
 *
 * 阶段里的东西分两类，别混：
 * - **实时统计**（卷数/题数/平均分）：每次查询现算，不落库
 * - **阶段总结**（summaryJson）：AI 给的反馈，交卷后自动重新生成；「全复习」就是基于它做的
 *
 * 字段与 data/store/stages.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("stage")
@Getter
@Setter
public class Stage {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 名称
    private String name;

    // 状态
    private String status;

    // 创建时间
    private long createdAt;

    // 更新时间
    private long updatedAt;

    // 完成时间
    private Long completedAt;

    // 阶段总结
    private String summaryJson;

}
