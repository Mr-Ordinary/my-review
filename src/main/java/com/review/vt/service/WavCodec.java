package com.review.vt.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 最小可用的 WAV 解析（RIFF / WAVE）。
 *
 * 浏览器录完的音频会以 16kHz 单声道 PCM16 WAV 传上来，这里把它解成
 * 归一化的 float[] + 采样率，再交给 sherpa-onnx 的 OfflineStream。
 *
 * 只做纯 Java 解析、不碰 native，方便单测；也不依赖第三方库。
 * 支持 PCM 8/16/24/32 位整数与 32 位浮点，单声道或多声道（多声道按声道平均降成单声道）。
 */
public final class WavCodec {

    /** 解析结果：样本归一化到 [-1, 1]，单声道 */
    public record PcmAudio(float[] samples, int sampleRate) {}

    private WavCodec() {}

    /**
     * 解析 WAV 字节流。
     *
     * @throws IllegalArgumentException 不是合法的 WAV（结构不对 / 没有 data 块 / 位深不支持）
     */
    public static PcmAudio read(byte[] bytes) {
        if (bytes == null || bytes.length < 44) {
            throw new IllegalArgumentException("音频太短，不是有效的 WAV");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (!tagEquals(bytes, 0, "RIFF") || !tagEquals(bytes, 8, "WAVE")) {
            throw new IllegalArgumentException("不是 WAV 格式（缺少 RIFF/WAVE 标识）");
        }

        int audioFormat = -1;
        int channels = -1;
        int sampleRate = -1;
        int bitsPerSample = -1;
        byte[] data = null;

        int pos = 12;
        while (pos + 8 <= bytes.length) {
            buf.position(pos);
            String id = new String(bytes, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int size = buf.getInt(pos + 4);
            int body = pos + 8;
            if (size < 0 || body + size > bytes.length) break; // 尾部被截断，按已有内容解析

            if ("fmt ".equals(id) && size >= 16) {
                buf.position(body);
                audioFormat = buf.getShort() & 0xffff;
                channels = buf.getShort() & 0xffff;
                sampleRate = buf.getInt();
                buf.getInt(); // byteRate
                buf.getShort(); // blockAlign
                bitsPerSample = buf.getShort() & 0xffff;
            } else if ("data".equals(id)) {
                int len = Math.min(size, bytes.length - body);
                data = new byte[len];
                System.arraycopy(bytes, body, data, 0, len);
            }
            pos = body + size + (size % 2); // chunk 按偶数字节对齐
        }

        if (data == null) throw new IllegalArgumentException("WAV 里没有 data 块");
        if (channels <= 0) throw new IllegalArgumentException("WAV 里没有 fmt 块");
        if (sampleRate <= 0) throw new IllegalArgumentException("WAV 采样率无效");
        // 1 = PCM，3 = IEEE float，0xfffe = 扩展格式（这里按 PCM 处理）
        if (audioFormat != 1 && audioFormat != 3 && audioFormat != 0xfffe) {
            throw new IllegalArgumentException("不支持的 WAV 编码（audioFormat=" + audioFormat + "），请传 PCM 或 IEEE float");
        }

        return new PcmAudio(toMonoFloats(data, audioFormat, channels, bitsPerSample), sampleRate);
    }

    /** 解成单声道 float；多声道取平均 */
    private static float[] toMonoFloats(byte[] data, int audioFormat, int channels, int bits) {
        int bytesPerSample = Math.max(1, bits / 8);
        int frameBytes = bytesPerSample * channels;
        if (frameBytes <= 0 || data.length < frameBytes) return new float[0];
        int frames = data.length / frameBytes;
        float[] out = new float[frames];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        for (int f = 0; f < frames; f++) {
            double acc = 0;
            int base = f * frameBytes;
            for (int c = 0; c < channels; c++) {
                buf.position(base + c * bytesPerSample);
                acc += sample(buf, audioFormat, bits);
            }
            out[f] = (float) (acc / channels);
        }
        return out;
    }

    /** 单个采样点 → [-1, 1] */
    private static double sample(ByteBuffer buf, int audioFormat, int bits) {
        if (audioFormat == 3 && bits == 32) return buf.getFloat();
        if (audioFormat == 3 && bits == 64) return buf.getDouble();
        return switch (bits) {
            case 8 -> (buf.get() - 128) / 128.0;                    // 8 位是无符号
            case 16 -> buf.getShort() / 32768.0;
            case 24 -> {
                int b0 = buf.get() & 0xff;
                int b1 = buf.get() & 0xff;
                int b2 = buf.get();
                yield ((b2 << 16) | (b1 << 8) | b0) / 8388608.0;
            }
            case 32 -> buf.getInt() / 2147483648.0;
            default -> throw new IllegalArgumentException("不支持的位深：" + bits);
        };
    }

    private static boolean tagEquals(byte[] bytes, int off, String tag) {
        if (off + tag.length() > bytes.length) return false;
        for (int i = 0; i < tag.length(); i++) {
            if (bytes[off + i] != (byte) tag.charAt(i)) return false;
        }
        return true;
    }
}
