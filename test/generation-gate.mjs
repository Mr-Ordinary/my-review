// 生成质量闸门 + 今日进度验收：
//   1. 质检会真的被调用（mock 回读「请检查下面这批题」）
//   2. 被质检打回的题会重出，最后一轮放行，卷子不会卡死（高可用）
//   3. /knowledge 返回覆盖/掌握/待巩固/阶段
// 前置：mock-llm.mjs + 后端已启动（BASE 可指定端口）。
// 用法：node test\generation-gate.mjs
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

  // 题目 topic 带「质检测试」→ mock 会给第一题埋 [[badq]]，质检应打回
  r = await call('POST', '/api/stages', { name: '生成闸门验收' });
  const stageId = r.json.id;
  r = await call('POST', `/api/stages/${stageId}/practices`, {
    topic: '质检测试 java集合', scopeMode: 'topic', level: 'mid',
    intensity: 'standard', paperSize: 6,
  });
  const prId = r.json.id;
  r = await call('POST', `/api/practices/${prId}/papers`, { mode: 'regular' });
  const paperId = r.json.papers[0].id;

  let paper = null;
  for (let i = 0; i < 80; i++) {
    await sleep(3000);
    const d = await call('GET', `/api/practices/${prId}`);
    paper = d.json.papers.find((p) => p.id === paperId);
    if (paper && paper.status !== 'generating') break;
  }
  const qs = paper.questions || [];
  check('质检打回后仍能出完卷（高可用）', paper.status === 'answering', paper.status);
  check('题量 = 计划的 6', qs.length === 6, qs.length);
  check('每道题都有题面与参考答案',
    qs.every((q) => q.prompt && q.referenceAnswer), '');

  // 质检确实被调用过（mock 回读）
  const last = await fetch('http://127.0.0.1:9099/last?q=' + encodeURIComponent('请检查下面这批题'))
    .then((x) => x.json()).catch(() => ({ prompt: null }));
  check('质检调用发生过', !!last.prompt, JSON.stringify(last).slice(0, 120));

  // 今日进度：还没作答 → 覆盖 0，全部未考，阶段=coverage
  const k = await call('GET', `/api/practices/${prId}/knowledge`);
  const st = k.json.stats || {};
  check('知识点清单已建立', (k.json.points || []).length > 0, (k.json.points || []).length);
  check('stats 有 weak/unseen/stage 字段',
    st.weak !== undefined && st.unseen !== undefined && st.stage, JSON.stringify(st));
  check('未作答时阶段=coverage', st.stage === 'coverage', st.stage);
  check('未作答时覆盖=0、未考=总点数', st.covered === 0 && st.unseen === st.total, JSON.stringify(st));

  // 场景 2：质检服务故障 → 必须 fail-open，卷子照常出完
  const crash = await generateScenario(stageId, '质检崩溃测试 java集合');
  check('质检故障时放行（fail-open）', crash.status === 'answering', crash.status);
  check('质检故障时题量仍完整', (crash.questions || []).length === 6, (crash.questions || []).length);

  // 场景 3：AI 输出被截断 → 救回完整题，缺的补齐
  const truncated = await generateScenario(stageId, '截断测试 java集合');
  check('JSON 截断后仍能出完卷', truncated.status === 'answering', truncated.status);
  check('截断拯救后题量仍完整', (truncated.questions || []).length === 6, (truncated.questions || []).length);

  // 清理本脚本创建的所有练习/阶段（只删自己创建的，不碰用户数据）
  const detail = await call('GET', `/api/stages/${stageId}`);
  for (const p of detail.json.practices || []) await call('DELETE', `/api/practices/${p.id}`);
  await call('DELETE', `/api/stages/${stageId}`);
  await restoreSettings(base);
  console.log(failed ? `FAILED ${failed}` : 'ALL PASS');
  process.exit(failed ? 1 : 0);
})().catch(async (e) => {
  await restoreSettings(base);
  console.error(e);
  process.exit(1);
});

/** 建练习 → 出卷 → 等出完，返回最终卷 */
async function generateScenario(stageId, topic) {
  const pr = await call('POST', `/api/stages/${stageId}/practices`, {
    topic, scopeMode: 'topic', level: 'mid', intensity: 'standard', paperSize: 6,
  });
  const prId = pr.json.id;
  const started = await call('POST', `/api/practices/${prId}/papers`, { mode: 'regular' });
  const paperId = started.json.papers[0].id;
  let paper = null;
  for (let i = 0; i < 100; i++) {
    await sleep(3000);
    const d = await call('GET', `/api/practices/${prId}`);
    paper = d.json.papers.find((p) => p.id === paperId);
    if (paper && paper.status !== 'generating') break;
  }
  return paper || { status: 'missing', questions: [] };
}
