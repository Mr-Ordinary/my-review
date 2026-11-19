package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.Practice;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/** practice 表 Mapper（SQL 见 resources/mapper/PracticeMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface PracticeMapper extends BaseMapper<Practice> {

    Practice selectByIdScoped(Long id, Long userId);

    default Optional<Practice> findById(Long id) {
        return Optional.ofNullable(selectByIdScoped(id, AuthContext.require().userId()));
    }

    default Practice save(Practice practice) {
        if (practice.getUserId() == null) practice.setUserId(AuthContext.require().userId());
        if (practice.getId() == null) {
            insert(practice);
        } else {
            updateById(practice);
        }
        return practice;
    }



    List<Practice> selectByStageAsc(Long userId, Long stageId);

    default List<Practice> findByStageIdOrderByCreatedAtAsc(Long stageId) {
        return selectByStageAsc(AuthContext.require().userId(), stageId);
    }

    List<Practice> selectByStageDesc(Long userId, Long stageId);

    default List<Practice> findByStageIdOrderByCreatedAtDesc(Long stageId) {
        return selectByStageDesc(AuthContext.require().userId(), stageId);
    }

    /** 批量取多个阶段的练习（一次 IN 查询，stage_id, created_at 序）：统计防逐阶段 N+1 */
    List<Practice> selectByStageIds(Long userId, List<Long> ids);

    default List<Practice> findByStageIds(List<Long> stageIds) {
        if (stageIds == null || stageIds.isEmpty()) return List.of();
        return selectByStageIds(AuthContext.require().userId(), stageIds);
    }

    /** 启动热备专用：全用户、按主键键集分页（WHERE id > ? ORDER BY id LIMIT n），请求路径禁用 */
    List<Practice> selectChunkForWarmup(Long afterId, int limit);

    void deleteByIds(Long userId, List<Long> ids);

    default void deleteAll(List<Practice> practices) {
        java.util.List<Long> ids = practices.stream().map(Practice::getId).toList();
        if (ids.isEmpty()) return;   // 空列表：IN () 会语法错（沿用旧 deleteByIds 守卫）
        deleteByIds(AuthContext.require().userId(), ids);
    }
}
