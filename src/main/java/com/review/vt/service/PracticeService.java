package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.review.vt.domain.Attempt;
import com.review.vt.domain.Paper;
import com.review.vt.domain.Practice;
import com.review.vt.domain.Question;
import com.review.vt.repo.AttemptRepository;
import com.review.vt.repo.PaperRepository;
import com.review.vt.repo.PracticeRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.repo.StageRepository;
import com.review.vt.web.Dtos.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
                           WebSearch webSearch) {
        this.stages = stages;
        this.practices = practices;
        this.papers = papers;
        this.questions = questions;
        this.attempts = attempts;
        this.llm = llm;
        this.stageSummary = stageSummary;
        this.references = references;
        this.webSearch = webSearch;
    }

    /* ================= 创建 / 详情 ================= */

    public PracticeDto create(String stageId, String topic, String scopeMode, String scopeText, boolean review) {
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
        p.setReview(review);
        p.setStatus("active");
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        practices.save(p);
        return Mappings.practice(p, Mappings.practiceStats(p, questions, attempts, papers));
    }

    public PracticeDetailDto detail(String practiceId) {
        return detailOf(get(practiceId));
    }

    /* ================= 出题（后台任务） ================= */

    /** 发起出题：立刻落一张「出题中」的卷并返回，后台并发出题，前端轮询进度 */
    public PracticeDetailDto startPaper(String practiceId) {
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
        paper.setStatus("generating");
        paper.setCreatedAt(System.currentTimeMillis());
        paper.setMaxScore(Scoring.PAPER_MAX);
        setProgress(paper, "出题", 0, 0, List.of(), null);
        papers.save(paper);
        touch(p);

        launchGenerate(paper);
        return detailOf(p);
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

    /** 出题主流程：出计划（首次）→ 只补缺失的题 → 全齐则转「答题中」 */
    private void doGenerate(Paper paper) {
        try {
            Practice p = get(paper.getPracticeId());
            List<PlanItem> plan = readPlan(paper);
            if (plan.isEmpty()) {
                setProgress(paper, "出题", 0, 0, List.of(), null);
                // 出题前联网搜一下当前高频面经线索，用来校准时效性（搜不到就降级，不影响出题）
                String freshness = WebSearch.toPrompt(webSearch.search(p.getTopic() + " 高频面试题"));
                JsonNode planJson = llm.chatJson(PromptTemplates.INTERVIEW_SYSTEM,
                        PromptTemplates.paperPlanUser(contextSection(p, freshness), paper.getSequence()),
                        PLAN_MAX_TOKENS, false);
                plan = parsePlan(planJson);
                writePlan(paper, plan);
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
                        System.err.println("[myreview] 出题批次失败：" + rootMessage(e));
                    }
                }
            }

            int done = countQuestions(paper.getId());
            if (failed.isEmpty() && done >= planItems.size()) {
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
            q.setKnowledgePointsJson(Mappings.writeStringList(Mappings.stringList(item, "knowledgePoints")));
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
            System.err.println("[myreview] 阶段总结生成失败：" + rootMessage(ex));
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
                for (int i = 0; i < futures.size(); i++) {
                    List<Map.Entry<Question, Attempt>> chunk =
                            pending.subList(i * GRADE_CHUNK, Math.min((i + 1) * GRADE_CHUNK, pending.size()));
                    applyEvaluations(chunk, joinChunk(futures.get(i)));
                    setProgress(paper, "批改", qs.size(), countEvaluated(qs), List.of(), null);
                }
            }

            // ② 总结 / 记忆 / 摘要 / 阶段总结（各自容错，失败只降级不卡卷）
            writeSummaries(p, paper, qs);

            // ③ 最后才把卷翻成「已交卷」——批改已落库，无论总结成不成都要走到这
            long now = System.currentTimeMillis();
            paper.setStatus("graded");
            paper.setSubmittedAt(now);
            paper.setProgressJson(null);
            papers.save(paper);
        } catch (Exception e) {
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

    /* ================= 上下文（受 PromptBudget 硬预算约束） ================= */

    /**
     * 出题/批改的上下文：范围 + L2 摘要 + L1 记忆 + 最近历史。
     * 每段都有硬上限，历史从最新往回砍，保证单次 prompt 长度永远有顶。
     */
    private String contextSection(Practice p, String freshness) {
        String scope = MemoryRules.cut(PromptTemplates.scopeSection(p.getTopic(), p.getScopeMode(), p.getScopeText()),
                PromptBudget.MAX_SCOPE);
        // 范围里提到的 URL：真的把网页正文抓下来给出题当依据（后台线程里做，不卡界面）
        String refs = MemoryRules.cut(references.expand(p.getScopeText()), PromptBudget.MAX_REFS);
        String digest = MemoryRules.cut(digestText(p), PromptBudget.MAX_DIGEST);
        String memory = MemoryRules.cut(memoryText(p), PromptBudget.MAX_MEMORY);

        int used = scope.length() + refs.length() + digest.length() + memory.length();
        List<String> recent = PromptBudget.fitRecent(recentLines(p), used);

        StringBuilder sb = new StringBuilder(scope);
        if (freshness != null && !freshness.isBlank()) sb.append("\n\n").append(freshness);
        if (!refs.isBlank()) sb.append("\n\n").append(refs);
        if (!digest.isBlank()) sb.append("\n\n会话摘要（长期积累，用来替代全部历史）：\n").append(digest);
        if (!memory.isBlank()) sb.append("\n\n长期记忆：\n").append(memory);
        if (!recent.isEmpty()) {
            sb.append("\n\n最近的题目与表现（最新的在前）：\n");
            for (String line : recent) sb.append(line).append('\n');
        }
        return sb.toString();
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
            items.add(new PlanItem(angle, normalizeTier(Mappings.text(n, "tier")),
                    normalizeType(Mappings.text(n, "type")), n.path("points").asInt(2)));
        }
        return items;
    }

    private void writePlan(Paper paper, List<PlanItem> plan) {
        ArrayNode arr = Mappings.MAPPER.createArrayNode();
        for (PlanItem it : plan) {
            ObjectNode n = arr.addObject();
            n.put("angle", it.angle());
            n.put("tier", it.tier());
            n.put("type", it.type());
            n.put("points", it.points());
        }
        paper.setPlanJson(arr.toString());
        paper.setMaxScore(Scoring.PAPER_MAX);
        papers.save(paper);
    }

    /** 解析出卷计划：角度/档位/题型/分值，分值归一化到合计 100 */
    private List<PlanItem> parsePlan(JsonNode plan) {
        List<PlanItem> items = new ArrayList<>();
        List<Integer> raw = new ArrayList<>();
        for (JsonNode n : plan.path("outline")) {
            String angle = Mappings.text(n, "angle").trim();
            if (angle.isBlank()) continue;
            items.add(new PlanItem(MemoryRules.cut(angle, 60), normalizeTier(Mappings.text(n, "tier")),
                    normalizeType(Mappings.text(n, "type")), n.path("points").asInt(0)));
            raw.add(n.path("points").asInt(0));
        }
        if (items.isEmpty()) throw new LlmService.LlmException("AI 没有返回出题计划，请重试");
        if (items.size() > 30) {
            items = new ArrayList<>(items.subList(0, 30));
            raw = new ArrayList<>(raw.subList(0, 30));
        }
        int[] norm = Scoring.normalizePoints(raw.stream().mapToInt(Integer::intValue).toArray());
        List<PlanItem> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            PlanItem it = items.get(i);
            out.add(new PlanItem(it.angle(), it.tier(), it.type(), norm[i]));
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

    /** @param thinking 出题/计划/总结类调用传 false：思考计入输出额度，会把正文挤到截断 */
    private JsonNode chatWithRetry(String system, String user, int maxTokens, boolean thinking) {
        try {
            return llm.chatJson(system, user, maxTokens, thinking);
        } catch (LlmService.LlmException first) {
            System.err.println("[myreview] 单批 AI 调用失败，重试一次：" + first.getMessage());
            return llm.chatJson(system, user, maxTokens, thinking);
        }
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
            System.err.println("[myreview] 附属调用失败（已降级）：" + rootMessage(e));
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
        return Mappings.practiceDetail(p, Mappings.practiceStats(p, questions, attempts, papers), paperDtos);
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
              .append("考察点：").append(String.join("、", Mappings.parseStringList(q.getKnowledgePointsJson()))).append('\n')
              .append("参考答案：").append(cut(q.getReferenceAnswer(), 600)).append('\n')
              .append("我的作答：").append(cut(a.getAnswer(), 800)).append('\n');
        }
        return sb.toString();
    }

    /** 批改写回：表现分 + 实得分（= score/10 × 该题分值） */
    private void applyEvaluations(List<Map.Entry<Question, Attempt>> chunk, JsonNode result) {
        Map<Integer, JsonNode> bySeq = new LinkedHashMap<>();
        for (JsonNode ev : result.path("evaluations")) {
            if (ev.path("sequence").isNumber()) bySeq.put(ev.path("sequence").asInt(), ev);
        }
        for (Map.Entry<Question, Attempt> e : chunk) {
            JsonNode ev = bySeq.get(e.getKey().getSequence());
            if (ev == null) continue;
            Attempt a = e.getValue();
            Double score = ev.path("score").isNumber() ? ev.path("score").doubleValue() : null;
            a.setScore(score);
            a.setPoints(Scoring.earnedPoints(score, e.getKey().getPoints()));
            a.setFeedback(Mappings.text(ev, "feedback"));
            ObjectNode dims = Mappings.MAPPER.createObjectNode();
            ev.path("dimensions").fields().forEachRemaining(en -> dims.put(en.getKey(), en.getValue().asDouble()));
            a.setDimensionsJson(dims.toString());
            a.setEvaluatedAt(System.currentTimeMillis());
            attempts.save(a);
        }
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
