package com.review.vt.repo;

import com.review.vt.domain.Question;

import java.util.List;
import java.util.Optional;

/**
 * 题目存储接口。当前实现为本地 JSON 文件，后续可无损切换到数据库实现。
 */
public interface QuestionRepository {
    Optional<Question> findById(Long id);
    Question save(Question question);
    List<Question> findByPaperIdOrderBySequenceAsc(Long paperId);
    List<Question> findByPracticeIdOrderBySequenceAsc(Long practiceId);
    long countByPaperId(Long paperId);
    void deleteAll(List<Question> questions);
}
