package com.review.vt.web;

import com.review.vt.service.PracticeService;
import com.review.vt.web.Dtos.PracticeDetailDto;
import com.review.vt.web.Dtos.PracticeDto;
import com.review.vt.web.Dtos.QuestionDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 背八股练习接口（出题/作答/看答案/总结）。 */
@RestController
public class PracticeController {

    public record CreatePracticeRequest(String topic) {}
    public record SubmitAttemptRequest(String answer) {}

    private final PracticeService practiceService;

    public PracticeController(PracticeService practiceService) {
        this.practiceService = practiceService;
    }

    @PostMapping("/api/stages/{stageId}/practices")
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeDto create(@PathVariable String stageId, @RequestBody CreatePracticeRequest req) {
        return practiceService.create(stageId, req.topic());
    }

    @GetMapping("/api/practices/{id}")
    public PracticeDetailDto detail(@PathVariable String id) {
        return practiceService.detail(id);
    }

    /** 出题（调 AI，一轮只出一道） */
    @PostMapping("/api/practices/{id}/questions")
    public QuestionDto nextQuestion(@PathVariable String id) {
        return practiceService.nextQuestion(id);
    }

    /** 结束练习 → 练习总结（调 AI） */
    @PostMapping("/api/practices/{id}/complete")
    public PracticeDto complete(@PathVariable String id) {
        return practiceService.complete(id);
    }

    /** 提交作答（原样保存）并生成点评（调 AI） */
    @PostMapping("/api/questions/{id}/attempts")
    public QuestionDto submitAttempt(@PathVariable String id, @RequestBody SubmitAttemptRequest req) {
        return practiceService.submitAttempt(id, req.answer());
    }

    /** 看答案（调 AI；不创建作答、不评分） */
    @PostMapping("/api/questions/{id}/reveal")
    public QuestionDto reveal(@PathVariable String id) {
        return practiceService.reveal(id);
    }
}
