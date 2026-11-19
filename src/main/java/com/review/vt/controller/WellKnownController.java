package com.review.vt.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chrome DevTools 会周期性探测这个路径（用于本地 workspace 映射）。
 * 显式给 404，避免落到静态资源处理器后抛 NoResourceFoundException 刷屏。
 */
@Tag(name = "其他", description = "站点元信息")
@RestController
public class WellKnownController {

    @Operation(summary = "DevTools 配置", description = "Chrome 自动发现用，无业务含义")
    @GetMapping("/.well-known/appspecific/com.chrome.devtools.json")
    public ResponseEntity<Void> chromeDevtools() {
        return ResponseEntity.notFound().build();
    }
}
