// Redis 鉴权 e2e：登录写缓存 → 校验 key/TTL/值（无 passwordHash）→ 改昵称两步同步（会话不掉、双 token 都更新）
// → 改密撤销全部 → 登出删 key。Redis 检查走裸 RESP（redis-cli 不一定在 PATH）。
const net = require('net');

const BASE = 'http://127.0.0.1:8080';
const REDIS = { host: '127.0.0.1', port: 6379, password: '123456' };

function resp(args) {
  let cmd = `*${args.length}\r\n`;
  for (const a of args) cmd += `$${Buffer.byteLength(String(a))}\r\n${a}\r\n`;
  return cmd;
}

/** 连接 → AUTH + 业务命令（一条 TCP 写两条 RESP），返回业务命令的原始响应 */
function redisCmd(...args) {
  return new Promise((resolve, reject) => {
    const s = net.createConnection({ host: REDIS.host, port: REDIS.port }, () => {
      s.write(resp(['AUTH', REDIS.password]) + resp(args));
    });
    let buf = '';
    s.on('data', (d) => { buf += d; });
    s.on('error', reject);
    // 稍等响应齐全再关（两条响应：+OK 与业务响应）
    setTimeout(() => { s.end(); resolve(buf.replace(/^\+OK\r\n/, '')); }, 120);
  });
}

const redisAuth = redisCmd;

const json = (r) => r.text().then((t) => (t ? JSON.parse(t) : null));

const results = [];
const check = (name, ok, extra) => {
  results.push([name, ok]);
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? '  [' + extra + ']' : ''));
};

(async () => {
  const suffix = Date.now().toString(36);
  const username = 'r_' + suffix;
  const password = 'pass1234a';

  // 1. 注册 → token1
  const reg = await json(await fetch(BASE + '/api/auth/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  }));
  check('register returns token', !!reg.token);
  const t1 = reg.token;

  // 2. Redis：user:{t1} 存在、TTL≈6h、值含用户信息且无 passwordHash
  const val1 = await redisAuth('GET', 'user:' + t1);
  check('user:{t1} exists in Redis', val1.includes('"username":"' + username + '"'), val1.slice(0, 80));
  check('value has no passwordHash', !val1.includes('passwordHash') && !val1.includes('argon2'));
  const ttl1 = await redisAuth('TTL', 'user:' + t1);
  const ttlVal = parseInt(ttl1.replace(/[^0-9]/g, '') || '0', 10);
  check('TTL ~6h (' + ttlVal + 's)', ttlVal > 20000 && ttlVal <= 21600);
  const idx1 = await redisAuth('SMEMBERS', 'user_tokens:' + reg.user.id);
  check('user_tokens index contains t1', idx1.includes(t1.slice(0, 20)));

  // 3. /api/auth/me 全走缓存（功能正确性；零 SQL 由 DEBUG 日志人工确认）
  const me1 = await json(await fetch(BASE + '/api/auth/me', { headers: { Authorization: 'Bearer ' + t1 } }));
  check('/api/auth/me works from cache', me1.username === username, JSON.stringify(me1));

  // 4. 同用户再登录 → token2（双 token）
  const login = await json(await fetch(BASE + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  }));
  const t2 = login.token;
  check('second login gets new token', !!t2 && t2 !== t1);

  // 5. 改昵称 → 两步同步：两个 token 的值都更新，TTL 只减流逝时间，会话不掉
  const nick = 'N' + suffix;
  const upd = await json(await fetch(BASE + '/api/me/profile', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t1 },
    body: JSON.stringify({ nickname: nick }),
  }));
  check('nickname update ok', upd.nickname === nick);
  const v1b = await redisAuth('GET', 'user:' + t1);
  const v2b = await redisAuth('GET', 'user:' + t2);
  check('t1 cache value updated', v1b.includes('"nickname":"' + nick + '"'));
  check('t2 cache value updated (multi-token sync)', v2b.includes('"nickname":"' + nick + '"'));
  const ttl2 = parseInt((await redisAuth('TTL', 'user:' + t1)).replace(/[^0-9]/g, '') || '0', 10);
  check('TTL preserved (not reset)', ttl2 <= ttlVal, ttl2 + ' <= ' + ttlVal);
  const meAfter = await fetch(BASE + '/api/auth/me', { headers: { Authorization: 'Bearer ' + t2 } });
  check('session t2 alive after update', meAfter.status === 200);

  // 6. 改密 → 旧 token 全部 401，只剩新 token
  const pw = await json(await fetch(BASE + '/api/auth/password', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t1 },
    body: JSON.stringify({ oldPassword: password, newPassword: 'pass1234b' }),
  }));
  check('password change returns new token', !!pw.token);
  const old1 = await fetch(BASE + '/api/auth/me', { headers: { Authorization: 'Bearer ' + t1 } });
  const old2 = await fetch(BASE + '/api/auth/me', { headers: { Authorization: 'Bearer ' + t2 } });
  check('old tokens dead after password change', old1.status === 401 && old2.status === 401);
  const e1 = await redisAuth('EXISTS', 'user:' + t1);
  const e2 = await redisAuth('EXISTS', 'user:' + t2);
  const e3 = await redisAuth('EXISTS', 'user:' + pw.token);
  const has = (out) => /:1(\D|$)/.test(out);
  check('old keys gone, new key alive', !has(e1) && !has(e2) && has(e3), e1.trim() + '/' + e2.trim() + '/' + e3.trim());

  // 7. 登出 → key 删除 + 索引 SREM
  await fetch(BASE + '/api/auth/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + pw.token } });
  const gone = await redisAuth('EXISTS', 'user:' + pw.token);
  check('logout deletes key', gone.includes(':0'));
  const idx3 = await redisAuth('SMEMBERS', 'user_tokens:' + reg.user.id);
  check('index cleaned (or absent)', !idx3.includes(pw.token.slice(0, 20)));

  const failed = results.filter(([, ok]) => !ok);
  console.log(failed.length ? `FAILED ${failed.length}/${results.length}` : `ALL_PASS (${results.length} checks)`);
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error('THREW:', e.message); process.exit(1); });
