// 端到端冒烟测试：阶段 → 背八股练习 → 出题 → 作答 → 看答案 → 练习总结 → 阶段总结
// 前置：mock-llm.mjs 已启动，后端 /api/settings 的 apiBase 指向 mock。
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

// 0. 设置指向 mock LLM
let r = await call('PUT', '/api/settings', {
  apiBase: 'http://127.0.0.1:9099', apiKey: 'mock-key', model: 'deepseek-chat', temperature: 1.3,
});
check('PUT /api/settings', r.status === 200 && r.json.apiBase === 'http://127.0.0.1:9099', JSON.stringify(r.json));

// 1. 创建阶段
r = await call('POST', '/api/stages', { name: '春招冲刺', goal: 'Java 后端，两周内过完集合与并发' });
check('POST /api/stages', r.status === 201 && r.json.id && r.json.stats, JSON.stringify(r.json));
const stageId = r.json.id;

// 2. 阶段内创建背八股练习
r = await call('POST', `/api/stages/${stageId}/practices`, { topic: 'Java 集合框架' });
check('POST practices', r.status === 201 && r.json.mode === 'bagu', JSON.stringify(r.json));
const practiceId = r.json.id;

// 3. 出题
r = await call('POST', `/api/practices/${practiceId}/questions`, {});
check('出题', r.status === 200 && r.json.prompt && r.json.sequence === 1, JSON.stringify(r.json));
const questionId = r.json.id;

// 4. 提交作答 → 点评
r = await call('POST', `/api/questions/${questionId}/attempts`, { answer: '1.8 引入了红黑树来优化查询。' });
check('作答+点评', r.status === 200 && r.json.attempts?.length === 1
  && r.json.attempts[0].evaluation?.score === 7.5
  && r.json.attempts[0].answer === '1.8 引入了红黑树来优化查询。', JSON.stringify(r.json));

// 5. 看答案
r = await call('POST', `/api/questions/${questionId}/reveal`, {});
check('看答案', r.status === 200 && r.json.explanation?.memorizationPoints, JSON.stringify(r.json));

// 6. 结束练习 → 练习总结
r = await call('POST', `/api/practices/${practiceId}/complete`, {});
check('练习总结', r.status === 200 && r.json.status === 'completed'
  && r.json.summary?.kind === 'interview' && r.json.summary?.strengths?.length >= 1, JSON.stringify(r.json));

// 7. 阶段详情：stats 应有数据
r = await call('GET', `/api/stages/${stageId}`);
check('阶段详情 stats', r.status === 200 && r.json.stats.practiceCount === 1
  && r.json.stats.questionCount === 1 && r.json.stats.averageScore === 7.5
  && r.json.practices?.length === 1, JSON.stringify(r.json));

// 8. 阶段总结
r = await call('POST', `/api/stages/${stageId}/summary`, {});
check('阶段总结', r.status === 200 && r.json.summary?.keyWeaknesses?.length >= 1
  && r.json.summary?.dimensionScores?.['知识准确性'] === 8, JSON.stringify(r.json));

// 9. 列表 / 设置读取
r = await call('GET', '/api/stages');
check('GET /api/stages', r.status === 200 && r.json.length >= 1 && r.json[0].summary, '');
r = await call('GET', '/api/settings');
check('GET /api/settings', r.status === 200 && r.json.model === 'deepseek-chat', '');

// 10. 错误路径：空主题应 400
r = await call('POST', `/api/stages/${stageId}/practices`, { topic: '' });
check('空主题 400', r.status === 400 && r.json.error, JSON.stringify(r.json));
r = await call('GET', '/api/practices/not-exist');
check('不存在 404', r.status === 404 && r.json.error, JSON.stringify(r.json));

// 11. 清理：删除阶段（连带数据）
r = await call('DELETE', `/api/stages/${stageId}`);
check('DELETE /api/stages/{id}', r.status === 204, String(r.status));

console.log(failed === 0 ? '\nALL PASS' : `\n${failed} FAILED`);
process.exit(failed === 0 ? 0 : 1);
