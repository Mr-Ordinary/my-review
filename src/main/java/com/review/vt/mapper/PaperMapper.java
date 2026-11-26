package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.Paper;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/** paper 表 Mapper（SQL 见 resources/mapper/PaperMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface PaperMapper extends BaseMapper<Paper> {

    Paper selectByIdScoped(Long id, Long userId);

    default Optional<Paper> findById(Long id) {
        return Optional.ofNullable(selectByIdScoped(id, AuthContext.require().userId()));
    }

    default Paper save(Paper paper) {
        if (paper.getUserId() == null) paper.setUserId(AuthContext.require().userId());
        if (paper.getId() == null) {
            insert(paper);
        } else {
            updateById(paper);
        }
        return paper;
    }



    List<Paper> selectByPracticeSeqAsc(Long userId, Long practiceId);

    default List<Paper> findByPracticeIdOrderBySequenceAsc(Long practiceId) {
        return selectByPracticeSeqAsc(AuthContext.require().userId(), practiceId);
    }

    /** 批量取多个练习的卷（一次 IN 查询，practice_id, sequence 序）：统计防逐练习 N+1 */
    List<Paper> selectByPracticeIds(Long userId, List<Long> ids);

    default List<Paper> findByPracticeIds(List<Long> practiceIds) {
        if (practiceIds == null || practiceIds.isEmpty()) return List.of();
        return selectByPracticeIds(AuthContext.require().userId(), practiceIds);
    }

    /** 阶段详情链：窄投影（只取 id/practice_id），数量即行数。部分列行禁止 save/updateById */
    List<Paper> selectRefsByPracticeIds(Long userId, List<Long> ids);

    default List<Paper> findRefsByPracticeIds(List<Long> practiceIds) {
        if (practiceIds == null || practiceIds.isEmpty()) return List.of();
        return selectRefsByPracticeIds(AuthContext.require().userId(), practiceIds);
    }

    /** 最新卷 id（非刷题卷里 id 最大者）：latest_paper:{practiceId} 缓存 miss 时的回源 */
    Long selectLatestIdByPractice(Long userId, Long practiceId);

    /** 启动热备专用：全用户、按主键键集分页（WHERE id > ? ORDER BY id LIMIT n），请求路径禁用 */
    List<Paper> selectChunkForWarmup(Long afterId, int limit);

    long countByPractice(Long userId, Long practiceId);

    default long countByPracticeId(Long practiceId) {
        return countByPractice(AuthContext.require().userId(), practiceId);
    }

    void deleteByIds(Long userId, List<Long> ids);

    default void deleteAll(List<Paper> papers) {
        java.util.List<Long> ids = papers.stream().map(Paper::getId).toList();
        if (ids.isEmpty()) return;   // 空列表：IN () 会语法错（沿用旧 deleteByIds 守卫）
        deleteByIds(AuthContext.require().userId(), ids);
    }
    /** 批量计数（GROUP BY practice_id）：一条 SQL 出多个练习的卷数，避免逐阶段 N+1。空列表短路防 IN () */
    List<com.review.vt.pojo.dto.PracticeCountDto> countGroupByPracticeIds(Long userId, List<Long> ids);

    /** 首页总览窄投影：全用户卷 meta（含刷题卷，计数口径含刷题）。部分列行禁止 save/updateById */
    List<Paper> selectMetaAllByUser(Long userId);

    /** 单练习卷 meta 窄投影（不含 plan/summary/progress 大字段）：起卷冲突检查、卷号编排、删除遍历用。部分列行禁止 save/updateById */
    List<Paper> selectMetaByPractice(Long userId, Long practiceId);

    default List<Paper> findMetaByPracticeId(Long practiceId) {
        return selectMetaByPractice(AuthContext.require().userId(), practiceId);
    }

    default List<com.review.vt.pojo.dto.PracticeCountDto> findCountGroupByPracticeIds(List<Long> practiceIds) {
        if (practiceIds == null || practiceIds.isEmpty()) return List.of();
        return countGroupByPracticeIds(com.review.vt.auth.AuthContext.require().userId(), practiceIds);
    }
}
