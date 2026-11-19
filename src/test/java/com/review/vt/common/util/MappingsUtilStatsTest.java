package com.review.vt.common.util;

import com.review.vt.mapper.AttemptMapper;
import com.review.vt.mapper.PaperMapper;
import com.review.vt.mapper.QuestionMapper;
import com.review.vt.pojo.dto.Stats;
import com.review.vt.pojo.entity.Attempt;
import com.review.vt.pojo.entity.Paper;
import com.review.vt.pojo.entity.Practice;
import com.review.vt.pojo.entity.Question;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 统计口径黄金测试：锁死 stageStats/practiceStats/paperStats 的数值语义。
 *
 * <p>这是「取数/计算分离」重构的逐位兼容闸门——重构前后这些数字必须完全一致。
 * 特别锁死的不对称语义：evaluatedCount 按 score!=null 计，paperScore 按 points!=null 计，
 * 两者不是一回事，不许「修正」。
 */
class MappingsUtilStatsTest {

    // ---------- fixtures ----------

    private static Practice practice(long id) {
        Practice p = new Practice();
        p.setId(id);
        p.setStageId(1L);
        return p;
    }

    private static Paper paper(long id, long practiceId, int seq, String kind, String status) {
        Paper p = new Paper();
        p.setId(id);
        p.setPracticeId(practiceId);
        p.setSequence(seq);
        p.setKind(kind);
        p.setStatus(status);
        return p;
    }

    private static Question question(long id, long paperId, int seq) {
        Question q = new Question();
        q.setId(id);
        q.setPaperId(paperId);
        q.setPracticeId(1L);
        q.setSequence(seq);
        return q;
    }

    private static Attempt attempt(long questionId, int seq, Double score, Double points, Long evaluatedAt) {
        Attempt a = new Attempt();
        a.setQuestionId(questionId);
        a.setSequence(seq);
        a.setScore(score);
        a.setPoints(points);
        a.setEvaluatedAt(evaluatedAt);
        return a;
    }

    /**
     * 把夹具数据接到 mock 的取数方法上（批量接口按 SQL 的 IN + 排序序返回）。
     * 断言值是黄金基准——重构取数方式后这些数字必须逐位不变。
     */
    private static void wire(PaperMapper pRepo, QuestionMapper qRepo, AttemptMapper aRepo,
                             List<Paper> papers, List<Question> questions, List<Attempt> attempts) {
        when(pRepo.findByPracticeIds(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            // SQL 序：practice_id ASC, sequence ASC
            return papers.stream()
                    .filter(p -> ids.contains(p.getPracticeId()))
                    .sorted(java.util.Comparator.comparing(Paper::getPracticeId).thenComparingInt(Paper::getSequence))
                    .toList();
        });
        when(qRepo.findRefsByPaperIds(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            // SQL 序：paper_id ASC, sequence ASC
            return questions.stream()
                    .filter(q -> ids.contains(q.getPaperId()))
                    .sorted(java.util.Comparator.comparing(Question::getPaperId).thenComparingInt(Question::getSequence))
                    .toList();
        });
        when(aRepo.findByQuestionIds(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            // SQL 序：question_id ASC, sequence ASC
            return attempts.stream()
                    .filter(a -> ids.contains(a.getQuestionId()))
                    .sorted(java.util.Comparator.comparing(Attempt::getQuestionId).thenComparingInt(Attempt::getSequence))
                    .toList();
        });
    }

    private static Stats stageStats(List<Practice> practices, List<Paper> papers,
                                    List<Question> questions, List<Attempt> attempts) {
        PaperMapper pRepo = mock(PaperMapper.class);
        QuestionMapper qRepo = mock(QuestionMapper.class);
        AttemptMapper aRepo = mock(AttemptMapper.class);
        wire(pRepo, qRepo, aRepo, papers, questions, attempts);
        return MappingsUtil.stageStats(practices, qRepo, aRepo, pRepo);
    }

    // ---------- 黄金用例 ----------

    @Test
    @DisplayName("空阶段：全零、全 null、空列表")
    void emptyStage() {
        Stats s = MappingsUtil.stageStats(List.of(), mock(QuestionMapper.class),
                mock(AttemptMapper.class), mock(PaperMapper.class));
        assertEquals(new Stats(0, 0, 0, 0, 0, null, null, List.of(), 0, null, null), s);
    }

    @Test
    @DisplayName("未批改卷（status≠graded）：分不上，但计数照常")
    void ungradedPaperOnly() {
        Paper pa = paper(10, 1, 1, null, "answering");
        List<Question> qs = List.of(question(100, 10, 1), question(101, 10, 2));
        List<Attempt> ats = List.of(attempt(100, 1, 5.0, 2.0, 99L));
        Stats s = stageStats(List.of(practice(1)), List.of(pa), qs, ats);

        assertEquals(1, s.practiceCount());
        assertEquals(1, s.paperCount());
        assertEquals(2, s.questionCount());
        assertEquals(1, s.attemptCount());
        assertEquals(1, s.evaluatedCount());
        assertNull(s.averageScore(), "未批改卷不计分");
        assertNull(s.lastScore());
        assertTrue(s.recentScores().isEmpty());
        assertEquals(0, s.drillCount());
    }

    @Test
    @DisplayName("已批改卷但零条已批改作答：paperScore 仍是 null")
    void gradedPaperWithoutEvaluatedAttempts() {
        Paper pa = paper(10, 1, 1, null, "graded");
        List<Question> qs = List.of(question(100, 10, 1), question(101, 10, 2));
        Stats s = stageStats(List.of(practice(1)), List.of(pa), qs, List.of());

        assertNull(s.averageScore());
        assertNull(s.lastScore());
        assertEquals(2, s.questionCount());
    }

    @Test
    @DisplayName("不对称语义：score 非 null 但 points 为 null → evaluatedCount 计、paperScore 不计")
    void scoreWithoutPoints() {
        Paper pa = paper(10, 1, 1, null, "graded");
        List<Attempt> ats = List.of(attempt(100, 1, 5.0, null, 99L));
        Stats s = stageStats(List.of(practice(1)), List.of(pa), List.of(question(100, 10, 1)), ats);

        assertEquals(1, s.evaluatedCount(), "evaluatedCount 条件是 score!=null");
        assertNull(s.averageScore(), "paperScore 只认 points!=null，any=false → null");
    }

    @Test
    @DisplayName("不对称语义：points 非 null 但 score 为 null → paperScore 计、evaluatedCount 不计")
    void pointsWithoutScore() {
        Paper pa = paper(10, 1, 1, null, "graded");
        List<Attempt> ats = List.of(attempt(100, 1, null, 3.0, 99L));
        Stats s = stageStats(List.of(practice(1)), List.of(pa), List.of(question(100, 10, 1)), ats);

        assertEquals(0, s.evaluatedCount(), "score==null 不算已批改");
        assertEquals(3.0, s.averageScore(), "points 非 null 即计入实得分");
        assertEquals(3.0, s.lastScore());
    }

    @Test
    @DisplayName("同题多条作答：取 sequence 末条的 points（last-wins）")
    void lastEvaluatedWins() {
        Paper pa = paper(10, 1, 1, null, "graded");
        List<Attempt> ats = List.of(
                attempt(100, 1, 8.0, 3.0, 99L),
                attempt(100, 2, 9.0, 7.0, 98L));
        Stats s = stageStats(List.of(practice(1)), List.of(pa), List.of(question(100, 10, 1)), ats);

        assertEquals(7.0, s.averageScore(), "末条 points=7.0 生效");
        assertEquals(2, s.evaluatedCount());
        assertEquals(2, s.attemptCount());
    }

    @Test
    @DisplayName("averageScore 舍入 0.1：70 与 75.5 的均值 72.75 → 72.8")
    void averageScoreRounding() {
        Paper pa1 = paper(10, 1, 1, null, "graded");
        Paper pa2 = paper(11, 1, 2, null, "graded");
        List<Attempt> ats = List.of(
                attempt(100, 1, 9.0, 70.0, 99L),
                attempt(101, 1, 8.5, 75.5, 99L));
        Stats s = stageStats(List.of(practice(1)), List.of(pa1, pa2),
                List.of(question(100, 10, 1), question(101, 11, 1)), ats);

        assertEquals(72.8, s.averageScore());
        assertEquals(75.5, s.lastScore(), "最近分 = 最后一张已批改卷");
        assertEquals(List.of(70.0, 75.5), s.recentScores());
    }

    @Test
    @DisplayName("recentScores 只取末 8 张（10 张已批改卷）")
    void recentScoresLast8() {
        List<Paper> papers = new ArrayList<>();
        List<Question> questions = new ArrayList<>();
        List<Attempt> attempts = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            papers.add(paper(100 + i, 1, i, null, "graded"));
            questions.add(question(200 + i, 100 + i, 1));
            attempts.add(attempt(200 + i, 1, 8.0, (double) i, 99L));
        }
        Stats s = stageStats(List.of(practice(1)), papers, questions, attempts);

        assertEquals(5.5, s.averageScore());
        assertEquals(10.0, s.lastScore());
        assertEquals(List.of(3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0), s.recentScores());
    }

    @Test
    @DisplayName("刷题卷：题数按全部题计，均/末只算已批改的 score（末条 wins）")
    void drillOnlyPractice() {
        Paper dr = paper(10, 1, 0, "drill", "answering");
        List<Question> qs = List.of(question(100, 10, 1), question(101, 10, 2), question(102, 10, 3));
        List<Attempt> ats = List.of(
                attempt(100, 1, 8.0, null, 99L),
                attempt(101, 1, 6.0, null, 99L),
                attempt(101, 2, 9.0, null, 99L));
        Stats s = stageStats(List.of(practice(1)), List.of(dr), qs, ats);

        assertEquals(0, s.paperCount(), "drill 卷不算真考卷");
        assertEquals(0, s.questionCount(), "drill 题不进卷题数");
        assertEquals(0, s.attemptCount(), "drill 作答不进卷作答数");
        assertEquals(3, s.drillCount(), "刷题数按出过的全部题");
        assertEquals(8.5, s.drillAvgScore(), "(8+9)/2，q2 取末条 9");
        assertEquals(9.0, s.drillLastScore());
    }

    @Test
    @DisplayName("kind=null 的卷算真考卷（只有 drill 被剔除）")
    void nullKindIncluded() {
        Paper pa = paper(10, 1, 1, null, "answering");
        Stats s = stageStats(List.of(practice(1)), List.of(pa), List.of(question(100, 10, 1)), List.of());

        assertEquals(1, s.paperCount());
        assertEquals(1, s.questionCount());
    }

    @Test
    @DisplayName("跨练习顺序：practice-major，lastScore 取练习序末张")
    void practiceMajorOrder() {
        Practice p1 = practice(1);
        Practice p2 = practice(2);
        Paper pa = paper(10, 1, 1, null, "graded");
        Paper pb = paper(11, 2, 1, null, "graded");
        List<Question> qs = List.of(question(100, 10, 1), question(101, 11, 1));
        List<Attempt> ats = List.of(
                attempt(100, 1, 6.0, 60.0, 99L),
                attempt(101, 1, 8.0, 80.0, 99L));

        Stats s = stageStats(List.of(p1, p2), List.of(pa, pb), qs, ats);
        assertEquals(80.0, s.lastScore(), "练习 2 的卷在后 → lastScore=80");

        Stats s2 = stageStats(List.of(p2, p1), List.of(pb, pa), qs, ats);
        assertEquals(60.0, s2.lastScore(), "顺序反转 → lastScore=60");
    }

    @Test
    @DisplayName("practiceStats/paperStats 包装：practiceCount 分别为 1 与 0")
    void wrapperPracticeCounts() {
        PaperMapper pRepo = mock(PaperMapper.class);
        QuestionMapper qRepo = mock(QuestionMapper.class);
        AttemptMapper aRepo = mock(AttemptMapper.class);
        Paper pa = paper(10, 1, 1, null, "answering");
        wire(pRepo, qRepo, aRepo, List.of(pa), List.of(question(100, 10, 1)), List.of());

        Stats ps = MappingsUtil.practiceStats(practice(1), qRepo, aRepo, pRepo);
        assertEquals(1, ps.practiceCount());

        Stats qs2 = MappingsUtil.paperStats(pa, qRepo, aRepo);
        assertEquals(0, qs2.practiceCount());
        assertEquals(1, qs2.paperCount());
    }
}
