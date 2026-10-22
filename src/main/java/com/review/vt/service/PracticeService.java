package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.review.vt.domain.Attempt;
import com.review.vt.domain.KnowledgePoint;
import com.review.vt.domain.Paper;
import com.review.vt.domain.Practice;
import com.review.vt.domain.Question;
import com.review.vt.repo.AttemptRepository;
import com.review.vt.repo.PaperRepository;
import com.review.vt.repo.PracticeRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.repo.StageRepository;
import com.review.vt.web.Dtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 真考卷式练习链路：
 * 出题（一张 100 分的卷，前易后难，15-30 题含参考答案）→ 作答（不调 AI，零等待）
 * → 交卷（批量批改 + 总结 + 记忆/摘要）→ 继续出卷（避开 coverage、向薄弱点推进）。
 *
 * 出题与批改都是**后台任务**：发起即返回，进度写在 Paper.progressJson，
 * 中断后可按 Paper.planJson 断点续跑，客户端刷新页面也能看到进度（无感衔接）。
 *
 * 一个练习 = 一个会话；LLM 调用本身无状态，所以「上下文过长」靠 {@link PromptBudget} 硬预算规避。
 */
@Service
public class PracticeService {

    private static final Logger log = LoggerFactory.getLogger(PracticeService.class);

    /** 出题 / 批改的分批大小（模型思考会吃输出额度，批次不能大） */
    private static final int GEN_CHUNK = 4;
    private static final int GRADE_CHUNK = 8;

    private static final int PLAN_MAX_TOKENS = 12288;
    private static final int PAPER_MAX_TOKENS = 16384;
    private static final int SUMMARY_MAX_TOKENS = 8192;

    private final StageRepository stages;
    private final PracticeRepository practices;
    private final PaperRepository papers;
    private final QuestionRepository questions;
    private final AttemptRepository attempts;
    private final LlmService llm;
    private final StageSummaryService stageSummary;
    private final ReferenceService references;
    private final WebSearch webSearch;
    private final KnowledgeService knowledge;
    private final NoteService notes;
    private final MistakeService mistakes;
    private final MemoryPointService memoryPoints;

    /** 后台任务线程池：出题/批改按批并发，交卷时总结/记忆/摘要也是三个并行调用，给足并发位 */
    private final ExecutorService executor = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "myreview-llm");
        t.setDaemon(true);
        return t;
    });

    /** 正在跑的任务：paperId → true。同一张卷同时只允许一个任务 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public PracticeService(StageRepository stages, PracticeRepository practices, PaperRepository papers,
                           QuestionRepository questions, AttemptRepository attempts, LlmService llm,
                           StageSummaryService stageSummary, ReferenceService references,
                           WebSearch webSearch, KnowledgeService knowledge,
                           NoteService notes, MistakeService mistakes,
                           MemoryPointService memoryPoints) {
        this.stages = stages;
        this.practices = practices;
        this.papers = papers;
        this.questions = questions;
        this.attempts = attempts;
        this.llm = llm;
        this.stageSummary = stageSummary;
        this.references = references;
        this.webSearch = webSearch;
        this.knowledge = knowledge;
        this.notes = notes;
        this.mistakes = mistakes;
        this.memoryPoints = memoryPoints;
    }

    /* ================= 创建 / 详情 ================= */

    public PracticeDto create(String stageId, String topic, String scopeMode, String scopeText,
                              String level, String intensity, Integer paperSize, boolean review) {
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("标题不能为空");
        boolean range = "range".equals(scopeMode);
        if (range && (scopeText == null || scopeText.isBlank())) {
            throw new IllegalArgumentException("范围模式必须填写范围描述");
        }
        if (!stages.existsById(stageId)) throw new NotFoundException("阶段不存在或已删除");
        long existing = practices.findByStageIdOrderByCreatedAtAsc(stageId).size();
        if (existing >= Limits.MAX_PRACTICES_PER_STAGE) {
            throw new IllegalArgumentException("这个阶段下已经有 " + existing + " 个练习（上限 "
                    + Limits.MAX_PRACTICES_PER_STAGE + " 个）。要继续练新内容请新建一个阶段。");
        }
        long now = System.currentTimeMillis();
        Practice p = new Practice();
        p.setId(UUID.randomUUID().toString());
        p.setStageId(stageId);
        p.setMode("interview");
        p.setTopic(topic.trim());
        p.setScopeMode(range ? "range" : "topic");
        p.setScopeText(range ? scopeText.trim() : null);
        p.setLevel(level == null || level.isBlank() ? "mid" : level.trim().toLowerCase());
        p.setIntensity(Intensities.normalize(intensity));
        p.setPaperSize(normalizePaperSize(paperSize));
        p.setReview(review);
        p.setStatus("active");
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        practices.save(p);
        return Mappings.practice(p, Mappings.practiceStats(p, questions, attempts, papers), 0, 0);
    }

    /** 只接受 6 / 10 / 15，其余一律落到标准档 10 */
    static int normalizePaperSize(Integer n) {
        return n != null && (n == 6 || n == 10 || n == 15) ? n : 10;
    }

    public PracticeDetailDto detail(String practiceId) {
        return detailOf(get(practiceId));
    }

    /* ================= 出题（后台任务） ================= */

    /**
     * 发起出题：立刻落一张「出题中」的卷并返回，后台并发出题，前端轮询进度。
     *
     * @param mode 组卷模式（见 {@link CompositionModes}），null/非法按常规。
     *             只约束这卷出哪些难度档、出几道题，不影响选点配比与深度阶梯。
     */
    public PracticeDetailDto startPaper(String practiceId, String mode) {
        Practice p = get(practiceId);
        Paper open = findOpenPaper(p.getId());
        if (open != null) {
            throw new IllegalArgumentException("第 " + open.getSequence() + " 张卷正在进行中（"
                    + statusLabel(open.getStatus()) + "），请等它结束或点「续跑」");
        }
        long existing = papers.countByPracticeId(p.getId());
        if (existing >= Limits.MAX_PAPERS_PER_PRACTICE) {
            throw new IllegalArgumentException("这个练习已经出了 " + existing + " 张卷（上限 "
                    + Limits.MAX_PAPERS_PER_PRACTICE + " 张）。要继续练请新建一个练习。");
        }

        Paper paper = new Paper();
        paper.setId(UUID.randomUUID().toString());
        paper.setPracticeId(p.getId());
        paper.setSequence((int) (papers.countByPracticeId(p.getId()) + 1));
        paper.setCompositionMode(CompositionModes.normalize(mode));
        paper.setStatus("generating");
        paper.setCreatedAt(System.currentTimeMillis());
        paper.setMaxScore(Scoring.PAPER_MAX);
        setProgress(paper, "出题", 0, 0, List.of(), null);
        papers.save(paper);
        touch(p);
        log.info("开始出卷：practice={} topic=\"{}\" paper={} seq={} mode={}",
                p.getId(), p.getTopic(), paper.getId(), paper.getSequence(), paper.getCompositionMode());

        launchGenerate(paper);
        return detailOf(p);
    }

    /* ================= 错题重做 ================= */

    /**
     * 针对待订正的错题出一张卷。
     *
     * 错题本原本「只进不出」——收了错题，复习它还得自己翻卷子找题。
     * 这里把错题反查到知识点，用这些点出卷，让错题能闭环。
     *
     * 选点范围锁定在出卷那一刻的错题上（写进 {@code focusPointsJson}），
     * 免得生成途中你去标记已订正、结果出的题对不上。
     * 其余（上下文、批改、回写掌握度）与普通卷完全一致。
     */
    public PracticeDetailDto startMistakeRetry(String practiceId) {
        Practice p = get(practiceId);
        Paper open = findOpenPaper(p.getId());
        if (open != null) {
            throw new IllegalArgumentException("第 " + open.getSequence() + " 张卷正在进行中（"
                    + statusLabel(open.getStatus()) + "），请等它结束再出错题卷");
        }

        Set<String> names = new LinkedHashSet<>();
        for (MistakeDto m : mistakes.dtoList(practiceId)) {
            if (!"open".equals(m.status())) continue;
            Question q = questions.findById(m.questionId()).orElse(null);
            if (q == null) continue;
            names.addAll(Mappings.parseStringList(q.getAssignedPointsJson()));
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("错题本里没有待订正的题，先把错题收进来");
        }

        Paper paper = new Paper();
        paper.setId(UUID.randomUUID().toString());
        paper.setPracticeId(p.getId());
        paper.setKind("mistake");
        paper.setFocusPointsJson(Mappings.writeStringList(new ArrayList<>(names)));
        paper.setSequence((int) (papers.countByPracticeId(p.getId()) + 1));
        paper.setStatus("generating");
        paper.setCreatedAt(System.currentTimeMillis());
        paper.setMaxScore(Scoring.PAPER_MAX);
        setProgress(paper, "出题", 0, 0, List.of(), null);
        papers.save(paper);
        touch(p);
        log.info("开始错题重做卷：practice={} paper={} 聚焦 {} 个点", p.getId(), paper.getId(), names.size());

        launchGenerate(paper);
        return detailOf(p);
    }

    /* ================= 期末综合考 ================= */

    /**
     * 申请一次期末综合考。
     *
     * 门槛看**覆盖**不看掌握度：掌握度低正是需要期末考来查漏的原因，
     * 卡掌握度等于「学会了才能考试」，逻辑是反的。掌握度只用来提示，不拦截。
     */
    public PracticeDetailDto startFinalExam(String practiceId) {
        Practice p = get(practiceId);
        FinalExamGateDto gate = finalExamGate(practiceId);
        if (!gate.unlocked()) {
            throw new IllegalArgumentException(gate.hint());
        }
        Paper open = findOpenPaper(p.getId());
        if (open != null) {
            throw new IllegalArgumentException("第 " + open.getSequence() + " 张卷正在进行中（"
                    + statusLabel(open.getStatus()) + "），请等它结束再申请期末考");
        }

        Paper paper = new Paper();
        paper.setId(UUID.randomUUID().toString());
        paper.setPracticeId(p.getId());
        paper.setKind("final");
        paper.setSequence((int) (papers.countByPracticeId(p.getId()) + 1));
        paper.setStatus("generating");
        paper.setCreatedAt(System.currentTimeMillis());
        paper.setMaxScore(Scoring.PAPER_MAX);
        setProgress(paper, "出题", 0, 0, List.of(), null);
        papers.save(paper);
        touch(p);
        log.info("开始期末考：practice={} paper={} seq={}", p.getId(), paper.getId(), paper.getSequence());

        launchGenerate(paper);
        return detailOf(p);
    }

    /**
     * 期末考解锁状态。
     * @param MIN_FINAL_PAPERS 出过几张普通卷才算「练过一阵子」
     */
    static final double FINAL_COVERAGE_GATE = 0.60;
    static final int MIN_FINAL_PAPERS = 3;

    public FinalExamGateDto finalExamGate(String practiceId) {
        List<KnowledgePoint> all = knowledge.listOf(practiceId);
        int total = all.size();
        int covered = 0;
        double sum = 0;
        for (KnowledgePoint kp : all) {
            if (kp.getTimesSeen() > 0) covered++;
            sum += kp.getMastery();
        }
        double coverage = total == 0 ? 0 : covered * 100.0 / total;
        double avgMastery = total == 0 ? 0 : Math.round(sum / total * 10) / 10.0;

        int normalPapers = 0;
        for (Paper paper : papers.findByPracticeIdOrderBySequenceAsc(practiceId)) {
            if (!isFinal(paper)) normalPapers++;
        }

        boolean unlocked = total > 0 && coverage >= FINAL_COVERAGE_GATE * 100
                && normalPapers >= MIN_FINAL_PAPERS;
        String hint;
        if (total == 0) {
            hint = "还没有知识点清单，先出一张普通卷。";
        } else if (normalPapers < MIN_FINAL_PAPERS) {
            hint = "至少要练过 " + MIN_FINAL_PAPERS + " 张普通卷才建议申请期末考（当前 "
                    + normalPapers + " 张）。";
        } else if (coverage < FINAL_COVERAGE_GATE * 100) {
            hint = "已覆盖 " + covered + "/" + total + " 个知识点（" + Math.round(coverage)
                    + "%），到 " + Math.round(FINAL_COVERAGE_GATE * 100) + "% 才解锁期末考。"
                    + "继续练就能铺到没碰过的点。";
        } else {
            hint = "可以申请期末考了。当前平均掌握度 " + avgMastery
                    + (avgMastery < 60 ? "——偏低，想稳一点可以再练练，但不影响申请。" : "。");
        }
        return new FinalExamGateDto(unlocked, normalPapers, total, covered, coverage, avgMastery, hint);
    }

    /** 历次期末考成绩单，最新的在前 */
    public List<FinalExamDto> finalExams(String practiceId) {
        List<FinalExamDto> out = new ArrayList<>();
        List<Paper> ps = papers.findByPracticeIdOrderBySequenceAsc(practiceId);
        for (int i = ps.size() - 1; i >= 0; i--) {
            Paper paper = ps.get(i);
            if (isFinal(paper)) out.add(finalReport(paper));
        }
        return out;
    }

    /** 一张期末考的成绩单：总分 + 抽样覆盖率 + 按大类得分率 */
    public FinalExamDto finalReport(Paper paper) {
        List<Question> qs = questions.findByPaperIdOrderBySequenceAsc(paper.getId());
        Map<String, String> catOf = new LinkedHashMap<>();
        for (KnowledgePoint kp : knowledge.listOf(paper.getPracticeId())) {
            catOf.put(kp.getName(), kp.getCategory());
        }

        Map<String, double[]> byCat = new LinkedHashMap<>(); // [分值合计, 实得合计]
        Map<String, Integer> askedPerCat = new LinkedHashMap<>();
        Set<String> askedPoints = new LinkedHashSet<>();
        Set<String> askedCats = new LinkedHashSet<>();
        List<Double> earnedAll = new ArrayList<>();
        List<QuestionDto> qdtos = new ArrayList<>();
        for (Question q : qs) {
            List<Attempt> ats = attempts.findByQuestionIdOrderBySequenceAsc(q.getId());
            qdtos.add(Mappings.question(q, ats));
            List<String> names = Mappings.parseStringList(q.getAssignedPointsJson());
            Double score = null;
            for (Attempt a : ats) {
                if (a.getEvaluatedAt() != null && a.getScore() != null) score = a.getScore();
            }
            Double earned = Scoring.earnedPoints(score, q.getPoints());
            earnedAll.add(earned);
            // 多点题的分值与实得分都摊到它挂的各点上，按大类归集
            double share = Math.max(1, names.size());
            for (String name : names) {
                String cat = catOf.getOrDefault(name, "综合");
                askedPoints.add(name);
                askedCats.add(cat);
                askedPerCat.merge(cat, 1, Integer::sum);
                double[] acc = byCat.computeIfAbsent(cat, k -> new double[2]);
                acc[0] += q.getPoints() / share;
                acc[1] += (earned == null ? 0 : earned) / share;
            }
        }

        List<CategoryScoreDto> cats = new ArrayList<>();
        for (Map.Entry<String, double[]> e : byCat.entrySet()) {
            double pts = e.getValue()[0], earn = e.getValue()[1];
            cats.add(new CategoryScoreDto(e.getKey(),
                    askedPerCat.getOrDefault(e.getKey(), 0),
                    (int) Math.round(pts), Math.round(earn * 10) / 10.0,
                    pts <= 0 ? 0 : Math.round(earn / pts * 1000) / 10.0));
        }

        boolean graded = "graded".equals(paper.getStatus());
        return new FinalExamDto(paper.getId(), paper.getSequence(), paper.getStatus(),
                paper.getCreatedAt(), paper.getSubmittedAt(),
                paper.getMaxScore() > 0 ? paper.getMaxScore() : Scoring.PAPER_MAX,
                graded ? Scoring.paperScore(earnedAll) : null,
                new CoverageDto(catOf.size(), askedPoints.size(),
                        new LinkedHashSet<>(catOf.values()).size(), askedCats.size()),
                cats, qdtos);
    }

    /** 断点续跑：出题/批改中断后从 planJson 继续，客户端刷新页面后照样能接上 */
    public PracticeDetailDto resumePaper(String paperId) {
        Paper paper = getPaper(paperId);
        if ("generating".equals(paper.getStatus())) {
            if (!launchGenerate(paper)) {
                throw new IllegalArgumentException("这张卷的出题任务正在进行中，请等它跑完");
            }
        } else if ("grading".equals(paper.getStatus())) {
            if (!launchGrade(paper)) {
                throw new IllegalArgumentException("这张卷的批改任务正在进行中，请等它跑完");
            }
        } else {
            throw new IllegalArgumentException("这张卷当前状态（" + statusLabel(paper.getStatus()) + "）不需要续跑");
        }
        return detailOf(get(paper.getPracticeId()));
    }

    /** @return false 表示这张卷已经有任务在跑，什么都没做 */
    private boolean launchGenerate(Paper paper) {
        if (!running.add(paper.getId())) return false;
        executor.submit(() -> doGenerate(paper));
        return true;
    }

    /** @return false 表示这张卷已经有任务在跑，什么都没做 */
    private boolean launchGrade(Paper paper) {
        if (!running.add(paper.getId())) return false;
        executor.submit(() -> doGrade(paper));
        return true;
    }

    /**
     * 出题主流程：挑知识点 → 让 AI 出角度 → 分批出题 → 全齐转「答题中」。
     * 缺题自动补轮，避免卡在「出了一半」。
     */
    private void doGenerate(Paper paper) {
        try {
            Practice p = get(paper.getPracticeId());
            List<PlanItem> plan = readPlan(paper);
            if (plan.isEmpty()) {
                setProgress(paper, "出题", 0, 0, List.of(), null);

                // ① 从知识点清单挑这一卷要考的点（补足/强化/深入，深度按点的出现次数）
                List<PlanItem> picked = pickPoints(p, paper);
                if (picked.isEmpty()) {
                    throw new LlmService.LlmException("这个练习还没有知识点清单，请重新创建练习");
                }
                log.info("出题选点：paper={} 共 {} 个点", paper.getId(), picked.size());

                // ② 让 AI 把点变成出题角度（这一步它可以用 web_search / fetch_url 做研究）
                RuntimeException last = null;
                for (int attempt = 1; attempt <= 3 && plan.isEmpty(); attempt++) {
                    try {
                        JsonNode angles = llm.chatJsonWithTools(PromptTemplates.INTERVIEW_SYSTEM,
                                PromptTemplates.paperPlanForPoints(contextSection(p, paper), pointLines(picked, p, paper)),
                                PLAN_MAX_TOKENS, false, planTools());
                        plan = parsePlan(picked, angles);
                    } catch (RuntimeException ex) {
                        last = ex;
                        log.warn("出题计划失败（第 {}/3 次）：{}", attempt, ex.getMessage());
                        try {
                            Thread.sleep(1500L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (plan.isEmpty()) {
                    throw last == null ? new LlmService.LlmException("AI 没有返回可用的出题计划") : last;
                }
                writePlan(paper, plan);
                // 角度落库：下次给同一个点出题时要靠它避开重复问法（变种题的落点）
                Map<String, String> angleByName = new LinkedHashMap<>();
                for (PlanItem it : plan) {
                    for (String name : it.pointNames()) angleByName.put(name, it.angle());
                }
                knowledge.recordAngles(p, paper.getSequence(), angleByName);
                log.info("出题计划完成：paper={} 共 {} 道题", paper.getId(), plan.size());
            }
            setProgress(paper, "出题", plan.size(), countQuestions(paper.getId()), List.of(), null);

            // 多轮补齐：模型偶尔会少给几道题，缺的题号下一轮重出，避免卡在「出了一半」
            final List<PlanItem> planItems = plan;
            List<Integer> failed = new ArrayList<>();
            for (int round = 0; round < 3; round++) {
                List<List<Integer>> runs = missingRuns(paper.getId(), planItems.size());
                if (runs.isEmpty()) break;
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (List<Integer> run : runs) {
                    futures.add(CompletableFuture.runAsync(() -> generateRun(p, paper, planItems, run), executor));
                }
                failed.clear();
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        futures.get(i).join();
                    } catch (CompletionException e) {
                        failed.addAll(runs.get(i));
                        log.warn("出题批次失败（题号 {}）：{}", runs.get(i), rootMessage(e));
                    }
                }
            }

            int done = countQuestions(paper.getId());
            if (failed.isEmpty() && done >= planItems.size()) {
                log.info("出卷完成：paper={} 共 {} 道题，进入答题", paper.getId(), done);
                paper.setStatus("answering");
                paper.setProgressJson(null);
                papers.save(paper);
                touch(p);
            } else {
                String msg = !failed.isEmpty()
                        ? "部分批次失败，点「续跑」继续出题"
                        : "还有 " + (planItems.size() - done) + " 道题没生成出来，点「续跑」继续";
                setProgress(paper, "出题", planItems.size(), done, failed, msg);
            }
        } catch (Exception e) {
            log.error("出题失败：paper={} 原因：{}", paper.getId(), rootMessage(e), e);
            setProgress(paper, "出题", readPlan(paper).size(), countQuestions(paper.getId()), List.of(), rootMessage(e));
        } finally {
            running.remove(paper.getId());
            markIdle(paper);
        }
    }

    /** 出一批题：成功立即落库并更新进度（这就是断点续跑的「点」） */
    private void generateRun(Practice p, Paper paper, List<PlanItem> plan, List<Integer> indexes) {
        List<PlanItem> slice = new ArrayList<>();
        for (int idx : indexes) slice.add(plan.get(idx));
        int fromSeq = indexes.get(0) + 1;

        JsonNode result = chatWithRetry(PromptTemplates.INTERVIEW_SYSTEM,
                PromptTemplates.questionChunkUser(scopeLine(p), slice, fromSeq), PAPER_MAX_TOKENS, false);
        List<JsonNode> items = new ArrayList<>();
        result.path("questions").forEach(items::add);

        for (int k = 0; k < indexes.size() && k < items.size(); k++) {
            int idx = indexes.get(k);
            int seq = idx + 1;
            if (hasQuestion(paper.getId(), seq)) continue;
            JsonNode item = items.get(k);
            String prompt = Mappings.text(item, "prompt").trim();
            if (prompt.isBlank()) continue;
            PlanItem planItem = plan.get(idx);

            Question q = new Question();
            q.setId(UUID.randomUUID().toString());
            q.setPracticeId(p.getId());
            q.setPaperId(paper.getId());
            q.setSequence(seq);
            q.setPrompt(prompt);
            q.setReferenceAnswer(Mappings.text(item, "referenceAnswer").trim());
            q.setKnowledgePointsJson(Mappings.writeStringList(
                    Mappings.cap(Mappings.stringList(item, "knowledgePoints"), 3)));
            // 「本题要求」是批改清单：基础/中档最多 2 条、大题最多 3 条（对应 ①②③），
            // 在数据层卡死，不指望模型自觉。批改只按它逐条核对
            q.setDemandsJson(Mappings.writeStringList(
                    Mappings.cap(Mappings.stringList(item, "demands"),
                            "大题".equals(planItem.tier()) ? 3 : 2)));
            // 后端挑中的点名是掌握度回写的主键，必须落到题上；
            // knowledgePointsJson 是 AI 自己的叫法，两者不能混用
            q.setAssignedPointsJson(Mappings.writeStringList(planItem.pointNames()));
            q.setAngle(planItem.angle());
            q.setTier(planItem.tier());
            q.setQuestionType(planItem.type());
            q.setPoints(planItem.points());
            q.setCreatedAt(System.currentTimeMillis());
            questions.save(q);
        }
        setProgress(paper, "出题", plan.size(), countQuestions(paper.getId()), List.of(), null);
    }

    /* ================= 作答：不调 AI ================= */

    /** 保存作答（追加一条，历史不覆盖）。纯落库，答题全程零等待。 */
    public QuestionDto saveAttempt(String questionId, String answer) {
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException("作答内容不能为空");
        Question q = getQuestion(questionId);
        Practice p = get(q.getPracticeId());
        Paper paper = getPaper(q.getPaperId());
        if (!"answering".equals(paper.getStatus())) {
            throw new IllegalArgumentException("第 " + paper.getSequence() + " 张卷当前是「"
                    + statusLabel(paper.getStatus()) + "」，不能修改作答");
        }

        Attempt a = new Attempt();
        a.setId(UUID.randomUUID().toString());
        a.setQuestionId(q.getId());
        a.setSequence(attempts.findByQuestionIdOrderBySequenceAsc(q.getId()).size() + 1);
        a.setAnswer(answer.trim());
        a.setSubmittedAt(System.currentTimeMillis());
        attempts.save(a);
        touch(p);
        return Mappings.question(q, attempts.findByQuestionIdOrderBySequenceAsc(q.getId()));
    }

    /* ================= 交卷（后台任务） ================= */

    /** 发起交卷：立刻转「批改中」并返回，后台批改，前端轮询进度 */
    public PracticeDetailDto submitPaper(String paperId) {
        Paper paper = getPaper(paperId);
        if (!"answering".equals(paper.getStatus())) {
            throw new IllegalArgumentException("第 " + paper.getSequence() + " 张卷当前是「"
                    + statusLabel(paper.getStatus()) + "」，不能交卷");
        }
        Practice p = get(paper.getPracticeId());
        List<Question> qs = questions.findByPaperIdOrderBySequenceAsc(paper.getId());
        if (latestAttempts(qs).isEmpty()) {
            throw new IllegalArgumentException("本卷还没有任何作答，先答几题再交卷");
        }

        paper.setStatus("grading");
        setProgress(paper, "批改", qs.size(), 0, List.of(), null);
        papers.save(paper);
        touch(p);

        launchGrade(paper);
        return detailOf(p);
    }

    /**
     * 本卷总结 / 记忆 / 会话摘要 / 阶段总结——批改完成后的收尾工作。
     * 这里每个 AI 调用都**各自容错**：任何一个失败都只是降级，绝不把已批完的卷卡在「批改中」。
     * 卷的 graded 状态翻转由调用方负责。
     */
    private void writeSummaries(Practice p, Paper paper, List<Question> qs) {
        // 本卷总结 / 记忆 / 会话摘要：三个并行小调用，输出都短，不会被截断
        String records = gradedRecordsText(qs);
        String brief = briefRecordsText(qs);
        String scope = scopeLine(p);
        CompletableFuture<JsonNode> summaryF = CompletableFuture.supplyAsync(() -> chatWithRetry(
                PromptTemplates.INTERVIEW_SYSTEM, PromptTemplates.paperSummaryUser(scope, records), SUMMARY_MAX_TOKENS, false), executor);
        CompletableFuture<JsonNode> memoryF = CompletableFuture.supplyAsync(() -> chatWithRetry(
                PromptTemplates.INTERVIEW_SYSTEM,
                PromptTemplates.memoryUpdateUser(scope, brief, p.getMemoryJson()), SUMMARY_MAX_TOKENS, false), executor);
        CompletableFuture<JsonNode> digestF = CompletableFuture.supplyAsync(() -> chatWithRetry(
                PromptTemplates.INTERVIEW_SYSTEM,
                PromptTemplates.digestUpdateUser(scope, p.getDigestJson(), statsLine(p), brief), SUMMARY_MAX_TOKENS, false), executor);
        // ② 总结 / 记忆 / 摘要：三个并行小调用，**各自容错**。
        //    这一阶段失败绝不能把已批完的卷卡住——那是"批阅后提示任务停止"的根因。
        JsonNode summary = joinOrNull(summaryF);
        JsonNode mem = joinOrNull(memoryF);
        JsonNode dig = joinOrNull(digestF);

        ObjectNode summaryJson = Mappings.MAPPER.createObjectNode();
        if (summary == null) {
            summaryJson.put("overall", "");
            summaryJson.put("failed", true);
        } else {
            summaryJson.put("overall", Mappings.text(summary, "overall"));
            ArrayNode strengths = summaryJson.putArray("strengths");
            Mappings.stringList(summary, "strengths").forEach(strengths::add);
            ArrayNode improvements = summaryJson.putArray("improvements");
            Mappings.stringList(summary, "improvements").forEach(improvements::add);
        }
        summaryJson.put("gradedAt", System.currentTimeMillis());
        paper.setSummaryJson(summaryJson.toString());
        papers.save(paper); // 先落盘，阶段总结要读到这张卷的 AI 评价

        // ① 先落记忆与摘要——阶段总结要读到刚更新的记忆，所以它必须排在这之后
        if (mem != null) p.setMemoryJson(writeMemory(mem, p.getMemoryJson()));
        if (dig != null) p.setDigestJson(writeDigest(dig));
        touch(p);

        // ② 阶段总结（AI 反馈）。它失败不能影响批改结果，单独吞掉。
        //    放在「翻成已交卷」之前：用户看到交卷完成时，阶段总结必须已经就绪
        // 阶段总结失败不能影响批改结果，但要让用户看得见，好去手动重出
        try {
            stageSummary.regenerate(p.getStageId());
        } catch (Exception ex) {
            log.warn("阶段总结生成失败（不影响批改结果）：{}", rootMessage(ex));
            JsonNode existing = Mappings.parseJson(paper.getSummaryJson());
            ObjectNode sj = existing != null && existing.isObject()
                    ? (ObjectNode) existing : Mappings.MAPPER.createObjectNode();
            sj.put("stageSummaryFailed", true);
            paper.setSummaryJson(sj.toString());
            papers.save(paper);
        }

    }

    /** 批改主流程：分批并发批改已作答题 → 本卷总结 → 记忆/摘要 → 转「已交卷」 */
    private void doGrade(Paper paper) {
        try {
            Practice p = get(paper.getPracticeId());
            List<Question> qs = questions.findByPaperIdOrderBySequenceAsc(paper.getId());
            Map<Question, Attempt> latest = latestAttempts(qs);
            if (latest.isEmpty()) throw new IllegalStateException("本卷没有任何已作答题目");
            log.info("开始批改：paper={} 已作答 {} 题", paper.getId(), latest.size());

            // 看过参考答案的题按规则直接 0 分，不交给 AI 批改
            List<Map.Entry<Question, Attempt>> pending = new ArrayList<>();
            for (Map.Entry<Question, Attempt> e : latest.entrySet()) {
                Attempt a = e.getValue();
                if (a.getEvaluatedAt() != null) continue; // 断点续跑时已写回的跳过
                if (e.getKey().getRevealedAt() != null) {
                    a.setScore(0.0);
                    a.setPoints(0.0);
                    a.setFeedback("本题已查看参考答案，按规则计 0 分。");
                    a.setDimensionsJson(Mappings.MAPPER.createObjectNode().toString());
                    a.setEvaluatedAt(System.currentTimeMillis());
                    attempts.save(a);
                    continue;
                }
                pending.add(e);
            }
            if (!pending.isEmpty()) {
                int chunkCnt = (pending.size() + GRADE_CHUNK - 1) / GRADE_CHUNK;
                List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
                for (int i = 0; i < pending.size(); i += GRADE_CHUNK) {
                    int chunkNo = i / GRADE_CHUNK + 1;
                    List<Map.Entry<Question, Attempt>> chunk =
                            pending.subList(i, Math.min(i + GRADE_CHUNK, pending.size()));
                    futures.add(CompletableFuture.supplyAsync(() -> chatWithRetry(PromptTemplates.INTERVIEW_SYSTEM,
                            PromptTemplates.gradeUser(scopeLine(p), chunkNo, chunkCnt, gradeChunkText(chunk)),
                            PAPER_MAX_TOKENS), executor));
                }
                // 一批失败不要拖垮整卷：先记下来继续跑，末尾统一补一次
                List<Integer> failedBatches = new ArrayList<>();
                for (int i = 0; i < futures.size(); i++) {
                    List<Map.Entry<Question, Attempt>> chunk =
                            pending.subList(i * GRADE_CHUNK, Math.min((i + 1) * GRADE_CHUNK, pending.size()));
                    try {
                        applyEvaluations(chunk, joinChunk(futures.get(i)));
                    } catch (Exception ex) {
                        failedBatches.add(i);
                        log.warn("批改第 {} 批失败，稍后补：{}", i + 1, rootMessage(ex));
                    }
                    setProgress(paper, "批改", qs.size(), countEvaluated(qs), List.of(), null);
                }
                for (int i : failedBatches) {
                    List<Map.Entry<Question, Attempt>> chunk =
                            pending.subList(i * GRADE_CHUNK, Math.min((i + 1) * GRADE_CHUNK, pending.size()));
                    applyEvaluations(chunk, chatWithRetry(PromptTemplates.INTERVIEW_SYSTEM,
                            PromptTemplates.gradeUser(scopeLine(p), i + 1, chunkCnt, gradeChunkText(chunk)),
                            PAPER_MAX_TOKENS));
                }
            }

            // ①b 按知识点回写掌握度，并从用户作答里发散新知识点（只收范围内的）。
            // 期末考**不回写**——它是总结性测量，成绩单单独留，别污染学习轨道。
            if (!isFinal(paper)) {
                try {
                    List<KnowledgeService.PointHit> hits = new ArrayList<>();
                    StringBuilder allAnswers = new StringBuilder();
                    for (Question q : qs) {
                        Attempt a = latestAttempt(q);
                        List<String> names = Mappings.parseStringList(q.getAssignedPointsJson());
                        if (a == null || names.isEmpty()) continue;
                        Double score = a.getScore();
                        if (score == null) continue;
                        hits.add(new KnowledgeService.PointHit(names, score, q.getAngle()));
                        if (allAnswers.length() > 0) allAnswers.append('\n');
                        allAnswers.append(a.getAnswer());
                    }
                    knowledge.record(p, paper.getSequence(), hits);
                    String scopeText = "range".equals(p.getScopeMode()) ? p.getScopeText() : p.getTopic();
                    knowledge.harvest(p, allAnswers.toString(), scopeText);
                } catch (Exception ex) {
                    log.warn("知识点回写失败（不影响批改结果）：{}", ex.getMessage());
                }
            }

            // ①c 错题自动收录：低分/未作答/看了参考答案。失败不能影响批改结果（同知识点回写）。
            // 期末考也收——空着的题是真缺口；嫌吵可在这一行加 isFinal 跳过。
            try {
                Map<String, Attempt> latestById = new LinkedHashMap<>();
                for (Map.Entry<Question, Attempt> e : latest.entrySet()) {
                    latestById.put(e.getKey().getId(), e.getValue());
                }
                mistakes.autoCollect(p.getId(), qs, latestById);
            } catch (Exception ex) {
                log.warn("错题收录失败（不影响批改结果）：{}", ex.getMessage());
            }

            // ② 总结 / 记忆 / 摘要 / 阶段总结（各自容错，失败只降级不卡卷）
            writeSummaries(p, paper, qs);

            // ③ 最后才把卷翻成「已交卷」——批改已落库，无论总结成不成都要走到这
            long now = System.currentTimeMillis();
            log.info("批改完成：paper={} 已交卷", paper.getId());
            paper.setStatus("graded");
            paper.setSubmittedAt(now);
            paper.setProgressJson(null);
            papers.save(paper);
        } catch (Exception e) {
            log.error("批改失败：paper={} 原因：{}", paper.getId(), rootMessage(e));
            setProgress(paper, "批改", questions.findByPaperIdOrderBySequenceAsc(paper.getId()).size(),
                    countEvaluated(questions.findByPaperIdOrderBySequenceAsc(paper.getId())), List.of(), rootMessage(e));
        } finally {
            running.remove(paper.getId());
            markIdle(paper);
        }
    }

    /** 重出本卷总结 / 记忆 / 摘要 / 阶段总结（不重新批改）。用于总结生成失败时补救。 */
    public PracticeDetailDto regeneratePaperSummary(String paperId) {
        Paper paper = getPaper(paperId);
        if (!"graded".equals(paper.getStatus())) {
            throw new IllegalArgumentException("这张卷还没交卷，交卷后才有总结可重出");
        }
        if (running.contains(paper.getId())) {
            throw new IllegalArgumentException("这张卷还有任务在跑，请等它结束");
        }
        Practice p = get(paper.getPracticeId());
        writeSummaries(p, paper, questions.findByPaperIdOrderBySequenceAsc(paper.getId()));
        return detailOf(p);
    }

    /** 删除练习（级联：卷 → 题 → 作答）。误操作填错时用。 */
    public void deletePractice(String practiceId) {
        Practice p = get(practiceId);
        for (Paper paper : papers.findByPracticeIdOrderBySequenceAsc(p.getId())) {
            if (running.contains(paper.getId())) {
                throw new IllegalArgumentException("这个练习还有任务在跑，请等它结束再删除");
            }
        }
        for (Paper paper : papers.findByPracticeIdOrderBySequenceAsc(p.getId())) {
            for (Question q : questions.findByPaperIdOrderBySequenceAsc(paper.getId())) {
                attempts.deleteAll(attempts.findByQuestionIdOrderBySequenceAsc(q.getId()));
            }
            questions.deleteAll(questions.findByPaperIdOrderBySequenceAsc(paper.getId()));
        }
        papers.deleteAll(papers.findByPracticeIdOrderBySequenceAsc(p.getId()));
        knowledge.deleteByPractice(p.getId());
        notes.deleteByPractice(p.getId());
            memoryPoints.deleteByPractice(p.getId());
        mistakes.deleteByPractice(p.getId());
        practices.deleteAll(List.of(p));
    }

    /* ================= 看参考答案（本题计 0 分） ================= */

    /**
     * 用户点「看参考答案」：打标记，交卷时这道题**直接计 0 分**，不再交给 AI 批改。
     *
     * 这是刻意的规则——参考答案是拿来复习的，看了就不能再拿这题的分。
     * 所以前端必须先弹确认框说明后果；后端也真的按 0 分算，两边一致。
     */
    public QuestionDto reveal(String questionId) {
        Question q = getQuestion(questionId);
        Paper paper = getPaper(q.getPaperId());
        // 期末考是测量：看了答案这一题就失去测量意义，所以压根不给看
        if (isFinal(paper)) {
            throw new IllegalArgumentException("期末考不能查看参考答案——看了就测不准了。考完出成绩单时会一起给出答案对照。");
        }
        if (!"answering".equals(paper.getStatus())) {
            throw new IllegalArgumentException("第 " + paper.getSequence() + " 张卷当前是「"
                    + statusLabel(paper.getStatus()) + "」，参考答案已随卷公开，不需要再点");
        }
        if (q.getRevealedAt() == null) {
            q.setRevealedAt(System.currentTimeMillis());
            questions.save(q);
            touch(get(q.getPracticeId()));
        }
        return Mappings.question(q, attempts.findByQuestionIdOrderBySequenceAsc(q.getId()));
    }

    /* ================= 知识点选点 ================= */

    /**
     * 从知识点清单里挑这一卷的点。清单可能还没建（老练习），这里兜底拆一次。
     *
     * 挑点逻辑在 {@link KnowledgeService#pick}：补足 / 强化 / 深入，
     * 每个点的深度由它**自己的 timesSeen** 决定，不是由卷序决定。
     * 串联大题不额外占名额——从已覆盖的点里挑几个同大类的，捆成卷末那一道。
     */
    private List<PlanItem> pickPoints(Practice p, Paper paper) {
        // 清单是第一次出题时才拆的（懒加载），拆完就一直用
        if (knowledge.listOf(p.getId()).isEmpty()) {
            knowledge.scaffold(p);
        }
        // 期末考走分层抽样，不走补足/强化/深入——它要的是无偏测量
        if (isFinal(paper)) return finalPlanItems(p);
        // 错题重做卷只从待订正错题反查出的点里选
        if (isMistake(paper)) return mistakePlanItems(p, paper);
        String mode = CompositionModes.normalize(paper.getCompositionMode());
        List<KnowledgePoint> all = knowledge.listOf(p.getId());
        int size = CompositionModes.questionCount(mode, paperSizeOf(p));

        // 串联大题：同大类里已覆盖的 2-3 个点，捆成一道卷末综合题，占 1 个题位。
        // 只在允许大题的模式下启用——基础卷/提升卷塞不下一整道场景题
        List<KnowledgePoint> linked = knowledge.pickLinkable(all, 3);
        boolean hasLink = linked.size() >= 2 && CompositionModes.allowsLink(mode);
        int linkSlots = hasLink ? 1 : 0;

        Set<String> used = new LinkedHashSet<>();
        for (KnowledgePoint kp : linked) used.add(kp.getName());

        List<PlanItem> out = new ArrayList<>();
        if (hasLink) {
            // 串联题就是场景大题（组卷模式允许大题才走到这），形式不靠深度推
            String tier = CompositionModes.snapTier("大题", mode);
            List<String> names = linked.stream().map(KnowledgePoint::getName).toList();
            out.add(new PlanItem(names, String.join(" + ", names) + "（串联）", tier, typeOf(tier),
                    pointsOf(tier)));
        }

        // 单点题补满剩下的题位；串联点已占掉，不要再各出一道
        for (KnowledgePoint kp : knowledge.pick(p, size - linkSlots)) {
            if (out.size() >= size) break;
            if (used.contains(kp.getName())) continue;
            used.add(kp.getName());
            out.add(singleItem(kp, p, mode));
        }
        // 选点结果可能因去重短几道，从清单里再补
        if (out.size() < size) {
            for (KnowledgePoint kp : all) {
                if (out.size() >= size) break;
                if (used.contains(kp.getName())) continue;
                used.add(kp.getName());
                out.add(singleItem(kp, p, mode));
            }
        }
        // 前易后难：基础在前、中档居中、大题靠后，**串联题永远在卷末**
        distributeTiers(out, p, mode);
        capMajors(out, mode);
        capByLevel(out, p, mode);
        out.sort(Comparator.comparingInt(PracticeService::tierRank));
        return out;
    }

    /**
     * 岗位难度管**题型取舍**：初级不考场景大题（概念与原理够用），
     * 中级/高级才碰大题。这是「不想进中小厂就不用死磕场景」的落点。
     */
    private static void capByLevel(List<PlanItem> out, Practice p, String mode) {
        String lv = p.getLevel() == null ? "mid" : p.getLevel().trim().toLowerCase();
        if (!lv.startsWith("junior") && !lv.startsWith("初")) return;
        for (int i = 0; i < out.size(); i++) {
            PlanItem it = out.get(i);
            if (!"大题".equals(it.tier())) continue;
            String tier = CompositionModes.snapTier("中档", mode);
            out.set(i, new PlanItem(it.pointNames(), it.angle(), tier, typeOf(tier), pointsOf(tier)));
        }
    }

    /**
     * 按组卷模式 + 复习强度分配题型档位（基础/中档/大题是**题型形式**，不是内容深度）。
     *
     * <p>谁进高档：掌握度高的点出更高档的形式（熟了才配场景题），
     * 掌握度相同时按清单顺序。内容深浅由点上的规格 + 复习强度管，这里只管形式配比。
     *
     * <pre>
     * 单档模式（基础/提升/深入）  吸附已定，这里不动
     * 两档模式（基础强化/中档提升）按配额分，高档约 1/3 ~ 2/5
     * 常规模式                    按强度权重：速通偏基础，标准三档都有，深挖偏中档大题
     * </pre>
     */
    private void distributeTiers(List<PlanItem> out, Practice p, String mode) {
        String m = CompositionModes.normalize(mode);
        Set<String> allowed = CompositionModes.tiersOf(m);
        if (allowed.size() == 1) return;

        String[] tiers;      // 按权重展开的档位序列
        if (allowed.size() == 2) {
            String high = allowed.contains("大题") ? "大题" : "中档";
            String low = "大题".equals(high) ? "中档" : "基础";
            int highTarget = "大题".equals(high)
                    ? Math.max(1, out.size() * 2 / 5)
                    : Math.max(1, out.size() / 3);
            tiers = new String[out.size()];
            for (int i = 0; i < tiers.length; i++) tiers[i] = i < highTarget ? high : low;
        } else {
            // 常规三档：按强度权重铺开
            int[] w = Intensities.tierWeights(p.getIntensity());
            int total = Math.max(0, w[0]) + Math.max(0, w[1]) + Math.max(0, w[2]);
            tiers = new String[out.size()];
            if (total == 0) {
                java.util.Arrays.fill(tiers, "基础");
            } else {
                String[] order = {"基础", "中档", "大题"};
                int idx = 0;
                for (int t = 0; t < 3; t++) {
                    int n = (int) Math.round(out.size() * (Math.max(0, w[t]) / (double) total));
                    for (int i = 0; i < n && idx < tiers.length; i++) tiers[idx++] = order[t];
                }
                while (idx < tiers.length) tiers[idx++] = "基础";
            }
        }

        // 串联题保留原档（本来就是高档），其余按掌握度降序抢占高档名额
        List<PlanItem> singles = new ArrayList<>();
        int linkHigh = 0;
        for (PlanItem it : out) {
            if (it.isLink()) {
                if ("大题".equals(it.tier()) || "中档".equals(it.tier())) linkHigh++;
            } else {
                singles.add(it);
            }
        }
        // 掌握度高的点拿更高档形式；掌握度相同时后考过的（清单顺序靠后的）略优先
        singles.sort((a, b) -> Double.compare(masteryOfItem(b, p), masteryOfItem(a, p)));

        // tiers 序列里已经排好「前面是高档」，把高档名次分给掌握度高的
        java.util.Arrays.sort(tiers, (x, y) -> Integer.compare(tierRankOf(y), tierRankOf(x)));
        int si = 0;
        int usedHigh = linkHigh;
        for (int i = 0; i < out.size(); i++) {
            PlanItem it = out.get(i);
            if (it.isLink()) continue;
            // 高档名额：tiers 里从前往后是高档，扣掉串联占的
            String tier = tiers[si++];
            if ("大题".equals(tier) || "中档".equals(tier)) {
                if (usedHigh > 0) usedHigh--;
            }
            if (!tier.equals(it.tier())) {
                out.set(i, new PlanItem(it.pointNames(), it.angle(), tier,
                        typeOf(tier), pointsOf(tier)));
            }
        }
    }

    private static int tierRankOf(String tier) {
        return "大题".equals(tier) ? 2 : "中档".equals(tier) ? 1 : 0;
    }

    /** 这道单点题挂的知识点掌握度（清单里查不到按 0） */
    private double masteryOfItem(PlanItem it, Practice p) {
        for (KnowledgePoint k : knowledge.listOf(p.getId())) {
            if (k.getName().equals(it.pointNames().get(0))) return k.getMastery();
        }
        return 0;
    }

    /**
     * 大题要够重但不能吃掉半张卷：除「深入」（整卷就是大题）外最多 4 道。
     * 没有这道闸，深挖强度下可能整卷吸成大题，
     * 保底分值 10×15 直接顶爆 100，Scoring 只能退回无档位均摊、15-20 的区间就没了。
     */
    private static void capMajors(List<PlanItem> out, String mode) {
        if (CompositionModes.DEEP.equals(CompositionModes.normalize(mode))) return;
        int max = 4;
        int majors = 0;
        for (PlanItem it : out) if ("大题".equals(it.tier())) majors++;
        // 从后往前压（串联题加在最前，优先保住它）
        for (int i = out.size() - 1; i >= 0 && majors > max; i--) {
            PlanItem it = out.get(i);
            if (!"大题".equals(it.tier())) continue;
            String tier = CompositionModes.snapTier("中档", mode);
            out.set(i, new PlanItem(it.pointNames(), it.angle(), tier,
                    typeOf(tier), pointsOf(tier)));
            majors--;
        }
    }

    /** 排序键：串联题恒排最后（3），其余按基础(0)/中档(1)/大题(2) */
    private static int tierRank(PlanItem it) {
        if (it.isLink()) return 3;
        return "大题".equals(it.tier()) ? 2 : "中档".equals(it.tier()) ? 1 : 0;
    }

    /**
     * 单点计划项。档位由组卷模式决定（形式），内容深浅由点上的规格 + 复习强度决定——
     * 不再有「timesSeen 越多挖越深」的自动加深。
     */
    private static PlanItem singleItem(KnowledgePoint kp, Practice p, String mode) {
        // 初始档位给「基础」，最终配比由 distributeTiers 按模式+强度统一调配
        String tier = CompositionModes.snapTier("基础", mode);
        return new PlanItem(List.of(kp.getName()), kp.getName(), tier,
                typeOf(tier), pointsOf(tier));
    }

    /** 题型跟着档位走（这是形式，不是内容深度） */
    private static String typeOf(String tier) {
        return "大题".equals(tier) ? "场景应用"
                : "中档".equals(tier) ? "原理追问"
                : "概念辨析";
    }

    /** 初始分值（最终由 Scoring 归一到合计 100） */
    private static int pointsOf(String tier) {
        return "大题".equals(tier) ? 16 : "中档".equals(tier) ? 6 : 3;
    }

    /** 一卷几道题：默认 10，快练 6 / 标准 10 / 深练 15 */
    static int paperSizeOf(Practice p) {
        int n = p.getPaperSize();
        return n == 6 || n == 15 ? n : 10;
    }

    /** 是否期末综合考 */
    static boolean isFinal(Paper paper) {
        return paper != null && "final".equals(paper.getKind());
    }

    /** 是否错题重做卷 */
    static boolean isMistake(Paper paper) {
        return paper != null && "mistake".equals(paper.getKind());
    }

    /**
     * 错题重做卷的计划项：只从 {@code focusPointsJson} 锁定的点里选。
     * 深度照旧按每个点自己的出现次数走——错题重做也是普通卷，不是另一套测量。
     */
    private List<PlanItem> mistakePlanItems(Practice p, Paper paper) {
        Set<String> focus = new LinkedHashSet<>(Mappings.parseStringList(paper.getFocusPointsJson()));
        List<PlanItem> out = new ArrayList<>();
        for (KnowledgePoint kp : knowledge.listOf(p.getId())) {
            if (!focus.contains(kp.getName())) continue;
            out.add(singleItem(kp, p, CompositionModes.REGULAR));
            if (out.size() >= paperSizeOf(p)) break;
        }
        if (out.isEmpty()) {
            throw new LlmService.LlmException("错题对应的考点已经不在清单里了，请刷新后重试");
        }
        out.sort(Comparator.comparingInt(PracticeService::tierRank));
        return out;
    }

    /** 期末考题量：固定 20 道，跟普通卷的 6/10/15 无关——它是独立的一张综合卷 */
    static final int FINAL_QUESTION_COUNT = 20;

    /** 期末考的计划项：分层抽样出的每道题（1 个点或 3 个点）。形式按强度配比，内容规格取当前强度档 */
    private List<PlanItem> finalPlanItems(Practice p) {
        List<PlanItem> out = new ArrayList<>();
        for (List<KnowledgePoint> group : knowledge.pickFinal(p, FINAL_QUESTION_COUNT)) {
            if (group.isEmpty()) continue;
            // 多点综合题出场景应用（大题形式），单点按强度铺基础/中档
            List<String> names = group.stream().map(KnowledgePoint::getName).toList();
            String tier;
            if (group.size() > 1) {
                tier = "大题";
            } else {
                // 确定性铺档：速通偏基础，标准/深挖基础与中档各半
                int[] w = Intensities.tierWeights(p.getIntensity());
                tier = w[1] >= w[0] && (out.size() % 2 == 1) ? "中档" : "基础";
            }
            out.add(new PlanItem(names, String.join(" + ", names), tier, typeOf(tier), pointsOf(tier)));
        }
        // 前易后难；综合题排在同类题之后
        out.sort(Comparator.comparingInt(PracticeService::tierRank));
        return out;
    }

    /**
     * 给 AI 看的选点清单，每行：
     * 「[补足] 点名 | 规格：xxx | 已问过：角度1、角度2」
     * 串联行是「[串联] 点A + 点B | 规格：各按自己规格 | 已问过：…」。
     *
     * 「规格」是内容天花板（考到哪为止），由点上的三档规格 + 复习强度决定——
     * 这是防「越考越深」的关键，不是给模型一个「第 N 层」让它自行发挥。
     * 「已问过」是变种题的落点——不告诉模型上一次怎么问的，「换一个问法」就是空话。
     */
    private List<String> pointLines(List<PlanItem> picked, Practice p, Paper paper) {
        List<KnowledgePoint> all = knowledge.listOf(p.getId());
        Map<String, KnowledgePoint> byName = new LinkedHashMap<>();
        for (KnowledgePoint kp : all) byName.put(kp.getName(), kp);
        boolean finalExam = isFinal(paper);

        List<String> lines = new ArrayList<>();
        for (PlanItem it : picked) {
            String label;
            String boundPart;
            boolean multi = it.pointNames().size() > 1;
            if (finalExam) {
                // 期末考不标补足/强化/巩固/串联——那是学习轨道的措辞，测量要无偏
                label = multi ? "[综合]" : "[单点]";
                boundPart = multi ? "规格：各按自己规格" : "规格：" + boundLabelOf(it, p);
            } else if (multi) {
                label = "[串联]";
                boundPart = "规格：各按自己规格";
            } else {
                KnowledgePoint kp = byName.get(it.pointNames().get(0));
                label = "[" + sideLabel(kp) + "]";
                boundPart = "规格：" + boundLabelOf(it, p);
            }
            StringBuilder past = new StringBuilder();
            for (String name : it.pointNames()) {
                KnowledgePoint kp = byName.get(name);
                if (kp == null) continue;
                List<String> angles = knowledge.anglesOf(kp);
                if (angles.isEmpty()) continue;
                if (past.length() > 0) past.append("；");
                past.append(it.pointNames().size() > 1 ? name + "：" : "")
                    .append(String.join("、", angles));
            }
            lines.add(label + " " + it.pointLabel() + " | " + boundPart
                    + " | " + (past.length() == 0 ? "无（新点）" : "已问过：" + past));
        }
        return lines;
    }

    /** 这个点本卷是补足 / 强化 / 巩固哪一档 */
    private static String sideLabel(KnowledgePoint kp) {
        if (kp == null) return "补足";
        if (kp.getTimesSeen() == 0) return "补足";
        return "已掌握".equals(kp.getStatus()) ? "巩固" : "强化";
    }

    /** 这道题的内容规格（考到哪为止）——由点上的三档规格 + 当前复习强度决定 */
    private String boundLabelOf(PlanItem it, Practice p) {
        KnowledgePoint kp = null;
        for (KnowledgePoint k : knowledge.listOf(p.getId())) {
            if (k.getName().equals(it.pointNames().get(0))) { kp = k; break; }
        }
        String bound = Intensities.boundOf(kp, p.getIntensity());
        return bound == null || bound.isBlank()
                ? "概念与用法、主干流程、关键参数（不要考源码实现）"
                : bound;
    }

    /* ================= 上下文（受 PromptBudget 硬预算约束） ================= */

    /**
     * 出题/批改的上下文：范围 + L2 摘要 + L1 记忆 + 最近历史。
     * 每段都有硬上限，历史从最新往回砍，保证单次 prompt 长度永远有顶。
     * 范围里的 URL 不再预抓正文——由智能体自己调 fetch_url 工具读取。
     *
     * **期末考只给范围**。摘要、记忆、最近历史都会暗示「最近在考什么」，
     * 那是给形成性学习用的，掺进总结性测量里就测不准了。
     */
    private String contextSection(Practice p, Paper paper) {
        String scope = MemoryRules.cut(PromptTemplates.scopeSection(p.getTopic(), p.getScopeMode(), p.getScopeText()),
                PromptBudget.MAX_SCOPE);
        if (isFinal(paper)) return scope;

        String digest = MemoryRules.cut(digestText(p), PromptBudget.MAX_DIGEST);
        String memory = MemoryRules.cut(memoryText(p), PromptBudget.MAX_MEMORY);

        int used = scope.length() + digest.length() + memory.length();
        List<String> recent = PromptBudget.fitRecent(recentLines(p), used);

        StringBuilder sb = new StringBuilder(scope);
        if (!digest.isBlank()) sb.append("\n\n会话摘要（长期积累，用来替代全部历史）：\n").append(digest);
        if (!memory.isBlank()) sb.append("\n\n长期记忆：\n").append(memory);
        if (!recent.isEmpty()) {
            sb.append("\n\n最近的题目与表现（最新的在前）：\n");
            for (String line : recent) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** 出题规划环节给模型的两个工具：联网搜索 + 抓网页正文 */
    private List<LlmService.Tool> planTools() {
        JsonNode searchSchema = parseSchema("""
            {"type":"object","properties":{"query":{"type":"string","description":"搜索关键词，中文优先，例如「Java 线程池 高频面试题」"}},"required":["query"],"additionalProperties":false}
            """);
        JsonNode fetchSchema = parseSchema("""
            {"type":"object","properties":{"url":{"type":"string","description":"完整的 http(s) 链接"}},"required":["url"],"additionalProperties":false}
            """);

        return List.of(
            new LlmService.Tool("web_search",
                "联网搜索，返回若干条搜索结果（标题+摘要）。用来核对某个技术点在当前面试中的高频度与时效性，或了解最新版本的主流实践。只在需要确认「现在还常不常问」「最新特性/最佳实践」时使用。",
                searchSchema,
                input -> {
                    String query = input.path("query").asText("");
                    if (query.isBlank()) return "搜索关键词为空，请提供 query";
                    List<WebSearch.Hit> hits = webSearch.search(query);
                    if (hits.isEmpty()) {
                        return "（没有搜到「" + query + "」的相关结果。请基于已有知识继续，或换个关键词再试）";
                    }
                    return WebSearch.toPrompt(hits);
                }),
            new LlmService.Tool("fetch_url",
                "抓取一个网页，返回标题与正文纯文本（已去噪，最多约 6000 字）。当练习范围里给出了 URL、或需要读某篇资料的内容时使用。PDF、需要登录或纯前端渲染的页面读不到，会返回失败原因。",
                fetchSchema,
                input -> {
                    String url = input.path("url").asText("");
                    if (url.isBlank()) return "URL 为空，请提供 url";
                    WebFetcher.FetchResult r = references.resolveOne(url);
                    if (r.ok()) {
                        return "【" + (r.title().isBlank() ? url : r.title()) + "】\n来源：" + url + "\n" + r.text();
                    }
                    return "【" + url + "】抓取失败：" + r.error() + "（可以把页面正文粘贴进练习范围，或换个链接）";
                })
        );
    }

    private JsonNode parseSchema(String json) {
        try {
            return Mappings.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("工具 schema 解析失败", e);
        }
    }

    /** 出题/批改时给 AI 的一行范围说明（比 contextSection 短，用于批改等场景） */
    private String scopeLine(Practice p) {
        return MemoryRules.cut(PromptTemplates.scopeSection(p.getTopic(), p.getScopeMode(), p.getScopeText()), 400);
    }

    private String memoryText(Practice p) {
        JsonNode mem = Mappings.parseJson(p.getMemoryJson());
        if (mem == null) return "";
        StringBuilder sb = new StringBuilder();
        appendList(sb, "已掌握", mem, "mastered");
        appendList(sb, "薄弱点", mem, "weakPoints");
        appendList(sb, "理解偏差", mem, "misconceptions");
        appendList(sb, "下一步方向", mem, "nextFocus");
        appendList(sb, "已覆盖知识点", mem, "coverage");
        return sb.toString();
    }

    private String digestText(Practice p) {
        JsonNode dig = Mappings.parseJson(p.getDigestJson());
        if (dig == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("总览：").append(Mappings.text(dig, "overview")).append('\n');
        sb.append("水平：").append(Mappings.text(dig, "level")).append('\n');
        List<String> covered = Mappings.stringList(dig, "coveredAreas");
        if (!covered.isEmpty()) sb.append("已覆盖大块：").append(String.join("、", covered)).append('\n');
        List<String> uncovered = Mappings.stringList(dig, "uncoveredAreas");
        if (!uncovered.isEmpty()) sb.append("尚未覆盖：").append(String.join("、", uncovered)).append('\n');
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String label, JsonNode node, String field) {
        List<String> list = Mappings.stringList(node, field);
        if (!list.isEmpty()) sb.append(label).append("：").append(String.join("；", list)).append('\n');
    }

    /** 最近历史，最新的在前；每条一行（受 PromptBudget.MAX_RECENT_ITEM 截断） */
    private List<String> recentLines(Practice p) {
        List<String> lines = new ArrayList<>();
        List<Paper> ps = papers.findByPracticeIdOrderBySequenceAsc(p.getId());
        for (int i = ps.size() - 1; i >= 0; i--) {
            Paper paper = ps.get(i);
            List<Question> qs = questions.findByPaperIdOrderBySequenceAsc(paper.getId());
            for (int j = qs.size() - 1; j >= 0; j--) {
                Question q = qs.get(j);
                StringBuilder line = new StringBuilder();
                line.append("第").append(paper.getSequence()).append("卷 第").append(q.getSequence()).append("题 [")
                    .append(q.getTier()).append('/').append(q.getQuestionType()).append("] ")
                    .append(cut(q.getPrompt(), 60));
                line.append("｜考察点：").append(String.join("、", Mappings.parseStringList(q.getKnowledgePointsJson())));
                Attempt a = latestAttempt(q);
                if (a == null) {
                    line.append("｜未作答");
                } else if (a.getEvaluatedAt() != null && a.getScore() != null) {
                    line.append("｜得分 ").append(a.getScore()).append("；").append(cut(a.getFeedback(), 60));
                } else {
                    line.append("｜已作答");
                }
                lines.add(line.toString());
            }
        }
        return lines;
    }

    private String statsLine(Practice p) {
        Stats st = Mappings.practiceStats(p, questions, attempts, papers);
        return "共 " + st.paperCount() + " 张卷、" + st.questionCount() + " 题，百分制平均 "
                + st.averageScore() + " 分，最近一卷 " + st.lastScore() + " 分";
    }

    /* ================= 记忆 / 摘要写入（服务端硬上限，保证不膨胀） ================= */

    /**
     * 写 L1 记忆：措辞交给 AI 蒸馏，条数与字数由服务端硬截断；
     * coverage 走**并集**——旧的必须保留，只增不减，这就是「知识点不丢失」的保证。
     */
    private String writeMemory(JsonNode mem, String oldMemoryJson) {
        JsonNode old = Mappings.parseJson(oldMemoryJson);
        ObjectNode out = Mappings.MAPPER.createObjectNode();
        for (String key : List.of("mastered", "weakPoints", "misconceptions", "nextFocus")) {
            List<String> merged = MemoryRules.tidy(Mappings.stringList(mem, key),
                    MemoryRules.MAX_ITEMS, MemoryRules.MAX_ITEM_CHARS);
            ArrayNode arr = out.putArray(key);
            merged.forEach(arr::add);
        }
        List<String> coverage = MemoryRules.mergeUnion(
                old == null ? List.of() : Mappings.stringList(old, "coverage"),
                Mappings.stringList(mem, "coverage"),
                MemoryRules.MAX_COVERAGE, MemoryRules.MAX_COVERAGE_CHARS);
        ArrayNode cov = out.putArray("coverage");
        coverage.forEach(cov::add);
        out.put("updatedAt", System.currentTimeMillis());
        return out.toString();
    }

    /**
     * 写 L2 会话摘要：固定长度，用来替代「全部历史」进上下文。
     * overview 绝不允许为空——它是后续出题上下文的主体，丢了就等于丢了会话记忆。
     */
    private String writeDigest(JsonNode dig) {
        List<String> covered = MemoryRules.tidy(Mappings.stringList(dig, "coveredAreas"), 8, 12);
        List<String> uncovered = MemoryRules.tidy(Mappings.stringList(dig, "uncoveredAreas"), 8, 12);

        String overview = MemoryRules.cut(Mappings.text(dig, "overview"), 200);
        if (overview.isBlank()) {
            StringBuilder sb = new StringBuilder("已覆盖：");
            sb.append(covered.isEmpty() ? "（无）" : String.join("、", covered));
            if (!uncovered.isEmpty()) sb.append("；尚未覆盖：").append(String.join("、", uncovered));
            overview = MemoryRules.cut(sb.toString(), 200);
        }

        ObjectNode out = Mappings.MAPPER.createObjectNode();
        out.put("overview", overview);
        out.put("level", MemoryRules.cut(Mappings.text(dig, "level"), 60));
        ArrayNode cov = out.putArray("coveredAreas");
        covered.forEach(cov::add);
        ArrayNode unc = out.putArray("uncoveredAreas");
        uncovered.forEach(unc::add);
        out.put("updatedAt", System.currentTimeMillis());
        return out.toString();
    }

    /* ================= 进度 / 计划 ================= */

    private void setProgress(Paper paper, String stage, int planned, int done, List<Integer> failed, String error) {
        // 任务上下文里写进度，一律认为还在跑；只有 markIdle 会写 false
        setProgress(paper, stage, planned, done, failed, error, true);
    }

    private void setProgress(Paper paper, String stage, int planned, int done, List<Integer> failed,
                             String error, boolean taskRunning) {
        ObjectNode node = Mappings.MAPPER.createObjectNode();
        node.put("stage", stage);
        node.put("planned", planned);
        node.put("done", done);
        ArrayNode f = node.putArray("failed");
        failed.forEach(f::add);
        if (error != null && !error.isBlank()) node.put("error", MemoryRules.cut(error, 200));
        // 前端据此判断「任务还在跑」还是「已经停了需要点续跑」
        node.put("running", taskRunning);
        node.put("updatedAt", System.currentTimeMillis());
        paper.setProgressJson(node.toString());
        papers.save(paper);
    }

    /**
     * 任务退出后刷新一次进度：把 running 置 false。
     * 卷还停在「出题中/批改中」时，前端据此显示「续跑」按钮——包括后端中途被杀的情况。
     */
    private void markIdle(Paper paper) {
        Paper cur = getPaper(paper.getId());
        if (!"generating".equals(cur.getStatus()) && !"grading".equals(cur.getStatus())) return;
        JsonNode old = Mappings.parseJson(cur.getProgressJson());
        List<Integer> failed = new ArrayList<>();
        if (old != null) {
            old.path("failed").forEach(n -> {
                if (n.canConvertToInt()) failed.add(n.asInt());
            });
        }
        setProgress(cur,
                old == null ? "出题" : Mappings.text(old, "stage"),
                old == null ? 0 : old.path("planned").asInt(0),
                old == null ? 0 : old.path("done").asInt(0),
                failed,
                old == null ? null : Mappings.text(old, "error"),
                false);
    }

    private List<PlanItem> readPlan(Paper paper) {
        List<PlanItem> items = new ArrayList<>();
        JsonNode node = Mappings.parseJson(paper.getPlanJson());
        if (node == null || !node.isArray()) return items;
        for (JsonNode n : node) {
            String angle = Mappings.text(n, "angle").trim();
            if (angle.isBlank()) continue;
            List<String> names = Mappings.stringList(n, "points");
            // 兼容旧格式：早先的 planJson 用单值 point + int points 存分值
            if (names.isEmpty()) {
                String one = Mappings.text(n, "point").trim();
                if (!one.isBlank()) names = List.of(one);
            }
            int weight = n.path("weight").isNumber() ? n.path("weight").asInt()
                    : n.path("points").isNumber() ? n.path("points").asInt() : 2;
            items.add(new PlanItem(names, angle,
                    normalizeTier(Mappings.text(n, "tier")),
                    normalizeType(Mappings.text(n, "type")), weight));
        }
        return items;
    }

    private void writePlan(Paper paper, List<PlanItem> plan) {
        ArrayNode arr = Mappings.MAPPER.createArrayNode();
        for (PlanItem it : plan) {
            ObjectNode n = arr.addObject();
            ArrayNode pts = n.putArray("points");
            for (String s : it.pointNames()) pts.add(s);
            n.put("angle", it.angle());
            n.put("tier", it.tier());
            n.put("type", it.type());
            n.put("weight", it.points());
        }
        paper.setPlanJson(arr.toString());
        paper.setMaxScore(Scoring.PAPER_MAX);
        papers.save(paper);
    }

    /**
     * 后端已挑好点了，这里只是把 AI 给的「出题角度」贴到点上，并把分值归一化到 100。
     * 分值按档位夹区间（大题 15-20 / 中档 4-8 / 基础 2-4），不指望模型自觉。
     *
     * 角度按**下标**对齐而不是按名字匹配——串联项的名字是「A + B」这种复合串，
     * 靠名字匹配一改写就对不上，退化成拿点名当角度。
     */
    private List<PlanItem> parsePlan(List<PlanItem> picked, JsonNode angles) {
        List<String> angleByIndex = new ArrayList<>();
        for (JsonNode n : angles.path("angles")) {
            String angle = MemoryRules.cut(Mappings.text(n, "angle"), 60);
            angleByIndex.add(angle.isBlank() ? null : angle);
        }
        List<PlanItem> items = new ArrayList<>();
        List<Integer> raw = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            PlanItem it = picked.get(i);
            String angle = i < angleByIndex.size() && angleByIndex.get(i) != null
                    ? angleByIndex.get(i) : it.angle();
            items.add(new PlanItem(it.pointNames(), angle, it.tier(), it.type(), it.points()));
            // 用档位给个初始权重，最终由 normalizePoints 定值
            raw.add("大题".equals(it.tier()) ? 12 : "中档".equals(it.tier()) ? 5 : 3);
        }
        int[] norm = Scoring.normalizePoints(raw.stream().mapToInt(Integer::intValue).toArray(),
                items.stream().map(PlanItem::tier).toArray(String[]::new));
        List<PlanItem> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            PlanItem it = items.get(i);
            out.add(new PlanItem(it.pointNames(), it.angle(), it.tier(), it.type(), norm[i]));
        }
        return out;
    }


    private static String normalizeTier(String tier) {
        String t = tier == null ? "" : tier.trim();
        if (t.contains("大") || t.contains("综合")) return "大题";
        if (t.contains("中")) return "中档";
        return "基础";
    }

    private static String normalizeType(String type) {
        String t = type == null ? "" : type.trim();
        for (String ok : List.of("概念辨析", "简答", "原理追问", "场景应用", "故障排查", "方案设计")) {
            if (ok.equals(t)) return ok;
        }
        return "简答";
    }

    /* ================= 后台任务辅助 ================= */

    private JsonNode chatWithRetry(String system, String user, int maxTokens) {
        return chatWithRetry(system, user, maxTokens, true);
    }

    /**
     * @param thinking 出题/计划/总结类调用传 false：思考计入输出额度，会把正文挤到截断
     *
     * 失败重试 3 次并退避。之前只重试 1 次，网络/服务端抖动时会直接把整批批改带崩，
     * 表现就是「交卷后提示任务停止，让你点续跑」。
     */
    private JsonNode chatWithRetry(String system, String user, int maxTokens, boolean thinking) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return llm.chatJson(system, user, maxTokens, thinking);
            } catch (RuntimeException e) {
                last = e;
                log.warn("AI 调用失败（第 {}/3 次）：{}", attempt, e.getMessage());
                if (attempt < 3) {
                    try {
                        Thread.sleep(1500L * attempt); // 越等越久，避开瞬时抖动
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last == null ? new LlmService.LlmException("AI 调用失败") : last;
    }

    private static JsonNode joinChunk(CompletableFuture<JsonNode> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new LlmService.LlmException("AI 调用失败：" + cause.getMessage());
        }
    }

    /** 尽力而为的 join：失败返回 null 而不抛，用于「总结/记忆/摘要」这类不能拖垮批改的环节 */
    private static JsonNode joinOrNull(CompletableFuture<JsonNode> f) {
        try {
            return f.join();
        } catch (Exception e) {
            log.warn("附属调用失败（已降级）：{}", rootMessage(e));
            return null;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) c = c.getCause();
        String m = c.getMessage();
        return m == null || m.isBlank() ? c.getClass().getSimpleName() : m;
    }

    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "generating" -> "出题中";
            case "answering" -> "答题中";
            case "grading" -> "批改中";
            case "graded" -> "已交卷";
            default -> status;
        };
    }

    /* ================= 查询辅助 ================= */

    private int countQuestions(String paperId) {
        return (int) questions.countByPaperId(paperId);
    }

    private boolean hasQuestion(String paperId, int sequence) {
        for (Question q : questions.findByPaperIdOrderBySequenceAsc(paperId)) {
            if (q.getSequence() == sequence) return true;
        }
        return false;
    }

    private int countEvaluated(List<Question> qs) {
        int n = 0;
        for (Question q : qs) {
            for (Attempt a : attempts.findByQuestionIdOrderBySequenceAsc(q.getId())) {
                if (a.getEvaluatedAt() != null) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /** 还没生成的题号（0-based 计划下标），按连续段切分，每段不超过 GEN_CHUNK */
    private List<List<Integer>> missingRuns(String paperId, int planSize) {
        Set<Integer> have = new LinkedHashSet<>();
        for (Question q : questions.findByPaperIdOrderBySequenceAsc(paperId)) have.add(q.getSequence() - 1);
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int i = 0; i < planSize; i++) {
            if (have.contains(i)) continue;
            current.add(i);
            if (current.size() >= GEN_CHUNK) {
                runs.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) runs.add(current);
        return runs;
    }

    private Map<Question, Attempt> latestAttempts(List<Question> qs) {
        Map<Question, Attempt> map = new LinkedHashMap<>();
        for (Question q : qs) {
            Attempt a = latestAttempt(q);
            if (a != null) map.put(q, a);
        }
        return map;
    }

    private Attempt latestAttempt(Question q) {
        List<Attempt> as = attempts.findByQuestionIdOrderBySequenceAsc(q.getId());
        return as.isEmpty() ? null : as.get(as.size() - 1);
    }

    private Practice get(String id) {
        return practices.findById(id).orElseThrow(() -> new NotFoundException("练习不存在或已删除"));
    }

    private Paper getPaper(String id) {
        return papers.findById(id).orElseThrow(() -> new NotFoundException("试卷不存在或已删除"));
    }

    private Question getQuestion(String id) {
        return questions.findById(id).orElseThrow(() -> new NotFoundException("题目不存在或已删除"));
    }

    private void touch(Practice p) {
        p.setUpdatedAt(System.currentTimeMillis());
        practices.save(p);
    }

    /** 未交卷且还在跑的卷（出题中/答题中/批改中） */
    private Paper findOpenPaper(String practiceId) {
        for (Paper paper : papers.findByPracticeIdOrderBySequenceAsc(practiceId)) {
            if (!"graded".equals(paper.getStatus())) return paper;
        }
        return null;
    }

    private PracticeDetailDto detailOf(Practice p) {
        List<PaperDto> paperDtos = new ArrayList<>();
        for (Paper paper : papers.findByPracticeIdOrderBySequenceAsc(p.getId())) {
            List<QuestionDto> qDtos = new ArrayList<>();
            for (Question q : questions.findByPaperIdOrderBySequenceAsc(paper.getId())) {
                qDtos.add(Mappings.question(q, attempts.findByQuestionIdOrderBySequenceAsc(q.getId())));
            }
            paperDtos.add(Mappings.paper(paper, Mappings.paperStats(paper, questions, attempts), qDtos));
        }
        return Mappings.practiceDetail(p, Mappings.practiceStats(p, questions, attempts, papers),
                notes.text(p.getId()), notes.updatedAt(p.getId()),
                mistakes.dtoList(p.getId()), paperDtos);
    }

    /* ================= 给 AI 的记录文本 ================= */

    private String gradeChunkText(List<Map.Entry<Question, Attempt>> chunk) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Question, Attempt> e : chunk) {
            Question q = e.getKey();
            Attempt a = e.getValue();
            sb.append("题号 ").append(q.getSequence()).append("（").append(q.getTier()).append(" / ")
              .append(q.getQuestionType()).append(" / 本题 ").append(q.getPoints()).append(" 分）\n")
              .append("题面：").append(cut(q.getPrompt(), 500)).append('\n')
              .append("本题要求：").append(Mappings.demandsText(q.getDemandsJson())).append('\n')
              .append("考察点：").append(String.join("、", Mappings.parseStringList(q.getKnowledgePointsJson()))).append('\n')
              .append("参考答案：").append(cut(q.getReferenceAnswer(), 600)).append('\n')
              .append("我的作答：").append(cut(a.getAnswer(), 800)).append('\n');
        }
        return sb.toString();
    }

    /** 批改写回：表现分 + 实得分（= score/10 × 该题分值） */
    private void applyEvaluations(List<Map.Entry<Question, Attempt>> chunk, JsonNode result) {
        Map<Integer, JsonNode> bySeq = new LinkedHashMap<>();
        List<JsonNode> unmatched = new ArrayList<>();
        for (JsonNode ev : result.path("evaluations")) {
            if (ev.path("sequence").isNumber()) {
                bySeq.put(ev.path("sequence").asInt(), ev);
            } else {
                unmatched.add(ev);
            }
        }
        List<JsonNode> leftover = new ArrayList<>();
        for (Map.Entry<Question, Attempt> e : chunk) {
            JsonNode ev = bySeq.remove(e.getKey().getSequence());
            if (ev == null) continue; // 留到后面兜底
            applyOne(e, ev);
        }
        // 序列号对不上的评价：按剩余顺序二次匹配（AI 偶尔改号），避免整题被吞
        if (!bySeq.isEmpty()) leftover.addAll(bySeq.values());
        leftover.addAll(unmatched);
        int li = 0;
        for (Map.Entry<Question, Attempt> e : chunk) {
            if (e.getValue().getEvaluatedAt() != null) continue;
            if (li < leftover.size()) {
                applyOne(e, leftover.get(li++));
            } else {
                // AI 完全漏了这题：兜底写回 0 分，保证已作答题不会再显示「未作答」
                log.warn("AI 批改漏了第 {} 题，兜底写回 0 分", e.getKey().getSequence());
                Attempt a = e.getValue();
                a.setScore(0.0);
                a.setPoints(0.0);
                a.setFeedback("AI 未返回本题评价，按 0 分计。请在下一题重练这个知识点。");
                a.setDimensionsJson(Mappings.MAPPER.createObjectNode().toString());
                a.setEvaluatedAt(System.currentTimeMillis());
                attempts.save(a);
            }
        }
        // bySeq 里多余的（AI 给了不存在的题号）直接丢弃
    }

    private void applyOne(Map.Entry<Question, Attempt> e, JsonNode ev) {
        Attempt a = e.getValue();
        Double score = ev.path("score").isNumber() ? ev.path("score").doubleValue() : null;
        a.setScore(score);
        a.setPoints(Scoring.earnedPoints(score, e.getKey().getPoints()));
        String feedback = Mappings.text(ev, "feedback");
        // 把记忆点拼进 feedback 末尾（前端按标记分区渲染），
        // **同时结构化存下来**——它是「可直接背诵的答案要点」，以前只拼进文本、看完就散，
        // 一个复习系统把最该攒的东西扔掉了。
        List<String> mems = Mappings.stringList(ev, "memoryPoints");
        if (!mems.isEmpty()) {
            try {
                Question q = e.getKey();
                memoryPoints.collect(q.getPracticeId(), q.getId(),
                        Mappings.parseStringList(q.getAssignedPointsJson()), mems);
            } catch (Exception ex) {
                log.warn("记忆点落库失败（不影响批改）：{}", ex.getMessage());
            }
        }
        if (!mems.isEmpty()) {
            StringBuilder sb = new StringBuilder(feedback);
            if (!feedback.isBlank()) sb.append("\n\n");
            sb.append("【记忆点】\n");
            for (int i = 0; i < mems.size(); i++) {
                sb.append(i + 1).append(". ").append(mems.get(i)).append('\n');
            }
            feedback = sb.toString().trim();
        }
        a.setFeedback(feedback);
        ObjectNode dims = Mappings.MAPPER.createObjectNode();
        ev.path("dimensions").fields().forEachRemaining(en -> dims.put(en.getKey(), en.getValue().asDouble()));
        a.setDimensionsJson(dims.toString());
        a.setEvaluatedAt(System.currentTimeMillis());
        attempts.save(a);
    }

    private String briefRecordsText(List<Question> qs) {
        StringBuilder sb = new StringBuilder();
        for (Question q : qs) {
            Attempt a = latestAttempt(q);
            sb.append(q.getSequence()).append(". [").append(q.getTier()).append('/').append(q.getQuestionType())
              .append(']').append(cut(q.getPrompt(), 80))
              .append("｜考察点：").append(String.join("、", Mappings.parseStringList(q.getKnowledgePointsJson())));
            if (a == null) {
                sb.append("｜未作答\n");
            } else if (a.getEvaluatedAt() != null && a.getScore() != null) {
                sb.append("｜得分 ").append(a.getScore()).append("；").append(cut(a.getFeedback(), 80)).append('\n');
            } else {
                sb.append("｜已作答\n");
            }
        }
        return sb.toString();
    }

    private String gradedRecordsText(List<Question> qs) {
        StringBuilder sb = new StringBuilder();
        for (Question q : qs) {
            sb.append("第 ").append(q.getSequence()).append(" 题（").append(q.getTier()).append(" / ")
              .append(q.getQuestionType()).append(" / ").append(q.getPoints()).append(" 分）：")
              .append(cut(q.getPrompt(), 200)).append('\n');
            Attempt a = latestAttempt(q);
            if (a == null) {
                sb.append("  （未作答）\n  参考答案：").append(cut(q.getReferenceAnswer(), 200)).append('\n');
                continue;
            }
            sb.append("  我的作答：").append(cut(a.getAnswer(), 300)).append('\n');
            if (a.getEvaluatedAt() != null && a.getScore() != null) {
                sb.append("  表现分：").append(a.getScore()).append("（实得 ").append(a.getPoints()).append(" 分）")
                  .append("；点评：").append(cut(a.getFeedback(), 120)).append('\n');
            }
            sb.append("  参考答案：").append(cut(q.getReferenceAnswer(), 200)).append('\n');
        }
        return sb.toString();
    }

    private static String cut(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
