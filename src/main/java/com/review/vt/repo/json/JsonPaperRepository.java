package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.Paper;
import com.review.vt.repo.PaperRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 卷表 → data/store/papers.json */
@Repository
public class JsonPaperRepository extends AbstractJsonStore<Paper> implements PaperRepository {

    public JsonPaperRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "papers.json"), Paper::getId, new TypeReference<>() {});
    }

    @Override
    public Optional<Paper> findById(String id) {
        return Optional.ofNullable(get(id));
    }

    @Override
    public Paper save(Paper paper) {
        return super.save(paper);
    }

    @Override
    public List<Paper> findByPracticeIdOrderBySequenceAsc(String practiceId) {
        return all().stream()
                .filter(p -> p.getPracticeId().equals(practiceId))
                .sorted(Comparator.comparingInt(Paper::getSequence))
                .toList();
    }

    @Override
    public long countByPracticeId(String practiceId) {
        return all().stream().filter(p -> p.getPracticeId().equals(practiceId)).count();
    }

    @Override
    public void deleteAll(List<Paper> papers) {
        super.deleteAll(papers);
    }
}
