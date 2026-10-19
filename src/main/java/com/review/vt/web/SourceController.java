package com.review.vt.web;

import com.review.vt.service.ReferenceService;
import com.review.vt.service.WebFetcher;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 网上资料抓取：范围模式里提到的 URL 会被真的读下来。
 * 这个接口让前端能在建练习前先探一下「这个链接能不能读到」，读不到就提醒用户粘正文。
 */
@RestController
@RequestMapping("/api/sources")
public class SourceController {

    public record ProbeRequest(String text) {}

    public record SourceProbeDto(String url, String title, Integer textLength, String error) {}

    private final ReferenceService references;

    public SourceController(ReferenceService references) {
        this.references = references;
    }

    /** 探测一段文字里的链接（最多 3 个）：能不能抓到、正文有多长 */
    @PostMapping("/probe")
    public List<SourceProbeDto> probe(@RequestBody ProbeRequest req) {
        return references.resolve(req.text() == null ? "" : req.text()).stream()
                .map(r -> new SourceProbeDto(r.url(), r.title(),
                        r.text() == null ? 0 : r.text().length(),
                        r.ok() ? null : r.error()))
                .toList();
    }

    /** 抓单个链接 */
    @PostMapping("/fetch")
    public SourceProbeDto fetch(@RequestBody ProbeRequest req) {
        WebFetcher.FetchResult r = references.resolveOne(req.text() == null ? "" : req.text().trim());
        return new SourceProbeDto(r.url(), r.title(),
                r.text() == null ? 0 : r.text().length(), r.ok() ? null : r.error());
    }
}
