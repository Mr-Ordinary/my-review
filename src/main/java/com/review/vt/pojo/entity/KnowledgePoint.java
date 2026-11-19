package com.review.vt.pojo.entity;
import lombok.Getter;
import lombok.Setter;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;


/**
 * 知识点：练习范围拆出来的一个个具体考点，出题与掌握度都挂在它上面。
 *
 * 关键设计（2026-10 改版）：
 * - **一个点 = 一个固定内容边界**，拆清单时就声明三档各考到哪为止
 *   （{@link #boundQuick}/{@link #boundStd}/{@link #boundDeep}），出题只能在对应档内出。
 *   不再有「timesSeen 越多挖越深」的自动加深——深度由用户选的**复习强度**决定，
 *   强化=换角度变种，不是往源码里钻。
 * - **重要度靠搜索取证**（{@link #priority} + {@link #evidenceJson}），
 *   不是 AI 空想。速通只出 high，标准出 high+medium，深挖才碰 low。
 * - **掌握度看变种题**：只有同一个点换个角度还答得不错才算掌握，避免蒙对一次就跳过。
 * - **来源两种**：范围拆解（scope）/ 用户作答里发散出来的（answer），后者必须 inScope 才收。
 *
 * 字段与 data/store/knowledge_points.json 行（及后续数据库表列）1:1 对应。
 */
@TableName("knowledge_point")
@Getter
@Setter
public class KnowledgePoint {

    // 主键
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID
    private Long userId;

    // 所属练习
    private Long practiceId;

    // 大类
    private String category;

    // 知识点名
    private String name;

    // 来源
    private String source;

    // 是否在范围
    private boolean inScope;

    // 重要度
    private String priority;

    // 证据
    private String evidenceJson;

    // 速通规格
    private String boundQuick;

    // 标准规格
    private String boundStd;

    // 深挖规格
    private String boundDeep;

    // 考过次数
    private int timesSeen;

    // 作答历史
    private String historyJson;

    // 出题角度
    private String anglesJson;

    // 掌握度
    private double mastery;

    // 状态
    private String status;

    // 创建时间
    private long createdAt;

    // 更新时间
    private long updatedAt;

}
