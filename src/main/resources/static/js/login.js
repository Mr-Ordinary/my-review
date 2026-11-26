/* ============================================================
   MyReview 登录 / 注册页
   独立于工作台（app.js）：只做认证，成功后把 token 写入
   localStorage['mr.token'] 并跳回 index.html。
   ============================================================ */
(function () {
  'use strict';

  const TOKEN_KEY = 'mr.token';
  const $ = (sel, root = document) => root.querySelector(sel);

  function getToken() {
    try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }
  function setToken(t) {
    try { if (t) localStorage.setItem(TOKEN_KEY, t); else localStorage.removeItem(TOKEN_KEY); } catch (e) { /* 隐私模式忽略 */ }
  }

  /** 轻提示（复用 app.css 的 .toast 样式；图标走 icons.js 线性图标，一套 16px） */
  function toast(msg, type) {
    let host = $('.toast-host');
    if (!host) {
      host = document.createElement('div');
      host.className = 'toast-host';
      document.body.appendChild(host);
    }
    while (host.children.length >= 3) host.firstElementChild.remove();
    const Icons = window.Icons || { get: () => '' };
    const icons = { info: 'info', success: 'check', error: 'close', warning: 'info' };
    const el = document.createElement('div');
    el.className = 'toast toast-' + (type || 'info');
    el.innerHTML = `
      <span class="toast-icon">${Icons.get(icons[type] || icons.info, 16)}</span>
      <div class="toast-body"><div class="toast-msg"></div></div>`;
    el.querySelector('.toast-msg').textContent = msg;
    host.appendChild(el);
    setTimeout(() => {
      el.classList.add('leaving');
      setTimeout(() => el.remove(), 260);
    }, type === 'error' ? 5200 : 3200);
  }

  /** 按钮忙态：转圈 + 禁用，出错弹 toast */
  async function busy(btn, text, fn) {
    const original = btn.innerHTML;
    const wasDisabled = btn.disabled;
    btn.disabled = true;
    btn.innerHTML = `<span class="spinner"></span> ${text}`;
    try {
      await fn();
    } catch (err) {
      toast(err && err.message ? err.message : String(err), 'error');
    } finally {
      if (document.contains(btn)) {
        btn.disabled = wasDisabled;
        btn.innerHTML = original;
      }
    }
  }

  async function api(path, body) {
    let res;
    try {
      res = await fetch(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
    } catch (e) {
      throw new Error('无法连接后端服务');
    }
    let data = null;
    const text = await res.text();
    if (text) { try { data = JSON.parse(text); } catch (e) { data = null; } }
    if (!res.ok) throw new Error((data && data.error) ? data.error : `请求失败（HTTP ${res.status}）`);
    return data;
  }

  /* ---- 登录 / 注册切换（文字 + 琥珀短线标签页） ---- */
  const seg = $('#auth-seg');
  const loginForm = $('#login-form');
  const regForm = $('#register-form');
  const title = $('#auth-title');
  seg.addEventListener('click', (e) => {
    const btn = e.target.closest('button[data-value]');
    if (!btn) return;
    seg.querySelectorAll('button').forEach((b) => b.classList.toggle('active', b === btn));
    const isLogin = btn.dataset.value === 'login';
    if (title) title.textContent = isLogin ? '登录' : '注册';
    loginForm.style.display = isLogin ? '' : 'none';
    regForm.style.display = isLogin ? 'none' : '';
  });

  /* ---- 左栏卷面卡轮播：出题 → 答题 → 批改 → 复习，缓慢交替 ---- */
  const cards = Array.from(document.querySelectorAll('.scene-card'));
  const steps = Array.from(document.querySelectorAll('.step-item'));
  let step = 0;
  let rotateTimer = null;

  function showStep(n) {
    step = ((n % cards.length) + cards.length) % cards.length;
    cards.forEach((c, i) => c.classList.toggle('is-active', i === step));
    steps.forEach((d, i) => {
      d.classList.toggle('is-active', i === step);
      d.setAttribute('aria-selected', i === step ? 'true' : 'false');
    });
  }

  function stopRotate() {
    if (rotateTimer) { clearInterval(rotateTimer); rotateTimer = null; }
  }

  function startRotate() {
    stopRotate();
    // 系统开了减弱动效就停在第一张，不自动轮播
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    rotateTimer = setInterval(() => showStep(step + 1), 4200);
  }

  steps.forEach((d, i) => d.addEventListener('click', () => { showStep(i); startRotate(); }));
  const stack = document.querySelector('.scene-stack');
  if (stack) {
    stack.addEventListener('mouseenter', stopRotate);   // 悬停暂停，方便读卡
    stack.addEventListener('mouseleave', startRotate);
  }
  showStep(0);
  startRotate();

  /* ---- 提交 ---- */
  async function submit(form, isLogin) {
    const fd = new FormData(form);
    const g = (k) => String(fd.get(k) === null || fd.get(k) === undefined ? '' : fd.get(k)).trim();
    const username = g('username');
    const password = g('password');
    if (!username || !password) return toast('请输入用户名和密码', 'warning');
    if (!isLogin && password !== g('password2')) return toast('两次输入的密码不一致', 'warning');
    const btn = form.querySelector('button[type="submit"]');
    await busy(btn, isLogin ? '登录中…' : '注册中…', async () => {
      // 登录/注册都直接返回 { token, expiresAt, user }
      const r = isLogin
        ? await api('/api/auth/login', { username, password })
        : await api('/api/auth/register', { username, password });
      setToken(r.token);
      toast(isLogin ? '登录成功' : '注册成功，已自动登录', 'success');
      location.replace('index.html');
    });
  }
  loginForm.addEventListener('submit', (e) => { e.preventDefault(); submit(loginForm, true); });
  regForm.addEventListener('submit', (e) => { e.preventDefault(); submit(regForm, false); });

  /* ---- 启动：已有有效 token 直接进工作台 ---- */
  (async function boot() {
    if (!getToken()) return;
    try {
      const res = await fetch('/api/auth/me', { headers: { Authorization: 'Bearer ' + getToken() } });
      if (res.ok) location.replace('index.html');
    } catch (e) { /* 后端不可达就留在登录页 */ }
  })();
})();
