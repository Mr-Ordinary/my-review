package com.review.vt.repo;

import com.review.vt.domain.ReviewCard;

import java.util.List;
import java.util.Optional;

/** 复习提纲学习卡存储接口（按知识点一行）。 */
public interface ReviewCardRepository {

    List<ReviewCard> findByReviewDocIdOrderBySequenceAsc(Long reviewDocId);

    Optional<ReviewCard> findByReviewDocIdAndPointName(Long reviewDocId, String pointName);

    ReviewCard save(ReviewCard card);

    void deleteByReviewDocId(Long reviewDocId);
}
