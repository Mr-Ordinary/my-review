package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.KnowledgePoint;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/** knowledge_point 表 Mapper（SQL 见 resources/mapper/KnowledgePointMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface KnowledgePointMapper extends BaseMapper<KnowledgePoint> {

    KnowledgePoint selectByIdScoped(Long id, Long userId);

    default Optional<KnowledgePoint> findById(Long id) {
        return Optional.ofNullable(selectByIdScoped(id, AuthContext.require().userId()));
    }

    default KnowledgePoint save(KnowledgePoint point) {
        if (point.getUserId() == null) point.setUserId(AuthContext.require().userId());
        if (point.getId() == null) {
            insert(point);
        } else {
            updateById(point);
        }
        return point;
    }



    List<KnowledgePoint> selectByPracticeAsc(Long userId, Long practiceId);

    default List<KnowledgePoint> findByPracticeIdOrderByCreatedAtAsc(Long practiceId) {
        return selectByPracticeAsc(AuthContext.require().userId(), practiceId);
    }

    /** 启动热备专用：全用户、按主键键集分页（WHERE id > ? ORDER BY id LIMIT n），请求路径禁用 */
    List<KnowledgePoint> selectChunkForWarmup(Long afterId, int limit);

    void deleteByIds(Long userId, List<Long> ids);

    default void deleteAll(List<KnowledgePoint> points) {
        java.util.List<Long> ids = points.stream().map(KnowledgePoint::getId).toList();
        if (ids.isEmpty()) return;   // 空列表：IN () 会语法错（沿用旧 deleteByIds 守卫）
        deleteByIds(AuthContext.require().userId(), ids);
    }
}
