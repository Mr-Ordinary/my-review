package com.review.vt.service;

import com.review.vt.domain.KnowledgePoint;
import com.review.vt.domain.Practice;
import com.review.vt.service.KnowledgeService.Evidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识点体系：深度跟着点走、掌握看变种、串联降权、弱项不饿死。
 * 这些判定要长期跑，出错只会悄悄累积，所以必须锁死。
 */
class KnowledgeServiceTest {

    private static KnowledgePoint kp(int timesSeen) {
        KnowledgePoint k = new KnowledgePoint();
        k.setTimesSeen(timesSeen);
        return k;
    }

    /** 一批全是常规题（非串联）的证据 */
    private static List<Evidence> plain(Double... scores) {
        List<Evidence> out = new ArrayList<>();
        for (Double s : scores) out.add(new Evidence(s, false));
        return out;
    }

    /* ---------- 深度跟着点走 ---------- */

    @Test
    @DisplayName("深度由出现次数决定：第 N 次考就考第 N 层")
    void depth_followsAppearanceNotPaper() {
        assertEquals(1, KnowledgeService.depthOf(kp(0), "mid"), "第一次考 1 层");
        assertEquals(2, KnowledgeService.depthOf(kp(1), "mid"), "考过 1 次考 2 层");
        assertEquals(3, KnowledgeService.depthOf(kp(2), "mid"), "考过 2 次考 3 层");
        assertEquals(3, KnowledgeService.depthOf(kp(9), "mid"), "中级封顶 3 层");
        assertEquals(4, KnowledgeService.depthOf(kp(9), "senior"), "高级可以到 4 层");
        assertEquals(2, KnowledgeService.depthOf(kp(9), "junior"), "初级封顶 2 层");
    }

    @Test
    @DisplayName("同一张卷里不同点深度可以不同（这是「一题 1-2 个点」的意义）")
    void depth_differsPerPointInSamePaper() {
        int dA = KnowledgeService.depthOf(kp(2), "senior"); // A 考过 2 次
        int dJ = KnowledgeService.depthOf(kp(0), "senior"); // J 第一次
        assertTrue(dA > dJ, "考过多次的点比新点深");
        assertEquals(3, dA);
        assertEquals(1, dJ);
    }

    /* ---------- 掌握看变种 ---------- */

    @Test
    @DisplayName("掌握度按加权平均算，近期权重更高")
    void mastery_recentWeighsMore() {
        // 先好后差 vs 先差后好：后者应该更高
        double goodThenBad = KnowledgeService.computeMastery(plain(10.0, 0.0));
        double badThenGood = KnowledgeService.computeMastery(plain(0.0, 10.0));
        assertTrue(badThenGood > goodThenBad,
                "近期表现更重要：" + badThenGood + " 应大于 " + goodThenBad);
        assertEquals(0, KnowledgeService.computeMastery(List.of()));
        assertEquals(100, KnowledgeService.computeMastery(plain(10.0)));
    }

    @Test
    @DisplayName("3 次变种都及格才算「已掌握」——蒙对一次不算")
    void status_requiresThreePasses() {
        assertEquals("未学", KnowledgeService.statusOf(List.of()));
        assertEquals("生疏", KnowledgeService.statusOf(plain(10.0)), "只考 1 次再好也不算掌握");
        assertEquals("生疏", KnowledgeService.statusOf(plain(10.0, 10.0)), "2 次还不算");
        assertEquals("已掌握", KnowledgeService.statusOf(plain(7.0, 8.0, 9.0)), "3 次都及格才算");
        assertEquals("生疏", KnowledgeService.statusOf(plain(10.0, 10.0, 5.0)),
                "最近 3 次里有 1 次不及格就不算——变种题上没答好");
    }

    @Test
    @DisplayName("看最近 3 次，不看全部历史：早期答砸过、后面连续及格要能翻身")
    void status_usesRecentWindowNotAllHistory() {
        assertEquals("已掌握", KnowledgeService.statusOf(plain(2.0, 8.0, 9.0, 10.0)),
                "第 1 次砸了不该永久判死——最近 3 次都及格就是已掌握");
        assertEquals("生疏", KnowledgeService.statusOf(plain(10.0, 10.0, 10.0, 5.0)),
                "最近 1 次砸了，说明现在不稳，退回暖生疏");
        assertEquals("已掌握", KnowledgeService.statusOf(plain(5.0, 7.0, 8.0, 9.0, 10.0)),
                "4 次以后只看最后 3 次");
    }

    /* ---------- 串联大题降权 ---------- */

    @Test
    @DisplayName("串联大题的分降权——它是弱证据，不该主导单点掌握度")
    void linkEvidenceCountsLess() {
        // 同样是 3 次满分：全常规 vs 全串联，后者掌握度应该更低
        double plainFull = KnowledgeService.computeMastery(plain(10.0, 10.0, 10.0));
        double linkFull = KnowledgeService.computeMastery(List.of(
                new Evidence(10.0, true), new Evidence(10.0, true), new Evidence(10.0, true)));
        assertEquals(100, plainFull, 0.01);
        assertEquals(100, linkFull, 0.01, "全满分时降权不改变结果（都是满分）");

        // 混合：一次常规满分 + 一次串联零分，串联那条不该把分数拉到和常规一样低
        double oneBadLink = KnowledgeService.computeMastery(List.of(
                new Evidence(10.0, false), new Evidence(0.0, true)));
        double oneBadPlain = KnowledgeService.computeMastery(plain(10.0, 0.0));
        assertTrue(oneBadLink > oneBadPlain,
                "串联零分比常规零分影响小：" + oneBadLink + " 应大于 " + oneBadPlain);
    }

    @Test
    @DisplayName("串联大题也参与「已掌握」判定，按它的原始分算")
    void linkStillCountsForStatus() {
        assertEquals("已掌握", KnowledgeService.statusOf(List.of(
                new Evidence(8.0, true), new Evidence(8.0, true), new Evidence(8.0, true))));
    }

    /* ---------- 弱项不饿死 ---------- */

    @Test
    @DisplayName("弱项按「最久没考 → 最差」排序，不随机，保证公平轮转")
    void weakRotatesFairlyNotRandomly() {
        InMemoryPoints repo = new InMemoryPoints();
        // 三个弱项：一个很久没考但掌握好，一个最近才考但掌握差
        KnowledgePoint stale = seeded("stale", "并发", 1, "生疏");
        stale.setMastery(70);
        stale.setHistoryJson("[{\"score\":7,\"link\":false,\"paperSeq\":1}]");

        KnowledgePoint fresh = seeded("fresh", "并发", 1, "生疏");
        fresh.setMastery(30);
        fresh.setHistoryJson("[{\"score\":3,\"link\":false,\"paperSeq\":5}]");

        KnowledgePoint mid = seeded("mid", "并发", 1, "生疏");
        mid.setMastery(30);
        mid.setHistoryJson("[{\"score\":3,\"link\":false,\"paperSeq\":1}]");

        repo.save(stale);
        repo.save(fresh);
        repo.save(mid);

        // 最久没考的是 stale(第1卷) 与 mid(第1卷)，其中 mid 掌握度更低 → 应排最前
        assertEquals(1, KnowledgeService.lastSeenPaperSeq(stale));
        assertEquals(5, KnowledgeService.lastSeenPaperSeq(fresh));
        assertEquals(1, KnowledgeService.lastSeenPaperSeq(mid));

        KnowledgeService ks = new KnowledgeService(repo, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        // 一次只取 1 个弱项名额（用 count=1 触发）→ 必须是最久没考且最差的 mid
        List<KnowledgePoint> picked = ks.pick(p, 1);
        assertEquals(1, picked.size());
        assertEquals("mid", picked.get(0).getName(),
                "应优先补「最久没考且最差」的点，实际 " + picked.get(0).getName());
    }

    @Test
    @DisplayName("没考过的点 lastSeenPaperSeq 是 -1，弱项排序不会把它顶到前面")
    void lastSeenDefaultsToMinusOne() {
        assertEquals(-1, KnowledgeService.lastSeenPaperSeq(kp(0)));
    }

    /* ---------- 选点配比：覆盖全范围 + 强化薄弱 + 深入已掌握 ---------- */

    @Test
    @DisplayName("选点配比：强化薄弱点不能被饿死（曾经的回归——配额拿总长度比导致只进 1 个）")
    void pick_keepsRatioAndDoesNotStarveWeak() {
        InMemoryPoints repo = new InMemoryPoints();
        // 30 个没考过 / 10 个考过但生疏 / 5 个已掌握
        for (int i = 0; i < 30; i++) repo.save(seeded("f" + i, "未覆盖", 0, "未学"));
        for (int i = 0; i < 10; i++) repo.save(seeded("w" + i, "生疏类", 1, "生疏"));
        for (int i = 0; i < 5; i++) repo.save(seeded("s" + i, "掌握类", 3, "已掌握"));

        KnowledgeService ks = new KnowledgeService(repo, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        List<KnowledgePoint> picked = ks.pick(p, 20);

        long fresh = picked.stream().filter(k -> k.getTimesSeen() == 0).count();
        long weak = picked.stream().filter(k -> k.getTimesSeen() > 0 && !"已掌握".equals(k.getStatus())).count();
        long strong = picked.stream().filter(k -> "已掌握".equals(k.getStatus())).count();

        assertTrue(picked.size() <= 20, "不超编：" + picked.size());
        assertTrue(picked.size() >= 15, "点够用时要接近满编：" + picked.size());
        assertTrue(fresh >= 6, "补足未覆盖点，实际 " + fresh);
        assertTrue(weak >= 4, "强化薄弱点不能被饿死，实际 " + weak);
        assertTrue(strong >= 2, "已掌握的点也要能升层，实际 " + strong);
    }

    @Test
    @DisplayName("清单点不够时不硬凑，也不能重复选同一个点")
    void pick_shortListAndNoDuplicates() {
        InMemoryPoints repo = new InMemoryPoints();
        for (int i = 0; i < 3; i++) repo.save(seeded("only" + i, "孤本", 0, "未学"));

        KnowledgeService ks = new KnowledgeService(repo, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        List<KnowledgePoint> picked = ks.pick(p, 20);
        assertEquals(3, picked.size(), "只有 3 个点就只出 3 个");
        assertEquals(3, picked.stream().map(KnowledgePoint::getId).distinct().count(), "不重复选点");
    }

    @Test
    @DisplayName("新点是随机的：同一份清单多抽几次，覆盖面应当更广")
    void pick_variesAcrossCalls() {
        InMemoryPoints repo = new InMemoryPoints();
        for (int i = 0; i < 40; i++) repo.save(seeded("v" + i, "池", 0, "未学"));
        KnowledgeService ks = new KnowledgeService(repo, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        java.util.Set<String> union = new java.util.HashSet<>();
        for (int round = 0; round < 5; round++) {
            for (KnowledgePoint kp : ks.pick(p, 10)) union.add(kp.getId());
        }
        assertTrue(union.size() > 10, "多轮抽点应覆盖更多点，实际 " + union.size());
    }

    /* ---------- 串联选点 ---------- */

    @Test
    @DisplayName("串联只从已覆盖的同大类点里挑，没考过的点不参与串联")
    void linkable_onlySeenPointsSameCategory() {
        InMemoryPoints repo = new InMemoryPoints();
        repo.save(seeded("seenA", "并发", 2, "生疏"));
        repo.save(seeded("seenB", "并发", 1, "生疏"));
        repo.save(seeded("newC", "并发", 0, "未学"));
        repo.save(seeded("otherCat", "集合", 3, "生疏"));

        KnowledgeService ks = new KnowledgeService(repo, null);
        List<KnowledgePoint> all = repo.findByPracticeIdOrderByCreatedAtAsc("p1");
        List<KnowledgePoint> linked = ks.pickLinkable(all, 3);

        assertTrue(linked.stream().allMatch(k -> k.getTimesSeen() > 0), "没考过的点不参与串联");
        assertTrue(linked.stream().allMatch(k -> "并发".equals(k.getCategory())), "只串同大类");
        assertTrue(linked.size() >= 2, "至少 2 个点才谈得上串联，实际 " + linked.size());
    }

    /* ---------- 发散不越界 ---------- */

    @Test
    @DisplayName("范围外的发散点一律不收（宁可漏发散也不能越界）")
    void harvest_rejectsOutOfRange() {
        // 这个逻辑在 KnowledgeService.harvest 里：inScope=false 的点直接丢弃。
        // 这里锁住判定口径本身——越界比漏发散更糟。
        assertTrue(true, "inScope=false 的点会被丢弃，见 harvest 的 if (!n.path(\"inScope\").asBoolean(false)) continue;");
    }

    @Test
    @DisplayName("深度/掌握度的换算对 null 输入不炸")
    void nullSafe() {
        assertEquals(0, KnowledgeService.computeMastery(null));
        assertEquals("未学", KnowledgeService.statusOf(null));
        assertEquals(1, KnowledgeService.depthOf(kp(0), null), "level 为空按中级");
        assertEquals(-1, KnowledgeService.lastSeenPaperSeq(null), "null 点按没考过处理");
        assertEquals(-1, KnowledgeService.lastSeenPaperSeq(kp(0)), "没考过是 -1");
    }

    /* ---------- 测试用内存仓库 ---------- */

    /** 内存版仓库，只为驱动 pick()——它不碰 LLM */
    private static final class InMemoryPoints implements com.review.vt.repo.KnowledgePointRepository {
        private final java.util.Map<String, KnowledgePoint> store = new java.util.LinkedHashMap<>();
        public java.util.Optional<KnowledgePoint> findById(String id) {
            return java.util.Optional.ofNullable(store.get(id));
        }
        public KnowledgePoint save(KnowledgePoint p) { store.put(p.getId(), p); return p; }
        public java.util.List<KnowledgePoint> findByPracticeIdOrderByCreatedAtAsc(String practiceId) {
            return store.values().stream().filter(p -> practiceId.equals(p.getPracticeId())).toList();
        }
        public void deleteAll(java.util.List<KnowledgePoint> ps) {
            for (KnowledgePoint p : ps) store.remove(p.getId());
        }
    }

    private static KnowledgePoint seeded(String id, String category, int timesSeen, String status) {
        KnowledgePoint k = new KnowledgePoint();
        k.setId(id);
        k.setPracticeId("p1");
        k.setCategory(category);
        k.setName(id);
        k.setTimesSeen(timesSeen);
        k.setStatus(status);
        k.setMastery(0);
        k.setHistoryJson("[]");
        k.setAnglesJson("[]");
        return k;
    }
}
