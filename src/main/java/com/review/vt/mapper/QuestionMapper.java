package com.review.vt.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.Question;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/** question 表 Mapper（SQL 见 resources/mapper/QuestionMapper.xml，全部带 user_id 隔离）。 */
@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    Question selectByIdScoped(Long id, Long userId);

    default Optional<Question> findById(Long id) {
        return Optional.ofNullable(selectByIdScoped(id, AuthContext.require().userId()));
    }

    default Question save(Question question) {
        if (question.getUserId() == null) question.setUserId(AuthContext.require().userId());
        if (question.getId() == null) {
            insert(question);
        } else {
            updateById(question);
        }
        return question;
    }



    List<Question> selectByPaperSeqAsc(Long userId, Long paperId);

    default List<Question> findByPaperIdOrderBySequenceAsc(Long paperId) {
        return selectByPaperSeqAsc(AuthContext.require().userId(), paperId);
    }

    List<Question> selectByPracticeSeqAsc(Long userId, Long practiceId);

    default List<Question> findByPracticeIdOrderBySequenceAsc(Long practiceId) {
        return selectByPracticeSeqAsc(AuthContext.require().userId(), practiceId);
    }

    /**
     * 批量取多卷的题（窄投影：仅 id/paper_id/sequence，一次 IN 查询，paper_id, sequence 序）。
     * 统计链专用——不带 prompt/参考答案大文本；要全行用 selectByPaperIds。
     */
    List<Question> selectRefsByPaperIds(Long userId, List<Long> ids);

    default List<Question> findRefsByPaperIds(List<Long> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) return List.of();
        return selectRefsByPaperIds(AuthContext.require().userId(), paperIds);
    }

    /** 卷题存根（答题页题号导航）：窄投影不带题面/参考答案大文本，sequence 序。部分列行禁止 save/updateById */
    List<Question> selectStubsByPaperId(Long userId, Long paperId);

    /** 批量取多卷的全行题（一次 IN 查询，paper_id, sequence 序）：详情/DTO 链防逐卷 N+1 */
    List<Question> selectByPaperIds(Long userId, List<Long> ids);

    default List<Question> findByPaperIds(List<Long> paperIds) {
        if (paperIds == null || paperIds.isEmpty()) return List.of();
        return selectByPaperIds(AuthContext.require().userId(), paperIds);
    }

    /* ================= 批量写（严禁循环插入/逐行写；超长用 BatchWriter 拆条） ================= */

    /** 批量插入（出题出完一次入库）。调用前须设每行 userId（worker 线程经 withUser 恢复 AuthContext 后填） */
    int insertByList(List<Question> rows);

    /** 批量更新参考答案（批改刷新链）：仅改 reference_answer，一条 SQL（VALUES 显式 CAST 防 text 推断） */
    int updateRefAnswerByList(Long userId, List<Question> rows);

    long countByPaper(Long userId, Long paperId);

    default long countByPaperId(Long paperId) {
        return countByPaper(AuthContext.require().userId(), paperId);
    }

    void deleteByIds(Long userId, List<Long> ids);

    default void deleteAll(List<Question> questions) {
        java.util.List<Long> ids = questions.stream().map(Question::getId).toList();
        if (ids.isEmpty()) return;   // 空列表：IN () 会语法错（沿用旧 deleteByIds 守卫）
        deleteByIds(AuthContext.require().userId(), ids);
    }
}
