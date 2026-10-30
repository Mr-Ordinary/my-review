package com.review.vt.repo;

import com.review.vt.domain.ReviewDoc;

import java.util.List;
import java.util.Optional;

/** 复习提纲存储接口。当前实现为本地 JSON 文件，后续可无损切数据库。 */
public interface ReviewDocRepository {
    Optional<ReviewDoc> findById(String id);
    Optional<ReviewDoc> findByPracticeId(String practiceId);
    ReviewDoc save(ReviewDoc doc);
    void deleteAll(List<ReviewDoc> docs);
}
