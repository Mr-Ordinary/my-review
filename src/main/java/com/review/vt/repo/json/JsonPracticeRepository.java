package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.Practice;
import com.review.vt.repo.PracticeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 会话（练习）表 → data/store/practices.json */
@Repository
public class JsonPracticeRepository extends AbstractJsonStore<Practice> implements PracticeRepository {

    public JsonPracticeRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "practices.json"), Practice::getId, new TypeReference<>() {});
    }

    @Override
    public Optional<Practice> findById(String id) {
        return Optional.ofNullable(get(id));
    }

    @Override
    public Practice save(Practice practice) {
        return super.save(practice);
    }

    @Override
    public List<Practice> findByStageIdOrderByCreatedAtAsc(String stageId) {
        return all().stream()
                .filter(p -> p.getStageId().equals(stageId))
                .sorted(Comparator.comparingLong(Practice::getCreatedAt))
                .toList();
    }

    @Override
    public List<Practice> findByStageIdOrderByCreatedAtDesc(String stageId) {
        return all().stream()
                .filter(p -> p.getStageId().equals(stageId))
                .sorted(Comparator.comparingLong(Practice::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public void deleteAll(List<Practice> practices) {
        super.deleteAll(practices);
    }
}
