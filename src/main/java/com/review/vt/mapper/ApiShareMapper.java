package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.review.vt.pojo.entity.ApiShare;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * llm_share 表 Mapper。共享关系跨用户，不走 AuthContext 过滤。
 * 单行走 MyBatis-Plus 内置 insert/deleteById；复杂 SQL 见 XML。
 */
@Mapper
public interface ApiShareMapper extends BaseMapper<ApiShare> {

    ApiShare selectByConfigTarget(Long configId, Long targetUserId);

    default Optional<ApiShare> findByConfigIdAndTargetUserId(Long configId, Long targetUserId) {
        return Optional.ofNullable(selectByConfigTarget(configId, targetUserId));
    }

    List<ApiShare> selectByConfigId(Long configId);

    default List<ApiShare> findByConfigId(Long configId) {
        return selectByConfigId(configId);
    }

    List<ApiShare> selectByTargetUserId(Long targetUserId);

    default List<ApiShare> findByTargetUserId(Long targetUserId) {
        return selectByTargetUserId(targetUserId);
    }

    void deleteByConfigId(Long configId);
}
