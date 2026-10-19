package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.Stage;
import com.review.vt.repo.StageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 阶段表 → data/store/stages.json */
@Repository
public class JsonStageRepository extends AbstractJsonStore<Stage> implements StageRepository {

    public JsonStageRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "stages.json"), Stage::getId, new TypeReference<>() {});
    }

    @Override
    public Optional<Stage> findById(String id) {
        return Optional.ofNullable(get(id));
    }

    @Override
    public boolean existsById(String id) {
        return findById(id).isPresent();
    }

    @Override
    public Stage save(Stage stage) {
        return super.save(stage);
    }

    @Override
    public void delete(Stage stage) {
        super.delete(stage);
    }

    @Override
    public List<Stage> findAllByOrderByUpdatedAtDesc() {
        return all().stream()
                .sorted(Comparator.comparingLong(Stage::getUpdatedAt).reversed())
                .toList();
    }
}
