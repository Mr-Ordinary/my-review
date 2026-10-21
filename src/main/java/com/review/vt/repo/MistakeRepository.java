package com.review.vt.repo;

import com.review.vt.domain.Mistake;

import java.util.List;
import java.util.Optional;

/**
 * 错题本存储接口。当前实现为本地 JSON 文件，后续可无损切换到数据库实现。
 * 列表最新在前——刚栽的题排最上面。
 */
public interface MistakeRepository {
    Optional<Mistake> findById(String id);
    Mistake save(Mistake mistake);
    List<Mistake> findByPracticeIdOrderByCreatedAtDesc(String practiceId);
    void deleteAll(List<Mistake> mistakes);
}
