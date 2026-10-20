package com.review.vt.repo;

import com.review.vt.domain.KnowledgePoint;

import java.util.List;
import java.util.Optional;

/**
 * 知识点存储接口。当前实现为本地 JSON 文件，后续可无损切换到数据库实现。
 */
public interface KnowledgePointRepository {
    Optional<KnowledgePoint> findById(String id);
    KnowledgePoint save(KnowledgePoint point);
    List<KnowledgePoint> findByPracticeIdOrderByCreatedAtAsc(String practiceId);
    void deleteAll(List<KnowledgePoint> points);
}
