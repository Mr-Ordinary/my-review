package com.review.vt.service;

import com.review.vt.auth.AuthContext;
import com.review.vt.mapper.AttemptMapper;
import com.review.vt.mapper.KnowledgePointMapper;
import com.review.vt.mapper.PaperMapper;
import com.review.vt.mapper.QuestionMapper;
import com.review.vt.pojo.dto.HomeOverviewDto;
import com.review.vt.pojo.dto.HomeOverviewDto.HomeKpisDto;
import com.review.vt.pojo.dto.HomeOverviewDto.HomePaperDto;
import com.review.vt.pojo.dto.HomeOverviewDto.HomeStageDto;
import com.review.vt.pojo.dto.KnowledgeAggDto;
import com.review.vt.pojo.entity.Attempt;
import com.review.vt.pojo.entity.Paper;
import com.review.vt.pojo.entity.Practice;
import com.review.vt.pojo.entity.Question;
import com.review.vt.pojo.entity.Stage;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台首页总览（KPI 行 + 阶段卡 + 最近的卷）。
 * <p>
 * 阶段/练习行走 {@link StageService#cachedStageRows}/{@link StageService#cachedPracticeRows}
 * 两级缓存（与 /api/stages 同一套键）；其余统计现算、永不缓存，全部批量窄投影，一条查询一件事。
 * 均分口径与 brief() 一致：已批改卷 = Σ每题最近一次已批改实得分（0-100），未批改卷不产出。
 * 阶段卡数量含刷题卷；KPI 的「本周卷量」与「最近的卷」不含刷题卷（刷题卷常开、不进卷列表）。
 */
@Service
@AllArgsConstructor
public class HomeService {
    private final StageService stageService;
    private final PaperMapper paperMapper;
    private final QuestionMapper questionMapper;
    private final AttemptMapper attemptMapper;
    private final KnowledgePointMapper knowledgePointMapper;

    /** 最近的卷最多展示 6 行 */
    private static final int RECENT_PAPERS = 6;
    /** 一周毫秒数（本周/上周卷量对比窗口） */
    private static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;

    public HomeOverviewDto overview() {
        Long userId = AuthContext.require().userId();
        long now = System.currentTimeMillis();

        // ① 阶段行 + 练习行：走 list() 同款两级缓存（stage_ids/stage:{id}/practice_ids/practice:{id}），
        //    热路径 0 条 SQL；阶段卡计数与「练习主题」都从这里出
        List<Stage> stages = stageService.cachedStageRows(userId);
        List<Long> stageIds = stages.stream().map(Stage::getId).toList();
        List<Practice> practices = stageService.cachedPracticeRows(userId, stageIds);
        Map<Long, String> topicByPractice = new HashMap<>();
        Map<Long, Long> stageByPractice = new HashMap<>();
        for (Practice p : practices) {
            topicByPractice.put(p.getId(), p.getTopic());
            stageByPractice.put(p.getId(), p.getStageId());
        }

        // ② 卷窄投影（全量 meta）：本周/上周卷量、最近的卷、逐卷得分的分母
        List<Paper> papers = paperMapper.selectMetaAllByUser(userId);

        // ③ 题窄投影（id/paper_id/tier）：逐卷题量与档位构成
        List<Question> tiers = questionMapper.selectTiersByUser(userId);
        Map<Long, int[]> tierCountByPaper = new LinkedHashMap<>();   // [基础, 中档, 大题]
        Map<Long, List<Long>> qidsByPaper = new LinkedHashMap<>();
        for (Question q : tiers) {
            int[] cnt = tierCountByPaper.computeIfAbsent(q.getPaperId(), k -> new int[3]);
            int idx = "大题".equals(q.getTier()) ? 2 : ("中档".equals(q.getTier()) ? 1 : 0);
            cnt[idx]++;
            qidsByPaper.computeIfAbsent(q.getPaperId(), k -> new ArrayList<>()).add(q.getId());
        }

        // ④ 已批改作答窄投影：每题取最近一次实得分，逐卷求和（与 brief() 同口径）
        Map<Long, List<Attempt>> atByQuestion = new HashMap<>();
        for (Attempt a : attemptMapper.selectEvaluatedScoresByUser(userId)) {
            atByQuestion.computeIfAbsent(a.getQuestionId(), k -> new ArrayList<>()).add(a);
        }
        Map<String, Double> scoreByPaper = new HashMap<>();
        for (Map.Entry<Long, List<Long>> e : qidsByPaper.entrySet()) {
            List<Double> earned = new ArrayList<>();
            for (Long qid : e.getValue()) {
                earned.add(com.review.vt.common.util.MappingsUtil.latestEarned(atByQuestion.getOrDefault(qid, List.of())));
            }
            scoreByPaper.put(String.valueOf(e.getKey()), com.review.vt.common.util.ScoringUtil.paperScore(earned));
        }

        // ⑤ 知识点聚合：覆盖 / 掌握 / 待巩固
        KnowledgeAggDto agg = knowledgePointMapper.selectAggByUser(userId);
        long total = agg == null ? 0 : agg.total();
        long covered = agg == null ? 0 : agg.covered();
        long mastered = agg == null ? 0 : agg.mastered();
        long weak = Math.max(0, covered - mastered);

        // ⑥ 本周 / 上周卷量（刷题卷不算「出的卷」）
        long weekAgo = now - WEEK_MS;
        long twoWeeksAgo = now - 2 * WEEK_MS;
        long papersThisWeek = 0;
        long papersLastWeek = 0;
        long gradedCount = 0;
        double gradedSum = 0;
        Map<Long, Double> gradedByStage = new HashMap<>();
        Map<Long, Long> gradedCountByStage = new HashMap<>();
        for (Paper p : papers) {
            if ("drill".equals(p.getKind())) continue;
            if (p.getCreatedAt() >= weekAgo) papersThisWeek++;
            else if (p.getCreatedAt() >= twoWeeksAgo) papersLastWeek++;
            Double score = scoreByPaper.get(String.valueOf(p.getId()));
            if (score != null && "graded".equals(p.getStatus())) {
                gradedCount++;
                gradedSum += score;
                Long stageId = stageByPractice.get(p.getPracticeId());
                if (stageId != null) {
                    gradedByStage.merge(stageId, score, Double::sum);
                    gradedCountByStage.merge(stageId, 1L, Long::sum);
                }
            }
        }
        Double averageScore = gradedCount == 0 ? null : Math.round(gradedSum / gradedCount * 10) / 10.0;

        // ⑦ 阶段卡：数量（练习/卷/题，含刷题）+ 该阶段已批改卷均分
        Map<Long, Long> papersByStage = new HashMap<>();
        Map<Long, Long> questionsByStage = new HashMap<>();
        Map<Long, Long> practicesByStage = new HashMap<>();
        for (Practice p : practices) {
            practicesByStage.merge(p.getStageId(), 1L, Long::sum);
        }
        for (Paper p : papers) {
            Long stageId = stageByPractice.get(p.getPracticeId());
            if (stageId == null) continue;
            papersByStage.merge(stageId, 1L, Long::sum);
            questionsByStage.merge(stageId, (long) qidsByPaper.getOrDefault(p.getId(), List.of()).size(), Long::sum);
        }
        List<HomeStageDto> stageCards = new ArrayList<>();
        for (Stage s : stages) {
            long gradedN = gradedCountByStage.getOrDefault(s.getId(), 0L);
            Double avg = gradedN == 0 ? null
                    : Math.round(gradedByStage.getOrDefault(s.getId(), 0.0) / gradedN * 10) / 10.0;
            stageCards.add(new HomeStageDto(
                    String.valueOf(s.getId()), s.getName(),
                    practicesByStage.getOrDefault(s.getId(), 0L),
                    papersByStage.getOrDefault(s.getId(), 0L),
                    questionsByStage.getOrDefault(s.getId(), 0L),
                    avg, s.getUpdatedAt(),
                    s.getSummaryJson() != null && !s.getSummaryJson().isEmpty()));
        }

        // ⑧ 最近的卷：非刷题卷按创建时间倒序取 6 行，题量/档位/得分逐行拼
        List<Paper> recent = papers.stream()
                .filter(p -> !"drill".equals(p.getKind()))
                .sorted(Comparator.comparingLong(Paper::getCreatedAt).reversed())
                .limit(RECENT_PAPERS)
                .toList();
        List<HomePaperDto> rows = new ArrayList<>();
        for (Paper p : recent) {
            int[] cnt = tierCountByPaper.getOrDefault(p.getId(), new int[3]);
            Double score = scoreByPaper.get(String.valueOf(p.getId()));
            if (score != null && !"graded".equals(p.getStatus())) score = null;
            rows.add(new HomePaperDto(
                    String.valueOf(p.getId()), String.valueOf(p.getPracticeId()),
                    topicByPractice.getOrDefault(p.getPracticeId(), ""),
                    p.getSequence(), p.getCompositionMode(), p.getStatus(),
                    cnt[0] + cnt[1] + cnt[2], cnt[0], cnt[1], cnt[2],
                    score, p.getCreatedAt()));
        }

        HomeKpisDto kpis = new HomeKpisDto(papersThisWeek, papersLastWeek,
                averageScore, gradedCount, weak, covered, mastered, total);
        return new HomeOverviewDto(kpis, stageCards, rows);
    }
}
