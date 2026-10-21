package com.review.vt.repo;

import com.review.vt.domain.MemoryPoint;

import java.util.List;
import java.util.Optional;

/**
 * 记忆点存储接口。当前实现为本地 JSON 文件，后续可无损切换到数据库实现。
 */
public interface MemoryPointRepository {
    Optional<MemoryPoint> findById(String id);
    MemoryPoint save(MemoryPoint point);
    List<MemoryPoint> findByPracticeIdOrderByCreatedAtDesc(String practiceId);
    void deleteAll(List<MemoryPoint> points);
}
