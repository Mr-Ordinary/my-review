package com.review.vt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.review.vt.pojo.dto.*;

import com.review.vt.pojo.param.*;

import com.review.vt.service.ReferenceService;
import com.review.vt.common.crawler.WebFetcher;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 网上资料抓取：范围模式里提到的 URL 会被真的读下来。
 * 这个接口让前端能在建练习前先探一下「这个链接能不能读到」，读不到就提醒用户粘正文。
 */
@Tag(name = "网上资料", description = "URL 探测与正文抓取")
@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final ReferenceService referenceService;

    public SourceController(ReferenceService referenceService) {
        this.referenceService = referenceService;
    }


    /** 探测一段文字里的链接（最多 3 个）：能不能抓到、正文有多长 */
    @Operation(summary = "链接探测", description = "探测一段文字里的链接能否抓到")
    @PostMapping("/probe")
    public List<SourceProbeDto> probe(@RequestBody ProbeParam req) {
        return referenceService.resolve(req.text() == null ? "" : req.text()).stream()
                .map(r -> new SourceProbeDto(r.url(), r.title(),
                        r.text() == null ? 0 : r.text().length(),
                        r.ok() ? null : r.error()))
                .toList();
    }

    /** 抓单个链接 */
    @Operation(summary = "抓取正文", description = "抓 URL 正文（jsoup 抽取）")
    @PostMapping("/fetch")
    public SourceProbeDto fetch(@RequestBody ProbeParam req) {
        WebFetcher.FetchResult r = referenceService.resolveOne(req.text() == null ? "" : req.text().trim());
        return new SourceProbeDto(r.url(), r.title(),
                r.text() == null ? 0 : r.text().length(), r.ok() ? null : r.error());
    }
}
