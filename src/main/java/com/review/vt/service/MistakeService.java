package com.review.vt.service;

import com.review.vt.domain.Attempt;
import com.review.vt.domain.Mistake;
import com.review.vt.domain.Question;
import com.review.vt.repo.MistakeRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.web.Dtos.MistakeDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 错题本：per-practice，一题一条。
 *
 * 收录口径只有一处（{@link #isMistake}）：低分 / 未作答 / 看过参考答案。
 * 手动收录优先——自动收录永不覆盖或删除已有行（手动收的题即使后来考好了也留在本子里）。
 */
@Service
public class MistakeService {

    private final MistakeRepository mistakes;
    private final QuestionRepository questions;

    public MistakeService(MistakeRepository mistakes, QuestionRepository questions) {
        this.mistakes = mistakes;
        this.questions = questions;
    }

    /* ================= 纯逻辑（可单测） ================= */

    /** 错题判定：低分 / 未作答 / 看过参考答案。口径只有这一处，别在前端再算 */
    public static boolean isMistake(Double score, boolean answered, boolean revealed) {
        if (revealed) return true;          // 看了参考答案按 0 分，必收
        if (!answered) return true;         // 空着的题就是没掌握
        return score == null || score < KnowledgeService.PASS_SCORE;
    }

    /** 收录原因（决定徽章文案）。优先级：看了答案 > 未作答 > 低分 */
    public static String reasonOf(Double score, boolean answered, boolean revealed) {
        if (revealed) return "revealed";
        if (!answered) return "unanswered";
        return "low-score";
    }

    /**
     * 纯函数：根据本卷题目 + 最新作答 + 已有错题，算出该落库的新行。不碰 repo，可直接单测。
     *
     * 幂等：已有行的题跳过（批改续跑不会翻倍）；手动行永不被自动收录覆盖。
     */
    public static List<Mistake> buildAutoMistakes(String practiceId, List<Question> qs,
            Map<String, Attempt> latest, List<Mistake> existing, long now) {
        Map<String, Mistake> byQuestion = new LinkedHashMap<>();
        for (Mistake m : existing) byQuestion.put(m.getQuestionId(), m);
        List<Mistake> out = new ArrayList<>();
        for (Question q : qs) {
            if (byQuestion.containsKey(q.getId())) continue;   // 已收录（含手动）不动
            Attempt a = latest.get(q.getId());
            boolean answered = a != null;
            boolean revealed = q.getRevealedAt() != null;
            Double score = answered ? a.getScore() : null;
            if (!isMistake(score, answered, revealed)) continue;
            if (existing.size() + out.size() >= Limits.MAX_MISTAKES_PER_PRACTICE) break;
            Mistake m = new Mistake();
            m.setId(UUID.randomUUID().toString());
            m.setPracticeId(practiceId);
            m.setQuestionId(q.getId());
            m.setSource("auto");
            m.setReason(reasonOf(score, answered, revealed));
            m.setScore(score);
            m.setStatus("open");
            m.setCreatedAt(now);
            m.setUpdatedAt(now);
            out.add(m);
        }
        return out;
    }

    /* ================= 读写 ================= */

    /** 交卷批改后的自动收录。调用方容错——失败绝不卡批改 */
    public void autoCollect(String practiceId, List<Question> qs, Map<String, Attempt> latest) {
        List<Mistake> existing = mistakes.findByPracticeIdOrderByCreatedAtDesc(practiceId);
        long now = System.currentTimeMillis();
        for (Mistake m : buildAutoMistakes(practiceId, qs, latest, existing, now)) {
            mistakes.save(m);
        }
    }

    /** 手动收录 / 重新打开已订正的行 */
    public MistakeDto addManual(String practiceId, String questionId) {
        if (questionId == null || questionId.isBlank()) throw new IllegalArgumentException("缺少题目 id");
        Question q = questions.findById(questionId.trim())
                .orElseThrow(() -> new NotFoundException("题目不存在或不属于这个练习"));
        if (!practiceId.equals(q.getPracticeId())) {
            throw new NotFoundException("题目不存在或不属于这个练习");
        }
        long now = System.currentTimeMillis();
        for (Mistake m : mistakes.findByPracticeIdOrderByCreatedAtDesc(practiceId)) {
            if (questionId.trim().equals(m.getQuestionId())) {
                // 已有行：重新打开（手动想再盯住它）
                m.setStatus("open");
                m.setResolvedAt(null);
                m.setUpdatedAt(now);
                return dto(mistakes.save(m));
            }
        }
        if (mistakes.findByPracticeIdOrderByCreatedAtDesc(practiceId).size() >= Limits.MAX_MISTAKES_PER_PRACTICE) {
            throw new IllegalArgumentException("一个练习最多 " + Limits.MAX_MISTAKES_PER_PRACTICE + " 条错题");
        }
        Mistake m = new Mistake();
        m.setId(UUID.randomUUID().toString());
        m.setPracticeId(practiceId);
        m.setQuestionId(questionId.trim());
        m.setSource("manual");
        m.setReason("manual");
        m.setStatus("open");
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        return dto(mistakes.save(m));
    }

    public MistakeDto setStatus(String practiceId, String mistakeId, String status) {
        if (!"open".equals(status) && !"resolved".equals(status)) {
            throw new IllegalArgumentException("状态只能是 open 或 resolved");
        }
        Mistake m = requireOwned(practiceId, mistakeId);
        m.setStatus(status);
        m.setResolvedAt("resolved".equals(status) ? System.currentTimeMillis() : null);
        m.setUpdatedAt(System.currentTimeMillis());
        return dto(mistakes.save(m));
    }

    public void delete(String practiceId, String mistakeId) {
        mistakes.deleteAll(List.of(requireOwned(practiceId, mistakeId)));
    }

    public List<MistakeDto> dtoList(String practiceId) {
        List<MistakeDto> out = new ArrayList<>();
        for (Mistake m : mistakes.findByPracticeIdOrderByCreatedAtDesc(practiceId)) out.add(dto(m));
        return out;
    }

    public long countOpenByPractice(String practiceId) {
        return mistakes.findByPracticeIdOrderByCreatedAtDesc(practiceId).stream()
                .filter(m -> "open".equals(m.getStatus()))
                .count();
    }

    /** 级联删除用（删练习/删阶段） */
    public void deleteByPractice(String practiceId) {
        mistakes.deleteAll(mistakes.findByPracticeIdOrderByCreatedAtDesc(practiceId));
    }

    private Mistake requireOwned(String practiceId, String mistakeId) {
        Mistake m = mistakes.findById(mistakeId).orElseThrow(() -> new NotFoundException("错题不存在"));
        if (!practiceId.equals(m.getPracticeId())) throw new NotFoundException("错题不存在");
        return m;
    }

    private static MistakeDto dto(Mistake m) {
        return new MistakeDto(m.getId(), m.getPracticeId(), m.getQuestionId(),
                m.getSource(), m.getReason(), m.getScore(), m.getStatus(),
                m.getCreatedAt(), m.getUpdatedAt(), m.getResolvedAt());
    }
}
