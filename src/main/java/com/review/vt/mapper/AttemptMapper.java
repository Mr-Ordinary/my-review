package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.Attempt;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** attempt 表 Mapper（SQL 见 resources/mapper/AttemptMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface AttemptMapper extends BaseMapper<Attempt> {

    Attempt selectByIdScoped(Long id, Long userId);

    default Attempt save(Attempt attempt) {
        if (attempt.getUserId() == null) attempt.setUserId(AuthContext.require().userId());
        if (attempt.getId() == null) {
            insert(attempt);
        } else {
            updateById(attempt);
        }
        return attempt;
    }



    List<Attempt> selectByQuestionSeqAsc(Long userId, Long questionId);

    default List<Attempt> findByQuestionIdOrderBySequenceAsc(Long questionId) {
        return selectByQuestionSeqAsc(AuthContext.require().userId(), questionId);
    }

    /** 批量取多道题的作答（一次 IN 查询）：统计/DTO 构建防逐题 N+1 */
    List<Attempt> selectByQuestionIds(Long userId, List<Long> ids);

    default List<Attempt> findByQuestionIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return selectByQuestionIds(AuthContext.require().userId(), ids);
    }

    /** 阶段详情得分链：窄投影只查得分（question_id/points/evaluated_at）。部分列行禁止 save/updateById */
    List<Attempt> selectScoresByQuestionIds(Long userId, List<Long> ids);

    default List<Attempt> findScoresByQuestionIds(List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) return List.of();
        return selectScoresByQuestionIds(AuthContext.require().userId(), questionIds);
    }

    /** 批量刷批改结果（score/points/feedback/dimensions/evaluated_at）：一条 UPDATE ... FROM (VALUES ...)，严禁逐行 update */
    int updateEvaluatedByList(Long userId, List<Attempt> rows);

    void deleteByIds(Long userId, List<Long> ids);

    default void deleteAll(List<Attempt> attempts) {
        java.util.List<Long> ids = attempts.stream().map(Attempt::getId).toList();
        if (ids.isEmpty()) return;   // 空列表：IN () 会语法错（沿用旧 deleteByIds 守卫）
        deleteByIds(AuthContext.require().userId(), ids);
    }
}
