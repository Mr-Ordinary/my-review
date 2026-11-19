package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.FollowUp;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** follow_up 表 Mapper（SQL 见 resources/mapper/FollowUpMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface FollowUpMapper extends BaseMapper<FollowUp> {

    default FollowUp save(FollowUp followUp) {
        if (followUp.getUserId() == null) followUp.setUserId(AuthContext.require().userId());
        if (followUp.getId() == null) {
            insert(followUp);
        } else {
            updateById(followUp);
        }
        return followUp;
    }



    List<FollowUp> selectByQuestionAsc(Long userId, Long questionId);

    default List<FollowUp> findByQuestionIdOrderByCreatedAtAsc(Long questionId) {
        return selectByQuestionAsc(AuthContext.require().userId(), questionId);
    }

    List<FollowUp> selectByPractice(Long userId, Long practiceId);

    default List<FollowUp> findByPracticeId(Long practiceId) {
        return selectByPractice(AuthContext.require().userId(), practiceId);
    }

    long countByQuestion(Long userId, Long questionId);

    default long countByQuestionId(Long questionId) {
        return countByQuestion(AuthContext.require().userId(), questionId);
    }

    void deleteByIds(Long userId, List<Long> ids);

    default void deleteAll(List<FollowUp> followUps) {
        java.util.List<Long> ids = followUps.stream().map(FollowUp::getId).toList();
        if (ids.isEmpty()) return;   // 空列表：IN () 会语法错（沿用旧 deleteByIds 守卫）
        deleteByIds(AuthContext.require().userId(), ids);
    }
}
