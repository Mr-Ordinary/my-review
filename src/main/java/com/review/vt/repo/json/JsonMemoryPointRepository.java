package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.MemoryPoint;
import com.review.vt.repo.MemoryPointRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 记忆点表 → data/store/memory_points.json */
@Repository
public class JsonMemoryPointRepository extends AbstractJsonStore<MemoryPoint> implements MemoryPointRepository {

    public JsonMemoryPointRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "memory_points.json"), MemoryPoint::getId, new TypeReference<>() {});
    }

    @Override
    public Optional<MemoryPoint> findById(String id) {
        return Optional.ofNullable(get(id));
    }

    @Override
    public MemoryPoint save(MemoryPoint point) {
        return super.save(point);
    }

    @Override
    public List<MemoryPoint> findByPracticeIdOrderByCreatedAtDesc(String practiceId) {
        return all().stream()
                .filter(m -> practiceId.equals(m.getPracticeId()))
                .sorted(Comparator.comparingLong(MemoryPoint::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public void deleteAll(List<MemoryPoint> points) {
        super.deleteAll(points);
    }
}
