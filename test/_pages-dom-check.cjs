// 一次性验证：jsdom 挂载 drill/mock/profile/practice 四页，stub 后端 API，断言渲染与交互逻辑
const fs = require('fs');
const path = require('path');
const { JSDOM } = require(require.resolve('jsdom', {
  paths: [path.join(process.env.TEMP || 'C:/Users/liu.yanlin/AppData/Local/Temp', 'vue-check/node_modules')],
}));

const STATIC = 'E:/workspace-review/my-review/src/main/resources/static';
const read = (p) => fs.readFileSync(path.join(STATIC, p), 'utf8');

// ---- 通用 stub 数据 ----
const user = { id: '9', username: 'tester', nickname: '测试', avatarExt: null, hasApiKey: true, apiKeyLast4: 'abcd', hasSummary: true, summaryAt: 1763300000000 };
const stages = [
  { id: '1', name: 'Java 基础', status: 'active', updatedAt: 1, stats: { practiceCount: 3 } },
  { id: '2', name: '数据库', status: 'completed', updatedAt: 2, stats: { practiceCount: 1 } },
];
const practices = [
  { id: 'p1', topic: '集合', review: false, level: 'mid', scopeMode: 'topic', intensity: 'standard', updatedAt: 1763300000000,
    paperCount: 2, questionCount: 5, averageScore: 77.5, openMistakeCount: 2 },
];

function stubFetch(extra) {
  return async (url, opts = {}) => {
    const u = String(url);
    const m = (opts.method || 'GET').toUpperCase();
    const json = (d) => ({ ok: true, status: 200, text: async () => JSON.stringify(d) });
    if (u === '/api/auth/me') return json(user);
    if (u === '/api/stages' && m === 'GET') return json(stages);
    if (u === '/api/stages' && m === 'POST') return json({ id: '9', name: JSON.parse(opts.body).name, status: 'active', updatedAt: 1, stats: { practiceCount: 0 } });
    if (u === '/api/stages/1') return json({ id: '1', name: 'Java 基础', practices });
    if (u === '/api/stages/2') return json({ id: '2', name: '数据库', practices: [] });
    if (u.includes('/practices') && m === 'POST') return json({ id: 'np1' });
    if (u === '/api/me/profile' && m === 'POST') return json({ ...user, nickname: JSON.parse(opts.body).nickname });
    if (u === '/api/me/summary' && m === 'GET') return json({ status: 'idle', error: null, startedAt: null, summary: null, summaryAt: null });
    if (u === '/api/me/llm-configs' && m === 'GET') return json([{ id: 'c1', name: 'Claude', active: true, apiBase: 'https://x', model: 'm1', hasApiKey: true, masked: 'sk-****abcd', apiFormat: 'anthropic', temperature: 1, sharedBy: null }]);
    if (u === '/api/auth/password') return json({ token: 'newtok', expiresAt: 1, user });
    if (extra) { const r = await extra(u, m, opts); if (r) return r; }
    return json(null);
  };
}

function loadPage(htmlFile, jsFile, fetchStub, patchNav) {
  const html = read(htmlFile);
  const dom = new JSDOM(html, {
    url: 'http://localhost:8080/' + htmlFile,
    runScripts: 'outside-only',
    pretendToBeVisual: true,
  });
  dom.window.fetch = fetchStub;
  dom.window.HTMLElement.prototype.scrollIntoView = () => {};
  dom.window.__nav = null;
  for (const f of ['js/icons.js', 'js/speech.js', 'lib/vue/vue.global.prod.js', 'js/common.js', jsFile]) {
    if (!fs.existsSync(path.join(STATIC, f))) continue;
    let src = read(f);
    if (patchNav && (f === jsFile || f === 'js/common.js')) {
      // jsdom 不允许重定义 location：把跳转改写成记录变量（仅测试侧替换）
      src = src.split('location.href = ').join('window.__nav = ')
        .split('location.replace(').join('window.__navReplace(');
    }
    try { dom.window.eval(src); } catch (e) {
      console.error('SCRIPT_THREW ' + f + ':', e.message);
      process.exit(1);
    }
  }
  dom.window.__navReplace = (u) => { dom.window.__nav = u; };
  return dom;
}

const results = [];
const check = (name, ok, extra) => {
  results.push([name, ok]);
  if (!ok) console.error('FAIL:', name, extra || '');
};
const tick = () => new Promise((r) => setTimeout(r, 40));

(async () => {
  /* ================= drill ================= */
  {
    const dom = loadPage('drill.html', 'js/drill.js', stubFetch());
    await tick(); await tick();
    const doc = dom.window.document;
    check('drill: h1 押题', doc.querySelector('h1') && doc.querySelector('h1').textContent === '押题');
    check('drill: Coming soon eyebrow', doc.querySelector('.eyebrow').textContent === 'Coming soon');
    check('drill: soon-card 文案', doc.querySelector('.soon-desc').textContent.includes('根据简历与目标岗位'));
    check('drill: 规划中 tag', doc.querySelector('.soon-tag').textContent === '规划中');
    check('drill: nav 押题 active', doc.querySelector('.nav-item.active').textContent.includes('押题'));
    check('drill: 用户名渲染', doc.querySelector('.user-name').textContent === '测试');
  }

  /* ================= mock ================= */
  {
    const dom = loadPage('mock.html', 'js/mock.js', stubFetch());
    await tick(); await tick();
    const doc = dom.window.document;
    check('mock: h1 模拟面试', doc.querySelector('h1').textContent === '模拟面试');
    check('mock: soon-card 文案', doc.querySelector('.soon-desc').textContent.includes('多轮追问'));
    check('mock: nav 模拟面试 active', doc.querySelector('.nav-item.active').textContent.includes('模拟面试'));
  }

  /* ================= profile ================= */
  {
    const dom = loadPage('profile.html', 'js/profile.js', stubFetch());
    await tick(); await tick();
    const doc = dom.window.document;
    check('profile: h1 个人中心', doc.querySelector('h1').textContent === '个人中心');
    check('profile: 昵称填充', doc.querySelector('input[maxlength="24"]').value === '测试');
    check('profile: LLM 摘要含 Claude', doc.body.textContent.includes('启用：Claude'));
    check('profile: 改密按钮存在', doc.body.textContent.includes('修改密码'));

    // 昵称保存
    const nickInput = doc.querySelector('input[maxlength="24"]');
    nickInput.value = '新昵称';
    nickInput.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
    doc.querySelector('form.setting-row button[type="submit"]').click();
    await tick(); await tick();
    check('profile: 昵称保存后侧栏同步', doc.querySelector('.user-name').textContent === '新昵称');

    // 改密弹窗
    [...doc.querySelectorAll('button')].find((b) => b.textContent.includes('修改密码')).click();
    await tick();
    check('profile: 改密弹窗打开', doc.body.textContent.includes('确认新密码'));
    // 遮罩点击不关（formDialog 语义）
    doc.querySelector('.dialog-backdrop').dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
    await tick();
    check('profile: 改密弹窗遮罩不关', !!doc.querySelector('#password-change-form'));

    // 模型配置弹窗
    [...doc.querySelectorAll('button')].find((b) => b.textContent.trim() === '配置').click();
    await tick(); await tick();
    check('profile: 模型配置列表渲染', doc.body.textContent.includes('sk-****abcd'));
    check('profile: 使用中徽章', doc.body.textContent.includes('使用中'));
  }

  /* ================= practice ================= */
  {
    const dom = loadPage('practice.html', 'js/practice.js', stubFetch(), true);
    await tick(); await tick(); await tick();
    const doc = dom.window.document;
    check('practice: h1 练习', doc.querySelector('h1').textContent === '练习');
    check('practice: chips 2 个阶段', doc.querySelectorAll('.stage-chip').length === 2, String(doc.querySelectorAll('.stage-chip').length));
    check('practice: 默认选中第一个 active', doc.querySelector('.stage-chip.active').textContent.includes('Java 基础'));
    check('practice: 练习卡片渲染', doc.body.textContent.includes('集合'));
    check('practice: 徽章/统计', doc.body.textContent.includes('主题模式') && doc.body.textContent.includes('77.5') && doc.body.textContent.includes('错题'));

    // 切到已完成阶段
    const chips = [...doc.querySelectorAll('.stage-chip')];
    chips[1].click();
    await tick(); await tick();
    check('practice: 切阶段后列表更新', doc.body.textContent.includes('还没有练习'));

    // 打开新建练习弹窗
    [...doc.querySelectorAll('button')].find((b) => b.textContent.includes('新建练习')).click();
    await tick();
    check('practice: 新建弹窗打开', !!doc.querySelector('#practice-create-form'));
    check('practice: 弹窗阶段下拉只列 active', doc.querySelectorAll('#practice-create-form option').length === 1, String(doc.querySelectorAll('#practice-create-form option').length));
    [...doc.querySelectorAll('.dialog-foot .btn-primary')].pop().click();
    await tick();
    check('practice: 空主题拦截', doc.querySelector('.toast-host') && doc.querySelector('.toast-host').textContent.includes('请填写知识点主题'));

    // 填主题提交
    const topic = doc.querySelector('input[placeholder="集合、泛型、反射"]');
    topic.value = 'JVM';
    topic.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
    [...doc.querySelectorAll('.dialog-foot .btn-primary')].pop().click();
    await tick(); await tick();
    check('practice: 创建后跳答题页', dom.window.__nav === 'answer.html?id=np1', String(dom.window.__nav));

    // 嵌套新建阶段
    dom.window.__nav = null;
    [...doc.querySelectorAll('button')].find((b) => b.textContent.includes('新建练习')).click();
    await tick();
    [...doc.querySelectorAll('button')].find((b) => b.textContent.includes('新建阶段') && b.classList.contains('link')).click();
    await tick();
    check('practice: 嵌套新建阶段弹窗', doc.body.textContent.includes('阶段名称'));
  }

  const failed = results.filter(([, ok]) => !ok);
  console.log(failed.length ? `FAILED ${failed.length}/${results.length}` : `ALL_PASS (${results.length} checks)`);
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error('THREW:', e.message, e.stack); process.exit(1); });
