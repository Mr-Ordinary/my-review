package com.review.vt.repo.db;

import com.review.vt.auth.AuthContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JDBC 存储基类：多用户隔离的公共逻辑。
 *
 * <ul>
 *   <li>{@link #userId()} 从 AuthContext 取当前用户——请求线程由 AuthFilter 设置，
 *       后台任务线程由 submitTask 包装器设置；迁移器不经仓库，直接用 JdbcTemplate。</li>
 *   <li>{@link #uidOf(String)} 实体上没带 userId 时回落到当前用户（服务层建实体不用改）。</li>
 *   <li>{@link #deleteByIds(String, List)} 批量删除永远带 user_id 兜底，防跨用户误删。</li>
 * </ul>
 */
public abstract class AbstractJdbcRepo {

    protected final JdbcTemplate jdbc;

    protected AbstractJdbcRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 当前登录用户 id；未登录抛 UnauthorizedException（正常请求过不了 AuthFilter 到不了这） */
    protected String userId() {
        return AuthContext.require().userId();
    }

    /** 实体 userId 优先，缺省回落当前登录用户 */
    protected String uidOf(String entityUserId) {
        return entityUserId != null ? entityUserId : userId();
    }

    /** 按 id 批量删除，永远带 user_id 过滤 */
    protected int deleteByIds(String table, List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        String ph = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(userId());
        args.addAll(ids);
        return jdbc.update("DELETE FROM " + table + " WHERE user_id = ? AND id IN (" + ph + ")", args.toArray());
    }
}
