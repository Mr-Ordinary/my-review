package com.review.vt.web;

import com.review.vt.service.ReviewDocService;
import com.review.vt.web.Dtos.ReviewDocDto;
import org.springframework.web.bind.annotation.*;

/**
 * 考前复习提纲接口：按练习生成/读取/重生成。
 * 生成是后台任务，前端轮询 status 看进度；ready 后一次性取全部学习卡。
 */
@RestController
public class ReviewDocController {

    private final ReviewDocService reviews;

    public ReviewDocController(ReviewDocService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/api/practices/{id}/review-doc")
    public ReviewDocDto get(@PathVariable String id) {
        return reviews.get(id);
    }

    /** 生成 / 续跑（已就绪直接返回，不重复生成） */
    @PostMapping("/api/practices/{id}/review-doc")
    public ReviewDocDto start(@PathVariable String id) {
        return reviews.start(id);
    }

    /** 清空并重新生成（用户对内容不满意时用） */
    @PostMapping("/api/practices/{id}/review-doc/regenerate")
    public ReviewDocDto regenerate(@PathVariable String id) {
        return reviews.regenerate(id);
    }
}
