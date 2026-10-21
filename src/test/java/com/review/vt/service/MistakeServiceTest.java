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

    private static Question q(String id, Long revealedAt) {
        Question question = new Question();
        question.setId(id);
        question.setPracticeId("p1");
        question.setRevealedAt(revealedAt);
        return question;
    }

    private static Attempt attempt(Double score) {
        Attempt a = new Attempt();
        a.setScore(score);
        return a;
    }

    private static Mistake existing(String questionId, String source) {
        Mistake m = new Mistake();
        m.setId("m-" + questionId);
        m.setPracticeId("p1");
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
                q("q-low", null), q("q-high", null), q("q-empty", null), q("q-revealed", 1L));
        Map<String, Attempt> latest = new LinkedHashMap<>();
        latest.put("q-low", attempt(3.0));
        latest.put("q-high", attempt(9.0));
        latest.put("q-revealed", attempt(9.0));

        List<Mistake> out = MistakeService.buildAutoMistakes("p1", qs, latest, List.of(), 100L);
        assertEquals(3, out.size(), "低分/未作答/看答案三条，高分不收");
        Map<String, String> reasons = new LinkedHashMap<>();
        for (Mistake m : out) reasons.put(m.getQuestionId(), m.getReason());
        assertEquals("low-score", reasons.get("q-low"));
        assertEquals("unanswered", reasons.get("q-empty"));
        assertEquals("revealed", reasons.get("q-revealed"));
        assertNull(reasons.get("q-high"));
    }

    @Test
    @DisplayName("幂等：已有行的题跳过，批改重入不翻倍")
    void buildAutoMistakes_idempotent() {
        List<Question> qs = List.of(q("q1", null), q("q2", null));
        Map<String, Attempt> latest = new LinkedHashMap<>();
        latest.put("q1", attempt(2.0));
        latest.put("q2", attempt(2.0));
        List<Mistake> existing = List.of(existing("q1", "auto"));

        List<Mistake> out = MistakeService.buildAutoMistakes("p1", qs, latest, existing, 100L);
        assertEquals(1, out.size(), "q1 已收录，只补 q2");
        assertEquals("q2", out.get(0).getQuestionId());
    }

    @Test
    @DisplayName("手动行永不被自动收录覆盖——后来考好的题也留在本子里")
    void buildAutoMistakes_preservesManual() {
        List<Question> qs = List.of(q("q1", null));
        Map<String, Attempt> latest = new LinkedHashMap<>();
        latest.put("q1", attempt(9.0));   // 后来考好了

        List<Mistake> out = MistakeService.buildAutoMistakes("p1", qs, latest,
                List.of(existing("q1", "manual")), 100L);
        assertTrue(out.isEmpty(), "已有手动行且高分，不再生成新行");
    }

    @Test
    @DisplayName("cap：一个练习最多 500 条错题")
    void buildAutoMistakes_respectsCap() {
        List<Question> qs = new ArrayList<>();
        Map<String, Attempt> latest = new LinkedHashMap<>();
        for (int i = 0; i < Limits.MAX_MISTAKES_PER_PRACTICE + 10; i++) {
            qs.add(q("q" + i, null));
            latest.put("q" + i, attempt(1.0));
        }
        List<Mistake> out = MistakeService.buildAutoMistakes("p1", qs, latest, List.of(), 100L);
        assertEquals(Limits.MAX_MISTAKES_PER_PRACTICE, out.size());
    }
}
