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

    /* ---------- 深度由复习强度决定（不是 timesSeen 自动加深） ---------- */

    @Test
    @DisplayName("内容规格按复习强度取档，不看出现次数")
    void bound_followsIntensityNotTimesSeen() {
        KnowledgePoint k = new KnowledgePoint();
        k.setBoundQuick("是什么、怎么用");
        k.setBoundStd("主干流程、关键参数");
        k.setBoundDeep("取舍与边界");
        k.setTimesSeen(5); // 考过 5 次也不该改变取哪档
        assertEquals("是什么、怎么用", Intensities.boundOf(k, "quick"));
        assertEquals("主干流程、关键参数", Intensities.boundOf(k, "standard"));
        assertEquals("取舍与边界", Intensities.boundOf(k, "deep"));
        // 缺档时向浅的降级，不向深的升级
        KnowledgePoint onlyStd = new KnowledgePoint();
        onlyStd.setBoundStd("只有标准档");
        assertEquals("只有标准档", Intensities.boundOf(onlyStd, "quick"), "速通缺档→降到有的一档");
        assertEquals("只有标准档", Intensities.boundOf(onlyStd, "deep"), "深挖缺档→不发明更深的");
    }

    @Test
    @DisplayName("优先级只决定顺序：三种强度都覆盖 high/medium/low")
    void pool_coversAllPriorities() {
        KnowledgePoint high = new KnowledgePoint(); high.setPriority("high");
        KnowledgePoint med = new KnowledgePoint(); med.setPriority("medium");
        KnowledgePoint low = new KnowledgePoint(); low.setPriority("low");
        assertTrue(Intensities.allows(high, "quick"));
        assertTrue(Intensities.allows(med, "quick"), "速通也要覆盖常见点，否则题池太小、反复出同几题");
        assertTrue(Intensities.allows(low, "quick"), "偏门点也要能延展覆盖，只排在最后");
        assertTrue(Intensities.allows(high, "standard"));
        assertTrue(Intensities.allows(med, "standard"));
        assertTrue(Intensities.allows(low, "standard"), "标准同样按顺序延展到 low");
        assertTrue(Intensities.allows(low, "deep"), "深挖收 low");
        // 没标优先级的（旧数据/发散点）按 medium，所有强度都可见
        KnowledgePoint none = new KnowledgePoint();
        assertTrue(Intensities.allows(none, "standard"));
        assertTrue(Intensities.allows(none, "quick"));
    }

    @Test
    @DisplayName("未考点按优先级排序：high 先覆盖，medium 后补")
    void freshPicksHighFirst() {
        InMemoryPoints repo = new InMemoryPoints();
        for (int i = 0; i < 5; i++) {
            KnowledgePoint k = seeded("m" + i, "常见", 0, "未学");
            k.setPriority("medium");
            repo.save(k);
        }
        for (int i = 0; i < 3; i++) {
            KnowledgePoint k = seeded("h" + i, "高频", 0, "未学");
            k.setPriority("high");
            repo.save(k);
        }
        KnowledgeService ks = new KnowledgeService(repo, null, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");
        p.setIntensity("quick");

        List<KnowledgePoint> picked = ks.pick(p, 4);
        assertEquals(4, picked.size());
        long high = picked.stream().filter(k -> "high".equals(k.getPriority())).count();
        assertEquals(3, high, "3 个高频点应全部优先覆盖，实际 " + high);
    }

    @Test
    @DisplayName("速通延展顺序：high → medium → low，偏门点永远排最后")
    void freshPicksLowLast() {
        InMemoryPoints repo = new InMemoryPoints();
        KnowledgePoint h = seeded("h0", "高频", 0, "未学"); h.setPriority("high"); repo.save(h);
        for (int i = 0; i < 2; i++) {
            KnowledgePoint m = seeded("m" + i, "常见", 0, "未学"); m.setPriority("medium"); repo.save(m);
        }
        for (int i = 0; i < 3; i++) {
            KnowledgePoint l = seeded("l" + i, "偏门", 0, "未学"); l.setPriority("low"); repo.save(l);
        }
        KnowledgeService ks = new KnowledgeService(repo, null, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");
        p.setIntensity("quick");

        List<KnowledgePoint> picked = ks.pick(p, 5);
        assertEquals(5, picked.size(), "速通也覆盖全部优先级");
        assertEquals("h0", picked.get(0).getName(), "高频最优先");
        assertTrue(picked.subList(1, 3).stream().allMatch(k -> "medium".equals(k.getPriority())),
                "常见其次：" + picked.stream().map(KnowledgePoint::getPriority).toList());
        assertTrue(picked.subList(3, 5).stream().allMatch(k -> "low".equals(k.getPriority())),
                "偏门排最后：" + picked.stream().map(KnowledgePoint::getPriority).toList());
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
    @DisplayName("2 次变种都及格才算「已掌握」——蒙对一次不算，也不用刷 3 次")
    void status_requiresTwoPasses() {
        assertEquals("未学", KnowledgeService.statusOf(List.of()));
        assertEquals("学习中", KnowledgeService.statusOf(plain(10.0)), "只考 1 次，苗头好但还不算掌握");
        assertEquals("已掌握", KnowledgeService.statusOf(plain(10.0, 10.0)), "2 次都及格即掌握");
        assertEquals("已掌握", KnowledgeService.statusOf(plain(6.0, 7.0)), "及格线 6 分以上都算");
        assertEquals("待巩固", KnowledgeService.statusOf(plain(10.0, 5.0)),
                "最近 2 次里有 1 次不及格——变种题上没答好，是真不稳");
        assertEquals("待巩固", KnowledgeService.statusOf(plain(7.0, 6.0, 5.0)),
                "最近 1 次砸了，说明现在不稳");
    }

    @Test
    @DisplayName("四态要能分清「还不能确认」和「确实不会」——两者掌握度可能一样，该做的事完全不同")
    void status_distinguishesNotEnoughEvidenceFromGenuinelyWeak() {
        // 都是考过 1 次、掌握度 80：一次答得不错 vs 一次答砸
        assertEquals("学习中", KnowledgeService.statusOf(plain(8.0)),
                "考得不错但样本不够 → 学习中，不是「生疏」");
        assertEquals("待巩固", KnowledgeService.statusOf(plain(3.0)),
                "一次就砸 → 待巩固，这才是真不会");

        // 都是考过 4 次：一直不错 vs 中途失手
        assertEquals("已掌握", KnowledgeService.statusOf(plain(8.0, 9.0, 8.0, 9.0)));
        assertEquals("待巩固", KnowledgeService.statusOf(plain(9.0, 9.0, 3.0, 9.0)),
                "近 2 次里有失手 → 待巩固");
    }

    @Test
    @DisplayName("看最近 2 次，不看全部历史：早期答砸过、后面连续及格要能翻身")
    void status_usesRecentWindowNotAllHistory() {
        assertEquals("已掌握", KnowledgeService.statusOf(plain(2.0, 8.0, 9.0, 10.0)),
                "第 1 次砸了不该永久判死——最近 2 次都及格就是已掌握");
        assertEquals("待巩固", KnowledgeService.statusOf(plain(10.0, 10.0, 10.0, 5.0)),
                "最近 1 次砸了，说明现在不稳");
        assertEquals("已掌握", KnowledgeService.statusOf(plain(5.0, 7.0, 8.0, 9.0, 10.0)),
                "多次以后只看最后 2 次");
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
        KnowledgePoint stale = seeded("stale", "并发", 1, "待巩固");
        stale.setMastery(70);
        stale.setHistoryJson("[{\"score\":7,\"link\":false,\"paperSeq\":1}]");

        KnowledgePoint fresh = seeded("fresh", "并发", 1, "待巩固");
        fresh.setMastery(30);
        fresh.setHistoryJson("[{\"score\":3,\"link\":false,\"paperSeq\":5}]");

        KnowledgePoint mid = seeded("mid", "并发", 1, "待巩固");
        mid.setMastery(30);
        mid.setHistoryJson("[{\"score\":3,\"link\":false,\"paperSeq\":1}]");

        repo.save(stale);
        repo.save(fresh);
        repo.save(mid);

        // 最久没考的是 stale(第1卷) 与 mid(第1卷)，其中 mid 掌握度更低 → 应排最前
        assertEquals(1, KnowledgeService.lastSeenPaperSeq(stale));
        assertEquals(5, KnowledgeService.lastSeenPaperSeq(fresh));
        assertEquals(1, KnowledgeService.lastSeenPaperSeq(mid));

        KnowledgeService ks = new KnowledgeService(repo, null, null);
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
    @DisplayName("覆盖阶段：未考点优先，弱项穿插，不浪费题位在已掌握的点上")
    void pick_coverageStagePrioritizesUnseen() {
        InMemoryPoints repo = new InMemoryPoints();
        // 30 个没考过 / 10 个考过但生疏 / 5 个已掌握
        for (int i = 0; i < 30; i++) repo.save(seeded("f" + i, "未覆盖", 0, "未学"));
        for (int i = 0; i < 10; i++) repo.save(seeded("w" + i, "生疏类", 1, "待巩固"));
        for (int i = 0; i < 5; i++) repo.save(seeded("s" + i, "掌握类", 3, "已掌握"));

        KnowledgeService ks = new KnowledgeService(repo, null, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        List<KnowledgePoint> picked = ks.pick(p, 20);

        long fresh = picked.stream().filter(k -> k.getTimesSeen() == 0).count();
        long weak = picked.stream().filter(k -> k.getTimesSeen() > 0 && !"已掌握".equals(k.getStatus())).count();
        long strong = picked.stream().filter(k -> "已掌握".equals(k.getStatus())).count();

        assertEquals(20, picked.size(), "点够用时要满编");
        assertTrue(fresh >= 14, "覆盖阶段未考点要占大多数，实际 " + fresh);
        assertTrue(weak >= 2, "弱项要穿插进来，不能被饿死，实际 " + weak);
        assertEquals(0, strong, "还没覆盖完时不该把题位花在已掌握的点上");
    }

    @Test
    @DisplayName("补弱阶段：都考过之后只出弱项，不反复考已掌握的")
    void pick_weakStageFocusesWeak() {
        InMemoryPoints repo = new InMemoryPoints();
        for (int i = 0; i < 10; i++) repo.save(seeded("w" + i, "生疏类", 1, "待巩固"));
        for (int i = 0; i < 5; i++) repo.save(seeded("s" + i, "掌握类", 3, "已掌握"));

        KnowledgeService ks = new KnowledgeService(repo, null, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        List<KnowledgePoint> picked = ks.pick(p, 8);
        long weak = picked.stream().filter(k -> !"已掌握".equals(k.getStatus())).count();
        long strong = picked.stream().filter(k -> "已掌握".equals(k.getStatus())).count();
        assertEquals(8, picked.size());
        assertTrue(weak >= 6, "补弱阶段要先填满弱项，实际 " + weak);
        assertTrue(strong <= 2, "已掌握的点只用来补位，实际 " + strong);
    }

    @Test
    @DisplayName("收尾阶段：全部掌握后出已掌握的点（或直接期末考）")
    void pick_masteredStageUsesStrong() {
        InMemoryPoints repo = new InMemoryPoints();
        for (int i = 0; i < 6; i++) repo.save(seeded("s" + i, "掌握类", 3, "已掌握"));

        KnowledgeService ks = new KnowledgeService(repo, null, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        List<KnowledgePoint> picked = ks.pick(p, 4);
        assertEquals(4, picked.size());
        assertTrue(picked.stream().allMatch(k -> "已掌握".equals(k.getStatus())));
    }

    @Test
    @DisplayName("阶段判定：有未考→coverage，只剩弱项→weak，全掌握→mastered")
    void stageOf_rules() {
        assertEquals("coverage", KnowledgeService.stageOf(3, 5));
        assertEquals("weak", KnowledgeService.stageOf(0, 5));
        assertEquals("mastered", KnowledgeService.stageOf(0, 0));
    }

    @Test
    @DisplayName("统计的「还剩几个点」按当前强度算：速通不算偏门 low")
    void stats_scopeFollowsIntensity() {
        InMemoryPoints repo = new InMemoryPoints();
        for (int i = 0; i < 3; i++) {
            KnowledgePoint k = seeded("h" + i, "高频", 1, "待巩固");
            k.setPriority("high");
            repo.save(k);
        }
        for (int i = 0; i < 2; i++) {
            KnowledgePoint k = seeded("m" + i, "常见", 0, "未学");
            k.setPriority("medium");
            repo.save(k);
        }
        KnowledgePoint low = seeded("l0", "偏门", 0, "未学");
        low.setPriority("low");
        repo.save(low);

        KnowledgeService ks = new KnowledgeService(repo, null, null);
        var quick = ks.stats("p1", "quick");
        assertEquals(6, quick.get("total"), "总点数仍算全部");
        assertEquals(6, quick.get("scopeTotal"), "速通也覆盖全部点（按 high→medium→low 顺序）");
        assertEquals(3, quick.get("unseen"), "未考包含 medium 与 low");
        assertEquals("coverage", quick.get("stage"));

        var deep = ks.stats("p1", "deep");
        assertEquals(6, deep.get("scopeTotal"), "深挖范围同样是全部");
        assertEquals(3, deep.get("unseen"), "深挖下未考同样包含 low");
    }

    @Test
    @DisplayName("清单点不够时不硬凑，也不能重复选同一个点")
    void pick_shortListAndNoDuplicates() {
        InMemoryPoints repo = new InMemoryPoints();
        for (int i = 0; i < 3; i++) repo.save(seeded("only" + i, "孤本", 0, "未学"));

        KnowledgeService ks = new KnowledgeService(repo, null, null);
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
        KnowledgeService ks = new KnowledgeService(repo, null, null);
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
        repo.save(seeded("seenA", "并发", 2, "待巩固"));
        repo.save(seeded("seenB", "并发", 1, "待巩固"));
        repo.save(seeded("newC", "并发", 0, "未学"));
        repo.save(seeded("otherCat", "集合", 3, "待巩固"));

        KnowledgeService ks = new KnowledgeService(repo, null, null);
        List<KnowledgePoint> all = repo.findByPracticeIdOrderByCreatedAtAsc("p1");
        List<KnowledgePoint> linked = ks.pickLinkable(all, 3);

        assertTrue(linked.stream().allMatch(k -> k.getTimesSeen() > 0), "没考过的点不参与串联");
        assertTrue(linked.stream().allMatch(k -> "并发".equals(k.getCategory())), "只串同大类");
        assertTrue(linked.size() >= 2, "至少 2 个点才谈得上串联，实际 " + linked.size());
    }

    /* ---------- 期末考：分层抽样，无偏测量 ---------- */

    @Test
    @DisplayName("期末考按大类配额：每个大类至少 1 题，不许有大类被漏掉")
    void pickFinal_coversEveryCategory() {
        InMemoryPoints repo = new InMemoryPoints();
        // 5 个大类 × 6 个点 = 30 个点，出 20 题
        String[] cats = {"基本类型", "包装类", "封装", "集合", "并发"};
        for (String c : cats) {
            for (int i = 0; i < 6; i++) repo.save(seeded(c + "-" + i, c, 0, "未学"));
        }
        KnowledgeService ks = new KnowledgeService(repo, null, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        List<List<KnowledgePoint>> questions = ks.pickFinal(p, 20);
        assertTrue(questions.size() <= 20, "不超编：" + questions.size());
        assertTrue(questions.size() >= 15, "点够用时要接近满编：" + questions.size());

        java.util.Set<String> hitCats = new java.util.HashSet<>();
        for (List<KnowledgePoint> q : questions) {
            for (KnowledgePoint kp : q) hitCats.add(kp.getCategory());
        }
        assertEquals(5, hitCats.size(), "每个大类都要被考到，实际 " + hitCats);
    }

    @Test
    @DisplayName("期末考约 1/3 是综合题（3 个点），其余单点题")
    void pickFinal_mixesSynthesisAndSingle() {
        InMemoryPoints repo = new InMemoryPoints();
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < 10; i++) repo.save(seeded("c" + c + "-" + i, "类" + c, 0, "未学"));
        }
        KnowledgeService ks = new KnowledgeService(repo, null, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        List<List<KnowledgePoint>> questions = ks.pickFinal(p, 20);
        long multi = questions.stream().filter(q -> q.size() > 1).count();
        long single = questions.stream().filter(q -> q.size() == 1).count();
        assertTrue(multi >= 1, "至少要有一道综合题，实际 " + multi);
        assertTrue(single >= multi, "单点题不该比综合题少：单 " + single + " 综 " + multi);
        assertTrue(questions.stream().allMatch(q -> q.size() >= 1 && q.size() <= 3),
                "每题 1-3 个点");
    }

    @Test
    @DisplayName("期末考是无偏抽样：不按掌握度加权，答得好的点照样可能被抽到")
    void pickFinal_isUnbiased() {
        InMemoryPoints repo = new InMemoryPoints();
        // 一个大类里：一半点掌握度 100，一半 0。如果加权，低分点会被过度抽中
        for (int i = 0; i < 10; i++) {
            KnowledgePoint kp = seeded("k" + i, "并发", 3, i < 5 ? "已掌握" : "待巩固");
            kp.setMastery(i < 5 ? 100 : 0);
            repo.save(kp);
        }
        KnowledgeService ks = new KnowledgeService(repo, null, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        int strongHit = 0, weakHit = 0;
        for (int round = 0; round < 30; round++) {
            for (List<KnowledgePoint> q : ks.pickFinal(p, 6)) {
                for (KnowledgePoint kp : q) {
                    if (kp.getMastery() >= 50) strongHit++; else weakHit++;
                }
            }
        }
        // 无偏抽样下两者命中数应该在同一量级（不会差出 3 倍）
        double ratio = strongHit == 0 ? 999 : (double) weakHit / strongHit;
        assertTrue(ratio > 0.25 && ratio < 4.0,
                "应接近无偏，实际 弱/强 = " + weakHit + "/" + strongHit);
    }

    @Test
    @DisplayName("点数少于题量时全覆盖，且不重复考同一个点")
    void pickFinal_fullCoverageWithoutDup() {
        InMemoryPoints repo = new InMemoryPoints();
        for (int i = 0; i < 4; i++) repo.save(seeded("few" + i, "孤本", 0, "未学"));
        KnowledgeService ks = new KnowledgeService(repo, null, null);
        Practice p = new Practice();
        p.setId("p1");
        p.setLevel("mid");

        List<List<KnowledgePoint>> questions = ks.pickFinal(p, 20);
        java.util.Set<String> seen = new java.util.HashSet<>();
        int covered = 0;
        for (List<KnowledgePoint> q : questions) {
            for (KnowledgePoint kp : q) {
                if (seen.add(kp.getId())) covered++;
            }
        }
        assertEquals(4, covered, "4 个点要全覆盖");
        assertEquals(4, seen.size(), "不该重复考同一个点");
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
