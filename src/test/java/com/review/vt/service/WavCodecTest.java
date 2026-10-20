package com.review.vt.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WAV 解析：浏览器传上来的录音靠它转成 sherpa 要的 float 采样。
 * 纯 Java、无依赖，所以可以完整单测——这也是不直接用 native 解析的原因。
 */
class WavCodecTest {

    /** 造一段 PCM16 单声道 WAV */
    private static byte[] pcm16Mono(int sampleRate, double[] values) {
        int n = values.length;
        ByteBuffer buf = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + n * 2);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);           // PCM
        buf.putShort((short) 1);           // mono
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * 2);
        buf.putShort((short) 2);
        buf.putShort((short) 16);
        buf.put("data".getBytes());
        buf.putInt(n * 2);
        for (double v : values) {
            double s = Math.max(-1, Math.min(1, v));
            buf.putShort((short) (s < 0 ? s * 0x8000 : s * 0x7fff));
        }
        return buf.array();
    }

    /** 造一段 16bit 双声道 WAV（验证多声道会降成单声道） */
    private static byte[] pcm16Stereo(int sampleRate, double[] left, double[] right) {
        int n = left.length;
        ByteBuffer buf = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + n * 4);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 2);           // stereo
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * 4);
        buf.putShort((short) 4);
        buf.putShort((short) 16);
        buf.put("data".getBytes());
        buf.putInt(n * 4);
        for (int i = 0; i < n; i++) {
            buf.putShort((short) (left[i] * 0x7fff));
            buf.putShort((short) (right[i] * 0x7fff));
        }
        return buf.array();
    }

    @Test
    @DisplayName("解析 PCM16 单声道，采样率与样本值正确")
    void readPcm16Mono() {
        byte[] wav = pcm16Mono(16000, new double[] {0, 0.5, -0.5, 1, -1});
        WavCodec.PcmAudio a = WavCodec.read(wav);
        assertEquals(16000, a.sampleRate());
        assertEquals(5, a.samples().length);
        assertEquals(0, a.samples()[0], 0.01);
        assertEquals(0.5, a.samples()[1], 0.01);
        assertEquals(-0.5, a.samples()[2], 0.01);
        assertEquals(1.0, a.samples()[3], 0.01);
        assertEquals(-1.0, a.samples()[4], 0.01);
    }

    @Test
    @DisplayName("双声道降成单声道（取两声道平均）")
    void readStereoAveraged() {
        byte[] wav = pcm16Stereo(16000, new double[] {1, 0}, new double[] {0, 1});
        WavCodec.PcmAudio a = WavCodec.read(wav);
        assertEquals(16000, a.sampleRate());
        assertEquals(2, a.samples().length);
        assertEquals(0.5, a.samples()[0], 0.02);
        assertEquals(0.5, a.samples()[1], 0.02);
    }

    @Test
    @DisplayName("样本值归一化到 [-1, 1]")
    void samplesAreNormalized() {
        byte[] wav = pcm16Mono(44100, new double[] {-1, -0.5, 0, 0.5, 1});
        WavCodec.PcmAudio a = WavCodec.read(wav);
        for (float v : a.samples()) {
            assertTrue(v >= -1.001f && v <= 1.001f, "超出 [-1,1]：" + v);
        }
    }

    @Test
    @DisplayName("不是 WAV 的内容要报明确错误")
    void rejectNonWav() {
        assertThrows(IllegalArgumentException.class,
                () -> WavCodec.read("这不是音频这是一段纯文本用来测试异常分支".getBytes()));
    }

    @Test
    @DisplayName("太短的字节流不算 WAV")
    void rejectTooShort() {
        assertThrows(IllegalArgumentException.class, () -> WavCodec.read(new byte[10]));
        assertThrows(IllegalArgumentException.class, () -> WavCodec.read(null));
    }

    @Test
    @DisplayName("缺少 data 块要报错")
    void rejectMissingDataChunk() {
        ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(12);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        assertThrows(IllegalArgumentException.class, () -> WavCodec.read(buf.array()));
    }

    @Test
    @DisplayName("空的 data 块返回零个样本，不算错误")
    void emptyDataIsOk() {
        ByteBuffer buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putInt(16000);
        buf.putInt(32000);
        buf.putShort((short) 2);
        buf.putShort((short) 16);
        buf.put("data".getBytes());
        buf.putInt(0);
        WavCodec.PcmAudio a = WavCodec.read(buf.array());
        assertEquals(0, a.samples().length);
        assertEquals(16000, a.sampleRate());
    }
}
