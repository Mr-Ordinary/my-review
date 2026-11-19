package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.MemoryPoint;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/** memory_point 表 Mapper（SQL 见 resources/mapper/MemoryPointMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface MemoryPointMapper extends BaseMapper<MemoryPoint> {

    MemoryPoint selectByIdScoped(Long id, Long userId);

    default Optional<MemoryPoint> findById(Long id) {
        return Optional.ofNullable(selectByIdScoped(id, AuthContext.require().userId()));
    }

    default MemoryPoint save(MemoryPoint point) {
        if (point.getUserId() == null) point.setUserId(AuthContext.require().userId());
        if (point.getId() == null) {
            insert(point);
        } else {
            updateById(point);
        }
        return point;
    }



    List<MemoryPoint> selectByPracticeDesc(Long userId, Long practiceId);

    default List<MemoryPoint> findByPracticeIdOrderByCreatedAtDesc(Long practiceId) {
        return selectByPracticeDesc(AuthContext.require().userId(), practiceId);
    }

    /** 批量插入（批改按块刷盘，严禁循环插入）。调用前须设每行 userId */
    int insertByList(List<MemoryPoint> rows);

    void deleteByIds(Long userId, List<Long> ids);

    default void deleteAll(List<MemoryPoint> points) {
        java.util.List<Long> ids = points.stream().map(MemoryPoint::getId).toList();
        if (ids.isEmpty()) return;   // 空列表：IN () 会语法错（沿用旧 deleteByIds 守卫）
        deleteByIds(AuthContext.require().userId(), ids);
    }
}
