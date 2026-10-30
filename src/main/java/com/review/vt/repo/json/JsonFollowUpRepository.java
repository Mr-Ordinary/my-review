package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.FollowUp;
import com.review.vt.repo.FollowUpRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** 追问聊天记录表 → data/store/followups.json */
@Repository
public class JsonFollowUpRepository extends AbstractJsonStore<FollowUp> implements FollowUpRepository {

    public JsonFollowUpRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "followups.json"), FollowUp::getId, new TypeReference<>() {});
    }

    @Override
    public FollowUp save(FollowUp followUp) {
        return super.save(followUp);
    }

    @Override
    public List<FollowUp> findByQuestionIdOrderByCreatedAtAsc(String questionId) {
        return all().stream()
                .filter(f -> questionId.equals(f.getQuestionId()))
                .sorted(Comparator.comparingLong(FollowUp::getCreatedAt))
                .toList();
    }

    @Override
    public List<FollowUp> findByPracticeId(String practiceId) {
        return all().stream()
                .filter(f -> practiceId.equals(f.getPracticeId()))
                .toList();
    }

    @Override
    public long countByQuestionId(String questionId) {
        return all().stream().filter(f -> questionId.equals(f.getQuestionId())).count();
    }

    @Override
    public void deleteAll(List<FollowUp> followUps) {
        super.deleteAll(followUps);
    }
}
