package com.review.vt.pojo.entity;
import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;


/**
 * 一道题（属于某张卷，卷内按 sequence 递增）。
 * 一张卷是一张 100 分的真考卷：题目分「基础 / 中档 / 大题」三档，每题有分值，卷内分值合计 100。
 * 参考答案在出题时就随题面一起返回，答题过程不需要再调 AI。
 * 字段与 data/store/questions.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("question")
@Getter
@Setter
public class Question {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属练习
    private Long practiceId;

    // 所属卷
    private Long paperId;

    // 题号
    private int sequence;

    // 题面
    private String prompt;

    // 参考答案
    private String referenceAnswer;

    // 考察知识点
    private String knowledgePointsJson;

    // 本题要求
    private String demandsJson;

    // 挂载知识点
    private String assignedPointsJson;

    // 出题角度
    private String angle;

    // 难度档
    private String tier;

    // 题型
    private String questionType;

    // 判分类型
    private String qtype;

    // 选项
    private String optionsJson;

    // 标准答案
    private String answerJson;

    // 分值
    private int points;

    // 创建时间
    private long createdAt;

    // 看答案时间
    private Long revealedAt;

}
