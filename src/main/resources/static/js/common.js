/* ============================================================
   MyReview 提取页公共助手（window.MRCommon）
   各独立页面（stages.html 等）共用：token / REST / toast / 忙态 /
   头像 / 字号 / 吸顶页头 / 导航项 / 退出。
   行为与 app.js 内部实现保持一致；app.js 暂不改用本文件。
   ============================================================ */
(function () {
  'use strict';

  const TOKEN_KEY = 'mr.token';

  function getToken() {
    try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }
  function setToken(t) {
    try { if (t) localStorage.setItem(TOKEN_KEY, t); else localStorage.removeItem(TOKEN_KEY); } catch (e) { /* 隐私模式忽略 */ }
  }

  const esc = (s) => String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
  const num = (v) => (v === null || v === undefined ? '-' : v);
  const fmtTime = (ts) => {
    if (!ts) return '—';
    const d = new Date(ts);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };
  const I = () => window.Icons || { get: () => '' };

  /** REST：自动 JSON body、Bearer token、401 清 token 回登录页 */
  async function api(path, options = {}) {
    const opts = Object.assign({}, options);
    const isForm = opts.body instanceof FormData;
    // multipart 的 Content-Type 必须交给浏览器带 boundary，不能手动指定
    opts.headers = isForm
      ? Object.assign({}, options.headers)
      : Object.assign({ 'Content-Type': 'application/json' }, options.headers);
    const token = getToken();
    if (token) opts.headers.Authorization = 'Bearer ' + token;
    // body 允许传对象——统一 JSON.stringify，防止 fetch 把对象转成 "[object Object]"
    if (opts.body != null && typeof opts.body !== 'string' && !isForm) {
      opts.body = JSON.stringify(opts.body);
    }
    let res;
    try {
      res = await fetch(path, opts);
    } catch (err) {
      throw new Error('无法连接后端服务');
    }
    let data = null;
    const text = await res.text();
    if (text) { try { data = JSON.parse(text); } catch (e) { data = null; } }
    if (res.status === 401) {
      // token 无效/过期：清掉本地 token，回独立登录页
      setToken('');
      location.replace('login.html');
    }
    if (!res.ok) throw new Error((data && data.error) ? data.error : `请求失败（HTTP ${res.status}）`);
    return data;
  }

  /* ---- toast（复用 app.css 的 .toast-host 样式） ---- */
  function toast(msg, type, title) {
    const icons = { info: 'info', success: 'check', error: 'close', warning: 'info' };
    let host = document.querySelector('.toast-host');
    if (!host) {
      host = document.createElement('div');
      host.className = 'toast-host';
      document.body.appendChild(host);
    }
    const key = (type || 'info') + '|' + (title || '') + '|' + msg;
    [...host.children].forEach((c) => { if (c.dataset.key === key) c.remove(); });
    while (host.children.length >= 3) host.firstElementChild.remove();
    const el = document.createElement('div');
    el.className = 'toast toast-' + (type || 'info');
    el.dataset.key = key;
    el.innerHTML = `
      <span class="toast-icon">${I().get(icons[type] || 'info', 16)}</span>
      <div class="toast-body">
        ${title ? `<div class="toast-title">${esc(title)}</div>` : ''}
        <div class="toast-msg">${esc(msg)}</div>
      </div>`;
    host.appendChild(el);
    const life = type === 'error' ? 5200 : 3200;
    setTimeout(() => {
      el.classList.add('leaving');
      setTimeout(() => el.remove(), 260);
    }, life);
  }

  /** 按钮忙态：转圈 + 禁用，出错弹 toast */
  async function busy(btn, text, fn) {
    const original = btn.innerHTML;
    const wasDisabled = btn.disabled;
    btn.disabled = true;
    btn.innerHTML = `<span class="spinner"></span> ${esc(text)}`;
    try {
      await fn();
    } catch (err) {
      toast(err && err.message ? err.message : String(err), 'error', '操作失败');
    } finally {
      if (document.contains(btn)) {
        btn.disabled = wasDisabled;
        btn.innerHTML = original;
      }
    }
  }

  /* ---- 头像：<img> 不带 Authorization，带 token fetch → blob URL ---- */
  const avatarCache = {};
  async function loadAvatar(userId) {
    const key = String(userId);
    if (avatarCache[key]) return avatarCache[key];
    const token = getToken();
    try {
      const res = await fetch('/api/avatars/' + encodeURIComponent(key), {
        headers: token ? { Authorization: 'Bearer ' + token } : {},
      });
      if (!res.ok) return '';
      const url = URL.createObjectURL(await res.blob());
      avatarCache[key] = url;
      return url;
    } catch (e) {
      return '';
    }
  }

  /* ---- 字号：html[data-fs] 三档，存 localStorage ---- */
  function initFontSize() {
    let s = 'standard';
    try { s = localStorage.getItem('mr.fontSize') || 'standard'; } catch (e) { /* 忽略 */ }
    if (s !== 'small' && s !== 'large') s = 'standard';
    document.documentElement.dataset.fs = s;
  }

  /* ---- 主题：html[data-theme] = light/dark（留空 = 跟随系统），存 localStorage ---- */
  function initTheme() {
    let t = '';
    try { t = localStorage.getItem('mr.theme') || ''; } catch (e) { /* 忽略 */ }
    if (t !== 'light' && t !== 'dark') {
      delete document.documentElement.dataset.theme;   // 跟随系统
      return;
    }
    document.documentElement.dataset.theme = t;
  }

  /** setTheme(null) = 跟随系统；('light'/'dark') = 手动指定 */
  function setTheme(t) {
    try {
      if (t === 'light' || t === 'dark') localStorage.setItem('mr.theme', t);
      else localStorage.removeItem('mr.theme');
    } catch (e) { /* 忽略 */ }
    initTheme();
  }

  /* ---- 吸顶页头：滚过页头原始位置后切换 mini 条 ----
     进入 1px / 解除 40px 的迟滞判定与占位块逻辑与 app.js 一致，
     selector 参数化（app.js 里绑定在 #view 上）。
     重测（measure）后用严格阈值重新判定，不走迟滞——否则测量时
     元素还隐藏着（v-cloak）导致的误入 mini 会被迟滞锁死。 */
  function initStickyHead(headSelector) {
    let headTop = Infinity;
    let raf = 0;
    /** mini 时页头脱离文档流（fixed 横跨全宽），用占位块补上高度 */
    let spacer = null;

    const headEl = () => document.querySelector(headSelector);

    function update(strict) {
      const h = headEl();
      if (!h) return;
      // 判定带迟滞：贴着阈值反复进出会让标题吸顶来回切，每次切换都伴随高度读写
      const isMini = h.classList.contains('mini');
      const exitAt = Math.max(0, headTop - 40);
      const mini = (isMini && !strict) ? window.scrollY > exitAt : window.scrollY >= headTop - 1;
      if (mini === isMini) return;
      if (mini) {
        // 先量文档流里的高度（含上下 margin），脱离前写进占位块，内容才不会往上跳
        const cs = getComputedStyle(h);
        const height = h.getBoundingClientRect().height
          + (parseFloat(cs.marginTop) || 0) + (parseFloat(cs.marginBottom) || 0);
        if (!spacer) {
          spacer = document.createElement('div');
          spacer.className = 'sticky-head-spacer';
          h.parentNode.insertBefore(spacer, h.nextSibling);
        }
        spacer.style.height = height + 'px';   // 不取整：分数像素残差会让文档高度差 1px
        h.classList.add('mini');
      } else {
        h.classList.remove('mini');
        if (spacer) spacer.style.height = '0px';
      }
    }

    const measure = () => {
      const h = headEl();
      // 隐藏态（v-cloak display:none）下 rect 全 0，测出来的位置不可信——下帧重测
      if (h && !h.getClientRects().length) {
        requestAnimationFrame(measure);
        return;
      }
      spacer = document.querySelector('.sticky-head-spacer');
      // mini（fixed）时页头 rect.top 已脱离文档流，直接量会把阈值写成 scrollY、再也解不开——
      // 改量占位块（它就躺在页头原来的文档位置上）
      const ref = (h && h.classList.contains('mini') && spacer) ? spacer : h;
      headTop = ref ? ref.getBoundingClientRect().top + window.scrollY : Infinity;
      update(true);
    };
    window.addEventListener('scroll', () => {
      if (raf) return;
      raf = requestAnimationFrame(() => { raf = 0; update(false); });
    }, { passive: true });
    window.addEventListener('resize', measure, { passive: true });
    measure();
    return measure;   // 页面数据异步渲染完后可再调一次重新测量
  }

  /* ---- 导航项：提取页各自成页；会话/详情等未拆页面指回 index.html 的 hash 路由 ---- */
  const NAV_ITEMS = [
    { section: '学习' },
    { route: 'stages',  href: 'stages.html',           icon: 'folder', label: '阶段' },
    { route: 'practice',href: 'practice.html',         icon: 'book',   label: '练习' },
    { section: '进阶' },
    { route: 'drill',   href: 'drill.html',            icon: 'target', label: '押题' },
    { route: 'mock',    href: 'mock.html',             icon: 'mic',    label: '模拟面试' },
    { section: '系统' },
    { route: 'profile', href: 'profile.html',          icon: 'user',   label: '个人中心' },
  ];

  /** 退出登录：服务端撤销 + 清本地 token + 回登录页（确认框由页面自己做） */
  async function logout() {
    try { await api('/api/auth/logout', { method: 'POST' }); } catch (err) { /* token 可能已过期 */ }
    setToken('');
    location.replace('login.html');
  }

  window.MRCommon = {
    getToken, setToken, api, esc, num, fmtTime,
    toast, busy, loadAvatar,
    initFontSize, initStickyHead, initTheme, setTheme,
    NAV_ITEMS, logout,
  };
})();
