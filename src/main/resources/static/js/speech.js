/* ============================================================
   Speech —— 浏览器端录音 + 转写
   录音 → 16kHz 单声道 PCM16 WAV → POST /api/transcribe → 文本
   用 AudioWorklet 采集；不支持时退回 ScriptProcessor。
   ============================================================ */
(function () {
  'use strict';

  const TARGET_RATE = 16000;   // paraformer 的输入采样率
  const MAX_SECONDS = 180;     // 单次录音上限，防止传个几十 MB 上去

  let ctx = null;
  let stream = null;
  let source = null;
  let node = null;             // worklet / scriptProcessor
  let chunks = [];             // Float32Array[]
  let startedAt = 0;
  let active = false;

  /** 混入 Float32 采样 */
  function collect(buf) {
    if (!active) return;
    chunks.push(new Float32Array(buf));
    const elapsed = (Date.now() - startedAt) / 1000;
    if (elapsed > MAX_SECONDS) {
      stop().catch(() => {});
      if (typeof window !== 'undefined' && window.UI && window.UI.toast) {
        window.UI.toast('录音已达 ' + MAX_SECONDS + ' 秒上限，已自动停止', 'warning');
      }
    }
  }

  /** AudioWorklet 优先；不支持就用 ScriptProcessor（兼容老浏览器） */
  async function attachSource() {
    source = ctx.createMediaStreamSource(stream);
    if (ctx.audioWorklet && ctx.audioWorklet.addModule) {
      try {
        const code = `class P extends AudioWorkletProcessor {
            process(inputs){ const b=inputs[0]&&inputs[0][0]; if(b) this.port.postMessage(b.slice(0)); return true; }
          }; registerProcessor('pcm-collector', P);`;
        const url = URL.createObjectURL(new Blob([code], { type: 'application/javascript' }));
        await ctx.audioWorklet.addModule(url);
        URL.revokeObjectURL(url);
        node = new AudioWorkletNode(ctx, 'pcm-collector', { numberOfInputs: 1, numberOfOutputs: 1, channelCount: 1 });
        node.port.onmessage = (e) => collect(e.data);
        source.connect(node);
        node.connect(ctx.destination);
        return;
      } catch (e) {
        /* 落到 ScriptProcessor */
      }
    }
    // ScriptProcessor 会被 Chrome 标记废弃，但无需额外文件、兼容性最好
    node = ctx.createScriptProcessor(4096, 1, 1);
    node.onaudioprocess = (e) => collect(e.inputBuffer.getChannelData(0));
    source.connect(node);
    node.connect(ctx.destination);
  }

  async function start() {
    if (active) throw new Error('录音已经在进行中');
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      throw new Error('当前浏览器不支持录音');
    }
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });
    ctx = new (window.AudioContext || window.webkitAudioContext)();
    if (ctx.state === 'suspended') await ctx.resume();
    chunks = [];
    active = true;
    startedAt = Date.now();
    await attachSource();
    return { sampleRate: ctx.sampleRate };
  }

  /** 停止录音并返回 WAV 字节 */
  async function stop() {
    if (!active) return null;
    active = false;
    try { if (node) node.disconnect(); } catch (e) {}
    try { if (source) source.disconnect(); } catch (e) {}
    if (stream) stream.getTracks().forEach((t) => t.stop());
    const rate = ctx ? ctx.sampleRate : TARGET_RATE;
    try { if (ctx) await ctx.close(); } catch (e) {}
    ctx = source = node = stream = null;

    const merged = merge(chunks);
    chunks = [];
    const pcm = downsample(merged, rate, TARGET_RATE);
    return { wav: encodeWav(pcm, TARGET_RATE), seconds: merged.length / (rate || TARGET_RATE) };
  }

  function merge(list) {
    let total = 0;
    for (const b of list) total += b.length;
    const out = new Float32Array(total);
    let off = 0;
    for (const b of list) { out.set(b, off); off += b.length; }
    return out;
  }

  /** 采样率转换（平均降采样）；16k 是 paraformer 的输入要求 */
  function downsample(buffer, fromRate, toRate) {
    if (!buffer || buffer.length === 0) return new Float32Array(0);
    if (!fromRate || fromRate === toRate) return buffer;
    const ratio = fromRate / toRate;
    const newLen = Math.round(buffer.length / ratio);
    const result = new Float32Array(newLen);
    for (let i = 0; i < newLen; i++) {
      const start = Math.round(i * ratio);
      const end = Math.min(Math.round((i + 1) * ratio), buffer.length);
      let sum = 0, count = 0;
      for (let j = start; j < end; j++) { sum += buffer[j]; count++; }
      result[i] = count > 0 ? sum / count : 0;
    }
    return result;
  }

  /** float [-1,1] → 16bit PCM WAV */
  function encodeWav(samples, sampleRate) {
    const n = samples.length;
    const buffer = new ArrayBuffer(44 + n * 2);
    const view = new DataView(buffer);
    const w = (off, s) => { for (let i = 0; i < s.length; i++) view.setUint8(off + i, s.charCodeAt(i)); };
    w(0, 'RIFF');
    view.setUint32(4, 36 + n * 2, true);
    w(8, 'WAVE');
    w(12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);   // PCM
    view.setUint16(22, 1, true);   // mono
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    w(36, 'data');
    view.setUint32(40, n * 2, true);
    let off = 44;
    for (let i = 0; i < n; i++, off += 2) {
      let s = Math.max(-1, Math.min(1, samples[i]));
      view.setInt16(off, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    }
    return new Uint8Array(buffer);
  }

  /** 把浏览器的原始异常翻译成用户能照做的提示 */
  function explainError(e) {
    const name = (e && e.name) || '';
    const insecure = typeof window !== 'undefined' && window.isSecureContext === false;
    switch (name) {
      case 'NotAllowedError':
      case 'PermissionDeniedError':
      case 'SecurityError':
        return '麦克风权限被拒绝。点地址栏左侧的锁形图标 →「网站设置」→ 麦克风改为「允许」，然后刷新页面。'
          + (insecure || insecureHost() ? ' 另外：浏览器只在 localhost 或 HTTPS 下才允许用麦克风，你现在是 ' + location.host + '，需要用 localhost 打开。' : '');
      case 'NotFoundError':
      case 'DevicesNotFoundError':
      case 'OverconstrainedError':
        return '没有检测到麦克风。请检查麦克风是否插好，并在「Windows 设置 → 隐私 → 麦克风」里打开访问权限。';
      case 'NotReadableError':
      case 'TrackStartError':
        return '麦克风被占用或无法读取。请关闭其他正在用麦克风的软件（会议/录音/直播等）后重试。';
      default:
        return e && e.message ? e.message : String(e);
    }
  }

  /** 浏览器只在安全上下文（localhost / HTTPS）下开放麦克风 */
  function insecureHost() {
    const h = location.hostname;
    return h !== 'localhost' && h !== '127.0.0.1' && h !== '[::1]' && location.protocol !== 'https:';
  }

  /** 一次性诊断：帮用户定位是哪一层卡住了 */
  async function diagnose() {
    const out = {
      host: location.host,
      protocol: location.protocol,
      secureContext: typeof window.isSecureContext === 'boolean' ? window.isSecureContext : !insecureHost(),
      hasMediaDevices: !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia),
      permission: 'unknown',
    };
    try {
      if (navigator.permissions && navigator.permissions.query) {
        const r = await navigator.permissions.query({ name: 'microphone' });
        out.permission = r.state; // granted / denied / prompt
      }
    } catch (e) { /* 某些浏览器不支持查询麦克风权限 */ }
    return out;
  }

  /** 上传并取回文本；hotwords 是当前题目的考察点，用来救回 equals/volatile 这类术语 */
  async function transcribe(wavBytes, hotwords) {
    const fd = new FormData();
    fd.append('audio', new Blob([wavBytes], { type: 'audio/wav' }), 'answer.wav');
    if (hotwords && hotwords.length) fd.append('hotwords', hotwords.join('、'));
    const res = await fetch('/api/transcribe', { method: 'POST', body: fd });
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
    if (!res.ok) throw new Error((data && data.error) ? data.error : '转写失败（HTTP ' + res.status + '）');
    return data;
  }

  /**
   * 纠正识别结果里的技术术语（equals → ecos 这类声学误识）。
   * 单独一步、由调用方异步发：先让用户看到字，术语准不准是后一步的事。
   */
  async function polish(text, topic, hotwords) {
    const res = await fetch('/api/transcribe/polish', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text, topic: topic || '', hotwords: (hotwords || []).join('、') }),
    });
    const body = await res.text();
    let data = null;
    try { data = body ? JSON.parse(body) : null; } catch (e) { data = null; }
    if (!res.ok) throw new Error((data && data.error) ? data.error : '润色失败（HTTP ' + res.status + '）');
    return data;
  }

  async function status() {
    try {
      const res = await fetch('/api/speech/status');
      return await res.json();
    } catch (e) {
      return { ready: false, reason: '无法获取语音识别状态' };
    }
  }

  window.Speech = {
    start, stop, transcribe, polish, status, diagnose, explainError,
    isRecording: () => active,
    elapsedMs: () => (active ? Date.now() - startedAt : 0),
  };
})();
