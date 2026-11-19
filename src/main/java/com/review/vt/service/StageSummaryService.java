package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.review.vt.pojo.entity.Attempt;
import com.review.vt.pojo.entity.Paper;
import com.review.vt.pojo.entity.Practice;
import com.review.vt.pojo.entity.Question;
import com.review.vt.pojo.entity.Stage;
import com.review.vt.mapper.AttemptMapper;
import com.review.vt.mapper.PaperMapper;
import com.review.vt.mapper.PracticeMapper;
import com.review.vt.mapper.QuestionMapper;
import com.review.vt.mapper.StageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import com.review.vt.common.util.MappingsUtil;
import com.review.vt.common.util.MemoryRulesUtil;
import com.review.vt.common.util.ScoringUtil;
import com.review.vt.common.util.PromptTemplatesUtil;
import com.review.vt.web.ex.NotFoundException;
import com.review.vt.web.ex.LlmException;
import com.review.vt.common.config.LlmConfig;

/**
 * 阶段总结：AI 给的反馈，不是实时统计。
 *
 * 两类东西分开看：
 * - **实时统计**（几张卷、多少题、平均分）——查询时现算，见 {@link MappingsUtil#stageStats}
 * - **阶段总结**（这里）——把「做了哪些练习 / 记忆 / 平均分 / 每次考试的 AI 评价」
 *   按固定格式喂给 AI，让它给反馈；交卷后自动重新生成
 *
 * 这份反馈是「全复习」的唯一依据：复习范围就是总结里的 reviewScope（一长串文字）。
 * 阶段本身仍然只是「名字 + 分组」，总结是这个分组下的学习成果，不改变阶段的语义。
 */
@Service
public class StageSummaryService {

    private static final Logger log = LoggerFactory.getLogger(StageSummaryService.class);

    /**
     * 阶段总结的输出含 reviewScope（一长串范围文字），8192 会被截断到非法 JSON。
     * 给足额度；同时提示词里限死了各段长度。
     */
    private static final int MAX_TOKENS = 16384;

    private final StageMapper stageMapper;
    private final PracticeMapper practiceMapper;
    private final PaperMapper paperMapper;
    private final QuestionMapper questionMapper;
    private final AttemptMapper attemptMapper;
    private final LlmService llmService;
    private final DataCacheService dataCache;

    public StageSummaryService(StageMapper stageMapper, PracticeMapper practiceMapper, PaperMapper paperMapper,
                               QuestionMapper questionMapper, AttemptMapper attemptMapper, LlmService llmService,
                               DataCacheService dataCache) {
        this.stageMapper = stageMapper;
        this.practiceMapper = practiceMapper;
        this.paperMapper = paperMapper;
        this.questionMapper = questionMapper;
        this.attemptMapper = attemptMapper;
        this.llmService = llmService;
        this.dataCache = dataCache;
    }

    /**
     * 重新生成阶段总结（交卷后自动调用；也可手动重出）。
     *
     * 输出里有 reviewScope 这种长文本，8192 额度会被截断到连 `}` 都没有——
     * 表现就是「AI 返回的不是合法 JSON」。所以给足额度并重试一次。
     */
    public JsonNode regenerate(Long stageId) {
        return regenerate(stageId, null);
    }

    /**
     * @param cfg 交卷后台线程调用时传入任务发起时解密的配置快照；
     *            为 null（请求线程手动重出）则现场取当前用户快照
     */
    public JsonNode regenerate(Long stageId, LlmConfig cfg) {
        Stage s = stageMapper.findById(stageId).orElseThrow(() -> new NotFoundException("阶段不存在或已删除"));
        List<Practice> ps = practiceMapper.findByStageIdOrderByCreatedAtAsc(stageId);
        if (ps.isEmpty()) throw new IllegalArgumentException("该阶段还没有练习，无法生成阶段总结");

        String user = PromptTemplatesUtil.stageSummaryUser(s.getName(), dataText(stageId));
        JsonNode result;
        try {
            result = chat(cfg, PromptTemplatesUtil.INTERVIEW_SYSTEM, user, MAX_TOKENS);
        } catch (LlmException first) {
            log.warn("阶段总结生成失败，重试一次：{}", first.getMessage());
            result = chat(cfg, PromptTemplatesUtil.INTERVIEW_SYSTEM, user, MAX_TOKENS);
        }

        ObjectNode out = MappingsUtil.MAPPER.createObjectNode();
        out.put("overall", MemoryRulesUtil.cut(MappingsUtil.text(result, "overall"), 200));
        ArrayNode strengths = out.putArray("strengths");
        MemoryRulesUtil.tidy(MappingsUtil.stringList(result, "strengths"), 5, 60).forEach(strengths::add);
        ArrayNode weak = out.putArray("keyWeaknesses");
        MemoryRulesUtil.tidy(MappingsUtil.stringList(result, "keyWeaknesses"), 5, 60).forEach(weak::add);
        ArrayNode steps = out.putArray("nextSteps");
        MemoryRulesUtil.tidy(MappingsUtil.stringList(result, "nextSteps"), 4, 80).forEach(steps::add);
        // 全复习的范围就取这里：必须是一长串连贯的文字，不是清单
        out.put("reviewScope", MemoryRulesUtil.cut(MappingsUtil.text(result, "reviewScope"), 600));
        out.put("generatedAt", System.currentTimeMillis());

        s.setSummaryJson(out.toString());
        s.setUpdatedAt(System.currentTimeMillis());
        // 阶段行写路径双删：保存前后各清一次 stage:{id}（兜住保存期间的并发回填）
        dataCache.evictStage(stageId);
        stageMapper.save(s);
        dataCache.evictStage(stageId);
        return MappingsUtil.parseJson(s.getSummaryJson());
    }

    /** 当前阶段总结；没有则 null */
    public JsonNode summary(Long stageId) {
        return stageMapper.findById(stageId)
                .map(s -> MappingsUtil.parseJson(s.getSummaryJson()))
                .orElse(null);
    }

    /** cfg 为 null 时走 LlmService 的当前用户快照路径 */
    private JsonNode chat(LlmConfig cfg, String system, String user, int maxTokens) {
        return cfg == null ? llmService.chatJson(system, user, maxTokens, false)
                : llmService.chatJson(cfg, system, user, maxTokens, false);
    }

    /* ================= 固定格式的阶段数据 ================= */

    /**
     * 喂给 AI 的阶段数据，**固定格式**：每个练习一段，写清主题/范围/成绩/每卷的 AI 评价/记忆。
     * 这样 AI 才能基于真实过程给反馈，而不是只看几个平均数。
     */
    String dataText(Long stageId) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Practice p : practiceMapper.findByStageIdOrderByCreatedAtAsc(stageId)) {
            i++;
            sb.append("练习 ").append(i).append("：《").append(p.getTopic()).append("》")
              .append("（").append("range".equals(p.getScopeMode()) ? "范围模式" : "主题模式").append("）")
              .append(p.isReview() ? "，这是系统发起的全复习" : "").append('\n');

            if ("range".equals(p.getScopeMode()) && p.getScopeText() != null && !p.getScopeText().isBlank()) {
                sb.append("  范围：").append(MemoryRulesUtil.cut(p.getScopeText(), 300)).append('\n');
            }

            List<Paper> ps = paperMapper.findByPracticeIdOrderBySequenceAsc(p.getId());
            List<Double> scores = new ArrayList<>();
            int questionCount = 0;
            for (Paper paper : ps) {
                List<Question> qs = questionMapper.findByPaperIdOrderBySequenceAsc(paper.getId());
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
                List<Question> qs = questionMapper.findByPaperIdOrderBySequenceAsc(paper.getId());
                Double sc = paperScore(qs, paper);
                sb.append("  卷 ").append(paper.getSequence()).append("（得分 ")
                  .append(sc == null ? "-" : sc).append("/100）");
                JsonNode summary = MappingsUtil.parseJson(paper.getSummaryJson());
                if (summary != null) {
                    sb.append("AI 评价：").append(MemoryRulesUtil.cut(MappingsUtil.text(summary, "overall"), 160)).append('\n');
                } else {
                    sb.append('\n');
                }
            }

            JsonNode mem = MappingsUtil.parseJson(p.getMemoryJson());
            if (mem != null) {
                sb.append("  长期记忆：");
                List<String> mastered = MappingsUtil.stringList(mem, "mastered");
                List<String> weak = MappingsUtil.stringList(mem, "weakPoints");
                List<String> cov = MappingsUtil.stringList(mem, "coverage");
                if (!mastered.isEmpty()) sb.append("已掌握：").append(String.join("、", mastered)).append("；");
                if (!weak.isEmpty()) sb.append("薄弱点：").append(String.join("、", weak)).append("；");
                if (!cov.isEmpty()) sb.append("已覆盖：").append(String.join("、", cov));
                sb.append('\n');
            }

            JsonNode dig = MappingsUtil.parseJson(p.getDigestJson());
            if (dig != null) {
                sb.append("  会话摘要：").append(MemoryRulesUtil.cut(MappingsUtil.text(dig, "overview"), 150)).append('\n');
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
            for (Attempt a : attemptMapper.findByQuestionIdOrderBySequenceAsc(q.getId())) {
                if (a.getEvaluatedAt() != null && a.getPoints() != null) {
                    e = a.getPoints();
                    any = true;
                }
            }
            earned.add(e);
        }
        return any ? ScoringUtil.paperScore(earned) : null;
    }

    private static double avg(List<Double> values) {
        double sum = 0;
        for (Double v : values) sum += v;
        return Math.round(sum / values.size() * 10) / 10.0;
    }
}
