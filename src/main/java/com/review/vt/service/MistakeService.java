package com.review.vt.service;

import com.review.vt.pojo.entity.Attempt;
import com.review.vt.pojo.entity.Mistake;
import com.review.vt.pojo.entity.Question;
import com.review.vt.mapper.AttemptMapper;
import com.review.vt.mapper.MistakeMapper;
import com.review.vt.mapper.QuestionMapper;
import com.review.vt.pojo.dto.MistakeDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.review.vt.common.util.BatchWriter;
import com.review.vt.common.util.MappingsUtil;
import com.review.vt.common.util.LimitsUtil;
import com.review.vt.web.ex.NotFoundException;

/**
 * 错题本：per-practice，一题一条。
 *
 * 收录口径只有一处（{@link #isMistake}）：低分 / 未作答 / 看过参考答案。
 * 手动收录优先——自动收录永不覆盖或删除已有行（手动收的题即使后来考好了也留在本子里）。
 */
@Service
public class MistakeService {

    private final MistakeMapper mistakeMapper;
    private final QuestionMapper questionMapper;
    private final AttemptMapper attemptMapper;

    public MistakeService(MistakeMapper mistakeMapper, QuestionMapper questionMapper, AttemptMapper attemptMapper) {
        this.mistakeMapper = mistakeMapper;
        this.questionMapper = questionMapper;
        this.attemptMapper = attemptMapper;
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
    public static List<Mistake> buildAutoMistakes(Long practiceId, List<Question> qs,
            Map<Long, Attempt> latest, List<Mistake> existing, long now) {
        Map<Long, Mistake> byQuestion = new LinkedHashMap<>();
        for (Mistake m : existing) byQuestion.put(m.getQuestionId(), m);
        List<Mistake> out = new ArrayList<>();
        for (Question q : qs) {
            if (byQuestion.containsKey(q.getId())) continue;   // 已收录（含手动）不动
            Attempt a = latest.get(q.getId());
            boolean answered = a != null;
            boolean revealed = q.getRevealedAt() != null;
            Double score = answered ? a.getScore() : null;
            if (!isMistake(score, answered, revealed)) continue;
            if (existing.size() + out.size() >= LimitsUtil.MAX_MISTAKES_PER_PRACTICE) break;
            Mistake m = new Mistake();
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

    /** 交卷批改后的自动收录。整卷批改完一次 insertByList（严禁循环插入）；调用方容错——失败绝不卡批改 */
    public void autoCollect(Long practiceId, List<Question> qs, Map<Long, Attempt> latest) {
        List<Mistake> existing = mistakeMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId);
        long now = System.currentTimeMillis();
        List<Mistake> fresh = buildAutoMistakes(practiceId, qs, latest, existing, now);
        if (fresh.isEmpty()) return;
        Long uid = com.review.vt.auth.AuthContext.require().userId();
        for (Mistake m : fresh) {
            if (m.getUserId() == null) m.setUserId(uid);
        }
        BatchWriter.write(fresh, mistakeMapper::insertByList);
    }

    /** 手动收录 / 重新打开已订正的行 */
    public MistakeDto addManual(Long practiceId, Long questionId) {
        if (questionId == null) throw new IllegalArgumentException("缺少题目 id");
        Question q = questionMapper.findById(questionId)
                .orElseThrow(() -> new NotFoundException("题目不存在或不属于这个练习"));
        if (!practiceId.equals(q.getPracticeId())) {
            throw new NotFoundException("题目不存在或不属于这个练习");
        }
        long now = System.currentTimeMillis();
        for (Mistake m : mistakeMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId)) {
            if (questionId.equals(m.getQuestionId())) {
                // 已有行：重新打开（手动想再盯住它）
                m.setStatus("open");
                m.setResolvedAt(null);
                m.setUpdatedAt(now);
                return dto(mistakeMapper.save(m));
            }
        }
        if (mistakeMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId).size() >= LimitsUtil.MAX_MISTAKES_PER_PRACTICE) {
            throw new IllegalArgumentException("一个练习最多 " + LimitsUtil.MAX_MISTAKES_PER_PRACTICE + " 条错题");
        }
        Mistake m = new Mistake();
        m.setPracticeId(practiceId);
        m.setQuestionId(questionId);
        m.setSource("manual");
        m.setReason("manual");
        m.setStatus("open");
        m.setCreatedAt(now);
        m.setUpdatedAt(now);
        return dto(mistakeMapper.save(m));
    }

    public MistakeDto setStatus(Long practiceId, Long mistakeId, String status) {
        if (!"open".equals(status) && !"resolved".equals(status)) {
            throw new IllegalArgumentException("状态只能是 open 或 resolved");
        }
        Mistake m = requireOwned(practiceId, mistakeId);
        m.setStatus(status);
        m.setResolvedAt("resolved".equals(status) ? System.currentTimeMillis() : null);
        m.setUpdatedAt(System.currentTimeMillis());
        return dto(mistakeMapper.save(m));
    }

    public void delete(Long practiceId, Long mistakeId) {
        mistakeMapper.deleteAll(List.of(requireOwned(practiceId, mistakeId)));
    }

    public List<MistakeDto> dtoList(Long practiceId) {
        List<MistakeDto> out = new ArrayList<>();
        for (Mistake m : mistakeMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId)) out.add(dto(m));
        return out;
    }

    public long countOpenByPractice(Long practiceId) {
        return mistakeMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId).stream()
                .filter(m -> "open".equals(m.getStatus()))
                .count();
    }

    /** 阶段详情链：批量未解决错题数（一条 IN 查询，Java 端按练习分组计数），替代逐练习 countOpenByPractice */
    public Map<Long, Long> openCountsByPractices(List<Long> practiceIds) {
        Map<Long, Long> out = new LinkedHashMap<>();
        for (Long practiceId : practiceIds) out.put(practiceId, 0L);
        for (Long practiceId : mistakeMapper.findOpenByPracticeIds(practiceIds)) {
            out.merge(practiceId, 1L, Long::sum);
        }
        return out;
    }

    /** 级联删除用（删练习/删阶段） */
    public void deleteByPractice(Long practiceId) {
        mistakeMapper.deleteAll(mistakeMapper.findByPracticeIdOrderByCreatedAtDesc(practiceId));
    }

    private Mistake requireOwned(Long practiceId, Long mistakeId) {
        Mistake m = mistakeMapper.findById(mistakeId).orElseThrow(() -> new NotFoundException("错题不存在"));
        if (!practiceId.equals(m.getPracticeId())) throw new NotFoundException("错题不存在");
        return m;
    }

    /** 题目/作答/点评快照随条目下发：刷题题不在考卷列表里，前端查不到题面，必须自带 */
    private MistakeDto dto(Mistake m) {
        String prompt = null, answer = null, feedback = null;
        Question q = questionMapper.findById(m.getQuestionId()).orElse(null);
        if (q != null) {
            prompt = q.getPrompt();
            List<Attempt> as = attemptMapper.findByQuestionIdOrderBySequenceAsc(q.getId());
            if (!as.isEmpty()) {
                Attempt a = as.get(as.size() - 1);
                answer = a.getAnswer();
                feedback = a.getFeedback();
            }
        }
        return new MistakeDto(MappingsUtil.id(m.getId()), MappingsUtil.id(m.getPracticeId()), MappingsUtil.id(m.getQuestionId()),
                m.getSource(), m.getReason(), m.getScore(), m.getStatus(),
                m.getCreatedAt(), m.getUpdatedAt(), m.getResolvedAt(),
                prompt, answer, feedback);
    }
}
