package com.review.vt.repo;

import com.review.vt.domain.ApiShare;

import java.util.List;
import java.util.Optional;

/**
 * llm_share 表存取接口（实现见 repo/db/JdbcApiShareRepository）。
 * 共享关系跨用户，方法一律显式带 userId/configId，不走 AuthContext 隐式过滤。
 */
public interface ApiShareRepository {

    Optional<ApiShare> findByConfigIdAndTargetUserId(Long configId, Long targetUserId);

    /** 某配置的全部共享记录（配置所有者看自己的共享给谁了） */
    List<ApiShare> findByConfigId(Long configId);

    /** 某用户收到的全部共享（配置选择列表用） */
    List<ApiShare> findByTargetUserId(Long targetUserId);

    ApiShare insert(ApiShare share);

    void deleteById(Long id);

    /** 删配置时级联清掉共享 */
    void deleteByConfigId(Long configId);
}
