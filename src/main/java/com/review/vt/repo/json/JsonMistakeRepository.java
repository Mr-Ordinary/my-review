package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.Mistake;
import com.review.vt.repo.MistakeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 错题本表 → data/store/mistakes.json */
@Repository
public class JsonMistakeRepository extends AbstractJsonStore<Mistake> implements MistakeRepository {

    public JsonMistakeRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "mistakes.json"), Mistake::getId, new TypeReference<>() {});
    }

    @Override
    public Optional<Mistake> findById(String id) {
        return Optional.ofNullable(get(id));
    }

    @Override
    public Mistake save(Mistake mistake) {
        return super.save(mistake);
    }

    @Override
    public List<Mistake> findByPracticeIdOrderByCreatedAtDesc(String practiceId) {
        return all().stream()
                .filter(m -> practiceId.equals(m.getPracticeId()))
                .sorted(Comparator.comparingLong(Mistake::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public void deleteAll(List<Mistake> mistakes) {
        super.deleteAll(mistakes);
    }
}
