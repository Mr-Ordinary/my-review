package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.KnowledgePoint;
import com.review.vt.repo.KnowledgePointRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 知识点表 → data/store/knowledge_points.json */
@Repository
public class JsonKnowledgePointRepository extends AbstractJsonStore<KnowledgePoint> implements KnowledgePointRepository {

    public JsonKnowledgePointRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "knowledge_points.json"), KnowledgePoint::getId, new TypeReference<>() {});
    }

    @Override
    public Optional<KnowledgePoint> findById(String id) {
        return Optional.ofNullable(get(id));
    }

    @Override
    public KnowledgePoint save(KnowledgePoint point) {
        return super.save(point);
    }

    @Override
    public List<KnowledgePoint> findByPracticeIdOrderByCreatedAtAsc(String practiceId) {
        return all().stream()
                .filter(k -> practiceId.equals(k.getPracticeId()))
                .sorted(Comparator.comparingLong(KnowledgePoint::getCreatedAt))
                .toList();
    }

    @Override
    public void deleteAll(List<KnowledgePoint> points) {
        super.deleteAll(points);
    }
}
