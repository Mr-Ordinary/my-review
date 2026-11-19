package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.ReviewDoc;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/** review_doc 表 Mapper（SQL 见 resources/mapper/ReviewDocMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface ReviewDocMapper extends BaseMapper<ReviewDoc> {

    ReviewDoc selectByIdScoped(Long id, Long userId);

    default Optional<ReviewDoc> findById(Long id) {
        return Optional.ofNullable(selectByIdScoped(id, AuthContext.require().userId()));
    }

    ReviewDoc selectByPracticeScoped(Long userId, Long practiceId);

    default Optional<ReviewDoc> findByPracticeId(Long practiceId) {
        return Optional.ofNullable(selectByPracticeScoped(AuthContext.require().userId(), practiceId));
    }

    default ReviewDoc save(ReviewDoc doc) {
        if (doc.getUserId() == null) doc.setUserId(AuthContext.require().userId());
        if (doc.getId() == null) {
            insert(doc);
        } else {
            updateById(doc);
        }
        return doc;
    }



    void deleteByIds(Long userId, List<Long> ids);

    default void deleteAll(List<ReviewDoc> docs) {
        java.util.List<Long> ids = docs.stream().map(ReviewDoc::getId).toList();
        if (ids.isEmpty()) return;   // 空列表：IN () 会语法错（沿用旧 deleteByIds 守卫）
        deleteByIds(AuthContext.require().userId(), ids);
    }

    /** 启动热备专用：全用户、按主键键集分页（WHERE id > ? ORDER BY id LIMIT n），请求路径禁用 */
    List<ReviewDoc> selectChunkForWarmup(Long afterId, int limit);
}
