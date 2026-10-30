package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.review.vt.domain.KnowledgePoint;
import com.review.vt.domain.Practice;
import com.review.vt.domain.ReviewDoc;
import com.review.vt.repo.PracticeRepository;
import com.review.vt.repo.ReviewDocRepository;
import com.review.vt.web.Dtos.ReviewDocDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final ReviewDocRepository reviews;
    private final PracticeRepository practices;
    private final KnowledgeService knowledge;
    private final LlmService llm;
    private final ReferenceService references;

    /** 后台生成用的小线程池（复习提纲不是高频操作，给 3 个并发位即可） */
    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "myreview-review");
        t.setDaemon(true);
        return t;
    });

    /** 正在生成的练习 id，防重复点击 */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public ReviewDocService(ReviewDocRepository reviews, PracticeRepository practices,
                            KnowledgeService knowledge, LlmService llm, ReferenceService references) {
        this.reviews = reviews;
        this.practices = practices;
        this.knowledge = knowledge;
        this.llm = llm;
        this.references = references;
    }

    /* ================= 对外 ================= */

    /** 读当前练习的复习提纲；没有返回 status=none */
    public ReviewDocDto get(String practiceId) {
        Practice p = getPractice(practiceId);
        return reviews.findByPracticeId(p.getId()).map(this::dto).orElse(noneDto(p));
    }

    /**
     * 生成或续跑：
     * - 已就绪 → 直接返回，不重复生成
     * - 生成中且任务在跑 → 拒绝
     * - 失败/进程重启后遗留的 generating → 续跑缺卡
     * - 没有 → 新建
     */
    public ReviewDocDto start(String practiceId) {
        Practice p = getPractice(practiceId);
        ReviewDoc doc = reviews.findByPracticeId(p.getId()).orElse(null);
        if (doc != null && "ready".equals(doc.getStatus())) return dto(doc);
        if (doc != null && running.contains(p.getId())) {
            throw new IllegalArgumentException("复习提纲正在生成，请稍等");
        }
        if (doc == null) {
            doc = newDoc(p.getId());
            reviews.save(doc);
        } else {
            doc.setStatus("generating");
            setProgress(doc, readPoints(doc).size(), countPlanned(doc, p), List.of(), null);
            reviews.save(doc);
        }
        launch(doc);
        return dto(doc);
    }

    /** 清空并重新生成 */
    public ReviewDocDto regenerate(String practiceId) {
        Practice p = getPractice(practiceId);
        if (running.contains(p.getId())) {
            throw new IllegalArgumentException("复习提纲正在生成，请稍等");
        }
        ReviewDoc old = reviews.findByPracticeId(p.getId()).orElse(null);
        if (old != null) reviews.deleteAll(List.of(old));
        ReviewDoc doc = newDoc(p.getId());
        reviews.save(doc);
        launch(doc);
        return dto(doc);
    }

    /* ================= 生成 ================= */

    private void launch(ReviewDoc doc) {
        if (!running.add(doc.getPracticeId())) {
            throw new IllegalArgumentException("复习提纲正在生成，请稍等");
        }
        executor.submit(() -> doGenerate(doc));
    }

    private void doGenerate(ReviewDoc doc) {
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
            missing = generateBatches(doc, p, ordered, byName, missing, REVIEW_CHUNK, "生成");

            // 缺卡补批：一卡一请求（和出题补题同样的原因——按批重试会让尾部卡一直饿死）
            for (int round = 1; round <= RETRY_ROUNDS && !missing.isEmpty(); round++) {
                log.warn("复习提纲还有 {} 个点未生成，第 {} 轮补批", missing.size(), round);
                missing = generateBatches(doc, p, ordered, byName, missing, 1, "补批");
            }

            if (missing.isEmpty()) {
                doc.setStatus("ready");
                doc.setProgressJson(null);
                doc.setUpdatedAt(System.currentTimeMillis());
                reviews.save(doc);
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
                                                 int batchSize, String stage) {
        List<List<KnowledgePoint>> batches = chunk(missing, batchSize);
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        for (List<KnowledgePoint> batch : batches) {
            futures.add(CompletableFuture.supplyAsync(() -> chatWithRetry(
                    PromptTemplates.REVIEW_SYSTEM,
                    PromptTemplates.reviewChunkUser(scopeLine(p), pointLines(batch)),
                    REVIEW_TOKENS), executor));
        }
        for (int i = 0; i < batches.size(); i++) {
            List<KnowledgePoint> batch = batches.get(i);
            try {
                mergeBatch(byName, batch, futures.get(i).join());
            } catch (Exception ex) {
                log.warn("复习{}批次失败（{} 个点）：{}", stage, batch.size(), rootMessage(ex));
            }
            saveContent(doc, ordered, byName);
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
            String rawName = Mappings.text(card, "name").trim();
            String name = resolvePointName(rawName, wanted.keySet());
            if (name == null) continue;
            KnowledgePoint kp = wanted.get(name);
            if (kp == null) continue;
            ObjectNode out = Mappings.MAPPER.createObjectNode();
            out.put("name", name);
            out.put("category", kp.getCategory() == null ? "综合" : kp.getCategory());
            out.put("priority", kp.getPriority() == null ? "medium" : kp.getPriority());
            out.put("summary", MemoryRules.cut(Mappings.text(card, "summary").trim(), 200));
            out.put("interview", MemoryRules.cut(Mappings.text(card, "interview").trim(), 200));
            out.put("principle", MemoryRules.cut(Mappings.text(card, "principle").trim(), 600));
            ArrayNode params = out.putArray("params");
            for (String s : Mappings.stringList(card, "params")) {
                if (params.size() >= 5) break;
                String v = MemoryRules.cut(s, 120).trim();
                if (!v.isBlank()) params.add(v);
            }
            ArrayNode pitfalls = out.putArray("pitfalls");
            for (String s : Mappings.stringList(card, "pitfalls")) {
                if (pitfalls.size() >= 5) break;
                String v = MemoryRules.cut(s, 120).trim();
                if (!v.isBlank()) pitfalls.add(v);
            }
            // 新提示词用平铺的 selfCheckQ/selfCheckA（减少嵌套，模型更少写错括号）；
            // 兼容旧格式的嵌套 selfCheck {q,a}
            JsonNode sc = card.path("selfCheck");
            String scQ = sc.isObject() ? Mappings.text(sc, "q") : Mappings.text(card, "selfCheckQ");
            String scA = sc.isObject() ? Mappings.text(sc, "a") : Mappings.text(card, "selfCheckA");
            if (!scQ.isBlank() || !scA.isBlank()) {
                ObjectNode self = out.putObject("selfCheck");
                self.put("q", MemoryRules.cut(scQ.trim(), 200));
                self.put("a", MemoryRules.cut(scA.trim(), 300));
            }
            // summary 是必学层，空的视为无效卡，留给补批
            if (out.path("summary").asText("").isBlank()) continue;
            byName.put(name, out);
        }
    }

    /* ================= 辅助 ================= */

    private void ensureScaffold(Practice p) {
        if (!knowledge.listOf(p.getId()).isEmpty()) return;
        String refs = "";
        if ("range".equals(p.getScopeMode())) {
            try {
                refs = MemoryRules.cut(references.expand(p.getScopeText()), PromptBudget.MAX_REFS);
            } catch (Exception e) {
                log.warn("复习提纲抓取参考资料失败（降级）：{}", rootMessage(e));
            }
        }
        knowledge.scaffold(p, refs);
    }

    /** 按大类分组、组内按优先级排序：高频先看、常见其次、偏门最后 */
    private List<KnowledgePoint> orderedPoints(Practice p) {
        List<KnowledgePoint> all = knowledge.listOf(p.getId());
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
        return MemoryRules.cut(PromptTemplates.scopeSection(
                p.getTopic(), p.getScopeMode(), p.getScopeText()), 400);
    }

    private JsonNode chatWithRetry(String system, String user, int maxTokens) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return llm.chatJson(system, user, maxTokens, false);
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
        throw last == null ? new LlmService.LlmException("复习卡生成失败") : last;
    }

    private ReviewDoc newDoc(String practiceId) {
        long now = System.currentTimeMillis();
        ReviewDoc doc = new ReviewDoc();
        doc.setId(UUID.randomUUID().toString());
        doc.setPracticeId(practiceId);
        doc.setStatus("generating");
        doc.setContentJson("{\"points\":[]}");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        return doc;
    }

    /** 已生成的卡：name → 卡 */
    private Map<String, ObjectNode> readPoints(ReviewDoc doc) {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        JsonNode root = Mappings.parseJson(doc.getContentJson());
        if (root == null) return out;
        for (JsonNode card : root.path("points")) {
            String name = Mappings.text(card, "name").trim();
            if (!name.isBlank() && card instanceof ObjectNode obj) out.put(name, obj);
        }
        return out;
    }

    private void saveContent(ReviewDoc doc, List<KnowledgePoint> ordered, Map<String, ObjectNode> byName) {
        ObjectNode root = Mappings.MAPPER.createObjectNode();
        ArrayNode arr = root.putArray("points");
        for (KnowledgePoint kp : ordered) {
            ObjectNode card = byName.get(kp.getName());
            if (card != null) arr.add(card);
        }
        doc.setContentJson(root.toString());
        doc.setUpdatedAt(System.currentTimeMillis());
        reviews.save(doc);
    }

    private void setProgress(ReviewDoc doc, int done, int planned, List<String> failed, String error) {
        ObjectNode p = Mappings.MAPPER.createObjectNode();
        p.put("planned", planned);
        p.put("done", done);
        ArrayNode f = p.putArray("failed");
        failed.forEach(f::add);
        p.put("running", running.contains(doc.getPracticeId()));
        if (error != null) p.put("error", error);
        doc.setProgressJson(p.toString());
        doc.setUpdatedAt(System.currentTimeMillis());
        reviews.save(doc);
    }

    private int countPlanned(ReviewDoc doc, Practice p) {
        return Math.max(knowledge.listOf(p.getId()).size(), readPoints(doc).size());
    }

    /* ================= DTO ================= */

    private ReviewDocDto dto(ReviewDoc doc) {
        JsonNode content = Mappings.parseJson(doc.getContentJson());
        if (content == null) content = Mappings.MAPPER.createObjectNode().putArray("points");
        JsonNode progress = Mappings.parseJson(doc.getProgressJson());
        // running 用**进程内实时状态**，不用落库的旧值：
        // 上次进程崩溃/重启后，库里还写着 running=true，前端会永远转圈；
        // 覆盖成 false 后，前端能给出「继续生成」。
        if ("generating".equals(doc.getStatus()) && progress instanceof ObjectNode po) {
            po.put("running", running.contains(doc.getPracticeId()));
        }
        int done = content.path("points").size();
        int planned = progress != null && progress.path("planned").isNumber()
                ? progress.path("planned").asInt() : done;
        return new ReviewDocDto(doc.getId(), doc.getPracticeId(), doc.getStatus(),
                content, progress, planned, done, doc.getUpdatedAt());
    }

    private ReviewDocDto noneDto(Practice p) {
        ObjectNode content = Mappings.MAPPER.createObjectNode();
        content.putArray("points");
        return new ReviewDocDto(null, p.getId(), "none", content, null,
                knowledge.listOf(p.getId()).size(), 0, 0);
    }

    private Practice getPractice(String practiceId) {
        return practices.findById(practiceId)
                .orElseThrow(() -> new NotFoundException("练习不存在或已删除"));
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null || m.isBlank() ? cur.getClass().getSimpleName() : m;
    }
}
