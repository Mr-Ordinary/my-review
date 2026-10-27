package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.review.vt.domain.KnowledgePoint;
import com.review.vt.domain.Practice;
import com.review.vt.repo.KnowledgePointRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 知识点体系：把练习范围拆成一棵考点清单，出题与掌握度都挂在这些点上。
 *
 * 四条设计原则（对应用户定的出题四原则）：
 * 1. **深度跟着点走**——同一个点被考过几次就考第几层，不是按第几张卷定深度。
 * 2. **掌握看变种**——同一个点换个角度还答得好才算掌握，蒙对一次不算。
 *    所以每次出的「角度」都要记下来，下次出题必须避开它。
 * 3. **发散不越界**——用户作答里露出的思路可以引出新考点，但只收**范围内**的，
 *    否则会变成「多说多错」，让用户不敢展开答。
 * 4. **强化不饿死**——弱项不随机抽，按「最久没考 → 最差」公平轮转，
 *    保证任何一个弱项都会在有限张卷内被考到。
 */
@Service
public class KnowledgeService {

    /** 及格线（表现分 0-10） */
    static final double PASS_SCORE = 6.0;

    /**
     * 已掌握需要的次数：**最近 2 次变种都及格**即算掌握。
     *
     * 从 3 次降到 2 次：3 次会显著拉长复习周期、反复出同一批题，
     * 对「高频点快速覆盖」的目标不划算。2 次换角度都过，足以确认不是蒙对。
     */
    static final int MASTERED_TIMES = 2;

    /** 单个练习的清单上限，防止拆得过细 */
    static final int MAX_POINTS = 400;

    /**
     * 串联大题的证据降权系数。
     * 一道跨 3 个点的综合题答得差，不该让三个点一起「连坐」——
     * 它考的是「能不能讲通」，对单点掌握度只是弱证据。
     */
    static final double LINK_WEIGHT = 0.5;

    /**
     * 一次作答证据。
     *
     * @param score 表现分 0-10
     * @param link  是否来自卷末串联大题（跨多点），是则算掌握度时降权
     */
    public record Evidence(double score, boolean link) {}

    /**
     * 交卷回写的一条记录：这道题考了哪几个点、多少分、用的什么角度。
     * 常规题 pointNames.size()==1；串联大题 2-3 个，link 自动成立。
     */
    public record PointHit(List<String> pointNames, double score, String angle) {
        public boolean isLink() { return pointNames.size() > 1; }
    }

    private final KnowledgePointRepository points;
    private final LlmService llm;
    private final WebSearch webSearch;

    public KnowledgeService(KnowledgePointRepository points, LlmService llm, WebSearch webSearch) {
        this.points = points;
        this.llm = llm;
        this.webSearch = webSearch;
    }

    /* ================= 深度与掌握度 ================= */

    // 「timesSeen→第N层」的自动加深已删除——那是上一版深度失控的根源。
    // 深度现在由用户选的复习强度决定（Intensities.boundOf），点上的三档规格是天花板。

    /**
     * 按历次（变种）作答算掌握度 0-100，近期权重更高。
     * 串联大题的证据按 {@link #LINK_WEIGHT} 降权——它是弱证据，不该主导单点掌握度。
     */
    public static double computeMastery(List<Evidence> history) {
        if (history == null || history.isEmpty()) return 0;
        double wSum = 0, acc = 0;
        for (int i = 0; i < history.size(); i++) {
            Evidence e = history.get(i);
            if (e == null) continue;
            double s = Math.max(0, Math.min(10, e.score()));
            double w = (i + 1) * (e.link() ? LINK_WEIGHT : 1.0); // 越近的越重；串联题降权
            acc += s * w;
            wSum += w;
        }
        if (wSum <= 0) return 0;
        return Math.round(acc / wSum * 10 * 10) / 10.0; // 0-100，1 位小数
    }

    /**
     * 四个状态，分清「还不能确认」和「确实不会」——这两种情况的掌握度可能都是 80 或 30，
     * 但用户该做的事完全不同：
     *
     * <pre>
     * 未学    从没考过
     * 学习中  考过 1 次且及格——样本不够，但苗头不错，继续考就行
     * 待巩固  已经露怯：有失手，或最近 2 次里有不及格
     * 已掌握  最近 2 次变种题都及格
     * </pre>
     *
     * 为什么「已掌握」只看最近 2 次而不是全部历史：一次早期答砸就永久判死，
     * 跟「强化直到掌握」自相矛盾——用户后面几轮都答好了，系统还说他没掌握。
     */
    public static String statusOf(List<Evidence> history) {
        if (history == null || history.isEmpty()) return "未学";

        boolean anyFail = false;
        for (Evidence e : history) {
            if (e == null || e.score() < PASS_SCORE) { anyFail = true; break; }
        }

        if (history.size() >= MASTERED_TIMES) {
            List<Evidence> recent = history.subList(history.size() - MASTERED_TIMES, history.size());
            for (Evidence e : recent) {
                if (e == null || e.score() < PASS_SCORE) return "待巩固";
            }
            return "已掌握";
        }
        return anyFail ? "待巩固" : "学习中";
    }

    /* ================= 拆清单（取证管道） ================= */

    /**
     * 首次出题前把范围拆成知识点清单。**宁精勿滥**——只收面试真会问的点，
     * 每个点自带三档内容规格（考到哪为止）与重要度（搜索取证分级）。
     *
     * <p>管道：粗拆候选 → 联网取证（5 条切口）→ 按证据分级 → 落库。
     * 重要度靠的是「大多数人的面试经验」（搜索结果），不是 AI 空想；
     * 证据（evidenceJson）落库可查，二刷觉得分级不对能翻出当时凭什么这么定。
     */
    public List<KnowledgePoint> scaffold(Practice p) {
        List<KnowledgePoint> existing = points.findByPracticeIdOrderByCreatedAtAsc(p.getId());
        if (!existing.isEmpty()) return existing;

        String scope = "range".equals(p.getScopeMode()) ? p.getScopeText() : p.getTopic();

        // ① 粗拆：候选点 + 三档内容规格
        JsonNode result = llm.chatJson(PromptTemplates.INTERVIEW_SYSTEM,
                PromptTemplates.scaffoldUser(scope, p.getLevel()), 16384, false);

        List<Pending> pending = new ArrayList<>();
        for (JsonNode cat : result.path("categories")) {
            String category = MemoryRules.cut(Mappings.text(cat, "name"), 20);
            for (JsonNode pt : cat.path("points")) {
                String name = MemoryRules.cut(Mappings.text(pt, "name"), 30);
                if (name.isBlank() || pending.size() >= MAX_POINTS) continue;
                boolean dup = pending.stream().anyMatch(k -> k.name.equals(name));
                if (dup) continue;
                pending.add(new Pending(category, name,
                        MemoryRules.cut(Mappings.text(pt, "quick"), 60),
                        MemoryRules.cut(Mappings.text(pt, "std"), 80),
                        MemoryRules.cut(Mappings.text(pt, "deep"), 80)));
            }
        }
        if (pending.isEmpty()) {
            throw new LlmService.LlmException("AI 没有拆出知识点清单，请重试");
        }

        // ② 联网取证：多切口搜「大多数人真在问什么」
        String evidence = gatherEvidence(scope);
        boolean fallback = evidence.isBlank();

        // ③ 按证据分级（搜索挂了就降级为 AI 常识分级，标 fallback）
        Map<String, Prior> priors = classifyPriorities(pending, evidence);

        List<KnowledgePoint> out = new ArrayList<>();
        for (Pending pt : pending) {
            Prior pr = priors.get(pt.name);
            String priority = pr != null && pr.priority != null ? pr.priority : "medium";
            KnowledgePoint kp = newPoint(p.getId(), pt.category, pt.name, "scope", true,
                    pt.quick, pt.std, pt.deep);
            kp.setPriority(priority);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("evidence", pr == null || pr.evidence == null ? "" : pr.evidence);
            ev.put("hits", pr == null ? List.of() : pr.hits);
            ev.put("source", fallback ? "fallback" : "search");
            ev.put("queryCount", fallback ? 0 : EVIDENCE_QUERIES.length);
            try {
                kp.setEvidenceJson(Mappings.MAPPER.writeValueAsString(ev));
            } catch (Exception e) {
                kp.setEvidenceJson("{\"source\":\"" + (fallback ? "fallback" : "search") + "\"}");
            }
            out.add(kp);
        }
        for (KnowledgePoint kp : out) points.save(kp);
        return out;
    }

    /** 取证查询条数——多切口交叉，单条查询太容易被 SEO 带偏 */
    private static final String[] EVIDENCE_QUERIES = {
            "%s 高频面试题",
            "%s 面试真题",
            "%s 八股文",
            "%s 源码 面试",
            "%s 场景题 面试",
    };

    /** 搜一轮面经线索拼成取证材料。全挂返回空串（调用方降级为 AI 常识分级） */
    private String gatherEvidence(String scope) {
        StringBuilder sb = new StringBuilder();
        String topic = MemoryRules.cut(scope, 40);
        for (String pattern : EVIDENCE_QUERIES) {
            List<WebSearch.Hit> hits = webSearch.search(pattern.formatted(topic));
            if (hits.isEmpty()) continue;
            sb.append("【查询】").append(pattern.formatted(topic)).append('\n');
            for (WebSearch.Hit h : hits) {
                sb.append("- ").append(h.title());
                if (h.snippet() != null && !h.snippet().isBlank()) {
                    sb.append(" —— ").append(h.snippet());
                }
                sb.append('\n');
            }
        }
        return MemoryRules.cut(sb.toString(), 4000);
    }

    private Map<String, Prior> classifyPriorities(List<Pending> pending, String evidence) {
        StringBuilder names = new StringBuilder();
        for (Pending pt : pending) {
            if (names.length() > 0) names.append("、");
            names.append(pt.name);
        }
        JsonNode result = llm.chatJson(PromptTemplates.INTERVIEW_SYSTEM,
                PromptTemplates.priorityUser(names.toString(), evidence), 8192, false);
        Map<String, Prior> out = new java.util.HashMap<>();
        for (JsonNode n : result.path("priorities")) {
            String name = Mappings.text(n, "name");
            if (name.isBlank()) continue;
            String priority = Mappings.text(n, "priority").trim().toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("high", "medium", "low").contains(priority)) priority = "medium";
            List<String> hits = new ArrayList<>();
            for (JsonNode h : n.path("hits")) {
                String s = h.asText("").trim();
                if (!s.isBlank()) hits.add(MemoryRules.cut(s, 60));
                if (hits.size() >= 3) break;
            }
            out.put(name, new Prior(priority, Mappings.text(n, "evidence"), hits));
        }
        return out;
    }

    /** 粗拆阶段的候选点（还没落库） */
    private record Pending(String category, String name, String quick, String std, String deep) {}

    /** 分级结果 */
    private record Prior(String priority, String evidence, List<String> hits) {}

    private KnowledgePoint newPoint(String practiceId, String category, String name,
                                    String source, boolean inScope,
                                    String boundQuick, String boundStd, String boundDeep) {
        long now = System.currentTimeMillis();
        KnowledgePoint kp = new KnowledgePoint();
        kp.setId(UUID.randomUUID().toString());
        kp.setPracticeId(practiceId);
        kp.setCategory(category == null ? "综合" : category);
        kp.setName(name);
        kp.setSource(source);
        kp.setInScope(inScope);
        kp.setBoundQuick(boundQuick);
        kp.setBoundStd(boundStd);
        kp.setBoundDeep(boundDeep);
        kp.setTimesSeen(0);
        kp.setHistoryJson("[]");
        kp.setAnglesJson("[]");
        kp.setMastery(0);
        kp.setStatus("未学");
        kp.setCreatedAt(now);
        kp.setUpdatedAt(now);
        return kp;
    }

    /* ================= 出卷选点 ================= */

    /**
     * 从清单里挑这一卷要考哪些点。配比体现「覆盖全范围 + 强化薄弱 + 巩固已掌握」：
     * 补足（没考过的）40% / 强化（考过但没掌握的）30% / 巩固（已掌握的）20%，
     * 串联不额外占名额——它是从已覆盖的点里挑几个，捆成卷末那一道大题。
     *
     * <p>**先按复习强度过滤点池**（速通只出 high，标准 high+medium，深挖全量），
     * 再按学习状态配比。弱项**不随机**：按「最久没考 → 掌握度最低」排序取，保证不饿死任何一个弱点。
     */
    public List<KnowledgePoint> pick(Practice p, int count) {
        List<KnowledgePoint> all = points.findByPracticeIdOrderByCreatedAtAsc(p.getId());
        if (all.isEmpty()) return List.of();
        // 按强度筛池：low 的点只在深挖时才进题池
        List<KnowledgePoint> pool = new ArrayList<>();
        for (KnowledgePoint kp : all) {
            if (Intensities.allows(kp, p.getIntensity())) pool.add(kp);
        }
        if (pool.isEmpty()) pool = all; // 全被筛掉时不卡死（兜底：宁可出题也不要交白卷）

        List<KnowledgePoint> fresh = new ArrayList<>();   // 没考过
        List<KnowledgePoint> weak = new ArrayList<>();    // 考过但没掌握
        List<KnowledgePoint> strong = new ArrayList<>();  // 已掌握/熟练
        for (KnowledgePoint kp : pool) {
            if (kp.getTimesSeen() == 0) fresh.add(kp);
            else if ("已掌握".equals(kp.getStatus())) strong.add(kp);
            else weak.add(kp);
        }
        shuffle(fresh);
        shuffle(strong);
        // 弱项公平轮转：最久没被考的排最前，同等久度下最差的优先
        weak.sort((a, b) -> {
            int la = lastSeenPaperSeq(a), lb = lastSeenPaperSeq(b);
            if (la != lb) return Integer.compare(la, lb);
            return Double.compare(a.getMastery(), b.getMastery());
        });

        List<KnowledgePoint> picked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 补足 / 强化 / 巩固按剩余名额分配
        int remain = count;
        take(picked, seen, fresh, (int) Math.ceil(remain * 0.4));
        take(picked, seen, weak, (int) Math.ceil(remain * 0.3));
        take(picked, seen, strong, (int) Math.ceil(remain * 0.2));
        // 还没填满就从剩余里补（清单里点不够时不会硬凑到 count）
        List<KnowledgePoint> rest = new ArrayList<>();
        for (KnowledgePoint kp : pool) if (!seen.contains(kp.getId())) rest.add(kp);
        shuffle(rest);
        take(picked, seen, rest, count - picked.size());
        return picked;
    }

    /**
     * 串联大题的原料：同一个大类里**已经考过**的 2-3 个点。
     * 没考过的点谈不上「串起来」——它们彼此还没有共同语境。
     */
    public List<KnowledgePoint> pickLinkable(List<KnowledgePoint> all, int need) {
        Map<String, List<KnowledgePoint>> byCat = new LinkedHashMap<>();
        for (KnowledgePoint kp : all) {
            if (kp.getTimesSeen() == 0) continue;
            byCat.computeIfAbsent(kp.getCategory(), k -> new ArrayList<>()).add(kp);
        }
        // 大类按「该类里最弱的点」排序，优先串那些需要巩固的
        List<Map.Entry<String, List<KnowledgePoint>>> groups = new ArrayList<>(byCat.entrySet());
        groups.sort((a, b) -> Double.compare(minMastery(b.getValue()), minMastery(a.getValue())));

        List<KnowledgePoint> out = new ArrayList<>();
        for (Map.Entry<String, List<KnowledgePoint>> e : groups) {
            if (out.size() >= need) break;
            List<KnowledgePoint> group = e.getValue();
            if (group.size() < 2) continue;
            // 该类里最弱的几个点优先被串
            group.sort((x, y) -> Double.compare(x.getMastery(), y.getMastery()));
            for (KnowledgePoint kp : group) {
                if (out.size() >= need) break;
                if (out.stream().noneMatch(o -> o.getId().equals(kp.getId()))) out.add(kp);
            }
        }
        return out;
    }

    private static double minMastery(List<KnowledgePoint> group) {
        double m = Double.MAX_VALUE;
        for (KnowledgePoint kp : group) m = Math.min(m, kp.getMastery());
        return m;
    }

    /**
     * 期末综合考的选点：**分层抽样**，不是补足/强化/深入配比。
     *
     * 普通卷的配比是为了「学习」——照顾薄弱、推进深度。
     * 期末考是为了「测量」——必须无偏，否则测出来的只是「系统认为你弱的地方」。
     *
     * 所以：按大类配额（每类至少 1 题，其余按点数比例），大类内**均匀随机**。
     * 约三分之一的题位做成综合题（3 个点），这样 20 道题能覆盖到 40-60 个点；
     * 点数少于题量时就全覆盖。
     *
     * @return 每个元素是一道题要考的点（常规题 1 个，综合题 3 个）
     */
    public List<List<KnowledgePoint>> pickFinal(Practice p, int questionCount) {
        List<KnowledgePoint> all = points.findByPracticeIdOrderByCreatedAtAsc(p.getId());
        if (all.isEmpty() || questionCount <= 0) return List.of();

        Map<String, List<KnowledgePoint>> byCat = new LinkedHashMap<>();
        for (KnowledgePoint kp : all) {
            byCat.computeIfAbsent(kp.getCategory(), k -> new ArrayList<>()).add(kp);
        }

        Map<String, Integer> slots = categorySlots(byCat, all.size(), questionCount);

        List<List<KnowledgePoint>> out = new ArrayList<>();
        for (Map.Entry<String, List<KnowledgePoint>> e : byCat.entrySet()) {
            int s = slots.getOrDefault(e.getKey(), 0);
            if (s <= 0) continue;
            List<KnowledgePoint> pool = new ArrayList<>(e.getValue());
            Collections.shuffle(pool); // 均匀随机，不按掌握度加权

            // 约 1/3 的题位做综合题；点不够时降级成单点题
            int multi = s / 3;
            if (multi == 0 && s >= 2 && pool.size() >= 3) multi = 1;
            int cursor = 0;
            for (int i = 0; i < multi && cursor + 3 <= pool.size(); i++) {
                out.add(List.of(pool.get(cursor), pool.get(cursor + 1), pool.get(cursor + 2)));
                cursor += 3;
            }
            for (int i = 0; i < s - multi && cursor < pool.size(); i++) {
                out.add(List.of(pool.get(cursor++)));
            }
        }
        return out;
    }

    /** 大类题位分配：每类至少 1 题，其余按点数比例，合计不超过 questionCount */
    private static Map<String, Integer> categorySlots(Map<String, List<KnowledgePoint>> byCat,
                                                      int totalPoints, int questionCount) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        int nCat = byCat.size();
        if (nCat == 0) return slots;

        // 题量比点还多时按点数封顶，别为凑题量重复考同一个点
        int cap = Math.min(questionCount, totalPoints);
        List<String> cats = new ArrayList<>(byCat.keySet());

        if (nCat >= cap) {
            // 大类比题位还多：随机挑 cap 个大类各 1 题
            Collections.shuffle(cats);
            for (int i = 0; i < cap; i++) slots.put(cats.get(i), 1);
            return slots;
        }

        // 先每类 1 题，剩余名额按点数比例分（最大余数法凑准总数）
        for (String c : cats) slots.put(c, 1);
        int rest = cap - nCat;
        double[] exact = new double[nCat];
        double floored = 0;
        for (int i = 0; i < nCat; i++) {
            exact[i] = (double) byCat.get(cats.get(i)).size() / totalPoints * rest;
            floored += Math.floor(exact[i]);
            slots.merge(cats.get(i), (int) Math.floor(exact[i]), Integer::sum);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < nCat; i++) order.add(i);
        order.sort((a, b) -> Double.compare(exact[b] - Math.floor(exact[b]),
                exact[a] - Math.floor(exact[a])));
        int leftover = rest - (int) floored;
        for (int i = 0; i < leftover && i < order.size(); i++) {
            slots.merge(cats.get(order.get(i)), 1, Integer::sum);
        }
        return slots;
    }

    /**
     * 从 src 里往 to 中补 n 个还没选过的点。
     * n 是**这一档的配额**，要跟「本次加入的数量」比，不是跟 to 的总长度比——
     * 否则前面的档已经把 to 撑大，后面的强化/深入档一个都进不来。
     */
    private static void take(List<KnowledgePoint> to, Set<String> seen,
                             List<KnowledgePoint> src, int n) {
        int mark = to.size();
        for (KnowledgePoint kp : src) {
            if (to.size() - mark >= n) break;
            if (seen.add(kp.getId())) to.add(kp);
        }
    }

    private static void shuffle(List<?> list) {
        Collections.shuffle(list);
    }

    /* ================= 交卷后：更新掌握度 + 发散新点 ================= */

    /**
     * 交卷后逐点回写：出现次数 +1，证据记入，掌握度与状态重算。
     * 串联大题的分同时写给它挂的每个点，但按 {@link #LINK_WEIGHT} 降权。
     */
    public void record(Practice p, int paperSeq, List<PointHit> hits) {
        if (hits == null || hits.isEmpty()) return;
        Map<String, List<Double>> byName = new LinkedHashMap<>();
        Map<String, Boolean> linkByName = new LinkedHashMap<>();
        for (PointHit hit : hits) {
            if (hit == null || hit.pointNames() == null) continue;
            for (String name : hit.pointNames()) {
                if (name == null || name.isBlank()) continue;
                byName.computeIfAbsent(name, k -> new ArrayList<>()).add(hit.score());
                linkByName.merge(name, hit.isLink(), (a, b) -> a || b);
            }
        }
        if (byName.isEmpty()) return;

        for (KnowledgePoint kp : points.findByPracticeIdOrderByCreatedAtAsc(p.getId())) {
            List<Double> scores = byName.get(kp.getName());
            if (scores == null) continue;
            boolean link = Boolean.TRUE.equals(linkByName.get(kp.getName()));
            List<Evidence> history = readHistory(kp);
            for (Double s : scores) {
                history.add(new Evidence(s == null ? 0 : s, link));
            }
            kp.setHistoryJson(writeHistory(history));
            kp.setTimesSeen(kp.getTimesSeen() + scores.size());
            kp.setMastery(computeMastery(history));
            kp.setStatus(statusOf(history));
            kp.setUpdatedAt(System.currentTimeMillis());
            points.save(kp);
        }
    }

    /**
     * 出完题后把每个点这次用的角度记下来。
     * 下次给同一个点出题时要拿它去禁掉重复问法——这是「变种」真正能落地的前提。
     */
    public void recordAngles(Practice p, int paperSeq, Map<String, String> angleByName) {
        if (angleByName == null || angleByName.isEmpty()) return;
        for (KnowledgePoint kp : points.findByPracticeIdOrderByCreatedAtAsc(p.getId())) {
            String angle = angleByName.get(kp.getName());
            if (angle == null || angle.isBlank()) continue;
            List<Map<String, Object>> angles = readAngles(kp);
            // 同一张卷不重复记（断点续跑时可能被再调一次）
            boolean dup = angles.stream().anyMatch(e -> Integer.valueOf(paperSeq).equals(e.get("paperSeq")));
            if (dup) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("paperSeq", paperSeq);
            entry.put("angle", angle);
            angles.add(entry);
            // 只留最近 5 次问法，够避重又不撑大 prompt
            if (angles.size() > 5) angles.subList(0, angles.size() - 5).clear();
            kp.setAnglesJson(writeAngles(angles));
            kp.setUpdatedAt(System.currentTimeMillis());
            points.save(kp);
        }
    }

    /** 这个点历次用过的出题角度（旧→新），出题时要避开 */
    public List<String> anglesOf(KnowledgePoint kp) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> e : readAngles(kp)) {
            Object a = e.get("angle");
            if (a != null && !String.valueOf(a).isBlank()) out.add(String.valueOf(a));
        }
        return out;
    }

    /**
     * 从用户作答里发散新知识点。**只收范围内的**——越界的宁可不要，
     * 否则用户「多说一句」反而招来答不出的难题，下次就不敢展开了。
     */
    public List<KnowledgePoint> harvest(Practice p, String answers, String scope) {
        if (answers == null || answers.isBlank()) return List.of();
        Set<String> existing = new LinkedHashSet<>();
        for (KnowledgePoint kp : points.findByPracticeIdOrderByCreatedAtAsc(p.getId())) {
            existing.add(kp.getName());
        }

        JsonNode result;
        try {
            result = llm.chatJson(PromptTemplates.INTERVIEW_SYSTEM,
                    PromptTemplates.harvestUser(scope, answers, existing), 4096, false);
        } catch (Exception e) {
            System.err.println("[myreview] 知识点发散失败（忽略，不影响批改）：" + e.getMessage());
            return List.of();
        }

        List<KnowledgePoint> added = new ArrayList<>();
        for (JsonNode n : result.path("newPoints")) {
            if (!n.path("inScope").asBoolean(false)) continue; // 越界的一律丢掉
            String name = MemoryRules.cut(Mappings.text(n, "name"), 30);
            if (name.isBlank() || existing.contains(name) || added.size() >= 10) continue;
            String category = MemoryRules.cut(Mappings.text(n, "category"), 20);
            // 发散点：默认 medium（标准可见），三档规格给个宽松默认（发散点不苛求精确规格）
            KnowledgePoint kp = newPoint(p.getId(), category.isBlank() ? "综合" : category, name,
                    "answer", true,
                    "是什么、怎么用", "原理与主干", "取舍与边界");
            kp.setPriority("medium");
            kp.setEvidenceJson("{\"source\":\"answer\"}");
            points.save(kp);
            added.add(kp);
            existing.add(name);
        }
        return added;
    }

    /* ================= 查询 ================= */

    public List<KnowledgePoint> listOf(String practiceId) {
        return points.findByPracticeIdOrderByCreatedAtAsc(practiceId);
    }

    /** 进度总览：总数 / 已覆盖 / 已掌握 / 平均掌握度 */
    public Map<String, Object> stats(String practiceId) {
        List<KnowledgePoint> all = points.findByPracticeIdOrderByCreatedAtAsc(practiceId);
        int total = all.size();
        int seen = 0, mastered = 0;
        double sum = 0;
        for (KnowledgePoint kp : all) {
            if (kp.getTimesSeen() > 0) seen++;
            if ("已掌握".equals(kp.getStatus())) mastered++;
            sum += kp.getMastery();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("covered", seen);
        m.put("mastered", mastered);
        m.put("avgMastery", total == 0 ? 0 : Math.round(sum / total * 10) / 10.0);
        m.put("percent", total == 0 ? 0 : Math.round(mastered * 100.0 / total));
        return m;
    }

    /** 前端画进度/趋势用的 DTO（service 包内组装，避免把 Mappings 暴露给 web） */
    public List<com.review.vt.web.Dtos.KnowledgePointDto> dtoList(String practiceId) {
        List<com.review.vt.web.Dtos.KnowledgePointDto> out = new ArrayList<>();
        for (KnowledgePoint kp : listOf(practiceId)) {
            List<Double> scores = new ArrayList<>();
            for (Evidence e : readHistory(kp)) scores.add(e.score());
            out.add(new com.review.vt.web.Dtos.KnowledgePointDto(
                    kp.getId(), kp.getCategory(), kp.getName(), kp.getSource(),
                    kp.getTimesSeen(), kp.getMastery(), kp.getStatus(), scores, anglesOf(kp),
                    kp.getPriority(), kp.getBoundQuick(), kp.getBoundStd(), kp.getBoundDeep()));
        }
        return out;
    }

    public void deleteByPractice(String practiceId) {
        points.deleteAll(points.findByPracticeIdOrderByCreatedAtAsc(practiceId));
    }

    /* ---------- 内部 ---------- */

    /** 该点最近一次被考是第几卷。没考过返回 -1，用来做弱项轮转的排序键 */
    static int lastSeenPaperSeq(KnowledgePoint kp) {
        if (kp == null) return -1;
        int max = -1;
        JsonNode node = Mappings.parseJson(kp.getHistoryJson());
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                if (n.path("paperSeq").isInt()) max = Math.max(max, n.path("paperSeq").asInt());
            }
        }
        return max;
    }

    private List<Evidence> readHistory(KnowledgePoint kp) {
        JsonNode node = Mappings.parseJson(kp.getHistoryJson());
        List<Evidence> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                double s = n.path("score").isNumber() ? n.path("score").asDouble() : 0;
                boolean link = n.path("link").asBoolean(false);
                out.add(new Evidence(s, link));
            }
        }
        return out;
    }

    private String writeHistory(List<Evidence> history) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append(',');
            Evidence e = history.get(i);
            sb.append("{\"score\":").append(e.score())
              .append(",\"link\":").append(e.link()).append('}');
        }
        return sb.append(']').toString();
    }

    private List<Map<String, Object>> readAngles(KnowledgePoint kp) {
        JsonNode node = Mappings.parseJson(kp.getAnglesJson());
        List<Map<String, Object>> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("paperSeq", n.path("paperSeq").asInt(0));
                e.put("angle", n.path("angle").asText(""));
                out.add(e);
            }
        }
        return out;
    }

    private String writeAngles(List<Map<String, Object>> angles) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < angles.size(); i++) {
            if (i > 0) sb.append(',');
            Map<String, Object> e = angles.get(i);
            sb.append("{\"paperSeq\":").append(e.get("paperSeq")).append(",\"angle\":")
              .append(jsonString(String.valueOf(e.get("angle")))).append('}');
        }
        return sb.append(']').toString();
    }

    private static String jsonString(String v) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        return sb.append('"').toString();
    }
}
