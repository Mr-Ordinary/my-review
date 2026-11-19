package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.review.vt.pojo.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * users 表 Mapper。用户数据按 username 唯一，自身无租户概念（不带 AuthContext 过滤）。
 * api_key_cipher / api_key_nonce 只在此处与加密服务之间流转，绝不出后端。
 * 单行走 MyBatis-Plus 内置 insert/updateById/selectById；复杂 SQL 见 resources/mapper/UserMapper.xml。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    default Optional<User> findById(Long id) {
        return Optional.ofNullable(selectById(id));
    }

    User selectByUsername(String username);

    default Optional<User> findByUsername(String username) {
        return Optional.ofNullable(selectByUsername(username));
    }

    int countByUsername(String username);

    default boolean existsByUsername(String username) {
        return countByUsername(username) > 0;
    }

    /**
     * 按 id 更新资料/密码/密钥/LLM 配置（内置 updateById，全列写入含 null=清空）。
     * 不含求职总结及其任务状态（字段标了 NEVER，且那部分走下面的专用原子方法，防并发覆盖）。
     */
    default void update(User user) {
        updateById(user);
    }

    /** 原子抢占求职总结任务：返回 false 表示已有任务在跑 */
    boolean tryStartSummary(@Param("userId") Long userId, @Param("now") long now,
                            @Param("staleBefore") long staleBefore);

    /** 任务成功：写入结果与时间，状态置 idle */
    void finishSummary(@Param("userId") Long userId, @Param("summaryJson") String summaryJson,
                       @Param("at") long at);

    /** 任务失败：状态置 failed 并记录原因，旧总结保留不动 */
    void failSummary(@Param("userId") Long userId, @Param("error") String error);

    /** 回收中断的 generating 任务，返回受影响的用户 id（缓存要按用户两步同步） */
    List<Long> failInterruptedSummaries(@Param("error") String error);

    /** 当前启用的 LLM 配置 id（null = 未启用） */
    Long selectActiveLlmConfigId(@Param("userId") Long userId);

    default Long activeLlmConfigId(Long userId) {
        return selectActiveLlmConfigId(userId);
    }

    void updateActiveLlmConfig(@Param("userId") Long userId, @Param("configId") Long configId);

    /** 按用户名/昵称模糊找用户（API 共享的选人搜索用） */
    List<User> searchByKeyword(@Param("query") String query, @Param("limit") int limit);

    default List<User> searchByUsernameOrNickname(String query, int limit) {
        return searchByKeyword(query, limit);
    }

    long countAll();

    default long count() {
        return countAll();
    }
}
