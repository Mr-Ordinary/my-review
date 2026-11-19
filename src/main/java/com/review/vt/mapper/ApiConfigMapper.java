package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.review.vt.pojo.entity.ApiConfig;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/**
 * llm_config 表 Mapper。共享场景要跨用户读配置，方法一律显式带 userId，不走 AuthContext 隐式过滤。
 * 单行走 MyBatis-Plus 内置 insert/updateById/selectById/deleteById；复杂 SQL 见 XML。
 */
@Mapper
public interface ApiConfigMapper extends BaseMapper<ApiConfig> {

    default Optional<ApiConfig> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    List<ApiConfig> selectByUserId(Long userId);

    default List<ApiConfig> findByUserId(Long userId) {
        return selectByUserId(userId);
    }

    long countByUser(Long userId);

    default long countByUserId(Long userId) {
        return countByUser(userId);
    }
}
