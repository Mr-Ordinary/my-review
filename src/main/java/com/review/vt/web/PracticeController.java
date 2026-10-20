package com.review.vt.web;

import com.review.vt.service.KnowledgeService;
import com.review.vt.service.PracticeService;
import com.review.vt.web.Dtos.PracticeDetailDto;
import com.review.vt.web.Dtos.PracticeDto;
import com.review.vt.web.Dtos.KnowledgePointDto;
import com.review.vt.web.Dtos.QuestionDto;
import com.review.vt.web.Dtos.FinalExamDto;
import com.review.vt.web.Dtos.FinalExamGateDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 真考卷式练习接口（出卷 / 保存作答 / 交卷批改）。出卷与交卷都是后台任务，返回后靠轮询看进度。 */
@RestController
public class PracticeController {

    /**
     * @param scopeMode topic（主题模式）/ range（范围模式）
     * @param paperSize 一卷几道题：6 快练 / 10 标准 / 15 深练。不传按标准 10。
     */
    public record CreatePracticeRequest(String topic, String scopeMode, String scopeText,
                                        String level, Integer paperSize) {}

    public record SaveAttemptRequest(String answer) {}

    private final PracticeService practiceService;
    private final KnowledgeService knowledge;

    public PracticeController(PracticeService practiceService, KnowledgeService knowledge) {
        this.practiceService = practiceService;
        this.knowledge = knowledge;
    }

    @PostMapping("/api/stages/{stageId}/practices")
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeDto create(@PathVariable String stageId, @RequestBody CreatePracticeRequest req) {
        return practiceService.create(stageId, req.topic(), req.scopeMode(), req.scopeText(),
                req.level(), req.paperSize(), false);
    }

    @GetMapping("/api/practices/{id}")
    public PracticeDetailDto detail(@PathVariable String id) {
        return practiceService.detail(id);
    }

    /** 发起出题：AI 出一张 100 分的真考卷（15-30 题，含参考答案），后台跑，立刻返回 */
    @PostMapping("/api/practices/{id}/papers")
    public PracticeDetailDto startPaper(@PathVariable String id) {
        return practiceService.startPaper(id);
    }

    /** 断点续跑：出题/批改中断后从断点继续 */
    @PostMapping("/api/papers/{id}/resume")
    public PracticeDetailDto resumePaper(@PathVariable String id) {
        return practiceService.resumePaper(id);
    }

    /** 发起交卷：后台批改整卷 + 本卷总结 + 更新记忆/摘要，立刻返回 */
    @PostMapping("/api/papers/{id}/submit")
    public PracticeDetailDto submitPaper(@PathVariable String id) {
        return practiceService.submitPaper(id);
    }

    /** 重出本卷总结 / 记忆 / 摘要 / 阶段总结（不重新批改），用于总结生成失败时补救 */
    @PostMapping("/api/papers/{id}/summary")
    public PracticeDetailDto regeneratePaperSummary(@PathVariable String id) {
        return practiceService.regeneratePaperSummary(id);
    }

    /** 删除练习（级联删除卷、题、作答） */
    @DeleteMapping("/api/practices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePractice(@PathVariable String id) {
        practiceService.deletePractice(id);
    }

    /** 保存作答（纯落库，不调 AI，答题零等待） */
    @PostMapping("/api/questions/{id}/attempts")
    public QuestionDto saveAttempt(@PathVariable String id, @RequestBody SaveAttemptRequest req) {
        return practiceService.saveAttempt(id, req.answer());
    }

    /** 知识点清单 + 掌握度（前端画进度/趋势用） */
    @GetMapping("/api/practices/{id}/knowledge")
    public java.util.Map<String, Object> knowledge(@PathVariable String id) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("stats", knowledge.stats(id));
        m.put("points", knowledge.dtoList(id));
        return m;
    }

    /* ---------- 期末综合考 ---------- */

    /** 期末考解锁状态：看覆盖率不看掌握度，掌握度只提示不拦截 */
    @GetMapping("/api/practices/{id}/final-exam/gate")
    public FinalExamGateDto finalExamGate(@PathVariable String id) {
        return practiceService.finalExamGate(id);
    }

    /** 申请一次期末综合考（可多次），后台出卷，返回后靠轮询进度 */
    @PostMapping("/api/practices/{id}/final-exam")
    public PracticeDetailDto startFinalExam(@PathVariable String id) {
        return practiceService.startFinalExam(id);
    }

    /** 历次期末考成绩单，最新的在前 */
    @GetMapping("/api/practices/{id}/final-exams")
    public java.util.List<FinalExamDto> finalExams(@PathVariable String id) {
        return practiceService.finalExams(id);
    }

    /** 查看参考答案：打标记，本题交卷时按规则计 0 分（前端必须先弹确认框） */
    @PostMapping("/api/questions/{id}/reveal")
    public QuestionDto reveal(@PathVariable String id) {
        return practiceService.reveal(id);
    }
}
