package com.review.vt.repo.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.review.vt.domain.Question;
import com.review.vt.repo.QuestionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 题目表 → data/store/questions.json */
@Repository
public class JsonQuestionRepository extends AbstractJsonStore<Question> implements QuestionRepository {

    public JsonQuestionRepository(@Value("${myreview.store-dir:./data/store}") String storeDir) {
        super(Path.of(storeDir, "questions.json"), Question::getId, new TypeReference<>() {});
    }

    @Override
    public Optional<Question> findById(String id) {
        return Optional.ofNullable(get(id));
    }

    @Override
    public Question save(Question question) {
        return super.save(question);
    }

    @Override
    public List<Question> findByPaperIdOrderBySequenceAsc(String paperId) {
        return all().stream()
                .filter(q -> paperId.equals(q.getPaperId()))
                .sorted(Comparator.comparingInt(Question::getSequence))
                .toList();
    }

    @Override
    public List<Question> findByPracticeIdOrderBySequenceAsc(String practiceId) {
        return all().stream()
                .filter(q -> practiceId.equals(q.getPracticeId()))
                .sorted(Comparator.comparing(Question::getCreatedAt))
                .toList();
    }

    @Override
    public long countByPaperId(String paperId) {
        return all().stream().filter(q -> paperId.equals(q.getPaperId())).count();
    }

    @Override
    public void deleteAll(List<Question> questions) {
        super.deleteAll(questions);
    }
}
