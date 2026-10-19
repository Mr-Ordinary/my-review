package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.review.vt.domain.Attempt;
import com.review.vt.domain.Practice;
import com.review.vt.domain.Question;
import com.review.vt.domain.Stage;
import com.review.vt.repo.AttemptRepository;
import com.review.vt.repo.PracticeRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.repo.StageRepository;
import com.review.vt.web.Dtos.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 阶段（工作区）：创建、列表、详情、删除、阶段总结。 */
@Service
public class StageService {

    private final StageRepository stages;
    private final PracticeRepository practices;
    private final QuestionRepository questions;
    private final AttemptRepository attempts;
    private final LlmService llm;

    public StageService(StageRepository stages, PracticeRepository practices,
                        QuestionRepository questions, AttemptRepository attempts, LlmService llm) {
        this.stages = stages;
        this.practices = practices;
        this.questions = questions;
        this.attempts = attempts;
        this.llm = llm;
    }

    public List<StageDto> list() {
        return stages.findAllByOrderByUpdatedAtDesc().stream()
                .map(s -> Mappings.stage(s, statsOf(s)))
                .toList();
    }

    public StageDto create(String name, String goal) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("阶段名称不能为空");
        long now = System.currentTimeMillis();
        Stage s = new Stage();
        s.setId(UUID.randomUUID().toString());
        s.setName(name.trim());
        s.setGoal(goal == null || goal.isBlank() ? null : goal.trim());
        s.setStatus("active");
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        stages.save(s);
        return Mappings.stage(s, statsOf(s));
    }

    public StageDetailDto detail(String id) {
        Stage s = get(id);
        List<PracticeDto> ps = practices.findByStageIdOrderByCreatedAtDesc(s.getId()).stream()
                .map(p -> Mappings.practice(p, Mappings.practiceStats(p, questions, attempts)))
                .toList();
        return Mappings.stageDetail(s, statsOf(s), ps);
    }

    public void delete(String id) {
        Stage s = get(id);
        for (Practice p : practices.findByStageIdOrderByCreatedAtAsc(s.getId())) {
            for (Question q : questions.findByPracticeIdOrderBySequenceAsc(p.getId())) {
                attempts.deleteAll(attempts.findByQuestionIdOrderBySequenceAsc(q.getId()));
            }
            questions.deleteAll(questions.findByPracticeIdOrderBySequenceAsc(p.getId()));
        }
        practices.deleteAll(practices.findByStageIdOrderByCreatedAtAsc(s.getId()));
        stages.delete(s);
    }

    /**
     * 阶段总结：统计与各维度平均分由后端计算（事实来源），总体评价/优势/重点不足/下一步由 AI 基于真实数据生成。
     */
    public StageDetailDto generateSummary(String id) {
        Stage s = get(id);
        List<Practice> ps = practices.findByStageIdOrderByCreatedAtAsc(s.getId());
        List<Question> allQuestions = new ArrayList<>();
        List<Attempt> allAttempts = new ArrayList<>();
        for (Practice p : ps) {
            for (Question q : questions.findByPracticeIdOrderBySequenceAsc(p.getId())) {
                allQuestions.add(q);
                allAttempts.addAll(attempts.findByQuestionIdOrderBySequenceAsc(q.getId()));
            }
        }
        if (allQuestions.isEmpty()) {
            throw new IllegalArgumentException("阶段内还没有练习数据，先做练习再生成总结");
        }

        Map<String, Double> dimensionScores = averageDimensions(allAttempts);
        JsonNode llmResult = llm.chatJson(PromptTemplates.STAGE_SUMMARY_SYSTEM,
                PromptTemplates.stageSummaryUser(s.getName(), s.getGoal(), dataText(ps)));

        ObjectNode summary = Mappings.MAPPER.createObjectNode();
        summary.put("overall", llmResult.path("overall").asText(""));
        ArrayNode strengths = summary.putArray("strengths");
        Mappings.stringList(llmResult, "strengths").forEach(strengths::add);
        ArrayNode weaknesses = summary.putArray("keyWeaknesses");
        Mappings.stringList(llmResult, "keyWeaknesses").forEach(weaknesses::add);
        ArrayNode nextSteps = summary.putArray("nextSteps");
        Mappings.stringList(llmResult, "nextSteps").forEach(nextSteps::add);
        ObjectNode dims = summary.putObject("dimensionScores");
        dimensionScores.forEach(dims::put);
        summary.put("generatedAt", System.currentTimeMillis());

        s.setSummaryJson(summary.toString());
        s.setSummaryAt(System.currentTimeMillis());
        s.setUpdatedAt(System.currentTimeMillis());
        stages.save(s);
        return detail(id);
    }

    /* ---------- 内部 ---------- */

    private Stage get(String id) {
        return stages.findById(id).orElseThrow(() -> new NotFoundException("阶段不存在或已删除"));
    }

    private Stats statsOf(Stage s) {
        List<Practice> ps = practices.findByStageIdOrderByCreatedAtAsc(s.getId());
        return Mappings.stageStats(ps, questions, attempts);
    }

    /** 各维度平均分（仅统计已评分作答，保留 1 位小数） */
    private Map<String, Double> averageDimensions(List<Attempt> allAttempts) {
        Map<String, double[]> acc = new LinkedHashMap<>(); // [sum, count]
        for (Attempt a : allAttempts) {
            if (a.getEvaluatedAt() == null) continue;
            Mappings.parseDimensions(a.getDimensionsJson()).forEach((k, v) -> {
                double[] cur = acc.computeIfAbsent(k, x -> new double[2]);
                cur[0] += v;
                cur[1] += 1;
            });
        }
        Map<String, Double> result = new LinkedHashMap<>();
        acc.forEach((k, v) -> result.put(k, v[1] == 0 ? 0.0 : Math.round(v[0] / v[1] * 10) / 10.0));
        return result;
    }

    /** 把阶段内的题目/作答/评分/点评整理成给 AI 的数据文本（截断控制 token） */
    private String dataText(List<Practice> ps) {
        StringBuilder sb = new StringBuilder();
        for (Practice p : ps) {
            sb.append("【练习】主题：").append(p.getTopic())
              .append(p.getStatus().equals("completed") ? "（已结束）" : "（进行中）").append('\n');
            int i = 0;
            for (Question q : questions.findByPracticeIdOrderBySequenceAsc(p.getId())) {
                i++;
                sb.append("  第 ").append(i).append(" 题：").append(cut(q.getPrompt(), 120)).append('\n');
                for (Attempt a : attempts.findByQuestionIdOrderBySequenceAsc(q.getId())) {
                    sb.append("    作答：").append(cut(a.getAnswer(), 300)).append('\n');
                    if (a.getEvaluatedAt() != null && a.getScore() != null) {
                        sb.append("    评分：").append(a.getScore())
                          .append("（维度：").append(Mappings.parseDimensions(a.getDimensionsJson())).append("）\n");
                        sb.append("    点评：").append(cut(a.getFeedback(), 200)).append('\n');
                    } else {
                        sb.append("    评分：（未评分）\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
