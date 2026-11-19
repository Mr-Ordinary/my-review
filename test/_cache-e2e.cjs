// 缓存 e2e：阶段页冷/热 SQL 数、新建练习 evict、删阶段清子树键
const fs = require('fs');
const net = require('net');

const BASE = 'http://127.0.0.1:8080';
const LOG = process.env.TEMP + '/app-cache.log';

function resp(args) { let c = `*${args.length}\r\n`; for (const a of args) c += `$${Buffer.byteLength(String(a))}\r\n${a}\r\n`; return c; }
function redisCmd(...args) {
  return new Promise((res) => {
    const s = net.createConnection({ host: '127.0.0.1', port: 6379 }, () => {
      s.write(resp(['AUTH', '123456']) + resp(args));
    });
    let b = '';
    s.on('data', (d) => { b += d; });
    setTimeout(() => { s.end(); res(b.replace(/^\+OK\r\n/, '')); }, 250);
  });
}

const json = (r) => r.text().then((t) => (t ? JSON.parse(t) : null));
const sqlCount = () => (fs.readFileSync(LOG, 'utf8').match(/Preparing:/g) || []).length;

const results = [];
const check = (name, ok, extra) => {
  results.push([name, ok]);
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? '  [' + extra + ']' : ''));
};

(async () => {
  const suffix = Date.now().toString(36);
  const reg = await json(await fetch(BASE + '/api/auth/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'c_' + suffix, password: 'pass1234a' }),
  }));
  const H = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + reg.token };
  const uid = reg.user.id;

  // 1. 建阶段 → 写路径 evict d:stages
  await json(await fetch(BASE + '/api/stages', { method: 'POST', headers: H, body: JSON.stringify({ name: '缓存验证' }) }));

  // 2. 阶段页第一刷（冷：stages 键被 evict，全链回填）
  let m0 = sqlCount();
  const list1 = await json(await fetch(BASE + '/api/stages', { headers: H }));
  let coldSql = sqlCount() - m0;
  check('cold stages load works', Array.isArray(list1) && list1.length === 1);
  check('cold SQL ≤ 6 (' + coldSql + ')', coldSql > 0 && coldSql <= 6, '原约 47');

  // 3. 二刷（热：应 0 SQL）
  m0 = sqlCount();
  await json(await fetch(BASE + '/api/stages', { headers: H }));
  const warmSql = sqlCount() - m0;
  check('warm stages load = 0 SQL (' + warmSql + ')', warmSql === 0);

  // 4. 新建练习 → evictStagePractices
  const stageId = list1[0].id;
  const before = await redisCmd('EXISTS', 'd:stage_prs:' + stageId);
  check('stage_prs cached before write', /:1/.test(before), before.trim());
  await json(await fetch(BASE + '/api/stages/' + stageId + '/practices', {
    method: 'POST', headers: H,
    body: JSON.stringify({ topic: 'evict验证', scopeMode: 'topic', level: 'mid', intensity: 'standard' }),
  }));
  const after = await redisCmd('EXISTS', 'd:stage_prs:' + stageId);
  check('practice create evicts stage_prs', /:0/.test(after), after.trim());

  // 5. 再刷列表：统计反映新练习（practiceCount=1）且键回填
  const list2 = await json(await fetch(BASE + '/api/stages', { headers: H }));
  check('stats recomputed after write', list2[0].stats.practiceCount === 1, JSON.stringify(list2[0].stats));
  const refilled = await redisCmd('EXISTS', 'd:stage_prs:' + stageId);
  check('stage_prs repopulated on read-miss', /:1/.test(refilled), refilled.trim());

  // 6. 删阶段 → 子树键清理
  await fetch(BASE + '/api/stages/' + stageId + '/delete', { method: 'POST', headers: H });
  const e1 = await redisCmd('EXISTS', 'd:stages:' + uid);
  const e2 = await redisCmd('EXISTS', 'd:stage_prs:' + stageId);
  check('delete stage cleans subtree keys', /:0/.test(e1) && /:0/.test(e2), e1.trim() + '/' + e2.trim());

  const failed = results.filter(([, ok]) => !ok);
  console.log(failed.length ? `FAILED ${failed.length}/${results.length}` : `ALL_PASS (${results.length} checks)`);
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error('THREW:', e.message); process.exit(1); });
