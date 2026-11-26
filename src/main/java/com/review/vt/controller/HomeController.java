package com.review.vt.controller;

import com.review.vt.pojo.dto.HomeOverviewDto;
import com.review.vt.service.HomeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作台首页接口：KPI 行 + 阶段卡 + 最近的卷，一次批量取齐。
 * 统计现算（永不缓存），读接口无副作用。
 */
@Tag(name = "工作台", description = "首页总览")
@RestController
@RequestMapping("/api/home")
@AllArgsConstructor
public class HomeController {

    private final HomeService homeService;

    @Operation(summary = "首页总览", description = "KPI / 阶段卡 / 最近的卷（统计现算）")
    @GetMapping("/overview")
    public HomeOverviewDto overview() {
        return homeService.overview();
    }
}
