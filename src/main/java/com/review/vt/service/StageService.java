package com.review.vt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.review.vt.domain.Paper;
import com.review.vt.domain.Practice;
import com.review.vt.domain.Question;
import com.review.vt.domain.Stage;
import com.review.vt.repo.AttemptRepository;
import com.review.vt.repo.PaperRepository;
import com.review.vt.repo.PracticeRepository;
import com.review.vt.repo.QuestionRepository;
import com.review.vt.repo.StageRepository;
import com.review.vt.web.Dtos.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 阶段：只是一个用来区分空间的名字（分组），没有业务含义。
 * 做创建 / 列表 / 详情 / 删除（级联）/ 实时统计，以及「全复习练习」的发起。
 *
 * 注意分清两类信息：
 * - 实时统计（几张卷、多少题、平均分）：查询现算
 * - 阶段总结（AI 反馈）：交卷后自动生成，是全复习的依据
 */
@Service
public class StageService {

    private final StageRepository stages;
    private final PracticeRepository practices;
    private final PaperRepository papers;
    private final QuestionRepository questions;
    private final AttemptRepository attempts;
    private final StageSummaryService stageSummary;
    private final PracticeService practiceService;

    public StageService(StageRepository stages, PracticeRepository practices, PaperRepository papers,
                        QuestionRepository questions, AttemptRepository attempts,
                        StageSummaryService stageSummary, PracticeService practiceService) {
        this.stages = stages;
        this.practices = practices;
        this.papers = papers;
        this.questions = questions;
        this.attempts = attempts;
        this.stageSummary = stageSummary;
        this.practiceService = practiceService;
    }

    public List<StageDto> list() {
        return stages.findAllByOrderByUpdatedAtDesc().stream()
                .map(s -> Mappings.stage(s, statsOf(s)))
                .toList();
    }

    public StageDto create(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("阶段名称不能为空");
        long now = System.currentTimeMillis();
        Stage s = new Stage();
        s.setId(UUID.randomUUID().toString());
        s.setName(name.trim());
        s.setStatus("active");
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        stages.save(s);
        return Mappings.stage(s, statsOf(s));
    }

    public StageDetailDto detail(String id) {
        Stage s = get(id);
        List<PracticeDto> ps = practices.findByStageIdOrderByCreatedAtDesc(s.getId()).stream()
                .map(p -> Mappings.practice(p, Mappings.practiceStats(p, questions, attempts, papers)))
                .toList();
        return Mappings.stageDetail(s, statsOf(s), ps);
    }

    /** 重新生成阶段总结（AI 反馈）。交卷后会自动重出，这里给手动补跑用。 */
    public StageDetailDto regenerateSummary(String id) {
        if (practices.findByStageIdOrderByCreatedAtAsc(id).isEmpty()) {
            throw new IllegalArgumentException("该阶段还没有练习，先做练习再生成阶段总结");
        }
        stageSummary.regenerate(id);
        return detail(id);
    }

    /**
     * 发起全复习练习：系统自动在该阶段下创建一个练习，针对整个阶段做全复习。
     *
     * 全复习的**范围就是阶段总结里的 reviewScope**（AI 依据练习成果框定出来的一长串文字），
     * 所以没有阶段总结就没法做全复习——这时候应该直接禁用按钮。
     */
    public PracticeDto createReviewPractice(String stageId) {
        Stage s = get(stageId);
        if (practices.findByStageIdOrderByCreatedAtAsc(stageId).isEmpty()) {
            throw new IllegalArgumentException("该阶段还没有任何练习，先练出数据再做全复习");
        }
        JsonNode summary = stageSummary.summary(stageId);
        String reviewScope = summary == null ? "" : Mappings.text(summary, "reviewScope");
        if (reviewScope.isBlank()) {
            throw new IllegalArgumentException("还没有阶段总结，无法框定全复习范围。请先生成阶段总结（交卷后会自动生成）");
        }

        return practiceService.create(stageId, "阶段全复盘", "range", reviewScope, true);
    }

    /** 级联删除：阶段 → 练习 → 卷 → 题目 → 作答 */
    public void delete(String id) {
        Stage s = get(id);
        List<Practice> ps = practices.findByStageIdOrderByCreatedAtAsc(s.getId());
        for (Practice p : ps) {
            for (Paper paper : papers.findByPracticeIdOrderBySequenceAsc(p.getId())) {
                for (Question q : questions.findByPaperIdOrderBySequenceAsc(paper.getId())) {
                    attempts.deleteAll(attempts.findByQuestionIdOrderBySequenceAsc(q.getId()));
                }
                questions.deleteAll(questions.findByPaperIdOrderBySequenceAsc(paper.getId()));
            }
            papers.deleteAll(papers.findByPracticeIdOrderBySequenceAsc(p.getId()));
        }
        practices.deleteAll(ps);
        stages.delete(s);
    }

    /* ---------- 内部 ---------- */

    private Stage get(String id) {
        return stages.findById(id).orElseThrow(() -> new NotFoundException("阶段不存在或已删除"));
    }

    /** 实时统计（卷数/题数/平均分），现算不落库 */
    private Stats statsOf(Stage s) {
        List<Practice> ps = practices.findByStageIdOrderByCreatedAtAsc(s.getId());
        return Mappings.stageStats(ps, questions, attempts, papers);
    }
}
