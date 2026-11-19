package com.review.vt.common.util;

import com.review.vt.service.PracticeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.vt.pojo.entity.Attempt;
import com.review.vt.pojo.entity.Paper;
import com.review.vt.pojo.entity.Practice;
import com.review.vt.pojo.entity.Question;
import com.review.vt.pojo.entity.Stage;
import com.review.vt.mapper.AttemptMapper;
import com.review.vt.mapper.PaperMapper;
import com.review.vt.mapper.QuestionMapper;
import com.review.vt.pojo.dto.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.review.vt.common.util.ScoringUtil;
import com.review.vt.common.util.IntensitiesUtil;
import com.review.vt.common.util.CompositionModesUtil;

/** 实体 → DTO 映射与百分制统计（个人数据规模，直接循环统计）。 */
public final class MappingsUtil {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private MappingsUtil() {}

    /** 实体 Long 主键 → 前端字符串（保持 API 契约不变） */
    public static String id(Long v) { return v == null ? null : String.valueOf(v); }

    public static JsonNode parseJson(String s) {
        if (s == null || s.isBlank()) return null;
        try { return MAPPER.readTree(s); } catch (Exception e) { return null; }
    }

    public static Map<String, Double> parseDimensions(String s) {
        JsonNode node = parseJson(s);
        if (node == null || !node.isObject()) return Map.of();
        Map<String, Double> map = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asDouble()));
        return map;
    }

    /** JSON 字符串数组 → List<String>（解析失败返回空列表） */
    public static List<String> parseStringList(String s) {
        JsonNode node = parseJson(s);
        if (node == null || !node.isArray()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        node.forEach(x -> list.add(x.asText()));
        return list;
    }

    /** 取字符串字段：模型偶尔给出非文本节点，此时退回节点文本，绝不返回 null */
    public static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.path(field);
        if (v == null || v.isMissingNode() || v.isNull()) return "";
        String s = v.isTextual() ? v.asText("") : v.toString();
        return normalizeNewlines(s);
    }

    /**
     * 归一化换行：模型有时把换行双重转义（JSON 里是 \\n，解析后是字面 \n），
     * 这里统一转成真换行；顺带归一化 Windows 的 \r\n。
     */
    public static String normalizeNewlines(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", "\t").replace("\r\n", "\n");
    }

    public static List<String> stringList(JsonNode node, String field) {
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
    public static List<String> cap(List<String> values, int n) {
        if (values == null || values.size() <= n) return values == null ? List.of() : values;
        return new ArrayList<>(values.subList(0, n));
    }

    public static String writeStringList(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static String writeJson(JsonNode node) {
        try {
            return node == null ? null : MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------- 实体 → DTO ---------- */

    public static StageDto stage(Stage s, Stats stats) {
        return new StageDto(id(s.getId()), s.getName(), s.getStatus(),
                s.getCreatedAt(), s.getUpdatedAt(), s.getCompletedAt(), parseJson(s.getSummaryJson()), stats);
    }

    public static StageDetailDto stageDetail(Stage s, Double averageScore,
                                             long practiceCount, long paperCount, long questionCount,
                                             List<PracticeCardDto> practices) {
        return new StageDetailDto(id(s.getId()), s.getName(), s.getStatus(),
                s.getCreatedAt(), s.getUpdatedAt(), s.getCompletedAt(),
                parseJson(s.getSummaryJson()), averageScore,
                practiceCount, paperCount, questionCount, practices);
    }

    public static PracticeCardDto practiceCard(Practice p, Double averageScore,
                                               long paperCount, long questionCount,
                                               int noteCount, int openMistakeCount) {
        return new PracticeCardDto(id(p.getId()), id(p.getStageId()), p.getMode(), p.getTopic(),
                p.getScopeMode(), p.getScopeText(), p.getLevel(), IntensitiesUtil.normalize(p.getIntensity()),
                p.isReview(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt(),
                averageScore, paperCount, questionCount, noteCount, openMistakeCount);
    }

    public static PracticeDto practice(Practice p, Stats stats, int noteCount, int openMistakeCount) {
        return new PracticeDto(id(p.getId()), id(p.getStageId()), p.getMode(), p.getTopic(),
                p.getScopeMode(), p.getScopeText(), p.getLevel(), IntensitiesUtil.normalize(p.getIntensity()),
                p.isReview(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt(),
                parseJson(p.getMemoryJson()), parseJson(p.getDigestJson()), stats,
                noteCount, openMistakeCount);
    }

    public static PracticeDetailDto practiceDetail(Practice p, Stats stats,
            String notesText, Long notesUpdatedAt, List<MistakeDto> mistakes, List<PaperDto> papers) {
        return new PracticeDetailDto(id(p.getId()), id(p.getStageId()), p.getMode(), p.getTopic(),
                p.getScopeMode(), p.getScopeText(), p.getLevel(), IntensitiesUtil.normalize(p.getIntensity()),
                p.isReview(), p.getStatus(),
                p.getCreatedAt(), p.getUpdatedAt(), p.getCompletedAt(),
                parseJson(p.getMemoryJson()), parseJson(p.getDigestJson()), stats,
                notesText, notesUpdatedAt, mistakes, papers);
    }

    public static PaperDto paper(Paper paper, Stats stats, List<QuestionDto> questions) {
        double score = ScoringUtil.paperScore(earnedOf(paper, questions));
        boolean graded = "graded".equals(paper.getStatus());
        return new PaperDto(id(paper.getId()), paper.getSequence(),
                paper.getKind() == null ? "normal" : paper.getKind(), paper.getStatus(),
                CompositionModesUtil.normalize(paper.getCompositionMode()),
                paper.getCreatedAt(), paper.getSubmittedAt(),
                paper.getMaxScore() > 0 ? paper.getMaxScore() : ScoringUtil.PAPER_MAX,
                graded ? score : null,
                parseJson(paper.getSummaryJson()), parseJson(paper.getProgressJson()), stats, questions);
    }

    public static QuestionDto question(Question q, List<Attempt> attempts) {
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
                q.getCreatedAt(), q.getRevealedAt(), attempts.stream().map(MappingsUtil::attempt).toList());
    }

    public static AttemptDto attempt(Attempt a) {
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

    /**
     * 批量取作答并按题分组（保持 sequence 序）：一次 IN 查询替代逐题 N+1。
     * 整卷/整练习的统计与 DTO 构建必须走这里，别在循环里逐题查库。
     */
    public static Map<Long, List<Attempt>> attemptsByQuestion(AttemptMapper aRepo, List<Long> questionIds) {
        Map<Long, List<Attempt>> byQ = new LinkedHashMap<>();
        if (questionIds == null || questionIds.isEmpty()) return byQ;
        for (Attempt a : aRepo.findByQuestionIds(questionIds)) {
            byQ.computeIfAbsent(a.getQuestionId(), k -> new ArrayList<>()).add(a);
        }
        return byQ;
    }

    /** 一张卷的得分（满分 100）；未批改返回 null。qid 序 = 卷内 sequence asc */
    private static Double paperScore(List<Long> qids, Map<Long, List<Attempt>> atByQ, Paper paper) {
        if (paper != null && !"graded".equals(paper.getStatus())) return null;
        List<Double> earned = new ArrayList<>();
        boolean any = false;
        for (Long qid : qids) {
            Double e = null;
            for (Attempt a : atByQ.getOrDefault(qid, List.of())) {
                if (a.getEvaluatedAt() != null && a.getPoints() != null) {
                    e = a.getPoints();
                    any = true;
                }
            }
            earned.add(e);
        }
        return any ? ScoringUtil.paperScore(earned) : null;
    }

    /** 卷列表里剔除刷题卷：它是常开的练习流，不是「一张考卷」 */
    public static List<Paper> realPapers(List<Paper> ps) {
        List<Paper> out = new ArrayList<>();
        for (Paper paper : ps) {
            if (!"drill".equals(paper.getKind())) out.add(paper);
        }
        return out;
    }

    /** 一次练习的统计（含刷题） */
    public static Stats practiceStats(Practice p, QuestionMapper qRepo, AttemptMapper aRepo,
                               PaperMapper pRepo) {
        return statsFromMappers(1, pRepo.findByPracticeIds(List.of(p.getId())), qRepo, aRepo);
    }

    /** 一张卷的统计 */
    public static Stats paperStats(Paper paper, QuestionMapper qRepo, AttemptMapper aRepo) {
        return statsFromMappers(0, List.of(paper), qRepo, aRepo);
    }

    /**
     * 一个阶段的统计（含各练习的刷题）。practices 序即卷的 practice-major 序——
     * lastScore/recentScores 以此为准，不能按 practice_id 重排。
     */
    public static Stats stageStats(List<Practice> practices, QuestionMapper qRepo, AttemptMapper aRepo,
                            PaperMapper pRepo) {
        Map<Long, List<Paper>> byPractice = new LinkedHashMap<>();
        for (Paper paper : pRepo.findByPracticeIds(practices.stream().map(Practice::getId).toList())) {
            byPractice.computeIfAbsent(paper.getPracticeId(), k -> new ArrayList<>()).add(paper);
        }
        List<Paper> allPapers = new ArrayList<>();
        for (Practice p : practices) {
            List<Paper> ps = byPractice.get(p.getId());
            if (ps != null) allPapers.addAll(ps);
        }
        return statsFromMappers(practices.size(), allPapers, qRepo, aRepo);
    }

    /** 取数 + 计算：题窄投影一次批量、作答一次批量（防逐卷 N+1），再走纯计算 */
    private static Stats statsFromMappers(long practiceCount, List<Paper> allPapers,
                                          QuestionMapper qRepo, AttemptMapper aRepo) {
        List<Long> paperIds = allPapers.stream().map(Paper::getId).toList();
        Map<Long, List<Long>> qidsByPaper = new LinkedHashMap<>();
        List<Long> allQids = new ArrayList<>();
        for (Question q : qRepo.findRefsByPaperIds(paperIds)) {
            qidsByPaper.computeIfAbsent(q.getPaperId(), k -> new ArrayList<>()).add(q.getId());
            allQids.add(q.getId());
        }
        return computeStats(practiceCount, allPapers, qidsByPaper, attemptsByQuestion(aRepo, allQids));
    }

    /**
     * 统计口径（纯计算，输入全部预取）：卷指标只算真考卷（百分制），刷题卷单独算刷题指标（0-10 分制，与刷题页同口径）。
     *
     * @param allPapers        卷序 = practice-major（练习列表序，各练习内 sequence asc）——lastScore/recentScores 以此为准
     * @param questionIdsByPaper 覆盖全部卷（含刷题卷），各卷内 sequence asc
     * @param atByQ            各题作答，sequence asc（「最近一次已批改」= 末条）
     */
    public static Stats computeStats(long practiceCount, List<Paper> allPapers,
                                     Map<Long, List<Long>> questionIdsByPaper,
                                     Map<Long, List<Attempt>> atByQ) {
        List<Paper> papers = realPapers(allPapers);
        long questionCount = 0;
        for (Paper paper : papers) questionCount += questionIdsByPaper.getOrDefault(paper.getId(), List.of()).size();
        long attemptCount = 0, evaluatedCount = 0;
        for (Paper paper : papers) {
            for (Long qid : questionIdsByPaper.getOrDefault(paper.getId(), List.of())) {
                for (Attempt a : atByQ.getOrDefault(qid, List.of())) {
                    attemptCount++;
                    if (a.getEvaluatedAt() != null && a.getScore() != null) evaluatedCount++;
                }
            }
        }
        // 平均分 = 各已批改卷的得分（满分 100）的平均；最近分 = 最后一张已批改卷的得分
        List<Double> paperScores = new ArrayList<>();
        for (Paper paper : papers) {
            Double s = paperScore(questionIdsByPaper.getOrDefault(paper.getId(), List.of()), atByQ, paper);
            if (s != null) paperScores.add(s);
        }
        Double averageScore = paperScores.isEmpty() ? null
                : Math.round(paperScores.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
        Double lastScore = paperScores.isEmpty() ? null : paperScores.get(paperScores.size() - 1);
        List<Double> recent = paperScores.size() <= 8 ? paperScores
                : paperScores.subList(paperScores.size() - 8, paperScores.size());
        // 刷题：题数按出过的全部题；均分/最近分只算已批改的（0-10 分制）
        long drillCount = 0;
        List<Double> drillScores = new ArrayList<>();
        for (Paper paper : allPapers) {
            if (!"drill".equals(paper.getKind())) continue;
            for (Long qid : questionIdsByPaper.getOrDefault(paper.getId(), List.of())) {
                drillCount++;
                Double s = latestEvaluatedScore(qid, atByQ);
                if (s != null) drillScores.add(s);
            }
        }
        Double drillAvg = drillScores.isEmpty() ? null
                : Math.round(drillScores.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
        Double drillLast = drillScores.isEmpty() ? null : drillScores.get(drillScores.size() - 1);
        return new Stats(practiceCount, papers.size(), questionCount, attemptCount, evaluatedCount,
                averageScore, lastScore, List.copyOf(recent), drillCount, drillAvg, drillLast);
    }

    /** 该题最近一次已批改作答的表现分（0-10）；没批改过返回 null */
    private static Double latestEvaluatedScore(Long qid, Map<Long, List<Attempt>> atByQ) {
        Double s = null;
        for (Attempt a : atByQ.getOrDefault(qid, List.of())) {
            if (a.getEvaluatedAt() != null && a.getScore() != null) s = a.getScore();
        }
        return s;
    }

    /* ---------- 阶段详情均分口径（Σ实得分/题数，纯计算） ---------- */

    /** 该题最近一次已批改作答的实得分（sequence 序，末条覆盖）；没批改过返回 null */
    public static Double latestEarned(List<Attempt> attempts) {
        if (attempts == null || attempts.isEmpty()) return null;
        Double e = null;
        for (Attempt a : attempts) {
            if (a.getEvaluatedAt() != null && a.getPoints() != null) e = a.getPoints();
        }
        return e;
    }

    /**
     * 均分 = Σ得分 / 题数（未批改计 0 进分母）；保留 1 位小数。
     * 题数为 0 或一道都没批改 → null（前端显示「—」，不显示 0 分）。
     */
    public static Double earnedAvg(long questionCount, double earnedSum, long gradedCount) {
        if (questionCount == 0 || gradedCount == 0) return null;
        return Math.round(earnedSum / questionCount * 10) / 10.0;
    }

    /** 窄作答行按题分组（保持 sequence 序，「最近一次已批改」= 末条） */
    public static Map<Long, List<Attempt>> groupByQuestion(List<Attempt> rows) {
        Map<Long, List<Attempt>> out = new LinkedHashMap<>();
        if (rows == null) return out;
        for (Attempt a : rows) {
            out.computeIfAbsent(a.getQuestionId(), k -> new ArrayList<>()).add(a);
        }
        return out;
    }
}
