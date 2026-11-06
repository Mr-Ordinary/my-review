package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.review.vt.auth.AuthContext;
import com.review.vt.domain.KnowledgePoint;
import com.review.vt.domain.MemoryPoint;
import com.review.vt.domain.Mistake;
import com.review.vt.domain.Paper;
import com.review.vt.domain.Practice;
import com.review.vt.domain.Question;
import com.review.vt.domain.Stage;
import com.review.vt.domain.User;
import com.review.vt.repo.KnowledgePointRepository;
import com.review.vt.repo.MemoryPointRepository;
import com.review.vt.repo.MistakeRepository;
import com.review.vt.repo.PaperRepository;
import com.review.vt.repo.PracticeRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.repo.StageRepository;
import com.review.vt.repo.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 个人求职总结（feature 11）：把用户**全部学习信息**聚合成固定格式，
 * 交给 AI 出一份求职建议报告，结果缓存到 users.summary_json。
 *
 * <p>输入走全量（不采样、不省略）——用户明确要求「总结要针对完整的学习信息」；
 * 总量用硬上限兜住（{@value #MAX_DATA} 字，超出的段落逐段截断）。
 * 输出是固定 JSON 结构（profile / strengths / weaknesses / jobAdvice /
 * interviewFocus / actionPlan），前端分区卡片渲染。
 */
@Service
public class ProfileSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ProfileSummaryService.class);

    /** 聚合数据的总字数硬上限（防 prompt 膨胀，与出题预算同一思路） */
    static final int MAX_DATA = 12000;

    private static final int MAX_TOKENS = 16384;

    private final StageRepository stages;
    private final PracticeRepository practices;
    private final PaperRepository papers;
    private final QuestionRepository questions;
    private final com.review.vt.repo.AttemptRepository attempts;
    private final KnowledgePointRepository knowledgePoints;
    private final MistakeRepository mistakes;
    private final MemoryPointRepository memoryPoints;
    private final UserRepository users;
    private final LlmService llm;

    public ProfileSummaryService(StageRepository stages, PracticeRepository practices,
                                 PaperRepository papers, QuestionRepository questions,
                                 com.review.vt.repo.AttemptRepository attempts,
                                 KnowledgePointRepository knowledgePoints, MistakeRepository mistakes,
                                 MemoryPointRepository memoryPoints, UserRepository users, LlmService llm) {
        this.stages = stages;
        this.practices = practices;
        this.papers = papers;
        this.questions = questions;
        this.attempts = attempts;
        this.knowledgePoints = knowledgePoints;
        this.mistakes = mistakes;
        this.memoryPoints = memoryPoints;
        this.users = users;
        this.llm = llm;
    }

    /** 生成超过这个时长仍是 generating，视为卡死，允许重新提交（LLM 单次超时 300s，留足重试余量） */
    private static final long STALE_MS = 15 * 60 * 1000L;

    /** 单线程足够：一个用户同时只有一个任务，用户之间的总结偶尔排队可以接受 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "profile-summary");
        t.setDaemon(true);
        return t;
    });

    /** 进程重启后，上次遗留的 generating 对应的线程已不存在，统一标记失败，免得按钮永远锁着 */
    @PostConstruct
    void recoverInterrupted() {
        int n = users.failInterruptedSummaries("服务重启，上次生成被中断，请重新生成");
        if (n > 0) log.warn("回收 {} 个被重启中断的求职总结任务", n);
    }

    /**
     * 提交后台生成。状态先落库再返回，所以刷新页面后仍能读到 generating。
     *
     * @return true=已启动；false=该用户已有任务在跑（不重复提交）
     */
    public boolean start(AuthContext.CurrentUser user, LlmConfig cfg) {
        if (cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            throw new IllegalArgumentException("还没有配置 API Key，请先在个人中心填写");
        }
        long now = System.currentTimeMillis();
        if (!users.tryStartSummary(user.userId(), now, now - STALE_MS)) return false;
        try {
            executor.submit(() -> run(user, cfg));
        } catch (RuntimeException e) {
            users.failSummary(user.userId(), "任务提交失败：" + e.getMessage());
            throw e;
        }
        return true;
    }

    /** worker 线程：ThreadLocal 不跨线程池，显式恢复用户上下文，仓库的 user_id 过滤才成立 */
    private void run(AuthContext.CurrentUser user, LlmConfig cfg) {
        AuthContext.set(user);
        try {
            String data = dataText(user.userId());
            JsonNode result = llm.chatJson(cfg, PromptTemplates.PROFILE_SYSTEM,
                    PromptTemplates.profileSummaryUser(data), MAX_TOKENS, false);
            users.finishSummary(user.userId(), result.toString(), System.currentTimeMillis());
            log.info("个人求职总结已生成：user={} 输入 {} 字", user.username(), data.length());
        } catch (Exception e) {
            log.warn("个人求职总结生成失败：user={}", user.username(), e);
            String msg = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            users.failSummary(user.userId(), cut(msg, 300));
        } finally {
            AuthContext.clear();
        }
    }

    /** 已缓存的总结；没有则 null */
    public JsonNode summary(Long userId) {
        return users.findById(userId)
                .map(u -> Mappings.parseJson(u.getSummaryJson()))
                .orElse(null);
    }

    /* ================= 全量聚合（固定格式） ================= */

    String dataText(Long userId) {
        StringBuilder sb = new StringBuilder();
        List<Stage> stageList = stages.findAllByOrderByUpdatedAtDesc();

        // —— 全景 ——
        int practiceCount = 0, paperCount = 0, questionCount = 0;
        List<Double> allScores = new ArrayList<>();
        List<KnowledgePoint> allKps = new ArrayList<>();
        List<Mistake> allMistakes = new ArrayList<>();
        List<MemoryPoint> allMemory = new ArrayList<>();
        for (Stage s : stageList) {
            for (Practice p : practices.findByStageIdOrderByCreatedAtAsc(s.getId())) {
                practiceCount++;
                for (Paper paper : papers.findByPracticeIdOrderBySequenceAsc(p.getId())) {
                    paperCount++;
                    List<Question> qs = questions.findByPaperIdOrderBySequenceAsc(paper.getId());
                    questionCount += qs.size();
                    Double sc = paperScore(paper, qs);
                    if (sc != null) allScores.add(sc);
                }
                allKps.addAll(knowledgePoints.findByPracticeIdOrderByCreatedAtAsc(p.getId()));
                allMistakes.addAll(mistakes.findByPracticeIdOrderByCreatedAtDesc(p.getId()));
                allMemory.addAll(memoryPoints.findByPracticeIdOrderByCreatedAtDesc(p.getId()));
            }
        }
        sb.append("学习全景：").append(stageList.size()).append(" 个阶段、").append(practiceCount)
          .append(" 个练习、").append(paperCount).append(" 张卷、").append(questionCount).append(" 道题");
        if (!allScores.isEmpty()) {
            sb.append("，百分制平均 ").append(avg(allScores))
              .append(" 分，最近一卷 ").append(allScores.get(allScores.size() - 1)).append(" 分");
        }
        sb.append("\n\n");

        // —— 每个阶段 + 练习 + 卷的 AI 评价 ——
        for (Stage s : stageList) {
            sb.append("阶段：《").append(s.getName()).append("》\n");
            JsonNode sm = Mappings.parseJson(s.getSummaryJson());
            if (sm != null) {
                sb.append("  阶段总结：").append(cut(Mappings.text(sm, "overall"), 200)).append('\n');
                List<String> weak = Mappings.stringList(sm, "keyWeaknesses");
                if (!weak.isEmpty()) sb.append("  阶段短板：").append(cut(String.join("、", weak), 300)).append('\n');
            }
            for (Practice p : practices.findByStageIdOrderByCreatedAtAsc(s.getId())) {
                sb.append("  练习：《").append(p.getTopic()).append("》（")
                  .append(levelLabel(p.getLevel())).append(" · ")
                  .append(intensityLabel(p.getIntensity())).append("）\n");
                if ("range".equals(p.getScopeMode()) && p.getScopeText() != null && !p.getScopeText().isBlank()) {
                    sb.append("    范围：").append(cut(p.getScopeText(), 200)).append('\n');
                }
                List<Paper> ps = papers.findByPracticeIdOrderBySequenceAsc(p.getId());
                int qc = 0;
                List<Double> scores = new ArrayList<>();
                for (Paper paper : ps) {
                    List<Question> qs = questions.findByPaperIdOrderBySequenceAsc(paper.getId());
                    qc += qs.size();
                    Double sc = paperScore(paper, qs);
                    if (sc != null) scores.add(sc);
                }
                sb.append("    成绩：").append(ps.size()).append(" 张卷、").append(qc).append(" 道题");
                if (!scores.isEmpty()) sb.append("，平均 ").append(avg(scores)).append(" 分");
                sb.append('\n');
                for (Paper paper : ps) {
                    if (!"graded".equals(paper.getStatus())) continue;
                    JsonNode psm = Mappings.parseJson(paper.getSummaryJson());
                    if (psm != null && !Mappings.text(psm, "overall").isBlank()) {
                        sb.append("    卷 ").append(paper.getSequence())
                          .append(" 评价：").append(cut(Mappings.text(psm, "overall"), 120)).append('\n');
                    }
                }
                JsonNode mem = Mappings.parseJson(p.getMemoryJson());
                if (mem != null) {
                    List<String> mastered = Mappings.stringList(mem, "mastered");
                    List<String> weak = Mappings.stringList(mem, "weakPoints");
                    if (!mastered.isEmpty()) sb.append("    已掌握：").append(cut(String.join("、", mastered), 300)).append('\n');
                    if (!weak.isEmpty()) sb.append("    薄弱点：").append(cut(String.join("、", weak), 300)).append('\n');
                }
            }
            sb.append('\n');
        }

        // —— 知识点画像（全量） ——
        sb.append("知识点画像：\n");
        Map<String, int[]> catStats = new LinkedHashMap<>(); // 分类 → [已掌握, 待巩固, 学习中, 未学]
        for (KnowledgePoint kp : allKps) {
            String cat = kp.getCategory() == null || kp.getCategory().isBlank() ? "综合" : kp.getCategory();
            int[] c = catStats.computeIfAbsent(cat, k -> new int[4]);
            c[statusIdx(kp.getStatus())]++;
        }
        for (Map.Entry<String, int[]> e : catStats.entrySet()) {
            int[] c = e.getValue();
            sb.append("  ").append(e.getKey()).append("：已掌握 ").append(c[0])
              .append(" / 待巩固 ").append(c[1])
              .append(" / 学习中 ").append(c[2])
              .append(" / 未学 ").append(c[3]).append('\n');
        }
        List<KnowledgePoint> byMastery = new ArrayList<>(allKps);
        byMastery.sort(Comparator.comparingDouble(KnowledgePoint::getMastery).reversed());
        List<KnowledgePoint> strongest = byMastery.subList(0, Math.min(10, byMastery.size()));
        if (!strongest.isEmpty()) {
            sb.append("  最强 10 点：");
            for (KnowledgePoint kp : strongest) {
                sb.append(kp.getName()).append("(").append((int) kp.getMastery()).append(") ");
            }
            sb.append('\n');
        }
        List<KnowledgePoint> weakest = new ArrayList<>(byMastery);
        java.util.Collections.reverse(weakest);
        weakest = weakest.subList(0, Math.min(10, weakest.size()));
        if (!weakest.isEmpty()) {
            sb.append("  最弱 10 点：");
            for (KnowledgePoint kp : weakest) {
                sb.append(kp.getName()).append("(").append((int) kp.getMastery()).append(") ");
            }
            sb.append('\n');
        }
        sb.append('\n');

        // —— 错题 ——
        long open = allMistakes.stream().filter(m -> "open".equals(m.getStatus())).count();
        long resolved = allMistakes.size() - open;
        sb.append("错题本：待订正 ").append(open).append(" / 已解决 ").append(resolved);
        List<String> reasons = allMistakes.stream()
                .filter(m -> m.getReason() != null && !m.getReason().isBlank())
                .map(Mistake::getReason).distinct().limit(8).toList();
        if (!reasons.isEmpty()) sb.append("；错因类型：").append(cut(String.join("、", reasons), 200));
        sb.append("\n\n");

        // —— 期末考成绩单 ——
        for (Stage s : stageList) {
            for (Practice p : practices.findByStageIdOrderByCreatedAtAsc(s.getId())) {
                for (Paper paper : papers.findByPracticeIdOrderBySequenceAsc(p.getId())) {
                    if (!"final".equals(paper.getKind())) continue;
                    List<Question> qs = questions.findByPaperIdOrderBySequenceAsc(paper.getId());
                    Double sc = paperScore(paper, qs);
                    sb.append("期末考：《").append(p.getTopic()).append("》第 ")
                      .append(paper.getSequence()).append(" 次：")
                      .append(sc == null ? "未交卷" : sc + " 分").append('\n');
                }
            }
        }
        sb.append('\n');

        // —— 记忆点样本 ——
        if (!allMemory.isEmpty()) {
            sb.append("记忆点样本（可背诵的结论，最多 20 条）：\n");
            int n = 0;
            for (MemoryPoint mp : allMemory) {
                if (n >= 20) break;
                if (mp.getText() == null || mp.getText().isBlank()) continue;
                sb.append("  - ").append(cut(mp.getText(), 100)).append('\n');
                n++;
            }
        }

        return cut(sb.toString(), MAX_DATA);
    }

    private Double paperScore(Paper paper, List<Question> qs) {
        if (!"graded".equals(paper.getStatus())) return null;
        List<Double> earned = new ArrayList<>();
        boolean any = false;
        for (Question q : qs) {
            Double e = null;
            for (com.review.vt.domain.Attempt a : attempts.findByQuestionIdOrderBySequenceAsc(q.getId())) {
                if (a.getEvaluatedAt() != null && a.getPoints() != null) e = a.getPoints();
            }
            earned.add(e);
            if (e != null) any = true;
        }
        return any ? Scoring.paperScore(earned) : null;
    }

    private static int statusIdx(String status) {
        if (status == null) return 3;
        return switch (status) {
            case "已掌握" -> 0;
            case "待巩固" -> 1;
            case "学习中" -> 2;
            default -> 3;
        };
    }

    private static String levelLabel(String level) {
        return switch (level == null ? "mid" : level) {
            case "junior" -> "初级";
            case "senior" -> "高级";
            default -> "中级";
        };
    }

    private static String intensityLabel(String intensity) {
        return switch (intensity == null ? "standard" : intensity) {
            case "quick" -> "速通";
            case "deep" -> "深挖";
            default -> "标准";
        };
    }

    private static double avg(List<Double> values) {
        double sum = 0;
        for (Double v : values) sum += v;
        return Math.round(sum / values.size() * 10) / 10.0;
    }

    private static String cut(String s, int max) {
        return MemoryRules.cut(s == null ? "" : s, max);
    }
}
