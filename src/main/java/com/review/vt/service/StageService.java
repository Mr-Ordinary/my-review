package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.review.vt.auth.AuthContext;
import com.review.vt.pojo.entity.Attempt;
import com.review.vt.pojo.entity.Paper;
import com.review.vt.pojo.entity.Practice;
import com.review.vt.pojo.entity.Question;
import com.review.vt.pojo.entity.Stage;
import com.review.vt.mapper.AttemptMapper;
import com.review.vt.mapper.FollowUpMapper;
import com.review.vt.mapper.PaperMapper;
import com.review.vt.mapper.PracticeMapper;
import com.review.vt.mapper.QuestionMapper;
import com.review.vt.mapper.ReviewDocMapper;
import com.review.vt.mapper.StageMapper;
import com.review.vt.pojo.dto.*;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.review.vt.common.util.MappingsUtil;
import com.review.vt.web.ex.NotFoundException;

/**
 * 阶段：只是一个用来区分空间的名字（分组），没有业务含义。
 * 做创建 / 列表 / 详情 / 删除（级联）/ 实时统计，以及「全复习练习」的发起。
 *
 * 注意分清两类信息：
 * - 实时统计：查询现算，永不缓存。首页列表直查 DB 且只算数量（均分要查作答表，没用）；
 *   详情页算完整统计（含均分/趋势），题与作答批量直查
 * - 阶段总结（AI 反馈）：交卷后自动生成，是全复习的依据
 */
@Service
@AllArgsConstructor
public class StageService {
    private final StageMapper stageMapper;
    private final PracticeMapper practiceMapper;
    private final PaperMapper paperMapper;
    private final QuestionMapper questionMapper;
    private final AttemptMapper attemptMapper;
    private final FollowUpMapper followUpMapper;
    private final ReviewDocMapper reviewDocMapper;
    private final com.review.vt.mapper.ReviewCardMapper reviewCardMapper;
    private final DataCacheService dataCache;
    private final StageSummaryService stageSummaryService;
    private final PracticeService practiceService;
    private final KnowledgeService knowledgeService;
    private final NoteService noteService;
    private final MistakeService mistakeService;
    private final MemoryPointService memoryPointService;

    /**
     * 阶段列表（首页）：**直查 DB，不走缓存**。只算数量（练习/卷/题/刷题）——
     * 算均分要拉全部作答，没用还多一条大查询，分数字段一律留 null（详情页再算）。
     * 4 条批量查询（阶段/练习/卷/题窄投影），不碰作答表。
     */
    public List<StageDto> list() {
        // 获取用户ID
        Long userId = AuthContext.require().userId();
        // 1 查询所有阶段===================================================
        List<Stage> stages = new ArrayList<>();
        // 查询当前用户的阶段ids (cache aside)
        String stageIdsKey = "stage_ids:user_id_" + userId;
        List<Long> stageIds = dataCache.getList(stageIdsKey, Long.class);
        if (stageIds == null) {
            // 缓存未命中，查询并写入数据库
            stages = stageMapper.selectAllDesc(userId);
            stageIds = stages.stream().map(Stage::getId).toList(); // 数据库查询ids
            dataCache.set(stageIdsKey,stageIds); // 缓存
        } else {
            // 缓存命中 根据id拿到所有stage
            List<Long> missingIds = new ArrayList<>();
            for (Long stageId : stageIds) {
                String stageKey = "stage:" + stageId;
                Stage stage = dataCache.getObj(stageKey, Stage.class);
                if (stage == null) {
                    missingIds.add(stageId); // 缓存未命中 插入List 后续并集查询
                } else {
                    // 缓存命中
                    stages.add(stage);
                }
            }
            if (!missingIds.isEmpty()) {
                // 并集查询
                stages.addAll(stageMapper.selectByIds(missingIds));
            }
        }
        // 2 查询各阶段下的练习 ============================================
        // 空父 id 直接短路——IN () 是坏 SQL（缓存层的空集合语义，直查后不能丢）
        Map<Long, List<Practice>> prs = stageIds.isEmpty() ? Map.of()
                : groupByStageId(practiceMapper.selectByStageIds(userId, stageIds));
        List<Long> practiceIds = prs.values().stream().flatMap(List::stream).map(Practice::getId).toList();
        Map<Long, List<Paper>> papers = practiceIds.isEmpty() ? Map.of()
                : groupByPracticeId(paperMapper.selectByPracticeIds(userId, practiceIds));
        List<Long> paperIds = papers.values().stream().flatMap(List::stream).map(Paper::getId).toList();
        Map<Long, List<Long>> qids = paperIds.isEmpty() ? Map.of()
                : questionIdsByPaper(questionMapper.selectRefsByPaperIds(userId, paperIds));

        List<StageDto> out = new ArrayList<>();
        for (Stage s : stages) {
            List<Practice> ps = prs.getOrDefault(s.getId(), List.of());
            List<Paper> allPapers = new ArrayList<>();
            for (Practice p : ps) allPapers.addAll(papers.getOrDefault(p.getId(), List.of()));
            // 空作答 → computeStats 只出计数，分数字段自然是 null
            out.add(MappingsUtil.stage(s, MappingsUtil.computeStats(ps.size(), allPapers, qids, Map.of())));
        }
        return out;
    }

    public StageDto create(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("阶段名称不能为空");
        long now = System.currentTimeMillis();
        Stage s = new Stage();
        s.setName(name.trim());
        s.setStatus("active");
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        stageMapper.save(s);
        dataCache.evictStage(s.getId());   // 插入后兜底清一次（自增 id 不会撞缓存键，保险）
        // 新阶段底下没有任何数据，计数全 0、分数全空
        return MappingsUtil.stage(s, MappingsUtil.computeStats(0, List.of(), Map.of(), Map.of()));
    }

    /**
     * 阶段详情（指定的 7 步链路，热 5 条 / 冷 6 条 SQL，全部窄投影批量、索引序、无函数运算）：
     * ① 阶段行走 stage:{id} 缓存；② 练习按阶段查；③ 卷只查主键（数量即行数）；
     * ④ 题窄投影；⑤ 作答只查得分；⑥ 未解决错题批量；⑦ Java 组装均分。
     * 均分口径 = Σ每题实得分（最近一次已批改）/ 题目数（题目加权，未批改计 0 进分母）；
     * 卷/题数全部计入（含刷题）。详情走缓存、范围直查 DB；题与作答、统计永不缓存。
     */
    public StageDetailDto detail(Long id) {
        Long userId = AuthContext.require().userId();
        Stage s = get(id);   // ① stage:{id} cache-aside

        List<Practice> prsAsc = practiceMapper.selectByStageAsc(userId, s.getId());   // ② created_at asc
        List<Long> practiceIds = prsAsc.stream().map(Practice::getId).toList();

        // ③ 卷窄投影（只查主键，数量即行数）→ 按练习分组
        Map<Long, List<Long>> paperIdsByPractice = new LinkedHashMap<>();
        if (!practiceIds.isEmpty()) {
            for (Paper r : paperMapper.selectRefsByPracticeIds(userId, practiceIds)) {
                paperIdsByPractice.computeIfAbsent(r.getPracticeId(), k -> new ArrayList<>()).add(r.getId());
            }
        }
        List<Long> allPaperIds = paperIdsByPractice.values().stream().flatMap(List::stream).toList();

        // ④ 题窄投影（复用 selectRefsByPaperIds，零新 SQL）
        Map<Long, List<Long>> qidsByPaper = allPaperIds.isEmpty() ? Map.of()
                : questionIdsByPaper(questionMapper.selectRefsByPaperIds(userId, allPaperIds));

        // ⑤ 作答只查得分（窄投影：question_id/points/evaluated_at），空短路
        List<Long> allQids = qidsByPaper.values().stream().flatMap(List::stream).toList();
        Map<Long, List<Attempt>> atByQ = allQids.isEmpty() ? Map.of()
                : MappingsUtil.groupByQuestion(attemptMapper.selectScoresByQuestionIds(userId, allQids));

        // ⑥ 未解决错题批量（一条 IN，Java 端计数），空短路
        Map<Long, Long> openByPractice = practiceIds.isEmpty() ? Map.of()
                : mistakeService.openCountsByPractices(practiceIds);

        // ⑦ Java 组装：练习均分=Σ得分/题数；阶段均分=所有题目总分/所有题目数（加权）
        long stageQuestionCount = 0, stageGraded = 0;
        double stageEarnedSum = 0;
        List<PracticeCardDto> cards = new ArrayList<>();
        for (int i = prsAsc.size() - 1; i >= 0; i--) {   // DTO 契约倒序：最新在前
            Practice p = prsAsc.get(i);
            List<Long> paperIds = paperIdsByPractice.getOrDefault(p.getId(), List.of());
            long qCount = 0, graded = 0;
            double earnedSum = 0;
            for (Long paperId : paperIds) {
                for (Long qid : qidsByPaper.getOrDefault(paperId, List.of())) {
                    qCount++;
                    Double e = MappingsUtil.latestEarned(atByQ.getOrDefault(qid, List.of()));
                    if (e != null) {
                        earnedSum += e;
                        graded++;
                    }
                }
            }
            stageQuestionCount += qCount;
            stageGraded += graded;
            stageEarnedSum += earnedSum;
            cards.add(MappingsUtil.practiceCard(p,
                    MappingsUtil.earnedAvg(qCount, earnedSum, graded),
                    paperIds.size(), qCount,
                    (int) NoteService.countLines(p.getNotesText()),
                    openByPractice.getOrDefault(p.getId(), 0L).intValue()));
        }
        return MappingsUtil.stageDetail(s,
                MappingsUtil.earnedAvg(stageQuestionCount, stageEarnedSum, stageGraded),
                prsAsc.size(), allPaperIds.size(), stageQuestionCount, cards);
    }

    /** 重新生成阶段总结（AI 反馈）。交卷后会自动重出，这里给手动补跑用。 */
    public StageDetailDto regenerateSummary(Long id) {
        if (practiceMapper.findByStageIdOrderByCreatedAtAsc(id).isEmpty()) {
            throw new IllegalArgumentException("该阶段还没有练习，先做练习再生成阶段总结");
        }
        stageSummaryService.regenerate(id);
        return detail(id);
    }

    /**
     * 发起全复习练习：系统自动在该阶段下创建一个练习，针对整个阶段做全复习。
     *
     * 全复习的**范围就是阶段总结里的 reviewScope**（AI 依据练习成果框定出来的一长串文字），
     * 所以没有阶段总结就没法做全复习——这时候应该直接禁用按钮。
     */
    public PracticeDto createReviewPractice(Long stageId, String level, String intensity) {
        Stage s = get(stageId);
        if (practiceMapper.findByStageIdOrderByCreatedAtAsc(stageId).isEmpty()) {
            throw new IllegalArgumentException("该阶段还没有任何练习，先练出数据再做全复习");
        }
        JsonNode summary = stageSummaryService.summary(stageId);
        String reviewScope = summary == null ? "" : MappingsUtil.text(summary, "reviewScope");
        if (reviewScope.isBlank()) {
            throw new IllegalArgumentException("还没有阶段总结，无法框定全复习范围。请先生成阶段总结（交卷后会自动生成）");
        }

        return practiceService.create(stageId, "阶段全复盘", "range", reviewScope, level, intensity, null, true);
    }

    /**
     * 级联删除：阶段 → 练习 → 卷 → 题目 → 作答 → 追问/提纲/知识点/笔记/记忆点/错题。
     * 事务保证要么全删要么全留（JSON 时代跨文件删孤儿，现在一步到位）。
     */
    @Transactional
    public void delete(Long id) {
        Stage s = get(id);
        // 用户指定的删除顺序：先删缓存 → 删库 → 再删一次缓存（兜住删除期间的并发回填）
        dataCache.evictStage(id);
        List<Practice> ps = practiceMapper.findByStageIdOrderByCreatedAtAsc(s.getId());
        for (Practice p : ps) {
            for (Paper paper : paperMapper.findByPracticeIdOrderBySequenceAsc(p.getId())) {
                for (Question q : questionMapper.findByPaperIdOrderBySequenceAsc(paper.getId())) {
                    for (Attempt a : attemptMapper.findByQuestionIdOrderBySequenceAsc(q.getId())) {
                        dataCache.evictAttemptById(a.getId());   // attempt:{id} 点查键
                    }
                    attemptMapper.deleteAll(attemptMapper.findByQuestionIdOrderBySequenceAsc(q.getId()));
                    dataCache.evictQuestionById(q.getId());
                }
                questionMapper.deleteAll(questionMapper.findByPaperIdOrderBySequenceAsc(paper.getId()));
                dataCache.evictPaperById(paper.getId());
            }
            paperMapper.deleteAll(paperMapper.findByPracticeIdOrderBySequenceAsc(p.getId()));
            // 修改前删缓存：整个子树的键都要清掉（列表键 + 点查键 + 最新卷）
            dataCache.evictPaperIds(p.getId());
            
            dataCache.evictPracticeById(p.getId());
            dataCache.evictLatestPaper(p.getId());
            knowledgeService.deleteByPractice(p.getId());
            noteService.deleteByPractice(p.getId());
            memoryPointService.deleteByPractice(p.getId());
            mistakeService.deleteByPractice(p.getId());
            followUpMapper.deleteAll(followUpMapper.findByPracticeId(p.getId()));
            reviewDocMapper.findByPracticeId(p.getId()).ifPresent(doc -> {
                reviewCardMapper.deleteByReviewDocId(doc.getId());
                reviewDocMapper.deleteAll(List.of(doc));
                dataCache.evictReviewCardIds(doc.getId());
                dataCache.evictReviewDoc(p.getId());
            });
        }
        practiceMapper.deleteAll(ps);
        stageMapper.delete(s);
        dataCache.evictStage(id);   // 第二次删缓存
    }

    /* ---------- 内部 ---------- */

    /** 阶段点查：走 stage:{id} 缓存（详情走缓存、范围直查 DB） */
    private Stage get(Long id) {
        Stage s = dataCache.stageById(AuthContext.require().userId(), id);
        if (s == null) throw new NotFoundException("阶段不存在或已删除");
        return s;
    }

    private static Map<Long, List<Practice>> groupByStageId(List<Practice> rows) {
        Map<Long, List<Practice>> out = new LinkedHashMap<>();
        for (Practice r : rows) out.computeIfAbsent(r.getStageId(), k -> new ArrayList<>()).add(r);
        return out;
    }

    private static Map<Long, List<Paper>> groupByPracticeId(List<Paper> rows) {
        Map<Long, List<Paper>> out = new LinkedHashMap<>();
        for (Paper r : rows) out.computeIfAbsent(r.getPracticeId(), k -> new ArrayList<>()).add(r);
        return out;
    }

    /** 题窄投影按卷分组 → 各卷 qid 列表（SQL 已按 paper_id, sequence 排，顺序即卷内题序） */
    private static Map<Long, List<Long>> questionIdsByPaper(List<Question> refs) {
        Map<Long, List<Long>> out = new LinkedHashMap<>();
        for (Question q : refs) out.computeIfAbsent(q.getPaperId(), k -> new ArrayList<>()).add(q.getId());
        return out;
    }
}
