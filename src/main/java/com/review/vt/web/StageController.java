package com.review.vt.web;

import com.review.vt.service.StageService;
import com.review.vt.web.Dtos.PracticeDto;
import com.review.vt.web.Dtos.StageDetailDto;
import com.review.vt.web.Dtos.StageDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 阶段接口（只是分组名字；阶段知识档案与全复盘练习从这里进）。 */
@RestController
@RequestMapping("/api/stages")
public class StageController {

    public record CreateStageRequest(String name) {}

    private final StageService stageService;

    public StageController(StageService stageService) {
        this.stageService = stageService;
    }

    @GetMapping
    public List<StageDto> list() {
        return stageService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StageDto create(@RequestBody CreateStageRequest req) {
        return stageService.create(req.name());
    }

    @GetMapping("/{id}")
    public StageDetailDto detail(@PathVariable String id) {
        return stageService.detail(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        stageService.delete(id);
    }

    /** 重新生成阶段总结（AI 反馈）。交卷后会自动重出，这里给手动补跑用。 */
    @PostMapping("/{id}/summary")
    public StageDetailDto regenerateSummary(@PathVariable String id) {
        return stageService.regenerateSummary(id);
    }

    /**
     * 发起全复习练习：系统自动创建一个针对整个阶段的复习练习。
     * 范围取自阶段总结的 reviewScope；没有阶段总结会拒绝（前端应把按钮禁用）。
     */
    @PostMapping("/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeDto createReviewPractice(@PathVariable String id,
                                                     @RequestBody(required = false) CreateReviewRequest req) {
        return stageService.createReviewPractice(id,
                req == null ? null : req.level(),
                req == null ? null : req.intensity());
    }

    /** @param intensity 复习强度 quick/standard/deep，默认 standard */
    public record CreateReviewRequest(String level, String intensity) {}
}
