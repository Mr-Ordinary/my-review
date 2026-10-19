package com.review.vt.web;

import com.review.vt.service.StageService;
import com.review.vt.web.Dtos.StageDetailDto;
import com.review.vt.web.Dtos.StageDto;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 阶段（工作区）接口。 */
@RestController
@RequestMapping("/api/stages")
public class StageController {

    public record CreateStageRequest(String name, String goal) {}

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
        return stageService.create(req.name(), req.goal());
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

    /** 生成阶段总结（调 AI，前端等待） */
    @PostMapping("/{id}/summary")
    public StageDetailDto summary(@PathVariable String id) {
        return stageService.generateSummary(id);
    }
}
