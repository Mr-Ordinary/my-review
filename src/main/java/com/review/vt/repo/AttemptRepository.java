package com.review.vt.repo;

import com.review.vt.domain.Attempt;

import java.util.List;

/**
 * 作答存储接口（追加保存，不覆盖历史）。当前实现为本地 JSON 文件，后续可无损切换到数据库实现。
 */
public interface AttemptRepository {
    Attempt save(Attempt attempt);
    List<Attempt> findByQuestionIdOrderBySequenceAsc(String questionId);
    void deleteAll(List<Attempt> attempts);
}
