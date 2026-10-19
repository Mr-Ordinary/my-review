package com.review.vt.repo;

import com.review.vt.domain.Practice;

import java.util.List;
import java.util.Optional;

/**
 * 会话（练习）存储接口。一次练习 = 一个会话，归属某个阶段。
 * 当前实现为本地 JSON 文件，后续可无损切换到数据库实现。
 */
public interface PracticeRepository {
    Optional<Practice> findById(String id);
    Practice save(Practice practice);
    List<Practice> findByStageIdOrderByCreatedAtAsc(String stageId);
    List<Practice> findByStageIdOrderByCreatedAtDesc(String stageId);
    void deleteAll(List<Practice> practices);
}
