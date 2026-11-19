package com.review.vt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.review.vt.pojo.param.*;

import com.review.vt.service.StageService;
import com.review.vt.pojo.dto.PracticeDto;
import com.review.vt.pojo.dto.StageDetailDto;
import com.review.vt.pojo.dto.StageDto;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 阶段接口（只是分组名字；阶段知识档案与全复盘练习从这里进）。 */
@Tag(name = "阶段与练习", description = "阶段管理、练习创建、全复习")
@RestController
@RequestMapping("/api/stages")
@AllArgsConstructor
public class StageController {

    /** 阶段Service */
    private final StageService stageService;

    @Operation(summary = "阶段列表", description = "含各阶段统计（练习/卷/题/均分/刷题）")
    @GetMapping
    public List<StageDto> list() {
        return stageService.list();
    }

    @Operation(summary = "新建阶段", description = "阶段只是分组名")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StageDto create(@RequestBody CreateStageParam req) {
        return stageService.create(req.name());
    }

    @Operation(summary = "阶段详情", description = "阶段下的练习列表")
    @GetMapping("/{id}")
    public StageDetailDto detail(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return stageService.detail(id);
    }

    @Operation(summary = "删除阶段", description = "级联删除练习及其全部数据")
    @PostMapping("/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        stageService.delete(id);
    }

    /** 重新生成阶段总结（AI 反馈）。交卷后会自动重出，这里给手动补跑用。 */
    @Operation(summary = "生成阶段总结", description = "AI 总结，含全复习范围 reviewScope")
    @PostMapping("/{id}/summary")
    public StageDetailDto regenerateSummary(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return stageService.regenerateSummary(id);
    }

    /**
     * 发起全复习练习：系统自动创建一个针对整个阶段的复习练习。
     * 范围取自阶段总结的 reviewScope；没有阶段总结会拒绝（前端应把按钮禁用）。
     */
    @Operation(summary = "发起全复习", description = "需先有阶段总结；范围取 reviewScope")
    @PostMapping("/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    public PracticeDto createReviewPractice(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id,
                                                     @RequestBody(required = false) CreateReviewParam req) {
        return stageService.createReviewPractice(id,
                req == null ? null : req.level(),
                req == null ? null : req.intensity());
    }

    /** @param intensity 复习强度 quick/standard/deep，默认 standard */
}
