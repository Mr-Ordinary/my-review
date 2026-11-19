// 一次性 API 冒烟：stages.js 依赖的三个接口端到端（register → me → stages 增删）
const BASE = 'http://127.0.0.1:8080';
const j = (r) => r.text().then((t) => (t ? JSON.parse(t) : null));

(async () => {
  const suffix = 'smk' + Date.now().toString(36);
  const username = 'u_' + suffix;
  const password = 'pass1234a';

  const reg = await fetch(BASE + '/api/auth/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const regData = await j(reg);
  if (!reg.ok) { console.error('REGISTER_FAIL', reg.status, regData); process.exit(1); }
  const token = regData.token;
  const H = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token };

  const me = await j(await fetch(BASE + '/api/auth/me', { headers: H }));
  if (!me || me.username !== username) { console.error('ME_FAIL', me); process.exit(1); }

  const created = await j(await fetch(BASE + '/api/stages', {
    method: 'POST', headers: H, body: JSON.stringify({ name: '冒烟阶段' }),
  }));
  if (!created || !created.id) { console.error('CREATE_FAIL', created); process.exit(1); }

  const list = await j(await fetch(BASE + '/api/stages', { headers: H }));
  if (!Array.isArray(list) || !list.some((s) => String(s.id) === String(created.id))) {
    console.error('LIST_FAIL', list); process.exit(1);
  }

  const del = await fetch(BASE + '/api/stages/' + created.id + '/delete', { method: 'POST', headers: H });
  if (!del.ok) { console.error('DELETE_FAIL', del.status); process.exit(1); }

  const list2 = await j(await fetch(BASE + '/api/stages', { headers: H }));
  if (list2.some((s) => String(s.id) === String(created.id))) { console.error('DELETE_NOT_REFLECTED'); process.exit(1); }

  console.log('API_SMOKE_OK');
})().catch((e) => { console.error('THREW:', e.message); process.exit(1); });
