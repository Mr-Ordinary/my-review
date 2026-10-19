package com.review.vt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统设置（本地配置）：缺省值来自 application.yml，可在系统设置页修改，
 * 保存到 data/settings.json（个人使用，不走数据库）。
 */
@Service
public class SettingsService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path settingsFile;

    private String apiBase;
    private String apiKey;
    private String model;
    private double temperature;

    public SettingsService(@Value("${myreview.settings-file}") String settingsFile,
                           @Value("${myreview.llm.api-base}") String apiBase,
                           @Value("${myreview.llm.api-key}") String apiKey,
                           @Value("${myreview.llm.model}") String model,
                           @Value("${myreview.llm.temperature}") double temperature) {
        this.settingsFile = Path.of(settingsFile);
        this.apiBase = apiBase;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
    }

    @PostConstruct
    void load() {
        if (!Files.exists(settingsFile)) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> saved = mapper.readValue(settingsFile.toFile(), Map.class);
            if (saved.get("apiBase") instanceof String s && !s.isBlank()) apiBase = s;
            if (saved.get("apiKey") instanceof String s && !s.isBlank()) apiKey = s;
            if (saved.get("model") instanceof String s && !s.isBlank()) model = s;
            if (saved.get("temperature") instanceof Number n) temperature = n.doubleValue();
        } catch (IOException e) {
            System.err.println("[myreview] 读取设置文件失败：" + e.getMessage());
        }
    }

    public synchronized Map<String, Object> get() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("apiBase", apiBase);
        map.put("apiKey", apiKey);
        map.put("model", model);
        map.put("temperature", temperature);
        return map;
    }

    public synchronized Map<String, Object> update(Map<String, Object> patch) {
        if (patch.get("apiBase") instanceof String s && !s.isBlank()) apiBase = s.trim();
        if (patch.get("apiKey") instanceof String s) apiKey = s.trim();
        if (patch.get("model") instanceof String s && !s.isBlank()) model = s.trim();
        if (patch.get("temperature") instanceof Number n) temperature = n.doubleValue();
        else if (patch.get("temperature") instanceof String s && !s.isBlank()) {
            try { temperature = Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        try {
            Files.createDirectories(settingsFile.toAbsolutePath().getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile.toFile(), get());
        } catch (IOException e) {
            throw new IllegalStateException("保存设置文件失败：" + e.getMessage(), e);
        }
        return get();
    }
}
