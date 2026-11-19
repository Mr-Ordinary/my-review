// 受控复现：注册 → 登录态下依次打 /api/auth/me、/api/stages（两次），精确切片日志看每步 SQL
const fs = require('fs');
const net = require('net');

const BASE = 'http://127.0.0.1:8080';
const LOG = process.env.TEMP + '/app-cache.log';

const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const json = (r) => r.text().then((t) => (t ? JSON.parse(t) : null));
const mark = () => (fs.readFileSync(LOG, 'utf8').match(/Preparing:/g) || []).length;
function sqlSince(m) {
  const lines = fs.readFileSync(LOG, 'utf8').split('\n');
  const out = [];
  let seen = 0;
  for (const l of lines) {
    if (/Preparing:/.test(l)) seen++;
    if (seen > m && /Preparing:/.test(l)) out.push(l.replace(/.*Preparing: /, ''));
  }
  return out;
}

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

(async () => {
  const suffix = Date.now().toString(36);

  // ① 注册（登录同理：这里必然查库——校验密码/建号）
  let m = mark();
  const reg = await json(await fetch(BASE + '/api/auth/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 't_' + suffix, password: 'pass1234a' }),
  }));
  await wait(1500);
  console.log('--- ① 注册/登录（预期有 SQL：查/写 users）');
  sqlSince(m).forEach((s) => console.log('   ', s.slice(0, 90)));

  const H = { Authorization: 'Bearer ' + reg.token };

  // ② /api/auth/me
  m = mark();
  await json(await fetch(BASE + '/api/auth/me', { headers: H }));
  await wait(1500);
  console.log('--- ② /api/auth/me（预期 0 SQL：走 user:{token} Redis 快照）');
  sqlSince(m).forEach((s) => console.log('   ', s.slice(0, 90)));

  // ③ 首页第一刷
  m = mark();
  await json(await fetch(BASE + '/api/stages', { headers: H }));
  await wait(1500);
  console.log('--- ③ GET /api/stages 第一刷（冷/半冷）');
  const cold = sqlSince(m);
  cold.forEach((s) => console.log('   ', s.slice(0, 90)));
  console.log('    共', cold.length, '条');

  // ④ 首页第二刷
  m = mark();
  await json(await fetch(BASE + '/api/stages', { headers: H }));
  const warm = sqlSince(m);
  await wait(1500);
  console.log('--- ④ GET /api/stages 第二刷（热）：', warm.length, '条');
  warm.forEach((s) => console.log('   ', s.slice(0, 90)));

  // ⑤ 头像接口（无头像时 loadForRead 仍会走一次）
  m = mark();
  await fetch(BASE + '/api/avatars/' + reg.user.id, { headers: H });
  await wait(1500);
  console.log('--- ⑤ GET /api/avatars/{id}：', sqlSince(m).length, '条');
  sqlSince(m).forEach((s) => console.log('   ', s.slice(0, 90)));

  // ⑥ Redis 侧用户键
  const k1 = await redisCmd('KEYS', 'user:*');
  const keys = k1.split('\r\n').filter((l) => l.startsWith('user')).sort();
  console.log('--- ⑥ Redis user 键（该用户）：');
  keys.filter((k) => k.includes(reg.token.slice(0, 12)) || k.startsWith('user_tokens')).forEach((k) => console.log('   ', k.slice(0, 60) + (k.length > 60 ? '…' : '')));
})();
