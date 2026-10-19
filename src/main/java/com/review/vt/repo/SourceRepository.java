package com.review.vt.repo;

import com.review.vt.domain.Source;

import java.util.List;
import java.util.Optional;

/**
 * 网上资料抓取缓存。当前实现为本地 JSON 文件，后续可无损切换到数据库实现。
 */
public interface SourceRepository {
    Optional<Source> findById(String url);
    Source save(Source source);
    List<Source> findAll();
}
