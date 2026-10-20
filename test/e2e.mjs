// 快速冒烟：阶段 → 练习 → 出卷 → 作答 → 交卷 → 知识点 → 错误路径 → 清理。
// 多轮、多练习、掌握度推进那套在 knowledge-e2e.mjs，这里只求「接口都还活着」。
// 前置：mock-llm.mjs 已启动，后端已启动。
// 用法：node test\e2e.mjs
const base = process.env.BASE || 'http://127.0.0.1:8080';
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

function check(name, cond, extra) {
  if (cond) console.log('PASS', name);
  else { failed++; console.log('FAIL', name, extra ?? ''); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForPaper(practiceId, paperId, want, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    const r = await call('GET', `/api/practices/${practiceId}`);
    const paper = (r.json.papers || []).find((p) => p.id === paperId);
    last = paper;
    if (paper && paper.status === want) return paper;
    await sleep(200);
  }
  throw new Error(`等 ${want} 超时，当前 ${last?.status}`);
}

// 0. 设置指向 mock LLM
let r = await call('PUT', '/api/settings', {
  apiBase: 'http://127.0.0.1:9099', apiKey: 'mock-key', model: 'deepseek-chat', temperature: 1.3,
});
check('PUT /api/settings', r.status === 200 && r.json.apiBase === 'http://127.0.0.1:9099', JSON.stringify(r.json));

// 1. 创建阶段
r = await call('POST', '/api/stages', { name: '冒烟测试', goal: '确认接口都还活着' });
check('POST /api/stages', r.status === 201 && r.json.id && r.json.stats, JSON.stringify(r.json));
const stageId = r.json.id;

// 2. 创建练习
r = await call('POST', `/api/stages/${stageId}/practices`, {
  topic: 'Java 集合框架', scopeMode: 'range', scopeText: 'Java 集合框架（小清单）', level: 'mid',
});
check('POST practices', r.status === 201 && r.json.mode === 'interview', JSON.stringify(r.json));
const practiceId = r.json.id;

// 3. 出卷（后台任务，轮询到可作答）
r = await call('POST', `/api/practices/${practiceId}/papers`, {});
check('出卷已发起', r.status === 200 && r.json.papers?.length === 1, JSON.stringify(r.json).slice(0, 200));
const paperId = r.json.papers[0].id;
const paper = await waitForPaper(practiceId, paperId, 'answering');
check('出卷完成', paper.status === 'answering' && paper.questions.length > 0,
  JSON.stringify({ status: paper.status, n: paper.questions.length }));

// 4. 作答 + 看答案（看答案要抢在交卷前，交卷后参考答案本就公开）
r = await call('POST', `/api/questions/${paper.questions[0].id}/attempts`, {
  answer: '[[score=8]] 1.8 引入了红黑树来优化查询。',
});
check('保存作答', r.status === 200 && r.json.attempts?.length === 1
  && r.json.attempts[0].answer.includes('红黑树'), JSON.stringify(r.json).slice(0, 200));

// 第二题也作答，但先偷偷看了参考答案——按规则这题要计 0 分
const peeked = paper.questions[1];
if (!peeked) throw new Error('这张卷只有 1 道题，验不了「看答案计 0 分」');
await call('POST', `/api/questions/${peeked.id}/attempts`, {
  answer: '[[score=9]] 这题我先看了答案再写的。',
});
r = await call('POST', `/api/questions/${peeked.id}/reveal`, {});
check('看答案', r.status === 200 && r.json.revealedAt, JSON.stringify(r.json).slice(0, 200));

r = await call('POST', `/api/papers/${paperId}/submit`, {});
check('交卷已发起', r.status === 200, JSON.stringify(r.json).slice(0, 200));
const graded = await waitForPaper(practiceId, paperId, 'graded');

const q0 = graded.questions[0];
const qPeek = graded.questions.find((q) => q.id === peeked.id);
check('批改完成', graded.status === 'graded' && graded.score != null
  && q0.attempts[0].evaluation?.score === 8,
  JSON.stringify({ score: graded.score, ev: q0.attempts[0].evaluation }));
check('看过的题按规则计 0 分', qPeek.attempts[0].evaluation?.score === 0,
  JSON.stringify(qPeek.attempts[0].evaluation));

// 5. 知识点清单已建立并回写
r = await call('GET', `/api/practices/${practiceId}/knowledge`);
check('知识点清单', r.status === 200 && r.json.stats?.total > 0 && r.json.points?.length > 0,
  JSON.stringify(r.json?.stats));
check('被考到的点有掌握度',
  (r.json.points || []).some((p) => p.timesSeen >= 1 && p.history?.length === 1),
  JSON.stringify((r.json.points || []).map((p) => [p.name, p.timesSeen])));

// 7. 阶段详情与总结
r = await call('GET', `/api/stages/${stageId}`);
check('阶段详情 stats', r.status === 200 && r.json.stats.practiceCount === 1
  && r.json.stats.questionCount > 0, JSON.stringify(r.json?.stats));
r = await call('POST', `/api/stages/${stageId}/summary`, {});
check('阶段总结', r.status === 200 && r.json.summary?.keyWeaknesses?.length >= 1,
  JSON.stringify(r.json?.summary).slice(0, 200));

// 8. 列表 / 设置读取
r = await call('GET', '/api/stages');
check('GET /api/stages', r.status === 200 && r.json.length >= 1, '');
r = await call('GET', '/api/settings');
check('GET /api/settings', r.status === 200 && r.json.model === 'deepseek-chat', '');

// 9. 错误路径
r = await call('POST', `/api/stages/${stageId}/practices`, { topic: '' });
check('空主题 400', r.status === 400 && r.json.error, JSON.stringify(r.json));
r = await call('POST', `/api/stages/${stageId}/practices`, {
  topic: '范围模式缺文本', scopeMode: 'range', scopeText: '',
});
check('范围模式缺文本 400', r.status === 400 && r.json.error, JSON.stringify(r.json));
r = await call('GET', '/api/practices/not-exist');
check('不存在 404', r.status === 404 && r.json.error, JSON.stringify(r.json));

// 10. 清理：删除阶段（连带练习/卷/题/作答/知识点）
r = await call('DELETE', `/api/stages/${stageId}`);
check('DELETE /api/stages/{id}', r.status === 204, String(r.status));
r = await call('GET', `/api/practices/${practiceId}`);
check('删除后练习已不存在', r.status === 404, String(r.status));

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
