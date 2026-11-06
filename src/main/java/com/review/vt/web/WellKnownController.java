package com.review.vt.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Chrome DevTools 会周期性探测这个路径（用于本地 workspace 映射）。
 * 显式给 404，避免落到静态资源处理器后抛 NoResourceFoundException 刷屏。
 */
@RestController
public class WellKnownController {

    @GetMapping("/.well-known/appspecific/com.chrome.devtools.json")
    public ResponseEntity<Void> chromeDevtools() {
        return ResponseEntity.notFound().build();
    }
}
