// 深度验收：多练习 × 多范围 × 多张卷 × 多轮，模拟真实用户参差不齐的作答。
//
// 重点验六件事（都是要跑很多轮才暴露的）：
//   1. 变种  ——上一次的问法真的传进了提示词，不是「说了等于没说」
//   2. 串联  ——卷末一道跨点大题，且它的分对单点掌握度降权
//   3. 弱项  ——答砸的点不会被饿死，公平轮转回来
//   4. 深度  ——同一个点第 N 次考就考第 N 层，跟第几张卷无关
//   5. 题量  ——6 / 10 / 15 三档严格生效
//   6. 参差  ——时好时坏的作答下，掌握度与状态仍然反映真实水平
//
// 前置：mock-llm.mjs 已启动，后端已启动。
// 用法：node test\deep-e2e.mjs
const base = process.env.BASE || 'http://127.0.0.1:8080';
const mock = process.env.MOCK || 'http://127.0.0.1:9099';
let failed = 0;

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

/** 从 mock 回读最近一次匹配到的提示词——用来断言「上游到底喂了什么」 */
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

/**
 * 模拟真实用户作答。scoreFn(q) 返回这道题该给几分，
 * 这样可以做出「这题会、那题不会」的参差效果。
 */
async function answerPaper(paper, scoreFn) {
  for (const q of paper.questions || []) {
    const score = scoreFn(q);
    await call('POST', `/api/questions/${q.id}/attempts`, {
      answer: `[[score=${score}]] 关于「${String(q.prompt).slice(0, 20)}…」我是这么理解的……`,
    });
  }
}

async function knowledge(practiceId) {
  return (await call('GET', `/api/practices/${practiceId}/knowledge`)).json;
}

/** 跑一轮完整考试，返回 { paper, before, after, planPrompt } */
async function runRound(practiceId, scoreFn) {
  const before = await knowledge(practiceId);
  let r = await call('POST', `/api/practices/${practiceId}/papers`, {});
  if (r.status !== 200) throw new Error('出卷失败：' + JSON.stringify(r.json));
  const paperId = r.json.papers.at(-1).id;
  await waitForPaper(practiceId, paperId, 'answering');

  const detail = (await call('GET', `/api/practices/${practiceId}`)).json;
  const paper = (detail.papers || []).find((p) => p.id === paperId);
  const planPrompt = await lastPrompt('请为每一项给一个');

  await answerPaper(paper, scoreFn);
  r = await call('POST', `/api/papers/${paperId}/submit`, {});
  if (r.status !== 200) throw new Error('交卷失败：' + JSON.stringify(r.json));
  const graded = await waitForPaper(practiceId, paperId, 'graded');
  return { paper: graded, before, after: await knowledge(practiceId), planPrompt };
}

function points(k) { return k.points || []; }
function scopePts(k) { return points(k).filter((p) => p.source !== 'answer'); }
function byName(k) {
  const m = new Map();
  for (const p of points(k)) m.set(p.name, p);
  return m;
}
function links(paper) {
  return (paper.questions || []).filter((q) => (q.assignedPoints || []).length > 1);
}
function singles(paper) {
  return (paper.questions || []).filter((q) => (q.assignedPoints || []).length === 1);
}

/* ================= 0. 指向 mock ================= */
let r = await call('PUT', '/api/settings', {
  apiBase: mock, apiKey: 'mock-key', model: 'deepseek-chat', temperature: 1.3,
});
check('PUT /api/settings', r.status === 200, JSON.stringify(r.json));
await fetch(mock + '/reset');

r = await call('POST', '/api/stages', { name: '深度验收', goal: '多范围多轮参差作答' });
check('创建阶段', r.status === 201, JSON.stringify(r.json));
const stageId = r.json.id;

/* ================= 练习 A：小清单 6 题 × 5 轮，看变种 / 深度 / 串联 ================= */
console.log('\n--- 练习 A：小清单 · 快练 6 · 中级 · 5 轮 ---');
{
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: 'Java 基础', scopeMode: 'range', scopeText: 'Java 基础（小清单）',
    level: 'mid', paperSize: 6,
  });
  check('A/创建练习', r.status === 201, JSON.stringify(r.json));
  const pid = r.json.id;

  // 第 1 轮：全答好
  const r1 = await runRound(pid, () => 9);
  check('A/题量=6', r1.paper.questions.length === 6, '实际 ' + r1.paper.questions.length);
  check('A/第1轮 还没有串联大题（没点被考过，谈不上串）',
    links(r1.paper).length === 0, '串联题数 ' + links(r1.paper).length);
  const s1 = scopePts(r1.after);
  check('A/第1轮 每个点都考到 1 次', s1.every((p) => p.timesSeen === 1),
    JSON.stringify(s1.map((p) => p.timesSeen)));
  check('A/第1轮 每个点记下了 1 种问法', s1.every((p) => (p.angles || []).length === 1),
    JSON.stringify(s1.map((p) => (p.angles || []).length)));
  check('A/第1轮 还没掌握（只考 1 次不算）', s1.every((p) => p.status !== '已掌握'));

  // 第 2 轮：变种必须真的发生——上次的问法要出现在提示词里
  const r2 = await runRound(pid, () => 9);
  const firstAngle = (s1[0].angles || [])[0];
  check('A/第2轮 出题提示词里带上了「已问过」',
    r2.planPrompt.includes('已问过'), '提示词片段：' + r2.planPrompt.slice(0, 200));
  check('A/第2轮 上次的问法真的传进去了',
    !!firstAngle && r2.planPrompt.includes(firstAngle),
    `上次问法「${firstAngle}」，提示词里有没有：${r2.planPrompt.includes(String(firstAngle))}`);
  const s2 = scopePts(r2.after);
  check('A/第2轮 每个点记下第 2 种问法', s2.every((p) => (p.angles || []).length === 2),
    JSON.stringify(s2.map((p) => (p.angles || []).length)));
  check('A/第2轮 出现了串联大题', links(r2.paper).length === 1, '串联题数 ' + links(r2.paper).length);
  if (links(r2.paper).length === 1) {
    const lk = links(r2.paper)[0];
    check('A/串联大题跨 2-3 个点', (lk.assignedPoints || []).length >= 2
      && (lk.assignedPoints || []).length <= 3, JSON.stringify(lk.assignedPoints));
    check('A/串联大题排在卷末',
      lk.sequence === Math.max(...r2.paper.questions.map((q) => q.sequence)),
      '序列 ' + lk.sequence + ' / 共 ' + r2.paper.questions.length);
    check('A/其余题都只考 1 个点',
      singles(r2.paper).every((q) => (q.assignedPoints || []).length === 1),
      JSON.stringify(singles(r2.paper).map((q) => q.assignedPoints)));
  }

  // 第 3 轮：深度该到 3 层（原理追问）
  const r3 = await runRound(pid, () => 9);
  const s3 = scopePts(r3.after);
  check('A/第3轮 出现次数继续累积', s3.every((p) => p.timesSeen >= 2),
    JSON.stringify(s3.map((p) => p.timesSeen)));
  const types3 = singles(r3.paper).map((q) => q.questionType);
  check('A/第3轮 单点题深度抬到 3 层', types3.includes('原理追问'),
    '题型 ' + JSON.stringify(types3));
  check('A/第3轮 三次都答好 → 已掌握',
    s3.filter((p) => p.timesSeen >= 3).every((p) => p.status === '已掌握'),
    JSON.stringify(s3.map((p) => [p.name, p.timesSeen, p.status])));

  // 第 4 轮：中级封顶 3 层，不该出 4 层
  const r4 = await runRound(pid, () => 9);
  const types4 = singles(r4.paper).map((q) => q.questionType);
  check('A/第4轮 中级不出 4 层（场景应用）',
    !types4.includes('场景应用'), '题型 ' + JSON.stringify(types4));
}

/* ================= 练习 B：大清单 15 题 × 4 轮，看覆盖 / 串联降权 ================= */
console.log('\n--- 练习 B：大清单 · 深练 15 · 高级 · 串联降权 ---');
{
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: '集合与并发', scopeMode: 'range', scopeText: 'Java 集合框架与并发基础',
    level: 'senior', paperSize: 15,
  });
  check('B/创建练习', r.status === 201, JSON.stringify(r.json));
  const pid = r.json.id;

  // 第 1 轮全答好，摸清哪些点会被串进大题
  const r1 = await runRound(pid, () => 9);
  check('B/题量=15', r1.paper.questions.length === 15, '实际 ' + r1.paper.questions.length);
  check('B/清单 30 个点', scopePts(r1.after).length === 30, '实际 ' + scopePts(r1.after).length);

  // 第 2 轮：单点题答好、串联大题答砸——验证串联降权
  const r2 = await runRound(pid, (q) => ((q.assignedPoints || []).length > 1 ? 3 : 9));
  const lk = links(r2.paper)[0];
  check('B/第2轮 有串联大题', !!lk, '串联题数 ' + links(r2.paper).length);

  if (lk) {
    const linkedNames = lk.assignedPoints;
    const k2 = byName(r2.after);
    // 这些点的历史是 [9(常规), 3(串联)]。
    // 不降权：(9*1 + 3*2)/3 = 5.0 → 50；降权 0.5：(9*1 + 3*1)/2 = 6.0 → 60
    // 断言掌握度明显高于不降权的结果
    const m = linkedNames.map((n) => k2.get(n)?.mastery);
    check('B/串联大题的分对单点掌握度降权',
      m.every((v) => v != null && v > 55),
      '串联点掌握度 ' + JSON.stringify(linkedNames.map((n, i) => [n, m[i]])));
  }

  // 第 3 轮：覆盖面继续扩大
  const r3 = await runRound(pid, () => 9);
  const cov = (x) => scopePts(x.after).filter((p) => p.timesSeen > 0).length;
  check('B/覆盖面逐轮扩大', cov(r1) < cov(r2) && cov(r2) <= cov(r3),
    `${cov(r1)} → ${cov(r2)} → ${cov(r3)}`);

  // 深度 4 层要一个点被考过 3 次才到得了（timesSeen=3 → 第 4 张卷才出 4 层），
  // 30 个点摊在 15 题的卷上，得到第 4、5 张卷才有足够多的点走到那一步。
  let hitDepth4 = false;
  let typesSeen = [];
  let depth4Round = 0;
  for (let round = 4; round <= 6 && !hitDepth4; round++) {
    const rr = await runRound(pid, () => 9);
    typesSeen = singles(rr.paper).map((q) => q.questionType);
    hitDepth4 = typesSeen.includes('场景应用');
    if (hitDepth4) depth4Round = round;
  }
  check('B/高级岗位最终能出到 4 层（场景应用）', hitDepth4,
    `第 ${depth4Round} 张卷出的题型 ` + JSON.stringify(typesSeen));
}

/* ================= 练习 C：参差作答 + 弱项轮转 + 初级封顶 ================= */
console.log('\n--- 练习 C：中清单 · 标准 10 · 初级 · 参差作答 ---');
{
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: '面向对象', scopeMode: 'range', scopeText: '面向对象与集合（小清单）',
    level: 'junior', paperSize: 10,
  });
  check('C/创建练习', r.status === 201, JSON.stringify(r.json));
  const pid = r.json.id;

  // 指定一个「总也答不好」的点，其余都答好——看它会不会被饿死
  let victim = null;

  const r1 = await runRound(pid, (q) => {
    const n = (q.assignedPoints || [])[0];
    if (!victim && n) victim = n;            // 第一个遇到的点当靶子
    return n === victim ? 2 : 9;             // 靶子答砸，其余答好
  });
  check('C/题量=10（小清单点不够时按实际数出）',
    r1.paper.questions.length <= 10 && r1.paper.questions.length > 0,
    '实际 ' + r1.paper.questions.length);
  check('C/靶子点存在', !!victim, String(victim));

  // 连续几轮靶子都答砸，看它是不是每轮都被考到（公平轮转，不饿死）
  const seenRounds = [];
  for (let round = 2; round <= 4; round++) {
    const rr = await runRound(pid, (q) => {
      const names = q.assignedPoints || [];
      return names.includes(victim) ? 2 : 9;
    });
    const hit = (rr.paper.questions || []).some((q) => (q.assignedPoints || []).includes(victim));
    if (hit) seenRounds.push(round);
    const vp = byName(rr.after).get(victim);
    if (vp) {
      check(`C/第${round}轮 靶子点仍是生疏（答砸就不算掌握）`,
        vp.status !== '已掌握', vp.status + ' / ' + JSON.stringify(vp.history));
    }
  }
  check('C/靶子点连续 3 轮都被考到（弱项不饿死）',
    seenRounds.length >= 3, '被考到的轮次 ' + JSON.stringify(seenRounds));

  // 初级封顶 2 层
  const last = await runRound(pid, () => 9);
  const types = singles(last.paper).map((q) => q.questionType);
  check('C/初级不出到 3 层（原理追问）', !types.includes('原理追问') && !types.includes('场景应用'),
    '题型 ' + JSON.stringify(types));

  // 参差之后，答好的点能翻身、答砸的点保持生疏
  const kc = byName(last.after);
  const good = [...kc.values()].filter((p) => p.timesSeen >= 3 && p.status === '已掌握');
  check('C/参差作答下仍有点能达到已掌握', good.length > 0,
    '已掌握 ' + good.length + ' / 总 ' + kc.size);
}

/* ================= 清理 ================= */
r = await call('DELETE', `/api/stages/${stageId}`);
check('DELETE /api/stages/{id}', r.status === 204, String(r.status));

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
