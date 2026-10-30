package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.ReviewDoc;
import com.review.vt.repo.ReviewDocRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** 复习提纲表 → data/store/reviews.json */
@Repository
public class JsonReviewDocRepository extends AbstractJsonStore<ReviewDoc> implements ReviewDocRepository {

    public JsonReviewDocRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "reviews.json"), ReviewDoc::getId, new TypeReference<>() {});
    }

    @Override
    public Optional<ReviewDoc> findById(String id) {
        return Optional.ofNullable(get(id));
    }

    @Override
    public Optional<ReviewDoc> findByPracticeId(String practiceId) {
        return all().stream().filter(d -> practiceId.equals(d.getPracticeId())).findFirst();
    }

    @Override
    public ReviewDoc save(ReviewDoc doc) {
        return super.save(doc);
    }

    @Override
    public void deleteAll(List<ReviewDoc> docs) {
        super.deleteAll(docs);
    }
}
