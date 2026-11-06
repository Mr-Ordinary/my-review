package com.review.vt.service;

import com.review.vt.domain.Attempt;
import com.review.vt.domain.Mistake;
import com.review.vt.domain.Question;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 错题判定与自动收录的纯逻辑。
 *
 * 错题口径只在 MistakeService 一处，测试锁死它——
 * 前端不许再算一遍，提示词也不许另立标准。
 */
class MistakeServiceTest {

    private static Question q(Long id, Long revealedAt) {
        Question question = new Question();
        question.setId(id);
        question.setPracticeId(1L);
        question.setRevealedAt(revealedAt);
        return question;
    }

    private static Attempt attempt(Double score) {
        Attempt a = new Attempt();
        a.setScore(score);
        return a;
    }

    private static Mistake existing(Long questionId, String source) {
        Mistake m = new Mistake();
        m.setId(questionId + 1000);
        m.setPracticeId(1L);
        m.setQuestionId(questionId);
        m.setSource(source);
        m.setStatus("open");
        return m;
    }

    @Test
    @DisplayName("错题判定矩阵：低分/未作答/看过答案收，及格不收")
    void isMistake_matrix() {
        assertTrue(MistakeService.isMistake(5.9, true, false), "低分要收");
        assertFalse(MistakeService.isMistake(6.0, true, false), "正好及格不收（PASS_SCORE 边界）");
        assertFalse(MistakeService.isMistake(8.0, true, false), "高分不收");
        assertTrue(MistakeService.isMistake(null, true, false), "已作答但没分要收");
        assertTrue(MistakeService.isMistake(null, false, false), "未作答要收");
        assertTrue(MistakeService.isMistake(8.0, false, true), "高分但看过答案也收");
        assertTrue(MistakeService.isMistake(null, false, true), "看过答案必收");
    }

    @Test
    @DisplayName("收录原因优先级：看了答案 > 未作答 > 低分")
    void reasonOf_precedence() {
        assertEquals("revealed", MistakeService.reasonOf(9.0, true, true));
        assertEquals("unanswered", MistakeService.reasonOf(null, false, false));
        assertEquals("low-score", MistakeService.reasonOf(3.0, true, false));
    }

    @Test
    @DisplayName("自动收录：低分/未作答/看答案都生成，高分不生成")
    void buildAutoMistakes_collectsOnlyMistakes() {
        List<Question> qs = List.of(
                q(1L, null), q(2L, null), q(3L, null), q(4L, 1L));
        Map<Long, Attempt> latest = new LinkedHashMap<>();
        latest.put(1L, attempt(3.0));
        latest.put(2L, attempt(9.0));
        latest.put(4L, attempt(9.0));

        List<Mistake> out = MistakeService.buildAutoMistakes(1L, qs, latest, List.of(), 100L);
        assertEquals(3, out.size(), "低分/未作答/看答案三条，高分不收");
        Map<Long, String> reasons = new LinkedHashMap<>();
        for (Mistake m : out) reasons.put(m.getQuestionId(), m.getReason());
        assertEquals("low-score", reasons.get(1L));
        assertEquals("unanswered", reasons.get(3L));
        assertEquals("revealed", reasons.get(4L));
        assertNull(reasons.get(2L));
    }

    @Test
    @DisplayName("幂等：已有行的题跳过，批改重入不翻倍")
    void buildAutoMistakes_idempotent() {
        List<Question> qs = List.of(q(5L, null), q(6L, null));
        Map<Long, Attempt> latest = new LinkedHashMap<>();
        latest.put(5L, attempt(2.0));
        latest.put(6L, attempt(2.0));
        List<Mistake> existing = List.of(existing(5L, "auto"));

        List<Mistake> out = MistakeService.buildAutoMistakes(1L, qs, latest, existing, 100L);
        assertEquals(1, out.size(), "q1 已收录，只补 q2");
        assertEquals(6L, out.get(0).getQuestionId());
    }

    @Test
    @DisplayName("手动行永不被自动收录覆盖——后来考好的题也留在本子里")
    void buildAutoMistakes_preservesManual() {
        List<Question> qs = List.of(q(5L, null));
        Map<Long, Attempt> latest = new LinkedHashMap<>();
        latest.put(5L, attempt(9.0));   // 后来考好了

        List<Mistake> out = MistakeService.buildAutoMistakes(1L, qs, latest,
                List.of(existing(5L, "manual")), 100L);
        assertTrue(out.isEmpty(), "已有手动行且高分，不再生成新行");
    }

    @Test
    @DisplayName("cap：一个练习最多 500 条错题")
    void buildAutoMistakes_respectsCap() {
        List<Question> qs = new ArrayList<>();
        Map<Long, Attempt> latest = new LinkedHashMap<>();
        for (int i = 0; i < Limits.MAX_MISTAKES_PER_PRACTICE + 10; i++) {
            qs.add(q(100L + i, null));
            latest.put(100L + i, attempt(1.0));
        }
        List<Mistake> out = MistakeService.buildAutoMistakes(1L, qs, latest, List.of(), 100L);
        assertEquals(Limits.MAX_MISTAKES_PER_PRACTICE, out.size());
    }
}
