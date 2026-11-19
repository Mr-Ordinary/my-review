package com.review.vt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.review.vt.pojo.param.*;

import com.review.vt.service.KnowledgeService;
import com.review.vt.service.PracticeService;
import com.review.vt.pojo.dto.*;
import com.review.vt.pojo.dto.PracticeDto;
import com.review.vt.pojo.dto.KnowledgePointDto;
import com.review.vt.pojo.dto.QuestionDto;
import com.review.vt.pojo.dto.FinalExamDto;
import com.review.vt.pojo.dto.FinalExamGateDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 真考卷式练习接口（出卷 / 保存作答 / 交卷批改）。出卷与交卷都是后台任务，返回后靠轮询看进度。 */
@Tag(name = "出题与作答", description = "出题 / 答题 / 交卷批改 / 刷题 / 错题本 / 记事本")
@RestController
public class PracticeController {

    private final PracticeService practiceService;
    private final KnowledgeService knowledgeService;
    private final com.review.vt.service.NoteService noteService;
    private final com.review.vt.service.MistakeService mistakeService;
    private final com.review.vt.service.MemoryPointService memoryPointService;
    private final com.review.vt.service.TaskEventService taskEventService;

    public PracticeController(PracticeService practiceService, KnowledgeService knowledgeService,
                              com.review.vt.service.NoteService noteService,
                              com.review.vt.service.MistakeService mistakeService,
                              com.review.vt.service.MemoryPointService memoryPointService,
                              com.review.vt.service.TaskEventService taskEventService) {
        this.practiceService = practiceService;
        this.knowledgeService = knowledgeService;
        this.noteService = noteService;
        this.mistakeService = mistakeService;
        this.memoryPointService = memoryPointService;
        this.taskEventService = taskEventService;
    }

    /**
     * @param scopeMode topic（主题模式）/ range（范围模式）
     * @param intensity 复习强度 quick（速通）/ standard（标准）/ deep（深挖），不传按 standard
     * @param paperSize 一卷几道题：6 快练 / 10 标准 / 15 深练。不传按标准 10。仅常规组卷模式生效
     */


    /** 记事本：整篇文本（PUT 覆盖）或要追加的一段（POST 追加） */

    @Operation(summary = "新建练习", description = "选主题/范围/难度/强度")
    @PostMapping("/api/stages/{stageId}/practices")
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeDto create(@Parameter(description = "阶段 ID") @PathVariable Long stageId, @RequestBody CreatePracticeParam req) {
        return practiceService.create(stageId, req.topic(), req.scopeMode(), req.scopeText(),
                req.level(), req.intensity(), req.paperSize(), false);
    }

    @Operation(summary = "练习详情", description = "卷列表/笔记/错题/统计，出题批改期间轮询")
    @GetMapping("/api/practices/{id}")
    public PracticeDetailDto detail(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.detail(id);
    }

    /* ================= 答题页轻加载链（拆页后专用，点查走缓存） ================= */

    @Operation(summary = "练习简报", description = "答题页 init：练习 meta（practice:{id} 缓存）+ 卷下拉 + 最新卷 id")
    @GetMapping("/api/practices/{id}/brief")
    public PracticeBriefDto brief(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.brief(id);
    }

    @Operation(summary = "卷大纲", description = "卷 meta + 题目存根（paper:{id} 缓存），切卷/题号导航用")
    @GetMapping("/api/papers/{id}")
    public PaperOutlineDto paperOutline(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.paperOutline(id);
    }

    @Operation(summary = "题内容", description = "切题只查这一题（question:{id} 缓存）；回答另走作答接口")
    @GetMapping("/api/questions/{id}")
    public QuestionDetailDto questionDetail(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.questionDetail(id);
    }

    @Operation(summary = "作答内容", description = "单条作答（attempt:{id} 缓存），有回答时前端再取")
    @GetMapping("/api/attempts/{id}")
    public AttemptDto attemptDetail(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.attemptDetail(id);
    }

    /** 发起出题：AI 出一张 100 分的真考卷（题量随组卷模式，含参考答案），后台跑，立刻返回 */
    @Operation(summary = "出题：生成一张卷", description = "后台并发出题，前端轮询进度；组卷模式见 body.mode")
    @PostMapping("/api/practices/{id}/papers")
    public PracticeDetailDto startPaper(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id,
                                        @RequestBody(required = false) StartPaperParam req) {
        return practiceService.startPaper(id, req == null ? null : req.mode(),
                req == null || req.examMix() == null ? null : req.examMix().toMap());
    }

    /* ================= 刷题（单题流式） ================= */

    /** 刷题状态：started / count / avgScore / streak / question */
    @Operation(summary = "刷题状态", description = "计数/均分/连对/当前题")
    @GetMapping("/api/practices/{id}/drill")
    public java.util.Map<String, Object> drillState(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.drillState(id);
    }

    /** 开始刷题（建刷题卷并出第一题，同步出题） */
    @Operation(summary = "开始刷题", description = "建刷题卷并出第一题")
    @PostMapping("/api/practices/{id}/drill/start")
    public java.util.Map<String, Object> startDrill(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.startDrill(id);
    }

    /** 下一题（同步出题） */
    @Operation(summary = "刷题下一题", description = "上一题答完才出下一题")
    @PostMapping("/api/practices/{id}/drill/next")
    public java.util.Map<String, Object> nextDrill(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.nextDrillQuestion(id);
    }

    /** 批改当前刷题题（AI 批改 + 知识点回写 + 错题收录） */
    @Operation(summary = "刷题批改", description = "单题 AI 批改，回写掌握度与错题本")
    @PostMapping("/api/questions/{id}/drill-grade")
    public java.util.Map<String, Object> gradeDrill(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.gradeDrill(id);
    }

    /** 断点续跑：出题/批改中断后从断点继续 */
    @Operation(summary = "续跑", description = "出题/批改中断后从断点继续")
    @PostMapping("/api/papers/{id}/resume")
    public PracticeDetailDto resumePaper(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.resumePaper(id);
    }

    /** 发起交卷：后台批改整卷 + 本卷总结 + 更新记忆/摘要，立刻返回 */
    @Operation(summary = "交卷", description = "后台批量批改+总结+记忆更新")
    @PostMapping("/api/papers/{id}/submit")
    public PracticeDetailDto submitPaper(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.submitPaper(id);
    }

    /** 重出本卷总结 / 记忆 / 摘要 / 阶段总结（不重新批改），用于总结生成失败时补救 */
    @Operation(summary = "生成卷总结", description = "重新生成本卷 AI 总结")
    @PostMapping("/api/papers/{id}/summary")
    public PracticeDetailDto regeneratePaperSummary(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.regeneratePaperSummary(id);
    }

    /** 删除练习（级联删除卷、题、作答） */
    @Operation(summary = "删除练习", description = "级联删除卷/题/作答/笔记/错题")
    @PostMapping("/api/practices/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePractice(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        practiceService.deletePractice(id);
    }

    /** 保存作答（纯落库，不调 AI，答题零等待） */
    @Operation(summary = "保存作答", description = "追加一次作答，不覆盖历史；看过参考答案的题允许空着交")
    @PostMapping("/api/questions/{id}/attempts")
    public AttemptDto saveAttempt(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id, @RequestBody SaveAttemptParam req) {
        return practiceService.saveAttempt(id, req.answer());
    }

    @Operation(summary = "任务事件流（SSE）", description = "出题/批改的进度与完成推送，免轮询；断线/刷新自动重连，空闲零开销")
    @GetMapping("/api/practices/{id}/events")
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter taskEvents(@PathVariable Long id) {
        practiceService.intensityOf(id);   // 归属校验（越权直接 404）
        return taskEventService.subscribe(id);
    }

    /** 知识点清单 + 掌握度 + 记忆点（前端画进度/趋势与背诵用） */
    @Operation(summary = "知识点掌握度", description = "清单/统计/记忆点")
    @GetMapping("/api/practices/{id}/knowledge")
    public java.util.Map<String, Object> knowledgeService(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("stats", knowledgeService.stats(id, practiceService.intensityOf(id)));
        m.put("points", knowledgeService.dtoList(id));
        // 点名 → 可背诵句子。以前只拼进点评文本、看完就散，现在能按点复习
        m.put("memoryPoints", memoryPointService.byPointName(id));
        return m;
    }

    /* ---------- 错题重做 ---------- */

    /**
     * 针对待订正的错题出一张卷。
     * 选点范围锁定在这一刻的错题上，其余与普通卷一致（可答题、批改、回写掌握度）。
     */
    @Operation(summary = "错题出卷", description = "对待订正的错题出一张卷")
    @PostMapping("/api/practices/{id}/mistakes/retry")
    public PracticeDetailDto retryMistakes(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.startMistakeRetry(id);
    }

    /* ---------- 期末综合考 ---------- */

    /** 期末考解锁状态：看覆盖率不看掌握度，掌握度只提示不拦截 */
    @Operation(summary = "期末考资格", description = "解锁条件与做卷情况")
    @GetMapping("/api/practices/{id}/final-exam/gate")
    public FinalExamGateDto finalExamGate(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.finalExamGate(id);
    }

    /** 申请一次期末综合考（可多次），后台出卷，返回后靠轮询进度 */
    @Operation(summary = "发起期末考", description = "综合考察全部知识点")
    @PostMapping("/api/practices/{id}/final-exam")
    public PracticeDetailDto startFinalExam(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.startFinalExam(id);
    }

    /** 历次期末考成绩单，最新的在前 */
    @Operation(summary = "期末考历史", description = "历次成绩单")
    @GetMapping("/api/practices/{id}/final-exams")
    public java.util.List<FinalExamDto> finalExams(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.finalExams(id);
    }

    /** 查看参考答案：打标记，本题交卷时按规则计 0 分（前端必须先弹确认框） */
    @Operation(summary = "查看参考答案", description = "看了本题按 0 分计；期末考不可看")
    @PostMapping("/api/questions/{id}/reveal")
    public QuestionDto reveal(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return practiceService.reveal(id);
    }

    /* ---------- 练习记事本（per-practice，一整块可编辑文本） ---------- */

    /** 整篇覆盖（前端编辑器保存）。空文本 = 清空 */
    @Operation(summary = "记事本整篇保存", description = "整篇覆盖")
    @PostMapping("/api/practices/{id}/notes/save")
    public java.util.Map<String, Object> setNotes(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id,
                                                  @RequestBody NoteTextParam req) {
        noteService.setText(id, req.text());
        return java.util.Map.of("text", noteService.text(id), "updatedAt", noteService.updatedAt(id));
    }

    /** 末尾换行追加（答题时点「记笔记」）。不掺题号 */
    @Operation(summary = "记事本追加", description = "末尾换行追加一段")
    @PostMapping("/api/practices/{id}/notes")
    public java.util.Map<String, Object> appendNote(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id,
                                                    @RequestBody NoteTextParam req) {
        noteService.append(id, req.text());
        return java.util.Map.of("text", noteService.text(id), "updatedAt", noteService.updatedAt(id));
    }

    /* ---------- 错题本（per-practice，一题一条） ---------- */

    @Operation(summary = "错题列表", description = "复盘页错题 tab/抽屉用")
    @GetMapping("/api/practices/{id}/mistakes")
    public java.util.List<MistakeDto> mistakes(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        practiceService.intensityOf(id);   // 归属校验（越权直接 404）
        return mistakeService.dtoList(id);
    }

    /** 手动收录 / 重新打开已订正的错题 */
    @Operation(summary = "手动收错题", description = "收进错题本（已收录则重新打开）")
    @PostMapping("/api/practices/{id}/mistakes")
    @ResponseStatus(HttpStatus.CREATED)
    public MistakeDto createMistake(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id, @RequestBody CreateMistakeParam req) {
        return mistakeService.addManual(id, req.questionId() == null ? null : Long.valueOf(req.questionId()));
    }

    @Operation(summary = "错题状态", description = "open=待订正 / resolved=已订正")
    @PostMapping("/api/practices/{id}/mistakes/{mistakeId}/status")
    public MistakeDto updateMistake(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id, @Parameter(description = "错题 ID") @PathVariable Long mistakeId,
                                         @RequestBody UpdateMistakeParam req) {
        return mistakeService.setStatus(id, mistakeId, req.status());
    }

    @Operation(summary = "移出错题本", description = "删除这条错题记录")
    @PostMapping("/api/practices/{id}/mistakes/{mistakeId}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMistake(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id, @Parameter(description = "错题 ID") @PathVariable Long mistakeId) {
        mistakeService.delete(id, mistakeId);
    }
}
