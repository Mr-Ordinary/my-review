package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.vt.domain.Attempt;
import com.review.vt.domain.Paper;
import com.review.vt.domain.Practice;
import com.review.vt.domain.Question;
import com.review.vt.domain.Stage;
import com.review.vt.repo.AttemptRepository;
import com.review.vt.repo.PaperRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.web.Dtos.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实体 → DTO 映射与百分制统计（个人数据规模，直接循环统计）。 */
final class Mappings {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Mappings() {}

    /** 实体 Long 主键 → 前端字符串（保持 API 契约不变） */
    public static String id(Long v) { return v == null ? null : String.valueOf(v); }

    static JsonNode parseJson(String s) {
        if (s == null || s.isBlank()) return null;
        try { return MAPPER.readTree(s); } catch (Exception e) { return null; }
    }

    static Map<String, Double> parseDimensions(String s) {
        JsonNode node = parseJson(s);
        if (node == null || !node.isObject()) return Map.of();
        Map<String, Double> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asDouble()));
        return map;
    }

    /** JSON 字符串数组 → List<String>（解析失败返回空列表） */
    static List<String> parseStringList(String s) {
        JsonNode node = parseJson(s);
        if (node == null || !node.isArray()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        node.forEach(x -> list.add(x.asText()));
        return list;
    }

    /** 取字符串字段：模型偶尔给出非文本节点，此时退回节点文本，绝不返回 null */
    static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.path(field);
        if (v == null || v.isMissingNode() || v.isNull()) return "";
        String s = v.isTextual() ? v.asText("") : v.toString();
        return normalizeNewlines(s);
    }

    /**
     * 归一化换行：模型有时把换行双重转义（JSON 里是 \\n，解析后是字面 \n），
     * 这里统一转成真换行；顺带归一化 Windows 的 \r\n。
     */
    static String normalizeNewlines(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", "\t").replace("\r\n", "\n");
    }

    static List<String> stringList(JsonNode node, String field) {
        List<String> list = new ArrayList<>();
        JsonNode arr = node == null ? null : node.path(field);
        if (arr == null || arr.isMissingNode() || arr.isNull()) return list;
        if (arr.isArray()) {
            arr.forEach(x -> {
                String s = x.isTextual() ? x.asText("") : x.toString();
                if (!s.isBlank()) list.add(s);
            });
        } else if (arr.isTextual()) {
            // 模型偶尔把数组给成一个逗号分隔的字符串
            for (String s : arr.asText("").split("[、,，;；]")) {
                if (!s.isBlank()) list.add(s.trim());
            }
        }
        return list;
    }

    /** 只取前 n 个（保序）；用于把「一题 1-2 个知识点」这类约束在数据层卡死，不指望模型自觉 */
    static List<String> cap(List<String> values, int n) {
        if (values == null || values.size() <= n) return values == null ? List.of() : values;
        return new ArrayList<>(values.subList(0, n));
    }

    static String writeStringList(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    static String writeJson(JsonNode node) {
        try {
            return node == null ? null : MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------- 实体 → DTO ---------- */

    static StageDto stage(Stage s, Stats stats) {
        return new StageDto(id(s.getId()), s.getName(), s.getStatus(),
                s.getCreatedAt(), s.getUpdatedAt(), s.getCompletedAt(), parseJson(s.getSummaryJson()), stats);
    }

    static StageDetailDto stageDetail(Stage s, Stats stats, List<PracticeDto> practices) {
        return new StageDetailDto(id(s.getId()), s.getName(), s.getStatus(),
                s.getCreatedAt(), s.getUpdatedAt(), s.getCompletedAt(),
                parseJson(s.getSummaryJson()), stats, practices);
    }

    static PracticeDto practice(Practice p, Stats stats, int noteCount, int openMistakeCount) {
        return new PracticeDto(id(p.getId()), id(p.getStageId()), p.getMode(), p.getTopic(),
                p.getScopeMode(), p.getScopeText(), p.getLevel(), Intensities.normalize(p.getIntensity()),
                p.isReview(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt(),
                parseJson(p.getMemoryJson()), parseJson(p.getDigestJson()), stats,
                noteCount, openMistakeCount);
    }

    static PracticeDetailDto practiceDetail(Practice p, Stats stats,
            String notesText, Long notesUpdatedAt, List<MistakeDto> mistakes, List<PaperDto> papers) {
        return new PracticeDetailDto(id(p.getId()), id(p.getStageId()), p.getMode(), p.getTopic(),
                p.getScopeMode(), p.getScopeText(), p.getLevel(), Intensities.normalize(p.getIntensity()),
                p.isReview(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt(),
                parseJson(p.getMemoryJson()), parseJson(p.getDigestJson()), stats,
                notesText, notesUpdatedAt, mistakes, papers);
    }

    static PaperDto paper(Paper paper, Stats stats, List<QuestionDto> questions) {
        double score = Scoring.paperScore(earnedOf(paper, questions));
        boolean graded = "graded".equals(paper.getStatus());
        return new PaperDto(id(paper.getId()), paper.getSequence(),
                paper.getKind() == null ? "normal" : paper.getKind(), paper.getStatus(),
                CompositionModes.normalize(paper.getCompositionMode()),
                paper.getCreatedAt(), paper.getSubmittedAt(),
                paper.getMaxScore() > 0 ? paper.getMaxScore() : Scoring.PAPER_MAX,
                graded ? score : null,
                parseJson(paper.getSummaryJson()), parseJson(paper.getProgressJson()), stats, questions);
    }

    static QuestionDto question(Question q, List<Attempt> attempts) {
        // 问点**从题面实时抽取**，不读历史 demandsJson——
        // 旧数据的要求可能已经漂移（题面问 2 点、要求多出/漏掉），以题面为准。
        // 例外：考试模式的简答/代码题以出题时落库的评分要点为准（题面不一定列 ①②③）；
        // 客观题（单选/多选/填空）机器判分，没有批改清单。
        List<String> demands;
        if (q.getQtype() == null) {
            demands = PracticeService.extractAsks(q.getPrompt());
        } else if ("short".equals(q.getQtype()) || "code".equals(q.getQtype())) {
            List<String> stored = parseStringList(q.getDemandsJson());
            demands = stored.isEmpty() ? PracticeService.extractAsks(q.getPrompt()) : stored;
        } else {
            demands = List.of();
        }
        return new QuestionDto(id(q.getId()), id(q.getPaperId()), q.getSequence(), q.getPrompt(),
                q.getReferenceAnswer(), parseStringList(q.getAssignedPointsJson()), q.getAngle(),
                parseStringList(q.getKnowledgePointsJson()), demands,
                q.getTier(), q.getQuestionType(), q.getPoints(),
                q.getQtype(), q.getOptionsJson(), q.getAnswerJson(),
                q.getCreatedAt(), q.getRevealedAt(), attempts.stream().map(Mappings::attempt).toList());
    }

    static AttemptDto attempt(Attempt a) {
        EvaluationDto evaluation = a.getEvaluatedAt() == null ? null
                : new EvaluationDto(a.getScore(), a.getPoints(), a.getFeedback(),
                        parseDimensions(a.getDimensionsJson()), a.getEvaluatedAt());
        return new AttemptDto(id(a.getId()), a.getSequence(), a.getAnswer(), a.getSubmittedAt(), evaluation);
    }

    /* ---------- 百分制统计 ---------- */

    /** 一张卷的各题实得分（每题取最近一次已评分作答），用于算卷得分 */
    private static List<Double> earnedOf(Paper paper, List<QuestionDto> questions) {
        List<Double> earned = new ArrayList<>();
        for (QuestionDto q : questions) {
            Double e = null;
            for (AttemptDto a : q.attempts()) {
                if (a.evaluation() != null && a.evaluation().points() != null) e = a.evaluation().points();
            }
            earned.add(e);
        }
        return earned;
    }

    /** 一张卷的得分（满分 100）；未批改返回 null */
    static Double paperScore(List<Question> questions, AttemptRepository aRepo, Paper paper) {
        if (paper != null && !"graded".equals(paper.getStatus())) return null;
        List<Double> earned = new ArrayList<>();
        boolean any = false;
        for (Question q : questions) {
            Double e = null;
            for (Attempt a : aRepo.findByQuestionIdOrderBySequenceAsc(q.getId())) {
                if (a.getEvaluatedAt() != null && a.getPoints() != null) {
                    e = a.getPoints();
                    any = true;
                }
            }
            earned.add(e);
        }
        return any ? Scoring.paperScore(earned) : null;
    }

    /** 卷列表里剔除刷题卷：它是常开的练习流，不是「一张考卷」 */
    static List<Paper> realPapers(List<Paper> ps) {
        List<Paper> out = new ArrayList<>();
        for (Paper paper : ps) {
            if (!"drill".equals(paper.getKind())) out.add(paper);
        }
        return out;
    }

    /** 一次练习的统计 */
    static Stats practiceStats(Practice p, QuestionRepository qRepo, AttemptRepository aRepo,
                               PaperRepository pRepo) {
        List<Paper> ps = realPapers(pRepo.findByPracticeIdOrderBySequenceAsc(p.getId()));
        List<Question> all = new ArrayList<>();
        for (Paper paper : ps) all.addAll(qRepo.findByPaperIdOrderBySequenceAsc(paper.getId()));
        return stats(1, ps.size(), all, aRepo, ps, qRepo);
    }

    /** 一张卷的统计 */
    static Stats paperStats(Paper paper, QuestionRepository qRepo, AttemptRepository aRepo) {
        List<Question> qs = qRepo.findByPaperIdOrderBySequenceAsc(paper.getId());
        return stats(0, 1, qs, aRepo, List.of(paper), qRepo);
    }

    /** 一个阶段的统计 */
    static Stats stageStats(List<Practice> practices, QuestionRepository qRepo, AttemptRepository aRepo,
                            PaperRepository pRepo) {
        List<Question> all = new ArrayList<>();
        List<Paper> allPapers = new ArrayList<>();
        for (Practice p : practices) {
            List<Paper> ps = realPapers(pRepo.findByPracticeIdOrderBySequenceAsc(p.getId()));
            allPapers.addAll(ps);
            for (Paper paper : ps) all.addAll(qRepo.findByPaperIdOrderBySequenceAsc(paper.getId()));
        }
        return stats(practices.size(), allPapers.size(), all, aRepo, allPapers, qRepo);
    }

    private static Stats stats(long practiceCount, long paperCount, List<Question> questions,
                               AttemptRepository aRepo, List<Paper> papers, QuestionRepository qRepo) {
        long attemptCount = 0, evaluatedCount = 0;
        for (Question q : questions) {
            for (Attempt a : aRepo.findByQuestionIdOrderBySequenceAsc(q.getId())) {
                attemptCount++;
                if (a.getEvaluatedAt() != null && a.getScore() != null) evaluatedCount++;
            }
        }
        // 平均分 = 各已批改卷的得分（满分 100）的平均；最近分 = 最后一张已批改卷的得分
        List<Double> paperScores = new ArrayList<>();
        for (Paper paper : papers) {
            Double s = paperScore(qRepo.findByPaperIdOrderBySequenceAsc(paper.getId()), aRepo, paper);
            if (s != null) paperScores.add(s);
        }
        Double averageScore = paperScores.isEmpty() ? null
                : Math.round(paperScores.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
        Double lastScore = paperScores.isEmpty() ? null : paperScores.get(paperScores.size() - 1);
        List<Double> recent = paperScores.size() <= 8 ? paperScores
                : paperScores.subList(paperScores.size() - 8, paperScores.size());
        return new Stats(practiceCount, paperCount, questions.size(), attemptCount, evaluatedCount,
                averageScore, lastScore, List.copyOf(recent));
    }
}
