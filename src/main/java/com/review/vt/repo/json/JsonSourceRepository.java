package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.Source;
import com.review.vt.repo.SourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** 资料缓存表 → data/store/sources.json */
@Repository
public class JsonSourceRepository extends AbstractJsonStore<Source> implements SourceRepository {

    public JsonSourceRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "sources.json"), Source::getId, new TypeReference<>() {});
    }

    @Override
    public Optional<Source> findById(String url) {
        return Optional.ofNullable(get(url));
    }

    @Override
    public Source save(Source source) {
        return super.save(source);
    }

    @Override
    public List<Source> findAll() {
        return all();
    }
}
