package com.review.vt.repo;

import com.review.vt.domain.Paper;

import java.util.List;
import java.util.Optional;

/**
 * 卷存储接口（一张卷 = AI 一次出的一批题）。
 * 当前实现为本地 JSON 文件，后续可无损切换到数据库实现。
 */
public interface PaperRepository {
    Optional<Paper> findById(String id);
    Paper save(Paper paper);
    List<Paper> findByPracticeIdOrderBySequenceAsc(String practiceId);
    long countByPracticeId(String practiceId);
    void deleteAll(List<Paper> papers);
}
