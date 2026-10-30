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
     * @param intensity 复习强度 quick（速通）/ standard（标准）/ deep（深挖），不传按 standard
     * @param paperSize 一卷几道题：6 快练 / 10 标准 / 15 深练。不传按标准 10。仅常规组卷模式生效
     */
    public record CreatePracticeRequest(String topic, String scopeMode, String scopeText,
                                        String level, String intensity, Integer paperSize) {}

    public record SaveAttemptRequest(String answer) {}

    /**
     * 出卷请求。
     * @param mode 组卷模式：regular（常规）/ base / improve / deep / baseImprove / midImprove。
     *             不传按 regular。只约束这卷出哪些难度档、出几道题。
     */
    public record StartPaperRequest(String mode) {}

    /** 记事本：整篇文本（PUT 覆盖）或要追加的一段（POST 追加） */
    public record NoteTextRequest(String text) {}

    public record CreateMistakeRequest(String questionId) {}

    public record UpdateMistakeRequest(String status) {}

    private final PracticeService practiceService;
    private final KnowledgeService knowledge;
    private final com.review.vt.service.NoteService notes;
    private final com.review.vt.service.MistakeService mistakes;
    private final com.review.vt.service.MemoryPointService memoryPoints;

    public PracticeController(PracticeService practiceService, KnowledgeService knowledge,
                              com.review.vt.service.NoteService notes,
                              com.review.vt.service.MistakeService mistakes,
                              com.review.vt.service.MemoryPointService memoryPoints) {
        this.practiceService = practiceService;
        this.knowledge = knowledge;
        this.notes = notes;
        this.mistakes = mistakes;
        this.memoryPoints = memoryPoints;
    }

    @PostMapping("/api/stages/{stageId}/practices")
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeDto create(@PathVariable String stageId, @RequestBody CreatePracticeRequest req) {
        return practiceService.create(stageId, req.topic(), req.scopeMode(), req.scopeText(),
                req.level(), req.intensity(), req.paperSize(), false);
    }

    @GetMapping("/api/practices/{id}")
    public PracticeDetailDto detail(@PathVariable String id) {
        return practiceService.detail(id);
    }

    /** 发起出题：AI 出一张 100 分的真考卷（题量随组卷模式，含参考答案），后台跑，立刻返回 */
    @PostMapping("/api/practices/{id}/papers")
    public PracticeDetailDto startPaper(@PathVariable String id,
                                        @RequestBody(required = false) StartPaperRequest req) {
        return practiceService.startPaper(id, req == null ? null : req.mode());
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

    /** 知识点清单 + 掌握度 + 记忆点（前端画进度/趋势与背诵用） */
    @GetMapping("/api/practices/{id}/knowledge")
    public java.util.Map<String, Object> knowledge(@PathVariable String id) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("stats", knowledge.stats(id, practiceService.intensityOf(id)));
        m.put("points", knowledge.dtoList(id));
        // 点名 → 可背诵句子。以前只拼进点评文本、看完就散，现在能按点复习
        m.put("memoryPoints", memoryPoints.byPointName(id));
        return m;
    }

    /* ---------- 错题重做 ---------- */

    /**
     * 针对待订正的错题出一张卷。
     * 选点范围锁定在这一刻的错题上，其余与普通卷一致（可答题、批改、回写掌握度）。
     */
    @PostMapping("/api/practices/{id}/mistakes/retry")
    public PracticeDetailDto retryMistakes(@PathVariable String id) {
        return practiceService.startMistakeRetry(id);
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

    /* ---------- 练习记事本（per-practice，一整块可编辑文本） ---------- */

    /** 整篇覆盖（前端编辑器保存）。空文本 = 清空 */
    @PutMapping("/api/practices/{id}/notes")
    public java.util.Map<String, Object> setNotes(@PathVariable String id,
                                                  @RequestBody NoteTextRequest req) {
        notes.setText(id, req.text());
        return java.util.Map.of("text", notes.text(id), "updatedAt", notes.updatedAt(id));
    }

    /** 末尾换行追加（答题时点「记笔记」）。不掺题号 */
    @PostMapping("/api/practices/{id}/notes")
    public java.util.Map<String, Object> appendNote(@PathVariable String id,
                                                    @RequestBody NoteTextRequest req) {
        notes.append(id, req.text());
        return java.util.Map.of("text", notes.text(id), "updatedAt", notes.updatedAt(id));
    }

    /* ---------- 错题本（per-practice，一题一条） ---------- */

    /** 手动收录 / 重新打开已订正的错题 */
    @PostMapping("/api/practices/{id}/mistakes")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.MistakeDto createMistake(@PathVariable String id, @RequestBody CreateMistakeRequest req) {
        return mistakes.addManual(id, req.questionId());
    }

    @PatchMapping("/api/practices/{id}/mistakes/{mistakeId}")
    public Dtos.MistakeDto updateMistake(@PathVariable String id, @PathVariable String mistakeId,
                                         @RequestBody UpdateMistakeRequest req) {
        return mistakes.setStatus(id, mistakeId, req.status());
    }

    @DeleteMapping("/api/practices/{id}/mistakes/{mistakeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMistake(@PathVariable String id, @PathVariable String mistakeId) {
        mistakes.delete(id, mistakeId);
    }
}
