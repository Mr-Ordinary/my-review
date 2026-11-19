// 组卷模式冒烟：逐个模式出一张卷，断言档位构成、题量、分值合计。
// 前置：mock-llm.mjs(9099) 已启动，后端已启动（BASE 可指定端口，如 8090）。
// 用法：node test\mode-smoke.mjs
//
// 注意：会临时把 /api/settings 指向 mock，结束时写回文件里保存的原配置。
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

(async () => {
  // 显式指向 mock，免得吃用户真实配置
  let r = await call('GET', '/api/settings');
  await call('PUT', '/api/settings', {
    ...r.json, apiBase: 'http://127.0.0.1:9099', apiKey: 'mock-key',
    model: 'deepseek-chat', protocol: 'anthropic',
  });

  r = await call('POST', '/api/stages', { name: '组卷模式冒烟' });
  check('建阶段', r.status === 201 || r.status === 200, JSON.stringify(r.json));
  const stageId = r.json.id;
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: '模式测试', scopeMode: 'topic', level: 'senior', paperSize: 10,
  });
  const prId = r.json.id;
  check('建练习', !!prId, JSON.stringify(r.json));

  async function gen(mode, wantTiers, wantCount) {
    r = await call('POST', `/api/practices/${prId}/papers`, { mode });
    check(`${mode} 出卷发起`, r.status === 200, JSON.stringify(r.json).slice(0, 200));
    if (r.status !== 200) return null;
    const paperId = r.json.papers[r.json.papers.length - 1].id;
    let paper = null;
    for (let i = 0; i < 80; i++) {
      await sleep(3000);
      const d = await call('GET', `/api/practices/${prId}`);
      paper = d.json.papers.find((p) => p.id === paperId);
      if (paper && paper.status !== 'generating') break;
    }
    const qs = paper.questions || [];
    const counts = {};
    qs.forEach((q) => { counts[q.tier] = (counts[q.tier] || 0) + 1; });
    const pts = qs.reduce((a, q) => a + (q.points || 0), 0);
    console.log(`  ${mode}: status=${paper.status} mode=${paper.compositionMode} 题数=${qs.length} 构成=${JSON.stringify(counts)} 合计=${pts}`);
    check(`${mode} 可答/已批`, paper.status === 'answering' || paper.status === 'graded', paper.status);
    check(`${mode} compositionMode 落库`, paper.compositionMode === mode, paper.compositionMode);
    check(`${mode} 题量 = ${wantCount}`, qs.length === wantCount, qs.length);
    check(`${mode} 只含允许档位`, Object.keys(counts).every((t) => wantTiers.includes(t)) && Object.keys(counts).length > 0, JSON.stringify(counts));
    // 混排模式还要求两档都有（配额保证）
    if (wantTiers.length === 2) {
      check(`${mode} 两档都有`, wantTiers.every((t) => counts[t] > 0), JSON.stringify(counts));
    }
    check(`${mode} 分值合计 100`, pts === 100, pts);
    return paper;
  }

  async function finish(paper) {
    if (!paper || paper.status !== 'answering') return;
    // 交卷要求至少有一题作答
    await call('POST', `/api/questions/${paper.questions[0].id}/attempts`, { answer: '冒烟作答 [[score=8]]' });
    r = await call('POST', `/api/papers/${paper.id}/submit`, {});
    check('交卷发起', r.status === 200, JSON.stringify(r.json).slice(0, 150));
    for (let i = 0; i < 40; i++) {
      await sleep(3000);
      const d = await call('GET', `/api/practices/${prId}`);
      if (d.json.papers.find((p) => p.id === paper.id)?.status === 'graded') break;
    }
  }

  await finish(await gen('base', ['基础'], 25));
  await finish(await gen('improve', ['中档'], 14));
  await finish(await gen('deep', ['大题'], 5));
  await finish(await gen('baseImprove', ['基础', '中档'], 20));
  await finish(await gen('midImprove', ['中档', '大题'], 10));

  await call('DELETE', `/api/practices/${prId}`);
  await call('DELETE', `/api/stages/${stageId}`);
  await restoreSettings(base);
  console.log(failed ? `FAILED ${failed}` : 'ALL PASS');
  process.exit(failed ? 1 : 0);
})().catch(async (e) => {
  await restoreSettings(base);
  console.error(e);
  process.exit(1);
});
