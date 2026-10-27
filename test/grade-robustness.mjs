// 批改健壮性验收：AI 首轮漏题 / 点评为空时，必须自动补批，
// 不能出现「兜底 0 分」或「有分无点评」。
// 前置：mock-llm.mjs + 后端已启动（BASE 可指定端口）。
// 用法：node test\grade-robustness.mjs
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
  let r = await call('GET', '/api/settings');
  await call('PUT', '/api/settings', {
    ...r.json, apiBase: 'http://127.0.0.1:9099', apiKey: 'mock-key',
    model: 'deepseek-chat', protocol: 'anthropic',
  });

  r = await call('POST', '/api/stages', { name: '批改健壮性' });
  const stageId = r.json.id;
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: '批改健壮性', scopeMode: 'topic', level: 'mid', intensity: 'standard', paperSize: 6,
  });
  const prId = r.json.id;
  r = await call('POST', `/api/practices/${prId}/papers`, { mode: 'regular' });
  const paperId = r.json.papers[0].id;

  let paper = null;
  for (let i = 0; i < 60; i++) {
    await sleep(3000);
    const d = await call('GET', `/api/practices/${prId}`);
    paper = d.json.papers.find((p) => p.id === paperId);
    if (paper && paper.status !== 'generating') break;
  }
  check('出卷完成', paper && paper.status === 'answering', paper && paper.status);
  const qs = paper.questions || [];
  check('题量 > 1', qs.length > 1, qs.length);

  // 第 1 题模拟「AI 漏题」，第 2 题模拟「点评为空」，其余正常
  for (let i = 0; i < qs.length; i++) {
    let answer = `我的回答 [[score=${i === 0 ? 9 : 8}]]`;
    if (i === 0) answer += ' [[omit]]';
    if (i === 1) answer += ' [[nofeedback]]';
    await call('POST', `/api/questions/${qs[i].id}/attempts`, { answer });
  }

  r = await call('POST', `/api/papers/${paperId}/submit`, {});
  check('交卷发起', r.status === 200, JSON.stringify(r.json).slice(0, 200));

  paper = null;
  for (let i = 0; i < 60; i++) {
    await sleep(3000);
    const d = await call('GET', `/api/practices/${prId}`);
    paper = d.json.papers.find((p) => p.id === paperId);
    if (paper && paper.status !== 'grading') break;
  }
  check('批改完成（补批生效）', paper && paper.status === 'graded', paper && paper.status);

  // 关键：重新拉取题目，旧对象里没有批改后的 attempts
  const fresh = (await call('GET', `/api/practices/${prId}`)).json;
  const gradedPaper = fresh.papers.find((p) => p.id === paperId);
  const evals = (gradedPaper.questions || []).map((q) => {
    const a = (q.attempts || [])[0];
    return a && a.evaluation ? a.evaluation : null;
  });
  const missing = evals.filter((e) => !e || e.score === null);
  check('没有题因 AI 漏题被兜底 0 分', missing.length === 0,
    JSON.stringify(evals.map((e) => e && e.score)));
  const blank = evals.filter((e) => e && (!e.feedback || !String(e.feedback).trim()));
  check('没有「有分无点评」', blank.length === 0,
    JSON.stringify(evals.map((e) => e && (e.feedback || '').slice(0, 20))));
  check('漏题的第 1 题拿到真实分数', evals[0] && evals[0].score === 9, evals[0] && evals[0].score);
  check('空点评的第 2 题拿到真实点评', evals[1] && evals[1].feedback, evals[1] && evals[1].feedback);
  check('没有旧的兜底文案残留',
    evals.every((e) => !e || !String(e.feedback || '').includes('AI 未返回本题评价')),
    JSON.stringify(evals.map((e) => e && e.feedback)));

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
