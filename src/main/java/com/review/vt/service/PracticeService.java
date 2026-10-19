package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.review.vt.domain.Attempt;
import com.review.vt.domain.Practice;
import com.review.vt.domain.Question;
import com.review.vt.repo.AttemptRepository;
import com.review.vt.repo.PracticeRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.repo.StageRepository;
import com.review.vt.web.Dtos.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 背八股练习链路：出题 → 作答+点评 → 看答案 → 练习总结（每轮只出一道题）。 */
@Service
public class PracticeService {

    private final StageRepository stages;
    private final PracticeRepository practices;
    private final QuestionRepository questions;
    private final AttemptRepository attempts;
    private final LlmService llm;

    public PracticeService(StageRepository stages, PracticeRepository practices,
                           QuestionRepository questions, AttemptRepository attempts, LlmService llm) {
        this.stages = stages;
        this.practices = practices;
        this.questions = questions;
        this.attempts = attempts;
        this.llm = llm;
    }

    public PracticeDto create(String stageId, String topic) {
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("知识点主题不能为空");
        if (!stages.existsById(stageId)) throw new NotFoundException("阶段不存在或已删除");
        long now = System.currentTimeMillis();
        Practice p = new Practice();
        p.setId(UUID.randomUUID().toString());
        p.setStageId(stageId);
        p.setMode("bagu");
        p.setTopic(topic.trim());
        p.setStatus("active");
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        practices.save(p);
        return Mappings.practice(p, Mappings.practiceStats(p, questions, attempts));
    }

    public PracticeDetailDto detail(String practiceId) {
        Practice p = get(practiceId);
        List<QuestionDto> qs = questions.findByPracticeIdOrderBySequenceAsc(p.getId()).stream()
                .map(q -> Mappings.question(q, attempts.findByQuestionIdOrderBySequenceAsc(q.getId())))
                .toList();
        return new PracticeDetailDto(p.getId(), p.getStageId(), p.getMode(), p.getTopic(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt(),
                Mappings.parseJson(p.getSummaryJson()),
                Mappings.practiceStats(p, questions, attempts), qs);
    }

    /** 出题：只输出一道题，参考全阶段所有会话历史，不断深入升级 */
    public QuestionDto nextQuestion(String practiceId) {
        Practice p = get(practiceId);
        if (p.getStatus().equals("completed")) throw new IllegalArgumentException("练习已结束，如需继续请重新打开");
        String history = questions.findByPracticeIdOrderBySequenceAsc(p.getId()).stream()
                .map(q -> q.getSequence() + ". " + q.getPrompt())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        String stageContext = stageContextText(p);
        JsonNode result = llm.chatJson(PromptTemplates.BAGU_SYSTEM,
                PromptTemplates.questionUser(p.getTopic(), history, stageContext));
        String prompt = result.path("prompt").asText("").trim();
        if (prompt.isBlank()) throw new LlmService.LlmException("AI 没有返回题面，请重试");

        Question q = new Question();
        q.setId(UUID.randomUUID().toString());
        q.setPracticeId(p.getId());
        q.setSequence((int) (questions.countByPracticeId(p.getId()) + 1));
        q.setPrompt(prompt);
        q.setCreatedAt(System.currentTimeMillis());
        questions.save(q);
        touch(p);
        return Mappings.question(q, List.of());
    }

    /** 看答案：只生成讲解，不创建作答、不评分（可重复看） */
    public QuestionDto reveal(String questionId) {
        Question q = getQuestion(questionId);
        if (q.getExplainedAt() == null) {
            JsonNode result = llm.chatJson(PromptTemplates.BAGU_SYSTEM, PromptTemplates.revealUser(q.getPrompt()));
            q.setExplanationDetail(result.path("detail").asText(""));
            q.setExplanationMemo(result.path("memorizationPoints").asText(""));
            q.setExplainedAt(System.currentTimeMillis());
            questions.save(q);
        }
        Practice p = get(q.getPracticeId());
        return Mappings.question(q, attempts.findByQuestionIdOrderBySequenceAsc(q.getId()));
    }

    /** 提交作答：原样保存，追加一条作答并生成针对性点评 */
    public QuestionDto submitAttempt(String questionId, String answer) {
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException("作答内容不能为空");
        Question q = getQuestion(questionId);
        Practice p = get(q.getPracticeId());
        if (p.getStatus().equals("completed")) throw new IllegalArgumentException("练习已结束，如需继续请重新打开");

        Attempt a = new Attempt();
        a.setId(UUID.randomUUID().toString());
        a.setQuestionId(q.getId());
        a.setSequence(attempts.findByQuestionIdOrderBySequenceAsc(q.getId()).size() + 1);
        a.setAnswer(answer);
        a.setSubmittedAt(System.currentTimeMillis());
        attempts.save(a);
        touch(p);

        JsonNode result = llm.chatJson(PromptTemplates.BAGU_SYSTEM, PromptTemplates.reviewUser(q.getPrompt(), answer));
        a.setScore(result.path("score").isNumber() ? result.path("score").doubleValue() : null);
        a.setFeedback(result.path("feedback").asText(""));
        ObjectNode dims = Mappings.MAPPER.createObjectNode();
        result.path("dimensions").fields().forEachRemaining(e -> dims.put(e.getKey(), e.getValue().asDouble()));
        a.setDimensionsJson(dims.toString());
        a.setEvaluationDetail(result.path("detail").asText(""));
        a.setEvaluationMemo(result.path("memorizationPoints").asText(""));
        a.setEvaluatedAt(System.currentTimeMillis());
        attempts.save(a);
        return Mappings.question(q, attempts.findByQuestionIdOrderBySequenceAsc(q.getId()));
    }

    /** 练习总结（结束练习） */
    public PracticeDto complete(String practiceId) {
        Practice p = get(practiceId);
        if (p.getStatus().equals("completed") && p.getSummaryJson() != null) {
            return Mappings.practice(p, Mappings.practiceStats(p, questions, attempts));
        }
        List<Question> qs = questions.findByPracticeIdOrderBySequenceAsc(p.getId());
        if (qs.isEmpty()) throw new IllegalArgumentException("练习还没有题目，先出题再结束");

        JsonNode result = llm.chatJson(PromptTemplates.BAGU_SYSTEM,
                PromptTemplates.practiceSummaryUser(p.getTopic(), recordsText(qs)));

        ObjectNode summary = Mappings.MAPPER.createObjectNode();
        summary.put("kind", "interview");
        summary.put("overall", result.path("overall").asText(""));
        ArrayNode strengths = summary.putArray("strengths");
        Mappings.stringList(result, "strengths").forEach(strengths::add);
        ArrayNode improvements = summary.putArray("improvements");
        Mappings.stringList(result, "improvements").forEach(improvements::add);
        summary.put("createdAt", System.currentTimeMillis());

        long now = System.currentTimeMillis();
        p.setSummaryJson(summary.toString());
        p.setStatus("completed");
        p.setCompletedAt(now);
        p.setUpdatedAt(now);
        practices.save(p);
        return Mappings.practice(p, Mappings.practiceStats(p, questions, attempts));
    }

    /* ---------- 内部 ---------- */

    private Practice get(String id) {
        return practices.findById(id).orElseThrow(() -> new NotFoundException("练习不存在或已删除"));
    }

    private Question getQuestion(String id) {
        return questions.findById(id).orElseThrow(() -> new NotFoundException("题目不存在或已删除"));
    }

    private void touch(Practice p) {
        p.setUpdatedAt(System.currentTimeMillis());
        practices.save(p);
    }

    /**
     * 汇总本阶段全部会话的题目/评分/点评/会话总结 + 阶段总结，
     * 让出题随练习量增加不断深入、升级难度（不只看当前会话）。
     */
    private String stageContextText(Practice current) {
        StringBuilder sb = new StringBuilder();
        List<Practice> all = practices.findByStageIdOrderByCreatedAtAsc(current.getStageId());
        // 题目行最多保留最近 40 条，控制 token；会话头与总结全保留
        List<String> questionLines = new java.util.ArrayList<>();
        int sessionNum = 0;
        for (Practice p : all) {
            sessionNum++;
            sb.append("【会话 ").append(sessionNum).append("】主题：").append(p.getTopic())
              .append(p.getStatus().equals("completed") ? "（已结束）" : "（进行中）").append('\n');
            for (Question q : questions.findByPracticeIdOrderBySequenceAsc(p.getId())) {
                StringBuilder line = new StringBuilder("  第 ").append(q.getSequence())
                        .append(" 题：").append(cut(q.getPrompt(), 120));
                // 取最近一次已评分作答的评价信号（不输出原始作答，控制 token）
                List<Attempt> as = attempts.findByQuestionIdOrderBySequenceAsc(q.getId());
                Attempt latest = null;
                for (Attempt a : as) {
                    if (a.getEvaluatedAt() != null && a.getScore() != null) latest = a;
                }
                if (latest != null) {
                    line.append("\n    最近评分：").append(latest.getScore())
                        .append("（维度：").append(Mappings.parseDimensions(latest.getDimensionsJson())).append("）")
                        .append("\n    点评：").append(cut(latest.getFeedback(), 200));
                } else if (!as.isEmpty()) {
                    line.append("（已作答未评分）");
                } else {
                    line.append("（未作答）");
                }
                questionLines.add(line.toString());
            }
            // 会话总结（跨会话薄弱点信号）
            if (p.getSummaryJson() != null && !p.getSummaryJson().isBlank()) {
                var summary = Mappings.parseJson(p.getSummaryJson());
                if (summary != null) {
                    sb.append("  本次会话总结：").append(cut(summary.path("overall").asText(""), 200));
                    List<String> improvements = Mappings.stringList(summary, "improvements");
                    if (!improvements.isEmpty()) {
                        sb.append("；改进：").append(String.join("；", improvements));
                    }
                    sb.append('\n');
                }
            }
        }
        // 追加题目行（保留最近 40 条）
        int skip = Math.max(0, questionLines.size() - 40);
        if (skip > 0) {
            sb.append("（中间省略 ").append(skip).append(" 道较早题目，仅保留最近 40 道）\n");
        }
        for (int i = skip; i < questionLines.size(); i++) {
            sb.append(questionLines.get(i)).append('\n');
        }
        // 阶段总结（最近一次）——回馈出题方向
        stages.findById(current.getStageId()).ifPresent(stage -> {
            var summary = Mappings.parseJson(stage.getSummaryJson());
            if (summary != null) {
                sb.append("阶段总结（最近生成）：\n");
                sb.append("  总体：").append(cut(summary.path("overall").asText(""), 200)).append('\n');
                List<String> weaknesses = Mappings.stringList(summary, "keyWeaknesses");
                if (!weaknesses.isEmpty()) sb.append("  重点不足：").append(String.join("；", weaknesses)).append('\n');
                List<String> nextSteps = Mappings.stringList(summary, "nextSteps");
                if (!nextSteps.isEmpty()) sb.append("  下一步：").append(String.join("；", nextSteps)).append('\n');
            }
        });
        return sb.toString().strip();
    }

    private String recordsText(List<Question> qs) {
        StringBuilder sb = new StringBuilder();
        for (Question q : qs) {
            sb.append("第 ").append(q.getSequence()).append(" 题：").append(q.getPrompt()).append('\n');
            List<Attempt> as = attempts.findByQuestionIdOrderBySequenceAsc(q.getId());
            if (as.isEmpty()) {
                sb.append("  （未作答）\n");
                continue;
            }
            for (Attempt a : as) {
                sb.append("  作答：").append(cut(a.getAnswer(), 300)).append('\n');
                if (a.getEvaluatedAt() != null && a.getScore() != null) {
                    sb.append("  评分：").append(a.getScore())
                      .append("（维度：").append(Mappings.parseDimensions(a.getDimensionsJson())).append("）\n");
                    sb.append("  点评：").append(cut(a.getFeedback(), 200)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
