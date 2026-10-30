// 考前复习提纲验收：全量内容（不受强度裁剪）、三层字段齐全、可重生成、删练习级联。
// 前置：mock-llm.mjs + 后端已启动（BASE 可指定端口）。
// 用法：node test\review-doc-e2e.mjs
import { protectSettings, restoreSettings } from './_settings.mjs';

const base = process.env.BASE || 'http://127.0.0.1:8080';
let failed = 0;
protectSettings();

const call = async (method, path, body) => {
  const r = await fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const t = await r.text();
  let j; try { j = JSON.parse(t); } catch { j = t; }
  return { status: r.status, json: j };
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function check(name, cond, extra) {
  if (cond) console.log('PASS', name);
  else { failed++; console.log('FAIL', name, extra ?? ''); }
}

async function waitReady(prId, timeoutMs = 180000) {
  const end = Date.now() + timeoutMs;
  let doc = null;
  while (Date.now() < end) {
    await sleep(2000);
    const r = await call('GET', `/api/practices/${prId}/review-doc`);
    doc = r.json;
    if (doc && (doc.status === 'ready' || doc.status === 'failed')) return doc;
  }
  return doc;
}

(async () => {
  let r = await call('GET', '/api/settings');
  await call('PUT', '/api/settings', {
    ...r.json, apiBase: 'http://127.0.0.1:9099', apiKey: 'mock-key',
    model: 'deepseek-chat', protocol: 'anthropic',
  });

  r = await call('POST', '/api/stages', { name: '复习提纲验收' });
  const stageId = r.json.id;
  // 速通：验证复习内容**不受强度裁剪**（medium 点也要有）
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: '复习测试 java集合', scopeMode: 'topic', level: 'mid',
    intensity: 'quick', paperSize: 6,
  });
  const prId = r.json.id;

  r = await call('GET', `/api/practices/${prId}/review-doc`);
  check('初始没有复习提纲', r.status === 200 && r.json.status === 'none',
    JSON.stringify(r.json).slice(0, 120));

  r = await call('POST', `/api/practices/${prId}/review-doc`);
  check('发起生成', r.status === 200 && (r.json.status === 'generating' || r.json.status === 'ready'),
    JSON.stringify(r.json).slice(0, 120));

  let doc = await waitReady(prId);
  check('生成完成', doc && doc.status === 'ready', doc && doc.status);

  const k = await call('GET', `/api/practices/${prId}/knowledge`);
  const kps = k.json.points || [];
  const cards = (doc.content && doc.content.points) || [];
  check('复习卡覆盖全部知识点（不受强度裁剪）', cards.length === kps.length,
    `cards=${cards.length}, points=${kps.length}`);
  check('包含 medium 点（速通也全量）', cards.some((c) => c.priority === 'medium'),
    JSON.stringify(cards.map((c) => c.priority)));
  check('每张卡三层字段齐全',
    cards.every((c) => c.summary && c.interview && c.principle
      && Array.isArray(c.params) && Array.isArray(c.pitfalls)
      && c.selfCheck && c.selfCheck.q && c.selfCheck.a),
    JSON.stringify(cards[0]).slice(0, 200));
  check('卡片带分类与优先级',
    cards.every((c) => c.category && c.priority), '');
  const names = new Set(kps.map((p) => p.name));
  check('卡片点名与知识点一致', cards.every((c) => names.has(c.name)),
    JSON.stringify(cards.map((c) => c.name).slice(0, 5)));

  // 重生成：清空后重新生成
  r = await call('POST', `/api/practices/${prId}/review-doc/regenerate`);
  check('重生成已发起', r.status === 200 && (r.json.status === 'generating' || r.json.status === 'ready'),
    JSON.stringify(r.json).slice(0, 120));
  doc = await waitReady(prId);
  check('重生成完成', doc && doc.status === 'ready', doc && doc.status);
  check('重生成后内容完整',
    doc && (doc.content.points || []).length === kps.length,
    doc && (doc.content.points || []).length);

  // 删练习 → 复习提纲级联清理（练习不存在，接口 404）
  await call('DELETE', `/api/practices/${prId}`);
  r = await call('GET', `/api/practices/${prId}/review-doc`);
  check('删练习后复习提纲 404', r.status === 404, r.status);
  await call('DELETE', `/api/stages/${stageId}`);

  await restoreSettings(base);
  console.log(failed ? `FAILED ${failed}` : 'ALL PASS');
  process.exit(failed ? 1 : 0);
})().catch(async (e) => {
  await restoreSettings(base);
  console.error(e);
  process.exit(1);
});
