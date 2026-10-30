// 覆盖推进验收：点池以 medium 为主时（只有 2 个 high），速通卷也必须逐步覆盖新点，
// 不能反复考同几个高频点。修复前：题池只有 high，补位又总从清单开头拿，覆盖会卡住。
// 前置：mock-llm.mjs + 后端已启动（BASE 可指定端口）。
// 用法：node test\coverage-e2e.mjs
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

async function waitPaper(prId, paperId, want, timeoutMs = 180000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    await sleep(2500);
    const d = await call('GET', `/api/practices/${prId}`);
    const p = d.json.papers.find((x) => x.id === paperId);
    if (p && p.status === want) return p;
    if (p && p.status === 'answering' && want === 'answering') return p;
    if (p && p.status === 'graded' && want === 'graded') return p;
  }
  return null;
}

(async () => {
  let r = await call('GET', '/api/settings');
  await call('PUT', '/api/settings', {
    ...r.json, apiBase: 'http://127.0.0.1:9099', apiKey: 'mock-key',
    model: 'deepseek-chat', protocol: 'anthropic',
  });

  r = await call('POST', '/api/stages', { name: '覆盖推进验收' });
  const stageId = r.json.id;
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: '中频覆盖测试 java锁', scopeMode: 'topic', level: 'mid',
    intensity: 'quick', paperSize: 6,
  });
  const prId = r.json.id;

  const seen = new Set();
  let st0 = null;
  for (let paperNo = 1; paperNo <= 3; paperNo++) {
    r = await call('POST', `/api/practices/${prId}/papers`, { mode: 'regular' });
    const paperId = r.json.papers[r.json.papers.length - 1].id;
    let paper = await waitPaper(prId, paperId, 'answering');
    check(`第 ${paperNo} 张卷出完`, !!paper, paper && paper.status);
    if (!paper) break;

    // 清单是第一次出题时才拆的，第一张卷出完后才能检查优先级分布
    if (paperNo === 1) {
      const k = await call('GET', `/api/practices/${prId}/knowledge`);
      const pts = k.json.points || [];
      const high = pts.filter((p) => p.priority === 'high').length;
      const medium = pts.filter((p) => p.priority === 'medium').length;
      check('点池以 medium 为主（模拟真实分布）', high <= 3 && medium >= 15,
        `high=${high}, medium=${medium}`);
      st0 = k.json.stats || {};
      check('速通范围包含 medium', st0.scopeTotal >= high + medium,
        JSON.stringify(st0));
    }

    for (const q of paper.questions || []) {
      await call('POST', `/api/questions/${q.id}/attempts`, { answer: '回答 [[score=8]]' });
    }
    await call('POST', `/api/papers/${paperId}/submit`, {});
    paper = await waitPaper(prId, paperId, 'graded');
    check(`第 ${paperNo} 张卷批改完成`, !!paper, paper && paper.status);
    for (const q of (paper && paper.questions) || []) {
      for (const n of q.assignedPoints || []) seen.add(n);
    }
  }

  // 3 卷 × 6 题 = 18 个题位；修复前只会在同几个 high/清单开头点之间打转。
  check('覆盖点在持续推进（不是反复考同几个）', seen.size >= 12, '实际覆盖 ' + seen.size);

  const k2 = await call('GET', `/api/practices/${prId}/knowledge`);
  const st = k2.json.stats || {};
  check('未考点数量在下降', st.unseen < (st0.unseen || 0),
    `before=${st0.unseen}, after=${st.unseen}`);
  check('仍有未见点（3 卷没覆盖完 30 个点）', st.unseen > 0, st.unseen);

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
