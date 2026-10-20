package com.review.vt.service;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * sherpa-onnx + Paraformer 的中文语音识别实现（Apache 2.0）。
 *
 * 模型期望放在 {@code speechModelDir}（默认 {@code data/models/paraformer-zh}）：
 * <pre>
 *   model.int8.onnx   （或 model.onnx）
 *   tokens.txt
 * </pre>
 *
 * 三个要点：
 * 1. **懒加载**——native 库与模型只在第一次真正转写时才加载，缺模型不影响应用启动；
 * 2. **线程安全**——sherpa 的 recognizer 不保证并发 decode 安全，这里整体加锁；
 * 3. **术语纠正**——paraformer 的英文词表很有限，equals/volatile 这类术语极易被认成同音词。
 *    由于 paraformer **只支持 greedy_search**（不支持热词偏置），改成识别后做术语纠正，
 *    见 {@link TermFixer}。注意别把 decoding_method 改成 modified_beam_search：
 *    paraformer 不支持，native 层会直接 abort 掉整个进程。
 */
@Service
public class SherpaOnnxTranscriber implements SpeechTranscriber {

    /**
     * paraformer 只支持 greedy_search。传别的值 native 层会 abort 整个进程
     * （不是抛 Java 异常，是进程直接没了），所以写死。
     */
    private static final String DECODING_METHOD = "greedy_search";

    private final SettingsService settings;

    private volatile OfflineRecognizer recognizer;
    private volatile String initError;

    public SherpaOnnxTranscriber(SettingsService settings) {
        this.settings = settings;
    }

    /* ================= 就绪状态 ================= */

    @Override
    public boolean isReady() {
        return ensureRecognizer() != null;
    }

    @Override
    public String unavailableReason() {
        ensureRecognizer();
        return initError;
    }

    /* ================= 转写 ================= */

    @Override
    public String transcribe(float[] samples, int sampleRate, List<String> hotwords) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("音频内容为空");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("采样率无效：" + sampleRate);
        }
        OfflineRecognizer rec = ensureRecognizer();
        if (rec == null) {
            throw new IllegalStateException("语音识别不可用：" + initError);
        }

        // sherpa 的 recognizer 不保证并发安全，串行化掉
        synchronized (this) {
            OfflineStream stream = rec.createStream();
            try {
                stream.acceptWaveform(samples, sampleRate);
                rec.decode(stream);
                OfflineRecognizerResult result = rec.getResult(stream);
                return result == null ? "" : result.getText();
            } finally {
                try {
                    stream.release();
                } catch (Exception ignored) {
                    // 释放失败不影响结果
                }
            }
        }
    }

    /* ================= 初始化 ================= */

    private synchronized OfflineRecognizer ensureRecognizer() {
        if (recognizer != null) return recognizer;
        if (initError != null) return null;

        try {
            Path dir = Path.of(String.valueOf(settings.get().get("speechModelDir")));
            if (!Files.isDirectory(dir)) {
                initError = "模型目录不存在：" + dir.toAbsolutePath();
                return null;
            }
            Path model = modelPath(dir);
            Path tokens = tokensPath(dir);
            if (model == null || tokens == null) {
                initError = "模型目录里缺文件（需要 model.int8.onnx 与 tokens.txt）：" + dir.toAbsolutePath();
                return null;
            }

            FeatureConfig feature = FeatureConfig.builder()
                    .setSampleRate(16000)
                    .setFeatureDim(80)
                    .build();

            OfflineParaformerModelConfig paraformer = OfflineParaformerModelConfig.builder()
                    .setModel(model.toString())
                    .build();

            OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                    .setParaformer(paraformer)
                    .setTokens(tokens.toString())
                    .setNumThreads(2)
                    .setDebug(false)
                    .setProvider("cpu")
                    .build();

            OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                    .setFeatureConfig(feature)
                    .setOfflineModelConfig(modelConfig)
                    .setDecodingMethod(DECODING_METHOD)
                    .build();

            recognizer = new OfflineRecognizer(config);
            initError = null;
            System.out.println("[myreview] 语音识别已就绪（sherpa-onnx paraformer / " + DECODING_METHOD + "）："
                    + dir.toAbsolutePath());
            return recognizer;
        } catch (Throwable e) {
            // native 库缺失、模型损坏、平台不支持等都在这里兜住，不影响其他功能
            initError = "初始化失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            System.err.println("[myreview] " + initError);
            return null;
        }
    }

    private static Path modelPath(Path dir) {
        for (String n : new String[] {"model.int8.onnx", "model.onnx"}) {
            Path p = dir.resolve(n);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static Path tokensPath(Path dir) {
        Path p = dir.resolve("tokens.txt");
        return Files.isRegularFile(p) ? p : null;
    }
}
