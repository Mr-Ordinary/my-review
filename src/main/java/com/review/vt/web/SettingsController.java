package com.review.vt.web;

import com.review.vt.service.SettingsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 系统设置（本地配置，存 data/settings.json）。 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public Map<String, Object> get() {
        return settings.get();
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        return settings.update(body);
    }
}
