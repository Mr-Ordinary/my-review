package com.review.vt.repo;

import com.review.vt.domain.User;

import java.util.Optional;

/** 用户表存取接口（实现见 repo/db/JdbcUserRepository）。 */
public interface UserRepository {

    Optional<User> findById(Long id);

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    /** 新增（主键由数据库自增生成，返回带 id 的对象） */
    User insert(User user);

    /** 按 id 更新资料/密码/密钥/LLM 配置。不含求职总结及其任务状态，那部分走下面的专用方法 */
    void update(User user);

    /**
     * 原子抢占求职总结任务：当前不在生成中（或上次生成已超过 staleBefore 判为卡死）才成功。
     * 返回 false 表示已有任务在跑，调用方应拒绝重复提交。
     */
    boolean tryStartSummary(Long userId, long now, long staleBefore);

    /** 任务成功：写入结果与时间，状态置 idle */
    void finishSummary(Long userId, String summaryJson, long at);

    /** 任务失败：状态置 failed 并记录原因，旧总结保留不动 */
    void failSummary(Long userId, String error);

    /** 服务启动时回收上次进程退出时遗留的 generating（线程已不存在），返回回收条数 */
    int failInterruptedSummaries(String error);

    long count();


}
