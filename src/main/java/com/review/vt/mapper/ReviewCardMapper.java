package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.ReviewCard;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/** review_card 表 Mapper（SQL 见 resources/mapper/ReviewCardMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface ReviewCardMapper extends BaseMapper<ReviewCard> {

    List<ReviewCard> selectByDocSeqAsc(Long userId, Long reviewDocId);

    default List<ReviewCard> findByReviewDocIdOrderBySequenceAsc(Long reviewDocId) {
        return selectByDocSeqAsc(AuthContext.require().userId(), reviewDocId);
    }

    ReviewCard selectByDocPointName(Long userId, Long reviewDocId, String pointName);

    default Optional<ReviewCard> findByReviewDocIdAndPointName(Long reviewDocId, String pointName) {
        return Optional.ofNullable(selectByDocPointName(AuthContext.require().userId(), reviewDocId, pointName));
    }

    /** 同一份提纲同一个点只允许一张卡：无 id 时先按唯一键找已有行，找到就转成更新 */
    ReviewCard selectByIdScoped(Long id, Long userId);

    default ReviewCard save(ReviewCard card) {
        if (card.getUserId() == null) card.setUserId(AuthContext.require().userId());
        if (card.getId() == null) {
            findByReviewDocIdAndPointName(card.getReviewDocId(), card.getPointName())
                    .ifPresent(existing -> card.setId(existing.getId()));
        }
        if (card.getId() == null) {
            insert(card);
        } else {
            updateById(card);
        }
        return card;
    }



    void deleteByDoc(Long userId, Long reviewDocId);

    default void deleteByReviewDocId(Long reviewDocId) {
        deleteByDoc(AuthContext.require().userId(), reviewDocId);
    }

    /** 启动热备专用：全用户、按主键键集分页（WHERE id > ? ORDER BY id LIMIT n），请求路径禁用 */
    List<ReviewCard> selectChunkForWarmup(Long afterId, int limit);
}
