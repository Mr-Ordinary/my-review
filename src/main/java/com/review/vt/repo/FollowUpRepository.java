package com.review.vt.repo;

import com.review.vt.domain.FollowUp;

import java.util.List;

/** 追问聊天记录存储接口。当前实现为本地 JSON 文件，后续可无损切数据库。 */
public interface FollowUpRepository {
    FollowUp save(FollowUp followUp);
    List<FollowUp> findByQuestionIdOrderByCreatedAtAsc(String questionId);
    List<FollowUp> findByPracticeId(String practiceId);
    long countByQuestionId(String questionId);
    void deleteAll(List<FollowUp> followUps);
}
