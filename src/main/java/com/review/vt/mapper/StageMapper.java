package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.Stage;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * stage 表 Mapper（SQL 见 resources/mapper/StageMapper.xml）。
 * 全部查询带 user_id（AuthContext），跨用户数据天然不可见。
 */
@Mapper
public interface StageMapper extends BaseMapper<Stage> {

    default Optional<Stage> findById(Long id) {
        return Optional.ofNullable(selectByIdScoped(id, AuthContext.require().userId()));
    }

    Stage selectByIdScoped(Long id, Long userId);

    default boolean existsById(Long id) {
        return countById(id, AuthContext.require().userId()) > 0;
    }

    int countById(Long id, Long userId);

    /** 新增/更新（id 空 = 新增并回填自增主键；更新带 user_id 兜底） */
    default Stage save(Stage stage) {
        if (stage.getUserId() == null) stage.setUserId(AuthContext.require().userId());
        if (stage.getId() == null) {
            insert(stage);
        } else {
            updateById(stage);
        }
        return stage;
    }



    default void delete(Stage stage) {
        deleteByIdScoped(stage.getId(), AuthContext.require().userId());
    }

    void deleteByIdScoped(Long id, Long userId);

    List<Stage> selectAllDesc(Long userId);

    default List<Stage> findAllByOrderByUpdatedAtDesc() {
        return selectAllDesc(AuthContext.require().userId());
    }

    /** 启动热备专用：全用户、按主键键集分页（WHERE id > ? ORDER BY id LIMIT n），请求路径禁用 */
    List<Stage> selectChunkForWarmup(Long afterId, int limit);

    List<Long> selectIdsByUserId(Long userId);

    List<Stage> selectByIds(List<Long> ids);
}
