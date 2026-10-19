package com.review.vt.repo;

import com.review.vt.domain.Question;

import java.util.List;
import java.util.Optional;

/**
 * 题目存储接口。当前实现为本地 JSON 文件，后续可无损切换到数据库实现。
 */
public interface QuestionRepository {
    Optional<Question> findById(String id);
    Question save(Question question);
    List<Question> findByPracticeIdOrderBySequenceAsc(String practiceId);
    long countByPracticeId(String practiceId);
    void deleteAll(List<Question> questions);
}
