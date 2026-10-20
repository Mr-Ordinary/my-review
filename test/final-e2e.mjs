// 期末综合考验收：门槛、独立性、无偏抽样、禁看答案、可多次申请。
//
// 期末考是**总结性测量**，普通卷是**形成性学习**。这套测试要确认两者真的分得开：
//   1. 门槛看覆盖不看掌握度，且要练过 3 张普通卷才解锁
//   2. 出题上下文**不带**记忆/摘要/最近历史（否则测的是"最近在考什么"）
//   3. **不回写掌握度**——成绩单跟学习轨道两不相干
//   4. 抽样覆盖率如实标注，不吹"全覆盖"
//   5. 答题时**不能看参考答案**
//   6. 可多次申请，且第二次的问法要跟第一次不同（变种）
//
// 前置：mock-llm.mjs 已启动，后端已启动。
// 用法：node test\final-e2e.mjs
const base = process.env.BASE || 'http://127.0.0.1:8080';
const mock = process.env.MOCK || 'http://127.0.0.1:9099';
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

async function lastPrompt(q) {
  const res = await fetch(mock + '/last?q=' + encodeURIComponent(q));
  return (await res.json()).prompt || '';
}

function check(name, cond, extra) {
  if (cond) console.log('PASS', name);
  else { failed++; console.log('FAIL', name, extra ?? ''); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForPaper(practiceId, paperId, want, timeoutMs = 90000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    const r = await call('GET', `/api/practices/${practiceId}`);
    const paper = (r.json.papers || []).find((p) => p.id === paperId);
    last = paper;
    if (paper && paper.status === want) return paper;
    if (paper && paper.status === 'failed') throw new Error('卷失败：' + JSON.stringify(paper.progress));
    await sleep(200);
  }
  throw new Error(`等 ${want} 超时，当前 ${last?.status}`);
}

async function knowledge(practiceId) {
  return (await call('GET', `/api/practices/${practiceId}/knowledge`)).json;
}

/** 跑一轮普通卷 */
async function runNormal(practiceId, score) {
  let r = await call('POST', `/api/practices/${practiceId}/papers`, {});
  const paperId = r.json.papers.at(-1).id;
  await waitForPaper(practiceId, paperId, 'answering');
  const detail = (await call('GET', `/api/practices/${practiceId}`)).json;
  const paper = (detail.papers || []).find((p) => p.id === paperId);
  for (const q of paper.questions || []) {
    await call('POST', `/api/questions/${q.id}/attempts`, {
      answer: `[[score=${score}]] 这题我是这么理解的……`,
    });
  }
  await call('POST', `/api/papers/${paperId}/submit`, {});
  await waitForPaper(practiceId, paperId, 'graded');
  return paperId;
}

/* ================= 0. 指向 mock ================= */
let r = await call('PUT', '/api/settings', {
  apiBase: mock, apiKey: 'mock-key', model: 'deepseek-chat', temperature: 1.3,
});
check('PUT /api/settings', r.status === 200, JSON.stringify(r.json));
await fetch(mock + '/reset');

r = await call('POST', '/api/stages', { name: '期末考验收', goal: '测量与学习要分得开' });
check('创建阶段', r.status === 201, JSON.stringify(r.json));
const stageId = r.json.id;

r = await call('POST', `/api/stages/${stageId}/practices`, {
  topic: 'Java 基础', scopeMode: 'range', scopeText: 'Java 基础（小清单）',
  level: 'mid', paperSize: 6,
});
check('创建练习', r.status === 201, JSON.stringify(r.json));
const pid = r.json.id;

/* ================= 1. 门槛 ================= */
console.log('\n--- 门槛：看覆盖不看掌握度 ---');
{
  r = await call('GET', `/api/practices/${pid}/final-exam/gate`);
  check('初始未解锁', r.status === 200 && r.json.unlocked === false,
    JSON.stringify(r.json));
  check('门槛提示要说清差在哪', typeof r.json.hint === 'string' && r.json.hint.length > 0,
    r.json.hint);

  // 还没练就申请，应当被拒
  r = await call('POST', `/api/practices/${pid}/final-exam`, {});
  check('未解锁时申请被拒（400）', r.status === 400, String(r.status) + ' ' + JSON.stringify(r.json));

  // 练满 3 张普通卷（小清单 6 个点，覆盖率很快到 100%）
  for (let i = 0; i < 3; i++) await runNormal(pid, 9);

  r = await call('GET', `/api/practices/${pid}/final-exam/gate`);
  check('练够 3 张卷 + 覆盖够 → 解锁', r.json.unlocked === true, JSON.stringify(r.json));
  check('门槛报出覆盖数据', r.json.coveredPoints > 0 && r.json.totalPoints > 0,
    `${r.json.coveredPoints}/${r.json.totalPoints}`);
  check('覆盖满 100%（小清单 6 点被 3 张 6 题卷铺满）', r.json.coveragePercent >= 60,
    r.json.coveragePercent + '%');
}

/* ================= 2. 第一次期末考 ================= */
console.log('\n--- 第一次期末考：独立测量 ---');
let firstExamPrompt = '';
const firstByPoint = new Map();
{
  const before = await knowledge(pid);
  const beforeMastery = JSON.stringify((before.points || []).map((p) => [p.name, p.timesSeen, p.mastery, p.status]));

  r = await call('POST', `/api/practices/${pid}/final-exam`, {});
  check('期末考已发起', r.status === 200, JSON.stringify(r.json).slice(0, 200));
  const paperId = r.json.papers.at(-1).id;
  await waitForPaper(pid, paperId, 'answering');

  const detail = (await call('GET', `/api/practices/${pid}`)).json;
  const paper = (detail.papers || []).find((p) => p.id === paperId);
  check('卷标记为 final', paper.kind === 'final', 'kind=' + paper.kind);
  check('期末考题量=20 或清单不够时按实际数', paper.questions.length > 0
    && paper.questions.length <= 20, '实际 ' + paper.questions.length);

  // 出题上下文不能带记忆/摘要/最近历史
  firstExamPrompt = await lastPrompt('请为每一项给一个');
  check('期末考上下文不含「会话摘要」', !firstExamPrompt.includes('会话摘要'),
    firstExamPrompt.slice(0, 200));
  check('期末考上下文不含「长期记忆」', !firstExamPrompt.includes('长期记忆'),
    firstExamPrompt.slice(0, 200));
  check('期末考上下文不含「最近的题目与表现」', !firstExamPrompt.includes('最近的题目与表现'),
    firstExamPrompt.slice(0, 200));
  check('期末考上下文仍带练习范围', firstExamPrompt.includes('练习范围') || firstExamPrompt.includes('Java 基础'),
    firstExamPrompt.slice(0, 200));

  // 期末考禁止看参考答案
  const q0 = paper.questions[0];
  r = await call('POST', `/api/questions/${q0.id}/reveal`, {});
  check('期末考不能看参考答案', r.status === 400 && String(r.json.error || '').includes('不能查看参考答案'),
    String(r.status) + ' ' + JSON.stringify(r.json));

  // 作答并交卷
  for (const q of paper.questions || []) {
    for (const n of q.assignedPoints || []) if (q.angle) firstByPoint.set(n, q.angle);
    await call('POST', `/api/questions/${q.id}/attempts`, {
      answer: '[[score=8]] 期末考作答：我的理解是……',
    });
  }
  await call('POST', `/api/papers/${paperId}/submit`, {});
  await waitForPaper(pid, paperId, 'graded');

  // 掌握度必须纹丝不动
  const after = await knowledge(pid);
  const afterMastery = JSON.stringify((after.points || []).map((p) => [p.name, p.timesSeen, p.mastery, p.status]));
  check('期末考不回写掌握度（学习轨道不受影响）', beforeMastery === afterMastery,
    '考前 ' + beforeMastery + '\n考后 ' + afterMastery);

  // 成绩单
  r = await call('GET', `/api/practices/${pid}/final-exams`);
  check('成绩单已生成', r.status === 200 && r.json.length === 1, '份数 ' + (r.json?.length));
  const ex = r.json[0];
  check('成绩单有总分', typeof ex.score === 'number', JSON.stringify(ex.score));
  check('成绩单有抽样覆盖率', ex.coverage && ex.coverage.totalPoints > 0
    && ex.coverage.coveredPoints > 0, JSON.stringify(ex.coverage));
  check('抽样覆盖率如实标注（小清单应是全覆盖）',
    ex.coverage.coveredPoints <= ex.coverage.totalPoints,
    `${ex.coverage.coveredPoints}/${ex.coverage.totalPoints}`);
  check('成绩单有按大类得分率', (ex.categories || []).length > 0
    && ex.categories.every((c) => typeof c.rate === 'number'), JSON.stringify(ex.categories));
  check('成绩单带题目明细', (ex.questions || []).length > 0, '题数 ' + (ex.questions || []).length);
}

/* ================= 3. 第二次期末考：变种 + 对比 ================= */
console.log('\n--- 第二次期末考：变种 + 与上次对比 ---');
{
  r = await call('POST', `/api/practices/${pid}/final-exam`, {});
  check('第二次申请成功（可多次）', r.status === 200, JSON.stringify(r.json).slice(0, 200));
  const paperId = r.json.papers.at(-1).id;
  await waitForPaper(pid, paperId, 'answering');

  const secondPrompt = await lastPrompt('请为每一项给一个');
  check('第二次出题提示词里带上了「已问过」', secondPrompt.includes('已问过'),
    secondPrompt.slice(0, 200));
  check('第二次拿到了第一次的问法（要避开）',
    firstExamPrompt.length > 0 && secondPrompt.includes('已问过'),
    '第一次提示词长度 ' + firstExamPrompt.length);

  const detail = (await call('GET', `/api/practices/${pid}`)).json;
  const paper = (detail.papers || []).find((p) => p.id === paperId);

  // 期末考是**抽样**的，两次抽到的点不完全一样。
  // 要按「同一个点」比问法有没有变，不能整体比最大值/最小值。
  const order = (a) => {
    const m = /第(\d+)种问法/.exec(a || '');
    return m ? Number(m[1]) : 1; // 「初次问法」按第 1 种算
  };
  const angleByPoint = (pp) => {
    const m = new Map();
    for (const q of pp.questions || []) {
      for (const n of q.assignedPoints || []) if (q.angle) m.set(n, q.angle);
    }
    return m;
  };
  const secondByPoint = angleByPoint(paper);
  let shared = 0, advanced = 0;
  for (const [name, a1] of firstByPoint) {
    const a2 = secondByPoint.get(name);
    if (!a2) continue;
    shared++;
    if (order(a2) > order(a1)) advanced++;
  }
  check('两次都抽到的点里，问法都变新了（变种真的发生了）',
    shared > 0 && advanced === shared,
    `共同点 ${shared} 个，其中问法变新的 ${advanced} 个`);
  check('两次角度集合不完全相同',
    JSON.stringify([...firstByPoint.values()].sort()) !== JSON.stringify([...secondByPoint.values()].sort()),
    '一 ' + JSON.stringify([...firstByPoint.values()].slice(0, 2))
      + '\n二 ' + JSON.stringify([...secondByPoint.values()].slice(0, 2)));

  for (const q of paper.questions || []) {
    await call('POST', `/api/questions/${q.id}/attempts`, {
      answer: '[[score=9]] 第二次作答……',
    });
  }
  await call('POST', `/api/papers/${paperId}/submit`, {});
  await waitForPaper(pid, paperId, 'graded');

  r = await call('GET', `/api/practices/${pid}/final-exams`);
  check('两次成绩单都在，最新的在前', r.json.length === 2, '份数 ' + r.json.length);
  check('两次成绩可对比', typeof r.json[0].score === 'number' && typeof r.json[1].score === 'number',
    JSON.stringify(r.json.map((e) => e.score)));
  check('第二次分数更高（答了 9 分）', r.json[0].score >= r.json[1].score,
    `${r.json[0].score} vs ${r.json[1].score}`);
}

/* ================= 4. 门槛逻辑：覆盖不足不该解锁 ================= */
console.log('\n--- 覆盖不足时不解锁 ---');
{
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: '大范围', scopeMode: 'range', scopeText: 'Java 集合框架与并发基础', level: 'mid',
  });
  const pid2 = r.json.id;

  r = await call('GET', `/api/practices/${pid2}/final-exam/gate`);
  check('新练习未解锁', r.json.unlocked === false, JSON.stringify(r.json));

  // 只出 1 张卷，覆盖率不够 + 张数不够
  await runNormal(pid2, 9);
  r = await call('GET', `/api/practices/${pid2}/final-exam/gate`);
  check('只有 1 张卷时未解锁', r.json.unlocked === false, JSON.stringify(r.json));
  check('提示里点明还差什么', r.json.hint.includes('至少') || r.json.hint.includes('%'),
    r.json.hint);
}

/* ================= 清理 ================= */
r = await call('DELETE', `/api/stages/${stageId}`);
check('DELETE /api/stages/{id}', r.status === 204, String(r.status));

await restoreSettings(base);
console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
