package com.review.vt.pojo.entity;

import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 考前复习提纲：一个练习一份，按知识点生成「必学 / 理解 / 选学」三层内容。
 *
 * <p>内容与考试共用同一份知识点清单（学考同源），**不受复习强度限制**——
 * 速通也能看到全部内容，只是前端按深度分层展示。
 *
 * 字段与 data/store/reviews.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("review_doc")
@Getter
@Setter
public class ReviewDoc {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属练习
    private Long practiceId;

    // 状态
    private String status;

    // 生成进度
    private String progressJson;

    // 创建时间
    private long createdAt;

    // 更新时间
    private long updatedAt;

}
