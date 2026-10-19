package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.Attempt;
import com.review.vt.repo.AttemptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** 作答表 → data/store/attempts.json */
@Repository
public class JsonAttemptRepository extends AbstractJsonStore<Attempt> implements AttemptRepository {

    public JsonAttemptRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "attempts.json"), Attempt::getId, new TypeReference<>() {});
    }

    @Override
    public Attempt save(Attempt attempt) {
        return super.save(attempt);
    }

    @Override
    public List<Attempt> findByQuestionIdOrderBySequenceAsc(String questionId) {
        return all().stream()
                .filter(a -> a.getQuestionId().equals(questionId))
                .sorted(Comparator.comparingInt(Attempt::getSequence))
                .toList();
    }

    @Override
    public void deleteAll(List<Attempt> attempts) {
        super.deleteAll(attempts);
    }
}
