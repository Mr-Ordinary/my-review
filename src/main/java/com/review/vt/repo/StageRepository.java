package com.review.vt.repo;

import com.review.vt.domain.Stage;

import java.util.List;
import java.util.Optional;

/**
 * 阶段存储接口。当前实现为本地 JSON 文件（repo.json.JsonStageRepository），
 * 后续可无损切换到数据库实现（同名方法对应同名表列），服务层无需改动。
 */
public interface StageRepository {
    Optional<Stage> findById(String id);
    boolean existsById(String id);
    Stage save(Stage stage);
    void delete(Stage stage);
    List<Stage> findAllByOrderByUpdatedAtDesc();
}
