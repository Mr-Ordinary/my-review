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

import java.util.*;
import java.util.stream.Collectors;

import com.review.vt.common.util.MappingsUtil;
import com.review.vt.web.ex.NotFoundException;

/**
 * 阶段：只是一个用来区分空间的名字（分组），没有业务含义。
 * 做创建 / 列表 / 详情 / 删除（级联）/ 实时统计，以及「全复习练习」的发起。
 * <p>
 * 注意分清两类信息：
 * - 实时统计：查询现算，永不缓存。首页列表直查 DB 且只算数量（均分要查作答表，没用）；
 * 详情页算完整统计（含均分/趋势），题与作答批量直查
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
     * 阶段列表（首页）：阶段/练习行走**两级缓存**（stage_ids → stage:{id}、practice_ids → practice:{id}），
     * 卷/题数量每表一条 GROUP BY 现算。只算数量——算均分要拉全部作答，没用还多一条大查询，
     * 分数字段一律留 null（详情页再算）。
     */
    public List<StageIdxDto> list() {
        Long userId = AuthContext.require().userId();

        // 1 阶段行（缓存）===================================================
        List<Stage> stages = cachedStageRows(userId);
        List<Long> stageIds = stages.stream().map(Stage::getId).toList();

        // 2 练习行（缓存）===================================================
        List<Practice> practices = cachedPracticeRows(userId, stageIds);
        Map<Long, List<Practice>> practicesByStage = practices.stream().collect(Collectors.groupingBy(Practice::getStageId));
        List<Long> practiceIds = practices.stream().map(Practice::getId).toList();

        // 3 批量统计每个练习的卷数/题数（每表一条 GROUP BY，不再逐阶段查库）
        Map<Long, Long> paperCountByPractice = toCountMap(paperMapper.findCountGroupByPracticeIds(practiceIds));
        Map<Long, Long> questionCountByPractice = toCountMap(questionMapper.findCountGroupByPracticeIds(practiceIds));

        // 4 组装 DTO（cachedStageRows 已按更新时间倒序）
        List<StageIdxDto> out = new ArrayList<>();
        for (Stage s : stages) {
            StageIdxDto dto = new StageIdxDto();
            dto.setId(s.getId());
            dto.setName(s.getName());
            dto.setStatus(s.getStatus());
            dto.setUpdatedAt(s.getUpdatedAt());
            dto.setHasSummary(s.getSummaryJson() != null && !s.getSummaryJson().isEmpty());

            List<Practice> prs = practicesByStage.getOrDefault(s.getId(), List.of());
            dto.setPracticeCount(prs.size());
            long paperCount = 0;
            long questionCount = 0;
            for (Practice p : prs) {
                paperCount += paperCountByPractice.getOrDefault(p.getId(), 0L);
                questionCount += questionCountByPractice.getOrDefault(p.getId(), 0L);
            }
            dto.setPaperCount(paperCount);
            dto.setQuestionCount(questionCount);
            out.add(dto);
        }
        return out;
    }

    /**
     * 阶段行（cache-aside：stage_ids:user_id_{uid} + stage:{id}），list() 与首页 overview 共用。
     * 失效沿用各写路径的既有 evict（建/删阶段、阶段总结改名等都清 stage_ids 或 stage:{id}）。
     */
    public List<Stage> cachedStageRows(Long userId) {
        List<Stage> stages = new ArrayList<>();

        // ==== 步骤 1：用户阶段 ids（cache aside） ====
        String stageIdsKey = "stage_ids:user_id_" + userId;
        List<Long> stageIds = dataCache.getList(stageIdsKey, Long.class);
        if (stageIds == null) {
            // 缓存未命中：整表查一次，回填 ids 键
            List<Stage> all = stageMapper.selectAllDesc(userId);
            dataCache.set(stageIdsKey, all.stream().map(Stage::getId).toList());
            return all;
        }

        // ==== 步骤 2：逐条点查 stage:{id}，miss 收起来并集回填 ====
        List<Long> missingIds = new ArrayList<>();
        for (Long stageId : stageIds) {
            Stage stage = dataCache.getObj("stage:" + stageId, Stage.class);
            if (stage == null) {
                missingIds.add(stageId);
                continue;
            }
            stages.add(stage);
        }
        if (!missingIds.isEmpty()) {
            List<Stage> added = stageMapper.selectByIds(missingIds);
            stages.addAll(added);
            added.forEach(e -> dataCache.set("stage:" + e.getId(), e));
        }

        // ==== 步骤 3：统一按更新时间倒序（与 selectAllDesc 口径一致） ====
        stages.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
        return stages;
    }

    /**
     * 练习行（cache-aside：practice_ids:stage_id:{sid} + practice:{id}），list() 与首页 overview 共用。
     * 任一阶段的 ids 缺失视为整组过期，一条 SQL 重灌两级；空阶段写空列表键防反复判失效。
     */
    public List<Practice> cachedPracticeRows(Long userId, List<Long> stageIds) {
        List<Practice> practices = new ArrayList<>();
        if (stageIds.isEmpty()) return practices;

        // ==== 步骤 1：各阶段练习 ids ====
        List<Long> practiceIds = new ArrayList<>();
        boolean reCache = false;
        for (Long stageId : stageIds) {
            List<Long> idsByStage = dataCache.getList("practice_ids:stage_id:" + stageId, Long.class);
            if (idsByStage == null) {
                reCache = true;   // 任意一个阶段下练习ids查不到，视为缓存丢失，重新加载数据库
                practiceIds.clear();
                break;
            }
            practiceIds.addAll(idsByStage);
        }

        // ==== 步骤 2a：整组重灌（一条 SQL） ====
        if (reCache) {
            List<Practice> rows = practiceMapper.selectByStageIds(userId, stageIds);
            Map<Long, List<Practice>> byStage = rows.stream().collect(Collectors.groupingBy(Practice::getStageId));
            rows.forEach(e -> dataCache.set("practice:" + e.getId(), e));
            byStage.forEach((key, value) -> dataCache.set("practice_ids:stage_id:" + key, value.stream().map(Practice::getId).toList()));
            // 没有练习的阶段也要写空列表键：漏写会让下次永远判缓存丢失、整表重查
            for (Long stageId : stageIds) {
                if (!byStage.containsKey(stageId)) dataCache.set("practice_ids:stage_id:" + stageId, List.of());
            }
            return rows;
        }

        // ==== 步骤 2b：逐条点查 practice:{id}，miss 并集回填 ====
        List<Long> missingIds = new ArrayList<>();
        for (Long practiceId : practiceIds) {
            Practice practice = dataCache.getObj("practice:" + practiceId, Practice.class);
            if (practice == null) {
                missingIds.add(practiceId);
                continue;
            }
            practices.add(practice);
        }
        // 回填放循环外：放循环里会漏掉最后几个 miss，还会重复补同一批
        if (!missingIds.isEmpty()) {
            List<Practice> added = practiceMapper.selectByIds(missingIds);
            practices.addAll(added);
            added.forEach(e -> dataCache.set("practice:" + e.getId(), e));
        }
        return practices;
    }


    public StageIdxDto create(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("阶段名称不能为空");
        long now = System.currentTimeMillis();
        Stage s = new Stage();
        s.setName(name.trim());
        s.setStatus("active");
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        stageMapper.save(s);
        dataCache.evictStage(s.getId());   // 插入后兜底清一次（自增 id 不会撞缓存键，保险）
        dataCache.delete("stage_ids:user_id_"+AuthContext.require().userId());
        // 新阶段底下没有任何数据，计数全 0、分数全空
        StageIdxDto dto = new StageIdxDto();
        dto.setId(s.getId());
        dto.setName(s.getName());
        dto.setHasSummary(false);
        dto.setPracticeCount(0);
        dto.setPaperCount(0);
        dto.setQuestionCount(0);
        dto.setUpdatedAt(s.getUpdatedAt());
        return dto;
    }

    /**
     * 阶段详情（指定的 7 步链路，热 5 条 / 冷 6 条 SQL，全部窄投影批量、索引序、无函数运算）：
     * ① 阶段行走 stage:{id} 缓存；② 练习按阶段查（practice_ids:stage_id + practice:{id} 两级缓存，与首页 list 同键）；
     * ③ 卷只查主键（数量即行数）；④ 题窄投影；⑤ 作答只查得分；⑥ 未解决错题批量；⑦ Java 组装均分。
     * 均分口径 = Σ每题实得分（最近一次已批改）/ 题目数（题目加权，未批改计 0 进分母）；
     * 卷/题数全部计入（含刷题）。详情走缓存、范围直查 DB；题与作答、统计永不缓存。
     */
    public StageDetailDto detail(Long id) {
        Long userId = AuthContext.require().userId();
        Stage s = get(id);   // ① stage:{id} cache-aside

        List<Practice> prsAsc = practicesOfStage(userId, s.getId());   // ② created_at asc
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
                : MappingsUtil.groupByQuestion(MappingsUtil.queryInChunks(allQids,
                        (chunk) -> attemptMapper.selectScoresByQuestionIds(userId, chunk)));

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

    /**
     * 重新生成阶段总结（AI 反馈）。交卷后会自动重出，这里给手动补跑用。
     */
    public StageDetailDto regenerateSummary(Long id) {
        if (practiceMapper.findByStageIdOrderByCreatedAtAsc(id).isEmpty()) {
            throw new IllegalArgumentException("该阶段还没有练习，先做练习再生成阶段总结");
        }
        stageSummaryService.regenerate(id);
        return detail(id);
    }

    /**
     * 发起全复习练习：系统自动在该阶段下创建一个练习，针对整个阶段做全复习。
     * <p>
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
        dataCache.delete("stage_ids:user_id_"+AuthContext.require().userId());
        dataCache.delete("practice_ids:stage_id:" + s.getId());   // 练习ids列表失效
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
            // 修改前删缓存：整个子树的点查键 + 最新卷（卷列表不建 ids 索引，无需清）

            dataCache.evictPracticeById(p.getId());
            dataCache.evictLatestPaper(p.getId());
            knowledgeService.deleteByPractice(p.getId());
            noteService.deleteByPractice(p.getId());
            memoryPointService.deleteByPractice(p.getId());
            mistakeService.deleteByPractice(p.getId());
            followUpMapper.deleteAll(followUpMapper.findByPracticeId(p.getId()));
            reviewDocMapper.findByPracticeId(p.getId()).ifPresent(doc -> {
                // 先整组失效卡片缓存（含逐条 review_card:{id}），再删库
                dataCache.evictReviewCardsOfDoc(p.getUserId(), doc.getId());
                reviewCardMapper.deleteByReviewDocId(doc.getId());
                reviewDocMapper.deleteAll(List.of(doc));
                dataCache.evictReviewDoc(p.getId());
            });
        }
        practiceMapper.deleteAll(ps);
        stageMapper.delete(s);
        dataCache.evictStage(id);   // 第二次删缓存
    }

    /* ---------- 内部 ---------- */

    /**
     * 阶段点查：走 stage:{id} 缓存（详情走缓存、范围直查 DB）
     */
    private Stage get(Long id) {
        Stage s = dataCache.stageById(AuthContext.require().userId(), id);
        if (s == null) throw new NotFoundException("阶段不存在或已删除");
        return s;
    }

    /**
     * 一个阶段下的练习（created_at asc）：practice_ids:stage_id:{id} + practice:{id} 两级缓存，
     * 与首页 list 同一套键。空阶段也写 []——否则永远判丢失、每次详情都整表重查。
     */
    private List<Practice> practicesOfStage(Long userId, Long stageId) {
        // ① 先取该阶段的练习 ids 列表
        String key = "practice_ids:stage_id:" + stageId;
        List<Long> ids = dataCache.getList(key, Long.class);
        if (ids != null) {
            // ② 命中：逐个点查 practice:{id}，缺的收集起来并集查库回填
            List<Practice> out = new ArrayList<>();
            List<Long> missingIds = new ArrayList<>();
            for (Long practiceId : ids) {
                Practice practice = dataCache.getObj("practice:" + practiceId, Practice.class);
                if (practice == null) {
                    missingIds.add(practiceId);
                    continue;
                }
                out.add(practice);
            }
            if (!missingIds.isEmpty()) {
                List<Practice> added = practiceMapper.selectByIds(missingIds);
                out.addAll(added);
                added.forEach(e -> dataCache.set("practice:" + e.getId(), e));
            }
            // ③ ids 列表不保序（缓存写入时是分组序），统一按创建时间排回 asc
            out.sort(Comparator.comparingLong(Practice::getCreatedAt));
            return out;
        }
        // ④ 未命中：一条 SQL 查整组，回填两级（空阶段写 [] 防反复 miss）
        List<Practice> rows = practiceMapper.selectByStageAsc(userId, stageId);
        for (Practice row : rows) dataCache.set("practice:" + row.getId(), row);
        dataCache.set(key, rows.stream().map(Practice::getId).toList());
        return rows;
    }

    /**
     * 题窄投影按卷分组 → 各卷 qid 列表（SQL 已按 paper_id, sequence 排，顺序即卷内题序）
     */
    private static Map<Long, List<Long>> questionIdsByPaper(List<Question> refs) {
        Map<Long, List<Long>> out = new LinkedHashMap<>();
        for (Question q : refs) out.computeIfAbsent(q.getPaperId(), k -> new ArrayList<>()).add(q.getId());
        return out;
    }

    /** GROUP BY 计数行 → Map（练习id → 行数） */
    private static Map<Long, Long> toCountMap(List<PracticeCountDto> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (PracticeCountDto row : rows) {
            out.put(row.getPracticeId(), row.getCnt());
        }
        return out;
    }
}
