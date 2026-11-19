// 一次性验证：jsdom 加载 stages.html + 真实跑 Vue 挂载，stub 后端 API，纯 DOM 断言渲染与交互
const fs = require('fs');
const path = require('path');
const { JSDOM } = require(require.resolve('jsdom', {
  paths: [path.join(process.env.TEMP || 'C:/Users/liu.yanlin/AppData/Local/Temp', 'vue-check/node_modules')],
}));

const STATIC = 'E:/workspace-review/my-review/src/main/resources/static';
const read = (p) => fs.readFileSync(path.join(STATIC, p), 'utf8');

const html = read('stages.html');
const dom = new JSDOM(html, {
  url: 'http://localhost:8080/stages.html',
  runScripts: 'outside-only',
  pretendToBeVisual: true,
});
const { window } = dom;

// ---- stub 后端：初始 14 条（分页 2 页），前两条带完整字段 ----
const stages = [
  { id: '1', name: 'Java 基础', status: 'active', updatedAt: 1763300000000, summary: null,
    stats: { practiceCount: 3, paperCount: 2, questionCount: 40, averageScore: 78.5, drillCount: 0, drillAvgScore: null } },
  { id: '2', name: '数据库', status: 'completed', updatedAt: 1763200000000, summary: { ok: 1 },
    stats: { practiceCount: 1, paperCount: 1, questionCount: 10, averageScore: null, drillCount: 4, drillAvgScore: 88 } },
];
for (let i = 0; i < 12; i++) stages.push({ id: 'x' + i, name: 'S' + i, status: 'active', updatedAt: 1, summary: null, stats: {} });
let deleted = [];
window.fetch = async (url, opts = {}) => {
  const u = String(url);
  const method = (opts.method || 'GET').toUpperCase();
  const json = (data) => ({ ok: true, status: 200, text: async () => JSON.stringify(data) });
  if (u === '/api/auth/me') return json({ id: 'u1', username: 'tester', nickname: '测试', avatarExt: null });
  if (u === '/api/stages' && method === 'GET') return json(stages.filter((s) => !deleted.includes(s.id)));
  if (u === '/api/stages' && method === 'POST') {
    const body = JSON.parse(opts.body);
    const s = { id: 'new', name: body.name, status: 'active', updatedAt: Date.now(), summary: null, stats: {} };
    stages.push(s);
    return { ok: true, status: 201, text: async () => JSON.stringify(s) };
  }
  if (/\/api\/stages\/.+\/delete$/.test(u) && method === 'POST') {
    deleted.push(decodeURIComponent(u.split('/')[3]));
    return { ok: true, status: 204, text: async () => '' };
  }
  return { ok: false, status: 404, text: async () => JSON.stringify({ error: 'no route ' + u }) };
};

for (const f of ['js/icons.js', 'lib/vue/vue.global.prod.js', 'js/common.js', 'js/stages.js']) {
  try {
    window.eval(read(f));
  } catch (e) {
    console.error('SCRIPT_THREW ' + f + ':', e.message);
    process.exit(1);
  }
}

const results = [];
const check = (name, ok, extra) => {
  results.push([name, ok]);
  if (!ok) console.error('FAIL:', name, extra || '');
};
const tick = () => new Promise((r) => setTimeout(r, 30));

(async () => {
  await tick(); await tick();
  const doc = window.document;
  const $ = (s) => doc.querySelector(s);
  const $$ = (s) => [...doc.querySelectorAll(s)];

  check('count text 共 14 个', $('.stages-count') && $('.stages-count').textContent === '共 14 个', $('.stages-count') && $('.stages-count').textContent);
  check('page1 shows 12 cards', $$('.item-card').length === 12, String($$('.item-card').length));
  check('card names on page1', $$('.li-title').slice(0, 2).map((e) => e.textContent).join(',') === 'Java 基础,数据库');
  check('badge 进行中', $$('.item-card')[0].textContent.includes('进行中'));
  check('badge 已归档 + 已有总结', $$('.item-card')[1].textContent.includes('已归档') && $$('.item-card')[1].textContent.includes('已有总结'));
  check('avg score dash for null', $$('.item-card')[1].textContent.includes('均分 —'));
  check('avg score value', $$('.item-card')[0].textContent.includes('均分 78.5'));
  check('drill line on stage 2', $$('.item-card')[1].textContent.includes('刷题 4'));
  check('foot time rendered', /更新于 20\d\d-/.test($$('.item-card')[0].textContent), $$('.item-card')[0].textContent);
  check('user panel nickname', $('.user-name') && $('.user-name').textContent === '测试', $('.user-name') && $('.user-name').textContent);
  check('nav stages active', $('.nav-item.active') && $('.nav-item.active').textContent.includes('阶段'));
  check('nav link to practice.html', $$('a.nav-item').some((a) => a.getAttribute('href') === 'practice.html'));

  // ---- 分页（14 条 → 2 页） ----
  check('pager visible', !!$('.list-pager'));
  check('page text 1 / 2', $('.list-pager .num').textContent.trim() === '1 / 2', $('.list-pager .num').textContent);
  check('prev disabled on page1', $$('.list-pager .btn')[0].disabled === true);
  $$('.list-pager .btn')[1].click();
  await tick();
  check('page2 shows 2 cards', $$('.item-card').length === 2, String($$('.item-card').length));
  check('page text 2 / 2', $('.list-pager .num').textContent.trim() === '2 / 2');
  check('next disabled on page2', $$('.list-pager .btn')[1].disabled === true);

  // ---- 新建弹窗（在第 2 页验证 push + 自动跳页） ----
  $$('.btn-strong').find((b) => b.textContent.includes('新建阶段')).click();
  await tick();
  check('create dialog open', !!$('.dialog-backdrop'));
  $('.dialog-backdrop').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  await tick();
  check('mask click does NOT close create dialog', !!$('.dialog-backdrop'));

  const input = $('#stage-create-form input[name="name"]');
  input.value = '  网络 ';
  input.dispatchEvent(new window.Event('input', { bubbles: true }));
  $('.dialog-foot .btn-primary').click();
  await tick(); await tick();
  check('create dialog closed after submit', !$('.dialog-backdrop'));
  check('15 items, on page 2 (3 cards)', $$('.item-card').length === 3, String($$('.item-card').length));
  check('new card appended last, name trimmed', $$('.li-title')[2].textContent === '网络', $$('.li-title')[2] && $$('.li-title')[2].textContent);
  check('count text 共 15 个', $('.stages-count').textContent === '共 15 个', $('.stages-count').textContent);

  // ---- 删除（删掉新增的，回到 14 条） ----
  $$('.item-card-del')[2].click();
  await tick();
  check('delete confirm open', !!$('.dialog-backdrop') && $('.dialog-backdrop').textContent.includes('不可恢复'));
  $('.dialog-backdrop').dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  await tick();
  check('mask click cancels delete', !$('.dialog-backdrop'));

  $$('.item-card-del')[2].click();
  await tick();
  [...doc.querySelectorAll('.dialog-foot .btn-danger')].find((b) => b.textContent.includes('删除')).click();
  await tick(); await tick();
  check('card spliced, page clamped to 2', $('.list-pager .num').textContent.trim() === '2 / 2', $('.list-pager .num') && $('.list-pager .num').textContent);
  check('14 items remain', $('.stages-count').textContent === '共 14 个', $('.stages-count').textContent);
  check('deleted card gone', !doc.body.textContent.includes('网络'));

  const failed = results.filter(([, ok]) => !ok);
  console.log(failed.length ? `FAILED ${failed.length}/${results.length}` : `ALL_PASS (${results.length} checks)`);
  process.exit(failed.length ? 1 : 0);
})();
