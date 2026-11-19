// 追问功能验收：按题隔离、批改后才能问、清空、空问题拦截、级联删除。
// 前置：mock-llm.mjs + 后端已启动（BASE 可指定端口）。
// 用法：node test\followup-e2e.mjs
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

  r = await call('POST', '/api/stages', { name: '追问验收' });
  const stageId = r.json.id;
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: '追问测试 java集合', scopeMode: 'topic', level: 'mid',
    intensity: 'standard', paperSize: 6,
  });
  const prId = r.json.id;
  r = await call('POST', `/api/practices/${prId}/papers`, { mode: 'regular' });
  const paperId = r.json.papers[0].id;

  let paper = null;
  for (let i = 0; i < 100; i++) {
    await sleep(2000);
    const d = await call('GET', `/api/practices/${prId}`);
    paper = d.json.papers.find((p) => p.id === paperId);
    if (paper && paper.status === 'answering') break;
  }
  check('出卷完成', paper && paper.status === 'answering', paper && paper.status);
  const qs = paper.questions || [];
  check('题目数量足够', qs.length >= 2, qs.length);
  const q1 = qs[0];
  const q2 = qs[1];

  // 未交卷：不允许追问（追问需要批改与参考答案做上下文）
  r = await call('POST', `/api/questions/${q1.id}/followups`, { content: '批改前能问吗？' });
  check('交卷前追问被拒绝', r.status === 400, r.status + ' ' + JSON.stringify(r.json));

  // 故意留最后一题不答：卷批改后，未作答的题也必须能追问
  const unanswered = qs[qs.length - 1];
  for (const q of qs) {
    if (q.id === unanswered.id) continue;
    await call('POST', `/api/questions/${q.id}/attempts`, { answer: '我的回答 [[score=8]]' });
  }
  r = await call('POST', `/api/papers/${paperId}/submit`, {});
  check('交卷发起', r.status === 200, JSON.stringify(r.json).slice(0, 150));
  for (let i = 0; i < 80; i++) {
    await sleep(2000);
    const d = await call('GET', `/api/practices/${prId}`);
    paper = d.json.papers.find((p) => p.id === paperId);
    if (paper && paper.status === 'graded') break;
  }
  check('批改完成', paper && paper.status === 'graded', paper && paper.status);

  // 初始为空
  r = await call('GET', `/api/questions/${q1.id}/followups`);
  check('初始没有追问记录', r.status === 200 && Array.isArray(r.json) && r.json.length === 0,
    JSON.stringify(r.json).slice(0, 100));

  // 第一问
  r = await call('POST', `/api/questions/${q1.id}/followups`, { content: '为什么中间插入是 O(n)？' });
  check('追问成功', r.status === 200 && r.json.length === 2, JSON.stringify(r.json).slice(0, 200));
  check('一问一答角色正确', r.json[0]?.role === 'user' && r.json[1]?.role === 'assistant',
    JSON.stringify(r.json.map((m) => m.role)));
  check('AI 回答非空', r.json[1]?.content && r.json[1].content.length > 10,
    r.json[1]?.content);

  // 第二问，历史累积
  r = await call('POST', `/api/questions/${q1.id}/followups`, { content: '那扩容呢？' });
  check('历史累积到 4 条', r.status === 200 && r.json.length === 4, r.json.length);

  // 题目隔离：Q2 的聊天记录是空的
  r = await call('GET', `/api/questions/${q2.id}/followups`);
  check('Q2 记录与 Q1 隔离（空）', r.status === 200 && r.json.length === 0, JSON.stringify(r.json).slice(0, 100));
  r = await call('POST', `/api/questions/${q2.id}/followups`, { content: '这道题怎么答更好？' });
  check('Q2 可以独立追问', r.status === 200 && r.json.length === 2, r.json.length);
  r = await call('GET', `/api/questions/${q1.id}/followups`);
  check('Q1 记录不受 Q2 影响', r.json.length === 4, r.json.length);

  // 未作答的题也能追问（卷已批改即可）
  r = await call('POST', `/api/questions/${unanswered.id}/followups`, { content: '这题我没答，应该怎么答？' });
  check('未作答题也能追问', r.status === 200 && r.json.length === 2,
    r.status + ' ' + JSON.stringify(r.json).slice(0, 150));
  const lastPrompt = await fetch('http://127.0.0.1:9099/last?q=' + encodeURIComponent('用户的新问题'))
    .then((x) => x.json()).catch(() => ({ prompt: '' }));
  check('未作答题上下文标注「未作答」', (lastPrompt.prompt || '').includes('（未作答）'),
    (lastPrompt.prompt || '').slice(0, 120));

  // 空问题拦截
  r = await call('POST', `/api/questions/${q1.id}/followups`, { content: '   ' });
  check('空问题被拒绝', r.status === 400, r.status);

  // 清空
  r = await call('DELETE', `/api/questions/${q1.id}/followups`);
  check('清空成功', r.status === 204, r.status);
  r = await call('GET', `/api/questions/${q1.id}/followups`);
  check('清空后 Q1 为空、Q2 不受影响',
    r.json.length === 0 && (await call('GET', `/api/questions/${q2.id}/followups`)).json.length === 2,
    r.json.length);

  // 级联删除：删练习后题目不存在（同时追问记录也被清掉）
  await call('DELETE', `/api/practices/${prId}`);
  r = await call('GET', `/api/questions/${q1.id}/followups`);
  check('删练习后追问接口 404（级联清理）', r.status === 404, r.status);
  await call('DELETE', `/api/stages/${stageId}`);

  await restoreSettings(base);
  console.log(failed ? `FAILED ${failed}` : 'ALL PASS');
  process.exit(failed ? 1 : 0);
})().catch(async (e) => {
  await restoreSettings(base);
  console.error(e);
  process.exit(1);
});
