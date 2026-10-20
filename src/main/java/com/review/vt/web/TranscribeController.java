package com.review.vt.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.review.vt.service.LlmService;
import com.review.vt.service.PromptTemplates;
import com.review.vt.service.SpeechTranscriber;
import com.review.vt.service.WavCodec;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语音转文字：浏览器录一段音传上来，返回文字。
 *
 * 只负责「收文件 → 解析 → 调转写 → 回 JSON」，模型缺失/平台不支持时
 * 都给明确提示，而不是抛 500 让用户摸不着头脑。
 */
@RestController
@RequestMapping("/api")
public class TranscribeController {

    /** 单次上传上限：3 分钟 16kHz 单声道 PCM16 约 5.7MB，16MB 足够 */
    private static final long MAX_UPLOAD_BYTES = 16L * 1024 * 1024;

    private final SpeechTranscriber transcriber;
    private final LlmService llm;

    public TranscribeController(SpeechTranscriber transcriber, LlmService llm) {
        this.transcriber = transcriber;
        this.llm = llm;
    }

    /** 前端用来判断要不要展示麦克风按钮、以及该怎么提示用户 */
    @GetMapping("/speech/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engine", "sherpa-onnx + paraformer");
        m.put("ready", transcriber.isReady());
        m.put("reason", transcriber.unavailableReason());
        return m;
    }

    /** 上传 WAV，返回识别文本。hotwords 传当前题目的考察点，用来救回 equals / volatile 这类术语 */
    @PostMapping("/transcribe")
    public Map<String, Object> transcribe(@RequestParam("audio") MultipartFile audio,
                                          @RequestParam(value = "hotwords", required = false) String hotwords) {
        if (audio == null || audio.isEmpty()) {
            throw new IllegalArgumentException("没有收到音频内容");
        }
        if (audio.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("音频太大（超过 16MB），请缩短录音时长");
        }
        if (!transcriber.isReady()) {
            throw new IllegalStateException("语音识别不可用：" + transcriber.unavailableReason());
        }

        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("读取音频失败：" + e.getMessage());
        }

        WavCodec.PcmAudio pcm;
        try {
            pcm = WavCodec.read(bytes);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage());
        }

        String text = transcriber.transcribe(pcm.samples(), pcm.sampleRate(), parseHotwords(hotwords)).trim();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("durationMs", pcm.sampleRate() <= 0 ? 0 : pcm.samples().length * 1000L / pcm.sampleRate());
        return m;
    }

    /** 逗号 / 顿号分隔的热词串 → 列表 */
    private static List<String> parseHotwords(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw.split("[、,，;；\\s]+")) {
            String v = s.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /**
     * 纠正识别结果里的技术术语（equals → ecos 这类声学误识）。
     *
     * 单独一个接口是为了不阻塞录音：前端先把原文填进作答框，再异步调这里，
     * 拿到结果后原地替换——用户立刻看到字，几秒后术语自动变准。
     */
    @PostMapping("/transcribe/polish")
    public Map<String, Object> polish(@RequestBody PolishRequest req) {
        if (req == null || req.text() == null || req.text().isBlank()) {
            throw new IllegalArgumentException("没有要润色的内容");
        }
        String topic = req.topic() == null ? "" : req.topic();
        List<String> hotwords = parseHotwords(req.hotwords());
        JsonNode result = llm.chatJson(PromptTemplates.INTERVIEW_SYSTEM,
                PromptTemplates.polishTranscriptUser(topic, hotwords, req.text()), 4096, false);

        // 模型偶尔会把提示词原文一起吐回来，这种结果宁可不要
        String out = result.path("text").asText("").trim();
        if (out.startsWith("\"") && out.endsWith("\"") && out.length() > 1) {
            out = out.substring(1, out.length() - 1).trim();
        }
        String original = req.text().trim();
        boolean looksLikeEcho = out.isBlank()
                || out.contains("待纠正原文") || out.contains("严格要求")
                || out.contains("练习主题：") || out.contains("text 字段里")
                || out.length() > original.length() + Math.max(40, original.length() / 2);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", looksLikeEcho ? original : out);
        m.put("polished", !looksLikeEcho && !out.equals(original));
        return m;
    }

    public record PolishRequest(String text, String topic, String hotwords) {}
}
