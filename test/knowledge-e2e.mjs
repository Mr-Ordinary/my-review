// 知识点体系端到端验收：多个练习 × 多轮「出卷 → 真实作答 → 批改 → 再出卷」。
//
// 要验的四件事（都是长期跑才会暴露的，肉眼看不出来）：
//   1. 深度跟着点走——同一个点第 N 次考就考第 N 层，跟第几张卷无关
//   2. 掌握看变种——3 次变种题都及格才算「已掌握」，早期答砸过后面能翻身
//   3. 覆盖全范围——清单比一张卷大时，多轮下来覆盖面持续扩大
//   4. 发散不越界——用户作答引出的新点，范围外的一律不收
//
// 前置：mock-llm.mjs 已启动（node test\mock-llm.mjs），后端已启动。
// 用法：node test\knowledge-e2e.mjs
const base = process.env.BASE || 'http://127.0.0.1:8080';
let failed = 0;

import { protectSettings, restoreSettings } from './_settings.mjs';
protectSettings();

async function call(method, path, body) {
  const res = await fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = text; }
  return { status: res.status, json };
}

function check(name, cond, extra) {
  if (cond) console.log('PASS', name);
  else { failed++; console.log('FAIL', name, extra ?? ''); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 轮询到某张卷进入目标状态（出题/批改都是后台任务） */
async function waitForPaper(practiceId, paperId, want, timeoutMs = 90000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    const r = await call('GET', `/api/practices/${practiceId}`);
    const paper = (r.json.papers || []).find((p) => p.id === paperId);
    last = paper;
    if (paper && paper.status === want) return paper;
    if (paper && paper.status === 'failed') throw new Error('卷进入失败状态：' + JSON.stringify(paper.progress));
    await sleep(200);
  }
  throw new Error(`等 ${want} 超时，当前 ${last?.status} / ${JSON.stringify(last?.progress)}`);
}

/** 模拟真实作答：每道题都写下答案，用 [[score=N]] 控制这位「用户」答得好不好 */
async function answerPaper(paper, score) {
  let n = 0;
  for (const q of paper.questions || []) {
    const r = await call('POST', `/api/questions/${q.id}/attempts`, {
      answer: `[[score=${score}]] 关于「${String(q.prompt).slice(0, 24)}…」我的理解是这样的，核心机制是……`,
    });
    if (r.status !== 200) throw new Error('作答失败：' + JSON.stringify(r.json));
    n++;
  }
  return n;
}

async function knowledge(practiceId) {
  const r = await call('GET', `/api/practices/${practiceId}/knowledge`);
  return r.json;
}

/** 跑完一轮完整考试：出卷 → 作答 → 交卷 → 等批完 → 返回考后知识点 */
async function runRound(practiceId, score) {
  let r = await call('POST', `/api/practices/${practiceId}/papers`, {});
  if (r.status !== 200) throw new Error('出卷失败：' + JSON.stringify(r.json));
  const paper = (r.json.papers || []).at(-1);
  await waitForPaper(practiceId, paper.id, 'answering');

  const detail = (await call('GET', `/api/practices/${practiceId}`)).json;
  const fresh = (detail.papers || []).find((p) => p.id === paper.id);
  const answered = await answerPaper(fresh, score);

  r = await call('POST', `/api/papers/${paper.id}/submit`, {});
  if (r.status !== 200) throw new Error('交卷失败：' + JSON.stringify(r.json));
  const graded = await waitForPaper(practiceId, paper.id, 'graded');
  return { paper: graded, answered, after: await knowledge(practiceId) };
}

/** 只看「范围拆解」来的点：发散点是交卷后才进来的，会干扰进度断言 */
function scopePoints(k) {
  return (k.points || []).filter((p) => p.source !== 'answer');
}

function byName(k) {
  const m = new Map();
  for (const p of k.points || []) m.set(p.name, p);
  return m;
}

/**
 * 这些点在「自己那道单点题」上的字段值（档位 tier / 题型 questionType）。
 * 只看单点题——串联大题是综合题，它的难度不代表任何一个点自己的深度。
 */
function fieldOn(paper, names, field) {
  return [...names]
    .map((n) => (paper.questions || []).find(
      (q) => (q.assignedPoints || []).length === 1 && (q.assignedPoints || [])[0] === n)?.[field])
    .filter(Boolean);
}

// 档位由组卷模式 + 复习强度决定（2026-10 改版）：
//   不再有「timesSeen→第N层」的自动加深。题型跟档位走：
//   基础→概念辨析  中档→原理追问  大题→场景应用。
//   内容深度由点上的三档规格 + 复习强度管，强化靠换角度变种。

/* ================= 0. 指向 mock LLM ================= */

let r = await call('PUT', '/api/settings', {
  apiBase: 'http://127.0.0.1:9099', apiKey: 'mock-key', model: 'deepseek-chat', temperature: 1.3,
});
check('PUT /api/settings', r.status === 200 && r.json.apiBase === 'http://127.0.0.1:9099', JSON.stringify(r.json));

r = await call('POST', '/api/stages', { name: '知识点体系验收', goal: '验证多轮出卷下的深度推进与掌握度' });
check('创建阶段', r.status === 201 && r.json.id, JSON.stringify(r.json));
const stageId = r.json.id;

/* ================= 练习 A：深度跟着点走 + 三次变种才掌握 ================= */
console.log('\n--- 练习 A：小清单 × 4 轮全答好 ---');
{
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: 'Java 基础', scopeMode: 'range', scopeText: 'Java 基础（小清单）', level: 'mid',
  });
  check('创建练习 A', r.status === 201 && r.json.id, JSON.stringify(r.json));
  const pid = r.json.id;

  const r1 = await runRound(pid, 8);
  check('A/第1轮 作答数 > 0', r1.answered > 0, '实际 ' + r1.answered);
  const s1 = scopePoints(r1.after);
  check('A/小清单只拆 6 个点', s1.length === 6, '实际 ' + s1.length);
  check('A/第1轮 每个点都被考到 1 次', s1.every((p) => p.timesSeen === 1),
    JSON.stringify(s1.map((p) => p.timesSeen)));
  check('A/第1轮 还没有点算已掌握（只考 1 次不算）',
    s1.every((p) => p.status !== '已掌握'), JSON.stringify(s1.map((p) => p.status)));

  const r2 = await runRound(pid, 8);
  const s2 = scopePoints(r2.after);
  check('A/第2轮 出现次数都到 2', s2.every((p) => p.timesSeen === 2),
    JSON.stringify(s2.map((p) => p.timesSeen)));
  check('A/第2轮 变种换了问法（角度累积）',
    s2.every((p) => (p.angles || []).length >= 2),
    JSON.stringify(s2.map((p) => (p.angles || []).length)));

  const r3 = await runRound(pid, 8);
  const s3 = scopePoints(r3.after);
  check('A/第3轮 出现次数都到 3', s3.every((p) => p.timesSeen === 3),
    JSON.stringify(s3.map((p) => p.timesSeen)));
  check('A/第3轮 三次变种都及格 → 已掌握',
    s3.every((p) => p.status === '已掌握'),
    JSON.stringify(s3.map((p) => [p.status, p.history])));

  const r4 = await runRound(pid, 8);
  check('A/第4轮 掌握度稳定在高位（没有被更深的题打下去）',
    r4.after.stats.avgMastery >= 80, '实际 ' + r4.after.stats.avgMastery);
}

/* ================= 练习 B：清单比卷大 → 覆盖面逐轮扩大 ================= */
console.log('\n--- 练习 B：30 个点 × 3 轮，看覆盖面 ---');
{
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: 'Java 集合与并发', scopeMode: 'range', scopeText: 'Java 集合框架与并发基础', level: 'mid',
  });
  check('创建练习 B', r.status === 201 && r.json.id, JSON.stringify(r.json));
  const pid = r.json.id;

  const r1 = await runRound(pid, 8);
  check('B/清单拆出 30 个范围点（另有发散点另计）',
    scopePoints(r1.after).length === 30, '实际 ' + scopePoints(r1.after).length);
  check('B/第1轮 不可能全覆盖（一张卷只有 20 槽）',
    scopePoints(r1.after).filter((p) => p.timesSeen > 0).length < 30,
    `已覆盖 ${scopePoints(r1.after).filter((p) => p.timesSeen > 0).length}/30`);
  check('B/每道题都挂上了知识点',
    (r1.paper.questions || []).length > 0
      && (r1.paper.questions || []).every((q) => (q.assignedPoints || []).length > 0),
    '有题没挂点');

  const r2 = await runRound(pid, 8);
  const r3 = await runRound(pid, 8);
  const cov = (x) => scopePoints(x.after).filter((p) => p.timesSeen > 0).length;
  check('B/覆盖面逐轮扩大', cov(r1) < cov(r2) && cov(r2) <= cov(r3),
    `${cov(r1)} → ${cov(r2)} → ${cov(r3)}`);
  check('B/三轮下来覆盖了大部分点', cov(r3) > 21, `已覆盖 ${cov(r3)}/30`);

  const n1 = new Set((r1.paper.questions || []).flatMap((q) => q.assignedPoints || []));
  const n2 = new Set((r2.paper.questions || []).flatMap((q) => q.assignedPoints || []));
  const overlap = [...n1].filter((n) => n2.has(n)).length;
  check('B/两张卷的选点不完全一样（有新点补进来）', overlap < n1.size,
    `重叠 ${overlap} / 第1轮 ${n1.size}`);
}

/* ================= 练习 C：早期答砸要能翻身 + 发散不越界 ================= */
console.log('\n--- 练习 C：先答砸再恢复，看掌握度能否翻身 ---');
{
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: '并发基础', scopeMode: 'range', scopeText: 'Java 并发基础（小清单）', level: 'senior',
  });
  check('创建练习 C', r.status === 201 && r.json.id, JSON.stringify(r.json));
  const pid = r.json.id;

  const bad = await runRound(pid, 3);
  check('C/第1轮 答砸后全是「待巩固」',
    scopePoints(bad.after).every((p) => p.status === '待巩固'),
    JSON.stringify(scopePoints(bad.after).map((p) => p.status)));

  await runRound(pid, 8);
  await runRound(pid, 8);
  const good = await runRound(pid, 8);
  const sg = scopePoints(good.after);
  check('C/早期答砸过、最近 3 次都及格 → 能翻身成已掌握',
    sg.every((p) => p.status === '已掌握'),
    JSON.stringify(sg.map((p) => [p.status, p.history])));

  const names = (good.after.points || []).map((p) => p.name);
  check('C/发散收了范围内的新点', names.some((n) => n.includes('范围内新增')), JSON.stringify(names));
  check('C/发散丢掉了越界的新点', !names.some((n) => n.includes('越界应丢弃')), JSON.stringify(names));

  check('C/每道题的题型都跟档位对齐（形式一致，不乱跳）',
    fieldOn(good.paper, sg.map((p) => p.name), 'tier').every((t) =>
      ['基础', '中档', '大题'].includes(t)),
    '档位 ' + JSON.stringify(fieldOn(good.paper, sg.map((p) => p.name), 'tier')));

  /* ---- 错题重做：错题本不能只进不出 ---- */
  const det = (await call('GET', `/api/practices/${pid}`)).json;
  const openMistakes = (det.mistakes || []).filter((m) => m.status === 'open');
  check('C/答砸的题进了错题本', openMistakes.length > 0, '待订正 ' + openMistakes.length);

  if (openMistakes.length) {
    // 反查：错题对应的考点，就是错题卷该考的范围
    const focus = new Set();
    for (const m of openMistakes) {
      const q = (det.papers || []).flatMap((p) => p.questions || []).find((x) => x.id === m.questionId);
      for (const n of (q && q.assignedPoints) || []) focus.add(n);
    }
    check('C/错题能反查到考点', focus.size > 0, '考点数 ' + focus.size);

    r = await call('POST', `/api/practices/${pid}/mistakes/retry`, {});
    check('C/错题重做卷已发起', r.status === 200, JSON.stringify(r.json).slice(0, 200));
    const retryId = r.json.papers.at(-1).id;
    await waitForPaper(pid, retryId, 'answering');
    const rd = (await call('GET', `/api/practices/${pid}`)).json;
    const rp = (rd.papers || []).find((p) => p.id === retryId);
    check('C/错题卷标记为 mistake', rp.kind === 'mistake', 'kind=' + rp.kind);
    check('C/错题卷有题且都挂了考点',
      rp.questions.length > 0 && rp.questions.every((q) => (q.assignedPoints || []).length > 0),
      '题数 ' + rp.questions.length);

    const covered = new Set(rp.questions.flatMap((q) => q.assignedPoints || []));
    check('C/错题卷只考错题反查出的点',
      [...covered].every((n) => focus.has(n)),
      '越界点 ' + JSON.stringify([...covered].filter((n) => !focus.has(n))));
  }
}

/* ================= 清理 ================= */
r = await call('DELETE', `/api/stages/${stageId}`);
check('DELETE /api/stages/{id}', r.status === 204, String(r.status));

await restoreSettings(base);
console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
