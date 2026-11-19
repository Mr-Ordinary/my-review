package com.review.vt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.review.vt.service.ReviewDocService;
import com.review.vt.pojo.dto.ReviewDocDto;
import org.springframework.web.bind.annotation.*;

/**
 * 考前复习提纲接口：按练习生成/读取/重生成。
 * 生成是后台任务，前端轮询 status 看进度；ready 后一次性取全部学习卡。
 */
@Tag(name = "复习提纲", description = "考前提纲的生成与学习卡")
@RestController
public class ReviewDocController {

    private final ReviewDocService reviewDocService;

    public ReviewDocController(ReviewDocService reviewDocService) {
        this.reviewDocService = reviewDocService;
    }

    @Operation(summary = "复习提纲内容", description = "按知识点一行的学习卡")
    @GetMapping("/api/practices/{id}/review-doc")
    public ReviewDocDto get(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return reviewDocService.get(id);
    }

    /** 生成 / 续跑（已就绪直接返回，不重复生成） */
    @Operation(summary = "生成复习提纲", description = "学考同源：按练习知识点出提纲")
    @PostMapping("/api/practices/{id}/review-doc")
    public ReviewDocDto start(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return reviewDocService.start(id);
    }

    /** 清空并重新生成（用户对内容不满意时用） */
    @Operation(summary = "重新生成提纲", description = "覆盖现有内容，已读进度保留")
    @PostMapping("/api/practices/{id}/review-doc/regenerate")
    public ReviewDocDto regenerate(@Parameter(description = "资源 ID（见路径）") @PathVariable Long id) {
        return reviewDocService.regenerate(id);
    }
}
