package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.Mistake;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/** mistake 表 Mapper（SQL 见 resources/mapper/MistakeMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface MistakeMapper extends BaseMapper<Mistake> {

    Mistake selectByIdScoped(Long id, Long userId);

    default Optional<Mistake> findById(Long id) {
        return Optional.ofNullable(selectByIdScoped(id, AuthContext.require().userId()));
    }

    default Mistake save(Mistake mistake) {
        if (mistake.getUserId() == null) mistake.setUserId(AuthContext.require().userId());
        if (mistake.getId() == null) {
            insert(mistake);
        } else {
            updateById(mistake);
        }
        return mistake;
    }



    List<Mistake> selectByPracticeDesc(Long userId, Long practiceId);

    default List<Mistake> findByPracticeIdOrderByCreatedAtDesc(Long practiceId) {
        return selectByPracticeDesc(AuthContext.require().userId(), practiceId);
    }

    /** 阶段详情链：批量取未解决错题的 practice_id（每行一个，Java 端计数）。空列表短路防 IN () */
    List<Long> selectOpenByPracticeIds(Long userId, List<Long> ids);

    default List<Long> findOpenByPracticeIds(List<Long> practiceIds) {
        if (practiceIds == null || practiceIds.isEmpty()) return List.of();
        return selectOpenByPracticeIds(AuthContext.require().userId(), practiceIds);
    }

    /** 批量插入（整卷批改完一次入错题本，严禁循环插入）。调用前须设每行 userId */
    int insertByList(List<Mistake> rows);

    void deleteByIds(Long userId, List<Long> ids);

    default void deleteAll(List<Mistake> mistakes) {
        java.util.List<Long> ids = mistakes.stream().map(Mistake::getId).toList();
        if (ids.isEmpty()) return;   // 空列表：IN () 会语法错（沿用旧 deleteByIds 守卫）
        deleteByIds(AuthContext.require().userId(), ids);
    }
}
