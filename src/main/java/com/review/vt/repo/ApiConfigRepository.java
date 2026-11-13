package com.review.vt.repo;

import com.review.vt.domain.ApiConfig;

import java.util.List;
import java.util.Optional;

/**
 * llm_config 表存取接口（实现见 repo/db/JdbcApiConfigRepository）。
 * 共享场景要跨用户读配置，方法一律显式带 userId，不走 AuthContext 隐式过滤。
 */
public interface ApiConfigRepository {

    Optional<ApiConfig> findById(Long id);

    /** 某用户的全部配置（自己的） */
    List<ApiConfig> findByUserId(Long userId);

    long countByUserId(Long userId);

    /** 新增（主键自增回填） */
    ApiConfig insert(ApiConfig config);

    void update(ApiConfig config);

    void deleteById(Long id);
}
