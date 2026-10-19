package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.review.vt.domain.Attempt;
import com.review.vt.domain.Paper;
import com.review.vt.domain.Practice;
import com.review.vt.domain.Question;
import com.review.vt.domain.Stage;
import com.review.vt.repo.AttemptRepository;
import com.review.vt.repo.PaperRepository;
import com.review.vt.repo.PracticeRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.repo.StageRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 阶段总结：AI 给的反馈，不是实时统计。
 *
 * 两类东西分开看：
 * - **实时统计**（几张卷、多少题、平均分）——查询时现算，见 {@link Mappings#stageStats}
 * - **阶段总结**（这里）——把「做了哪些练习 / 记忆 / 平均分 / 每次考试的 AI 评价」
 *   按固定格式喂给 AI，让它给反馈；交卷后自动重新生成
 *
 * 这份反馈是「全复习」的唯一依据：复习范围就是总结里的 reviewScope（一长串文字）。
 * 阶段本身仍然只是「名字 + 分组」，总结是这个分组下的学习成果，不改变阶段的语义。
 */
@Service
public class StageSummaryService {

    /**
     * 阶段总结的输出含 reviewScope（一长串范围文字），8192 会被截断到非法 JSON。
     * 给足额度；同时提示词里限死了各段长度。
     */
    private static final int MAX_TOKENS = 16384;

    private final StageRepository stages;
    private final PracticeRepository practices;
    private final PaperRepository papers;
    private final QuestionRepository questions;
    private final AttemptRepository attempts;
    private final LlmService llm;

    public StageSummaryService(StageRepository stages, PracticeRepository practices, PaperRepository papers,
                               QuestionRepository questions, AttemptRepository attempts, LlmService llm) {
        this.stages = stages;
        this.practices = practices;
        this.papers = papers;
        this.questions = questions;
        this.attempts = attempts;
        this.llm = llm;
    }

    /**
     * 重新生成阶段总结（交卷后自动调用；也可手动重出）。
     *
     * 输出里有 reviewScope 这种长文本，8192 额度会被截断到连 `}` 都没有——
     * 表现就是「AI 返回的不是合法 JSON」。所以给足额度并重试一次。
     */
    public JsonNode regenerate(String stageId) {
        Stage s = stages.findById(stageId).orElseThrow(() -> new NotFoundException("阶段不存在或已删除"));
        List<Practice> ps = practices.findByStageIdOrderByCreatedAtAsc(stageId);
        if (ps.isEmpty()) throw new IllegalArgumentException("该阶段还没有练习，无法生成阶段总结");

        String user = PromptTemplates.stageSummaryUser(s.getName(), dataText(stageId));
        JsonNode result;
        try {
            result = llm.chatJson(PromptTemplates.INTERVIEW_SYSTEM, user, MAX_TOKENS, false);
        } catch (LlmService.LlmException first) {
            System.err.println("[myreview] 阶段总结生成失败，重试一次：" + first.getMessage());
            result = llm.chatJson(PromptTemplates.INTERVIEW_SYSTEM, user, MAX_TOKENS, false);
        }

        ObjectNode out = Mappings.MAPPER.createObjectNode();
        out.put("overall", MemoryRules.cut(Mappings.text(result, "overall"), 200));
        ArrayNode strengths = out.putArray("strengths");
        MemoryRules.tidy(Mappings.stringList(result, "strengths"), 5, 60).forEach(strengths::add);
        ArrayNode weak = out.putArray("keyWeaknesses");
        MemoryRules.tidy(Mappings.stringList(result, "keyWeaknesses"), 5, 60).forEach(weak::add);
        ArrayNode steps = out.putArray("nextSteps");
        MemoryRules.tidy(Mappings.stringList(result, "nextSteps"), 4, 80).forEach(steps::add);
        // 全复习的范围就取这里：必须是一长串连贯的文字，不是清单
        out.put("reviewScope", MemoryRules.cut(Mappings.text(result, "reviewScope"), 600));
        out.put("generatedAt", System.currentTimeMillis());

        s.setSummaryJson(out.toString());
        s.setUpdatedAt(System.currentTimeMillis());
        stages.save(s);
        return Mappings.parseJson(s.getSummaryJson());
    }

    /** 当前阶段总结；没有则 null */
    public JsonNode summary(String stageId) {
        return stages.findById(stageId)
                .map(s -> Mappings.parseJson(s.getSummaryJson()))
                .orElse(null);
    }

    /* ================= 固定格式的阶段数据 ================= */

    /**
     * 喂给 AI 的阶段数据，**固定格式**：每个练习一段，写清主题/范围/成绩/每卷的 AI 评价/记忆。
     * 这样 AI 才能基于真实过程给反馈，而不是只看几个平均数。
     */
    String dataText(String stageId) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Practice p : practices.findByStageIdOrderByCreatedAtAsc(stageId)) {
            i++;
            sb.append("练习 ").append(i).append("：《").append(p.getTopic()).append("》")
              .append("（").append("range".equals(p.getScopeMode()) ? "范围模式" : "主题模式").append("）")
              .append(p.isReview() ? "，这是系统发起的全复习" : "").append('\n');

            if ("range".equals(p.getScopeMode()) && p.getScopeText() != null && !p.getScopeText().isBlank()) {
                sb.append("  范围：").append(MemoryRules.cut(p.getScopeText(), 300)).append('\n');
            }

            List<Paper> ps = papers.findByPracticeIdOrderBySequenceAsc(p.getId());
            List<Double> scores = new ArrayList<>();
            int questionCount = 0;
            for (Paper paper : ps) {
                List<Question> qs = questions.findByPaperIdOrderBySequenceAsc(paper.getId());
                questionCount += qs.size();
                Double sc = paperScore(qs, paper);
                if (sc != null) scores.add(sc);
            }
            sb.append("  成绩：").append(ps.size()).append(" 张卷、").append(questionCount).append(" 道题");
            if (!scores.isEmpty()) {
                sb.append("，百分制平均 ").append(avg(scores)).append(" 分，最近一卷 ")
                  .append(scores.get(scores.size() - 1)).append(" 分");
            }
            sb.append('\n');

            // 每次考试的 AI 评价（这是阶段总结的关键输入）
            for (Paper paper : ps) {
                if (!"graded".equals(paper.getStatus())) {
                    sb.append("  卷 ").append(paper.getSequence()).append("：尚未交卷\n");
                    continue;
                }
                List<Question> qs = questions.findByPaperIdOrderBySequenceAsc(paper.getId());
                Double sc = paperScore(qs, paper);
                sb.append("  卷 ").append(paper.getSequence()).append("（得分 ")
                  .append(sc == null ? "-" : sc).append("/100）");
                JsonNode summary = Mappings.parseJson(paper.getSummaryJson());
                if (summary != null) {
                    sb.append("AI 评价：").append(MemoryRules.cut(Mappings.text(summary, "overall"), 160)).append('\n');
                } else {
                    sb.append('\n');
                }
            }

            JsonNode mem = Mappings.parseJson(p.getMemoryJson());
            if (mem != null) {
                sb.append("  长期记忆：");
                List<String> mastered = Mappings.stringList(mem, "mastered");
                List<String> weak = Mappings.stringList(mem, "weakPoints");
                List<String> cov = Mappings.stringList(mem, "coverage");
                if (!mastered.isEmpty()) sb.append("已掌握：").append(String.join("、", mastered)).append("；");
                if (!weak.isEmpty()) sb.append("薄弱点：").append(String.join("、", weak)).append("；");
                if (!cov.isEmpty()) sb.append("已覆盖：").append(String.join("、", cov));
                sb.append('\n');
            }

            JsonNode dig = Mappings.parseJson(p.getDigestJson());
            if (dig != null) {
                sb.append("  会话摘要：").append(MemoryRules.cut(Mappings.text(dig, "overview"), 150)).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /* ---------- 内部 ---------- */

    private Double paperScore(List<Question> qs, Paper paper) {
        if (!"graded".equals(paper.getStatus())) return null;
        List<Double> earned = new ArrayList<>();
        boolean any = false;
        for (Question q : qs) {
            Double e = null;
            for (Attempt a : attempts.findByQuestionIdOrderBySequenceAsc(q.getId())) {
                if (a.getEvaluatedAt() != null && a.getPoints() != null) {
                    e = a.getPoints();
                    any = true;
                }
            }
            earned.add(e);
        }
        return any ? Scoring.paperScore(earned) : null;
    }

    private static double avg(List<Double> values) {
        double sum = 0;
        for (Double v : values) sum += v;
        return Math.round(sum / values.size() * 10) / 10.0;
    }
}
