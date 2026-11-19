package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.review.vt.auth.AuthContext;
import java.util.Optional;
import com.review.vt.pojo.entity.KnowledgePoint;
import com.review.vt.pojo.entity.Practice;
import com.review.vt.pojo.entity.ReviewCard;
import com.review.vt.pojo.entity.ReviewDoc;
import com.review.vt.mapper.PracticeMapper;
import com.review.vt.mapper.ReviewCardMapper;
import com.review.vt.mapper.ReviewDocMapper;
import com.review.vt.pojo.dto.ReviewDocDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.review.vt.common.util.MappingsUtil;
import com.review.vt.common.util.MemoryRulesUtil;
import com.review.vt.common.util.PromptBudgetUtil;
import com.review.vt.common.util.PromptTemplatesUtil;
import com.review.vt.web.ex.NotFoundException;
import com.review.vt.web.ex.LlmException;
import com.review.vt.common.config.LlmConfig;

/**
 * 考前复习提纲：一个练习一份，按知识点生成「必学 / 理解 / 选学」三层学习卡。
 *
 * <p>关键约束：
 * <ul>
 *   <li>**不受复习强度限制**：速通也生成全部内容，前端按深度分层展示，用户自己决定学到哪
 *   <li>学考同源：与出题共用同一份知识点清单、优先级与深挖上限
 *   <li>分批并发生成：每批 3 个点，避免长 JSON 截断；每批成功立刻落库
 *   <li>可续跑：缺卡单独补批，最后仍缺只标 failed 并保留已生成部分
 *   <li>AI 失败不伪造内容：宁可 failed 让用户重试，也不写空卡
 * </ul>
 */
@Service
public class ReviewDocService {

    private static final Logger log = LoggerFactory.getLogger(ReviewDocService.class);

    /** 每批几个点：每点约 400-500 字，3 点一批输出安全 */
    static final int REVIEW_CHUNK = 3;
    /** 单批输出额度 */
    private static final int REVIEW_TOKENS = 12288;
    /** 缺卡补批轮数 */
    private static final int RETRY_ROUNDS = 2;

    private final ReviewDocMapper reviewDocMapper;
    private final ReviewCardMapper reviewCardMapper;
    private final PracticeMapper practiceMapper;
    private final KnowledgeService knowledgeService;
    private final LlmService llmService;
    private final UserLlmService userLlmService;
    private final ReferenceService referenceService;
    private final DataCacheService dataCache;

    /** 后台生成用的小线程池（复习提纲不是高频操作，给 3 个并发位即可） */
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "myreview-review");
        t.setDaemon(true);
        return t;
    });

    /** 正在生成的练习 id，防重复点击 */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public ReviewDocService(ReviewDocMapper reviewDocMapper, ReviewCardMapper reviewCardMapper,
                            PracticeMapper practiceMapper,
                            KnowledgeService knowledgeService, LlmService llmService, UserLlmService userLlmService,
                            ReferenceService referenceService, DataCacheService dataCache) {
        this.reviewDocMapper = reviewDocMapper;
        this.reviewCardMapper = reviewCardMapper;
        this.practiceMapper = practiceMapper;
        this.knowledgeService = knowledgeService;
        this.llmService = llmService;
        this.userLlmService = userLlmService;
        this.referenceService = referenceService;
        this.dataCache = dataCache;
    }

    /** 文档落库入口：改前删缓存（review_doc:{practiceId}） */
    private void saveDoc(ReviewDoc doc) {
        dataCache.evictReviewDoc(doc.getPracticeId());
        reviewDocMapper.save(doc);
    }

    /* ================= 对外 ================= */

    /** 读当前练习的复习提纲；没有返回 status=none。复习页一条查询：review_doc:{practiceId} 点查缓存 */
    public ReviewDocDto get(Long practiceId) {
        ReviewDoc doc = dataCache.reviewDocByPractice(AuthContext.require().userId(), practiceId);
        if (doc != null) return dto(doc);
        // none 态也不查练习行/知识点——复习页就该只有 review_doc 这一条查询（用户指定）
        return noneDto(practiceId);
    }

    /**
     * 生成或续跑：
     * - 已就绪 → 直接返回，不重复生成
     * - 生成中且任务在跑 → 拒绝
     * - 失败/进程重启后遗留的 generating → 续跑缺卡
     * - 没有 → 新建
     */
    public ReviewDocDto start(Long practiceId) {
        Practice p = getPractice(practiceId);
        ReviewDoc doc = reviewDocMapper.findByPracticeId(p.getId()).orElse(null);
        if (doc != null && "ready".equals(doc.getStatus())) return dto(doc);
        if (doc != null && running.contains(p.getId())) {
            throw new IllegalArgumentException("复习提纲正在生成，请稍等");
        }
        if (doc == null) {
            doc = newDoc(p.getId());
            saveDoc(doc);
        } else {
            doc.setStatus("generating");
            setProgress(doc, readPoints(doc).size(), countPlanned(doc, p), List.of(), null);
            saveDoc(doc);
        }
        launch(doc);
        return dto(doc);
    }

    /** 清空并重新生成 */
    public ReviewDocDto regenerate(Long practiceId) {
        Practice p = getPractice(practiceId);
        if (running.contains(p.getId())) {
            throw new IllegalArgumentException("复习提纲正在生成，请稍等");
        }
        ReviewDoc old = reviewDocMapper.findByPracticeId(p.getId()).orElse(null);
        if (old != null) {
            reviewCardMapper.deleteByReviewDocId(old.getId());
            dataCache.evictReviewCardIds(old.getId());
            reviewDocMapper.deleteAll(List.of(old));
        }
        ReviewDoc doc = newDoc(p.getId());
        saveDoc(doc);
        launch(doc);
        return dto(doc);
    }

    /* ================= 生成 ================= */

    private void launch(ReviewDoc doc) {
        if (!running.add(doc.getPracticeId())) {
            throw new IllegalArgumentException("复习提纲正在生成，请稍等");
        }
        // 后台任务：submit 前捕获用户与 LLM 配置快照（解密一次），worker 线程恢复 AuthContext
        AuthContext.CurrentUser user = AuthContext.require();
        LlmConfig cfg = userLlmService.snapshotFor(user.userId());
        executor.submit(() -> {
            AuthContext.set(user);
            try {
                doGenerate(doc, cfg);
            } finally {
                AuthContext.clear();
            }
        });
    }

    private void doGenerate(ReviewDoc doc, LlmConfig cfg) {
        AuthContext.CurrentUser user = AuthContext.require(); // launch 已恢复
        try {
            Practice p = getPractice(doc.getPracticeId());
            ensureScaffold(p);
            List<KnowledgePoint> ordered = orderedPoints(p);
            if (ordered.isEmpty()) {
                throw new IllegalStateException("这个练习还没有知识点清单，请先出一次题");
            }

            Map<String, ObjectNode> byName = readPoints(doc);
            List<KnowledgePoint> missing = new ArrayList<>();
            for (KnowledgePoint kp : ordered) {
                if (!byName.containsKey(kp.getName())) missing.add(kp);
            }
            setProgress(doc, byName.size(), ordered.size(), List.of(), null);

            // 第一批：按 3 个点一批并发
            missing = generateBatches(doc, p, ordered, byName, missing, REVIEW_CHUNK, "生成", user, cfg);

            // 缺卡补批：一卡一请求（和出题补题同样的原因——按批重试会让尾部卡一直饿死）
            for (int round = 1; round <= RETRY_ROUNDS && !missing.isEmpty(); round++) {
                log.warn("复习提纲还有 {} 个点未生成，第 {} 轮补批", missing.size(), round);
                missing = generateBatches(doc, p, ordered, byName, missing, 1, "补批", user, cfg);
            }

            if (missing.isEmpty()) {
                doc.setStatus("ready");
                doc.setProgressJson(null);
                doc.setUpdatedAt(System.currentTimeMillis());
                saveDoc(doc);
                log.info("复习提纲生成完成：practice={} 共 {} 个点", p.getId(), ordered.size());
            } else {
                List<String> names = missing.stream().map(KnowledgePoint::getName).toList();
                doc.setStatus("failed");
                setProgress(doc, byName.size(), ordered.size(), names,
                        "还有 " + missing.size() + " 个点没生成出来，点「重新生成」或稍后续跑");
                log.warn("复习提纲生成不完整：practice={} 缺 {} 个点", p.getId(), missing.size());
            }
        } catch (Exception e) {
            log.error("复习提纲生成失败：practice={} 原因：{}", doc.getPracticeId(), rootMessage(e), e);
            doc.setStatus("failed");
            setProgress(doc, readPoints(doc).size(), 0, List.of(), rootMessage(e));
        } finally {
            running.remove(doc.getPracticeId());
        }
    }

    /** 生成一批（或单卡）并合并落库；返回仍然缺失的点 */
    private List<KnowledgePoint> generateBatches(ReviewDoc doc, Practice p, List<KnowledgePoint> ordered,
                                                 Map<String, ObjectNode> byName, List<KnowledgePoint> missing,
                                                 int batchSize, String stage,
                                                 AuthContext.CurrentUser user, LlmConfig cfg) {
        List<List<KnowledgePoint>> batches = chunk(missing, batchSize);
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        for (List<KnowledgePoint> batch : batches) {
            // 嵌套并发任务：ThreadLocal 不随线程池传递，恢复用户上下文再调 LLM
            futures.add(CompletableFuture.supplyAsync(() -> {
                AuthContext.set(user);
                try {
                    return chatWithRetry(cfg,
                            PromptTemplatesUtil.REVIEW_SYSTEM,
                            PromptTemplatesUtil.reviewChunkUser(scopeLine(p), pointLines(batch)),
                            REVIEW_TOKENS);
                } finally {
                    AuthContext.clear();
                }
            }, executor));
        }
        for (int i = 0; i < batches.size(); i++) {
            List<KnowledgePoint> batch = batches.get(i);
            try {
                mergeBatch(byName, batch, futures.get(i).join());
            } catch (Exception ex) {
                log.warn("复习{}批次失败（{} 个点）：{}", stage, batch.size(), rootMessage(ex));
            }
            saveBatchCards(doc, ordered, batch, byName);
            setProgress(doc, byName.size(), ordered.size(), List.of(), null);
        }
        List<KnowledgePoint> still = new ArrayList<>();
        for (KnowledgePoint kp : ordered) {
            if (!byName.containsKey(kp.getName())) still.add(kp);
        }
        return still;
    }

    /** 把一批返回的卡按点名合并进来；只收本批请求过的点 */
    private void mergeBatch(Map<String, ObjectNode> byName, List<KnowledgePoint> batch, JsonNode result) {
        Map<String, KnowledgePoint> wanted = new LinkedHashMap<>();
        for (KnowledgePoint kp : batch) wanted.put(kp.getName(), kp);
        for (JsonNode card : result.path("points")) {
            String rawName = MappingsUtil.text(card, "name").trim();
            String name = resolvePointName(rawName, wanted.keySet());
            if (name == null) continue;
            KnowledgePoint kp = wanted.get(name);
            if (kp == null) continue;
            ObjectNode out = MappingsUtil.MAPPER.createObjectNode();
            out.put("name", name);
            out.put("category", kp.getCategory() == null ? "综合" : kp.getCategory());
            out.put("priority", kp.getPriority() == null ? "medium" : kp.getPriority());
            out.put("summary", MemoryRulesUtil.cut(MappingsUtil.text(card, "summary").trim(), 200));
            out.put("interview", MemoryRulesUtil.cut(MappingsUtil.text(card, "interview").trim(), 200));
            out.put("principle", MemoryRulesUtil.cut(MappingsUtil.text(card, "principle").trim(), 600));
            ArrayNode params = out.putArray("params");
            for (String s : MappingsUtil.stringList(card, "params")) {
                if (params.size() >= 5) break;
                String v = MemoryRulesUtil.cut(s, 120).trim();
                if (!v.isBlank()) params.add(v);
            }
            ArrayNode pitfalls = out.putArray("pitfalls");
            for (String s : MappingsUtil.stringList(card, "pitfalls")) {
                if (pitfalls.size() >= 5) break;
                String v = MemoryRulesUtil.cut(s, 120).trim();
                if (!v.isBlank()) pitfalls.add(v);
            }
            // 新提示词用平铺的 selfCheckQ/selfCheckA（减少嵌套，模型更少写错括号）；
            // 兼容旧格式的嵌套 selfCheck {q,a}
            JsonNode sc = card.path("selfCheck");
            String scQ = sc.isObject() ? MappingsUtil.text(sc, "q") : MappingsUtil.text(card, "selfCheckQ");
            String scA = sc.isObject() ? MappingsUtil.text(sc, "a") : MappingsUtil.text(card, "selfCheckA");
            if (!scQ.isBlank() || !scA.isBlank()) {
                ObjectNode self = out.putObject("selfCheck");
                self.put("q", MemoryRulesUtil.cut(scQ.trim(), 200));
                self.put("a", MemoryRulesUtil.cut(scA.trim(), 300));
            }
            // summary 是必学层，空的视为无效卡，留给补批
            if (out.path("summary").asText("").isBlank()) continue;
            byName.put(name, out);
        }
    }

    /* ================= 辅助 ================= */

    private void ensureScaffold(Practice p) {
        if (!knowledgeService.listOf(p.getId()).isEmpty()) return;
        String refs = "";
        if ("range".equals(p.getScopeMode())) {
            try {
                refs = MemoryRulesUtil.cut(referenceService.expand(p.getScopeText()), PromptBudgetUtil.MAX_REFS);
            } catch (Exception e) {
                log.warn("复习提纲抓取参考资料失败（降级）：{}", rootMessage(e));
            }
        }
        knowledgeService.scaffold(p, refs);
    }

    /** 按大类分组、组内按优先级排序：高频先看、常见其次、偏门最后 */
    private List<KnowledgePoint> orderedPoints(Practice p) {
        List<KnowledgePoint> all = knowledgeService.listOf(p.getId());
        Map<String, Integer> catOrder = new LinkedHashMap<>();
        for (KnowledgePoint kp : all) {
            catOrder.putIfAbsent(kp.getCategory() == null ? "综合" : kp.getCategory(), catOrder.size());
        }
        List<KnowledgePoint> ordered = new ArrayList<>(all);
        ordered.sort(Comparator
                .comparingInt((KnowledgePoint k) -> catOrder.getOrDefault(
                        k.getCategory() == null ? "综合" : k.getCategory(), 0))
                .thenComparing(Comparator.comparingInt(KnowledgeService::priorityRank).reversed()));
        return ordered;
    }

    static List<String> pointLines(List<KnowledgePoint> batch) {
        List<String> lines = new ArrayList<>();
        for (KnowledgePoint kp : batch) {
            String priority = kp.getPriority() == null ? "medium" : kp.getPriority();
            String label = "high".equalsIgnoreCase(priority) ? "高频"
                    : "low".equalsIgnoreCase(priority) ? "偏门" : "常见";
            String bound = kp.getBoundDeep() == null || kp.getBoundDeep().isBlank()
                    ? "概念、原理、关键参数与取舍，不考源码实现"
                    : kp.getBoundDeep();
            lines.add("点名：" + kp.getName() + "｜优先级：" + label
                    + "｜类别：" + (kp.getCategory() == null ? "综合" : kp.getCategory())
                    + "｜深挖上限：" + bound);
        }
        return lines;
    }

    /**
     * 把模型返回的点名对齐到清单里的原名。
     *
     * <p>两个真实坑：
     * <ul>
     *   <li>scaffold 会把点名截断（旧数据 30 字），像 "happens-befo"；模型看到不完整会
     *       “好心补全”成 "happens-before"，严格按原名匹配就丢卡，导致每次续跑都补不齐</li>
     *   <li>模型偶尔把整行复制进 name（带「【优先级 / 类别】」或「｜深挖上限」）</li>
     * </ul>
     * 先去掉装饰，再精确匹配；不中则按「前缀 + 差异 ≤ 8 字」对齐到最接近的原名。
     */
    static String resolvePointName(String returned, java.util.Collection<String> wanted) {
        if (returned == null || wanted == null || wanted.isEmpty()) return null;
        String name = returned.trim();
        int bar = name.indexOf('｜');
        if (bar >= 0) name = name.substring(0, bar);
        int bracket = name.lastIndexOf('】');
        if (bracket >= 0) name = name.substring(bracket + 1);
        name = name.trim();
        if (name.isEmpty()) return null;
        if (wanted.contains(name)) return name;

        String a = name.replaceAll("\\s+", "");
        String best = null;
        int bestExtra = Integer.MAX_VALUE;
        for (String w : wanted) {
            String b = w.replaceAll("\\s+", "");
            int extra;
            if (a.startsWith(b)) extra = a.length() - b.length();
            else if (b.startsWith(a)) extra = b.length() - a.length();
            else continue;
            if (extra <= 8 && extra < bestExtra) {
                best = w;
                bestExtra = extra;
            }
        }
        return best;
    }

    private static List<List<KnowledgePoint>> chunk(List<KnowledgePoint> list, int size) {
        List<List<KnowledgePoint>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(new ArrayList<>(list.subList(i, Math.min(i + size, list.size()))));
        }
        return out;
    }

    private String scopeLine(Practice p) {
        return MemoryRulesUtil.cut(PromptTemplatesUtil.scopeSection(
                p.getTopic(), p.getScopeMode(), p.getScopeText()), 400);
    }

    /** @param cfg 任务发起时解密的 LLM 配置快照（后台线程没有会话，不能现场解析） */
    private JsonNode chatWithRetry(LlmConfig cfg, String system, String user, int maxTokens) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return llmService.chatJson(cfg, system, user, maxTokens, false);
            } catch (RuntimeException e) {
                last = e;
                log.warn("复习卡生成失败（第 {}/3 次）：{}", attempt, rootMessage(e));
                try {
                    Thread.sleep(1200L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last == null ? new LlmException("复习卡生成失败") : last;
    }

    private ReviewDoc newDoc(Long practiceId) {
        long now = System.currentTimeMillis();
        ReviewDoc doc = new ReviewDoc();
        doc.setPracticeId(practiceId);
        doc.setStatus("generating");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        return doc;
    }

    /** 已生成的卡：name → 卡（读走缓存，review_card 一行一卡） */
    private Map<String, ObjectNode> readPoints(ReviewDoc doc) {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        if (doc.getId() == null) return out;
        for (ReviewCard card : dataCache.reviewCardsOfDoc(doc.getUserId(), doc.getId())) {
            JsonNode node = MappingsUtil.parseJson(card.getContentJson());
            if (node instanceof ObjectNode obj) {
                if (MappingsUtil.text(obj, "name").isBlank()) obj.put("name", card.getPointName());
                out.put(card.getPointName(), obj);
            }
        }
        return out;
    }

    /** 只落本批新生成的卡（每批 3 张，不再整份 30KB JSON 重写） */
    private void saveBatchCards(ReviewDoc doc, List<KnowledgePoint> ordered,
                                List<KnowledgePoint> batch, Map<String, ObjectNode> byName) {
        dataCache.evictReviewCardIds(doc.getId());
        long now = System.currentTimeMillis();
        for (KnowledgePoint kp : batch) {
            ObjectNode card = byName.get(kp.getName());
            if (card == null) continue;
            int seq = ordered.indexOf(kp);
            ReviewCard rc = new ReviewCard();
            rc.setUserId(doc.getUserId());
            rc.setReviewDocId(doc.getId());
            rc.setPointName(kp.getName());
            rc.setCategory(kp.getCategory());
            rc.setPriority(kp.getPriority());
            rc.setContentJson(card.toString());
            rc.setSequence(seq < 0 ? 0 : seq);
            rc.setCreatedAt(now);
            rc.setUpdatedAt(now);
            reviewCardMapper.save(rc);
        }
        doc.setUpdatedAt(now);
        saveDoc(doc);
    }

    private void setProgress(ReviewDoc doc, int done, int planned, List<String> failed, String error) {
        ObjectNode p = MappingsUtil.MAPPER.createObjectNode();
        p.put("planned", planned);
        p.put("done", done);
        ArrayNode f = p.putArray("failed");
        failed.forEach(f::add);
        p.put("running", running.contains(doc.getPracticeId()));
        if (error != null) p.put("error", error);
        doc.setProgressJson(p.toString());
        doc.setUpdatedAt(System.currentTimeMillis());
        saveDoc(doc);
    }

    private int countPlanned(ReviewDoc doc, Practice p) {
        return Math.max(knowledgeService.listOf(p.getId()).size(), readPoints(doc).size());
    }

    /* ================= DTO ================= */

    private ReviewDocDto dto(ReviewDoc doc) {
        // 组装成前端约定的 {"points":[...]}，顺序与知识点清单一致
        ObjectNode content = MappingsUtil.MAPPER.createObjectNode();
        ArrayNode arr = content.putArray("points");
        if (doc.getId() != null) {
            for (ReviewCard c : dataCache.reviewCardsOfDoc(doc.getUserId(), doc.getId())) {
                JsonNode node = MappingsUtil.parseJson(c.getContentJson());
                if (node instanceof ObjectNode obj) {
                    if (MappingsUtil.text(obj, "name").isBlank()) obj.put("name", c.getPointName());
                    arr.add(obj);
                }
            }
        }
        JsonNode progress = MappingsUtil.parseJson(doc.getProgressJson());
        // running 用**进程内实时状态**，不用落库的旧值：
        // 上次进程崩溃/重启后，库里还写着 running=true，前端会永远转圈；
        // 覆盖成 false 后，前端能给出「继续生成」。
        if ("generating".equals(doc.getStatus()) && progress instanceof ObjectNode po) {
            po.put("running", running.contains(doc.getPracticeId()));
        }
        int done = content.path("points").size();
        int planned = progress != null && progress.path("planned").isNumber()
                ? progress.path("planned").asInt() : done;
        return new ReviewDocDto(MappingsUtil.id(doc.getId()), MappingsUtil.id(doc.getPracticeId()), doc.getStatus(),
                content, progress, planned, done, doc.getUpdatedAt());
    }

    private ReviewDocDto noneDto(Long practiceId) {
        ObjectNode content = MappingsUtil.MAPPER.createObjectNode();
        content.putArray("points");
        return new ReviewDocDto(null, MappingsUtil.id(practiceId), "none", content, null, 0, 0, 0);
    }

    private Practice getPractice(Long practiceId) {
        return practiceMapper.findById(practiceId)
                .orElseThrow(() -> new NotFoundException("练习不存在或已删除"));
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null || m.isBlank() ? cur.getClass().getSimpleName() : m;
    }
}
