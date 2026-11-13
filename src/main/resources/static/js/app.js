/* ============================================================
   MyReview 前端主逻辑
   hash 路由 + REST 交互；界面走「工程工具」基调：冷中性灰阶 + 单一强调色，
   动效短而克制。全工程不使用 alert/confirm，统一走 toast / dialog。
   ============================================================ */
(function () {
  'use strict';

  /* ============================================================
   *  基础工具
   * ============================================================ */
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const esc = (s) => String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
  /** 富文本渲染：HTML 转义后支持 **加粗** 和换行（含字面 \n 序列），用于参考答案 / AI 点评 */
  const rich = (s) => esc(s)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\\n/g, '\n')   // 模型双重转义的字面 \n → 真换行
    .replace(/\n/g, '<br>');
  /** AI 点评渲染：识别【记忆点】标记，把记忆点单独渲染成卡片 */
  const richFeedback = (s) => {
    const text = String(s || '');
    const idx = text.indexOf('【记忆点】');
    if (idx < 0) return `<div class="review-text">${rich(text)}</div>`;
    const main = text.slice(0, idx).trim();
    const memBlock = text.slice(idx + 5).trim();
    const mems = memBlock.split('\n').map(l => l.replace(/^\d+\.\s*/, '').trim()).filter(Boolean);
    return `<div class="review-text">${rich(main)}</div>
      <div class="mem-card">
        <div class="mem-title">📌 记忆点</div>
        ${mems.map((m, i) => `<div class="mem-item"><b>${i + 1}</b> ${rich(m)}</div>`).join('')}
      </div>`;
  };
  const enc = encodeURIComponent;
  const num = (v) => (v === null || v === undefined ? '-' : v);
  const fmtTime = (ts) => {
    if (!ts) return '—';
    const d = new Date(ts);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };
  const I = window.Icons || { get: () => '' };

  /* ============================================================
   *  UI：toast / dialog（替代 alert / confirm）
   * ============================================================ */
  const UI = (() => {
    let host = null;

    function ensureHost() {
      if (host && document.body.contains(host)) return host;
      host = document.createElement('div');
      host.className = 'toast-host';
      document.body.appendChild(host);
      return host;
    }

    /** 轻提示：type = info | success | error | warning */
    function toast(msg, type, title) {
      const icons = { info: 'info', success: 'check', error: 'close', warning: 'info' };
      const host = ensureHost();
      const key = (type || 'info') + '|' + (title || '') + '|' + msg;
      // 同样的提示不重复堆叠；最多同时显示 3 条，旧的先移除
      [...host.children].forEach((c) => { if (c.dataset.key === key) c.remove(); });
      while (host.children.length >= 3) host.firstElementChild.remove();
      const el = document.createElement('div');
      el.className = 'toast toast-' + (type || 'info');
      el.dataset.key = key;
      el.innerHTML = `
        <span class="toast-icon">${I.get(icons[type] || 'info', 16)}</span>
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

    /** 确认框，返回 Promise<boolean> */
    function confirm(opts) {
      const o = opts || {};
      return new Promise((resolve) => {
        const backdrop = document.createElement('div');
        backdrop.className = 'dialog-backdrop';
        backdrop.innerHTML = `
          <div class="dialog" role="dialog" aria-modal="true">
            <div class="dialog-head">
              <div class="dialog-title">
                ${I.get(o.danger ? 'info' : 'check', 17)} ${esc(o.title || '请确认')}
              </div>
            </div>
            <div class="dialog-body">${o.html ? o.body : esc(o.body || '')}</div>
            <div class="dialog-foot">
              <button class="btn btn-ghost" data-act="cancel">${esc(o.cancelText || '取消')}</button>
              <button class="btn ${o.danger ? 'btn-danger' : 'btn-primary'}" data-act="ok">
                ${esc(o.okText || '确定')}
              </button>
            </div>
          </div>`;
        document.body.appendChild(backdrop);

        let closed = false;
        const close = (val) => {
          if (closed) return;
          closed = true;
          document.removeEventListener('keydown', onKey);
          backdrop.classList.add('leaving');
          setTimeout(() => backdrop.remove(), 220);
          resolve(val);
        };
        backdrop.addEventListener('click', (e) => {
          const act = e.target.closest('[data-act]');
          if (act) return close(act.dataset.act === 'ok');
          if (!e.target.closest('.dialog')) close(false);
        });
        const onKey = (e) => {
          if (e.key === 'Escape') close(false);
          if (e.key === 'Enter') close(true);
        };
        document.addEventListener('keydown', onKey);
        setTimeout(() => { const b = backdrop.querySelector('[data-act="ok"]'); if (b) b.focus(); }, 40);
      });
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

    /**
     * 多行输入弹窗（快速记笔记用），返回 Promise<string|null>（null=取消）。
     * 按键与 confirm 不同：**禁止裸 Enter 确认**——会吃掉换行。
     * Escape 取消，Ctrl/Cmd+Enter 确认。
     */
    function prompt(opts) {
      const o = opts || {};
      return new Promise((resolve) => {
        const backdrop = document.createElement('div');
        backdrop.className = 'dialog-backdrop';
        backdrop.innerHTML = `
          <div class="dialog dialog-wide" role="dialog" aria-modal="true">
            <div class="dialog-head">
              <div class="dialog-title">${I.get('chat', 17)} ${esc(o.title || '记一笔')}</div>
            </div>
            <div class="dialog-body">
              ${o.hint ? `<div class="prompt-hint">${esc(o.hint)}</div>` : ''}
              <textarea id="prompt-input" rows="4" maxlength="${o.maxLength || 2000}"
                placeholder="${esc(o.placeholder || '随手记下你的想法…')}"></textarea>
            </div>
            <div class="dialog-foot">
              <span class="prompt-tip sub">Ctrl+Enter 保存</span>
              <button class="btn btn-ghost" data-act="cancel">取消</button>
              <button class="btn btn-primary" data-act="ok">保存</button>
            </div>
          </div>`;
        document.body.appendChild(backdrop);
        const input = backdrop.querySelector('#prompt-input');
        if (o.value) input.value = o.value;

        let closed = false;
        const close = (val) => {
          if (closed) return;
          closed = true;
          document.removeEventListener('keydown', onKey);
          backdrop.classList.add('leaving');
          setTimeout(() => backdrop.remove(), 220);
          resolve(val);
        };
        // 点遮罩关闭：用 closest('.dialog') 判断是否点在弹窗内，
        // 比直接比较 backdrop 节点可靠（点到遮罩的子节点也算遮罩）
        backdrop.addEventListener('click', (e) => {
          const act = e.target.closest('[data-act]');
          if (act) return close(act.dataset.act === 'ok' ? input.value : null);
          if (!e.target.closest('.dialog')) close(null);
        });
        const onKey = (e) => {
          if (e.key === 'Escape') {
            close(null);
          } else if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
            close(input.value);
          }
          // 裸 Enter 不处理：留给 textarea 换行
        };
        document.addEventListener('keydown', onKey);
        setTimeout(() => input.focus(), 40);
      });
    }

    /**
     * 表单弹窗：只能点右上角 X 或「取消」关闭，点遮罩、按 Esc 都不关闭。
     * opts: { title, icon, okText, cancelText, formId, closeOnly, body(HTML 里含 form[data-form]), onOpen }
     * closeOnly: 底栏只留一个关闭按钮（只读弹窗用），不渲染取消/提交。
     * 提交按钮通过 form 属性与原生的 form[data-form] 关联，提交逻辑仍走全局 submit 监听。
     */
    function formDialog(opts) {
      const o = opts || {};
      return new Promise((resolve) => {
        const backdrop = document.createElement('div');
        backdrop.className = 'dialog-backdrop';
        backdrop.innerHTML = `
          <div class="dialog dialog-wide" role="dialog" aria-modal="true">
            <div class="dialog-head">
              <div class="dialog-title" style="justify-content:space-between">
                <span>${I.get(o.icon || 'plus', 17)} ${esc(o.title || '新建')}</span>
                <button type="button" class="dialog-x" data-act="x" aria-label="关闭">${I.get('close', 15)}</button>
              </div>
            </div>
            <div class="dialog-body">${o.body || ''}</div>
            <div class="dialog-foot">
              ${o.closeOnly ? `
              <button type="button" class="btn btn-primary" data-act="cancel">${esc(o.okText || '关闭')}</button>
              ` : `
              <button type="button" class="btn btn-ghost" data-act="cancel">${esc(o.cancelText || '取消')}</button>
              <button type="submit" class="btn btn-primary" data-act="submit"
                ${o.submitId ? `id="${esc(o.submitId)}"` : ''}
                form="${esc(o.formId || '')}">${esc(o.okText || '确定')}</button>
              `}
            </div>
          </div>`;
        document.body.appendChild(backdrop);

        let closed = false;
        const close = (val) => {
          if (closed) return;
          closed = true;
          backdrop.classList.add('leaving');
          setTimeout(() => backdrop.remove(), 220);
          resolve(val);
        };
        backdrop.addEventListener('click', (e) => {
          const act = e.target.closest('[data-act]');
          if (!act) return; // 点遮罩不关闭
          if (act.dataset.act === 'x' || act.dataset.act === 'cancel') close(null);
          // 提交按钮靠 form 属性原生提交，这里不处理
        });
        // 按要求：不绑定 Esc 关闭
        if (o.onOpen) setTimeout(() => o.onOpen(backdrop), 0);
        const first = backdrop.querySelector('input, textarea, select');
        if (first) setTimeout(() => first.focus(), 40);
      });
    }

    return { toast, confirm, busy, prompt, formDialog };
  })();

  /* ============================================================
   *  路由 / 状态
   * ============================================================ */
  const NAV_ITEMS = [
    { section: '学习' },
    { route: 'stages', href: '#/stages', icon: 'folder', label: '阶段' },
    { route: 'practice', href: '#/practice', icon: 'book', label: '练习' },
    { section: '进阶' },
    { route: 'drill', href: '#/drill', icon: 'target', label: '押题' },
    { route: 'mock', href: '#/mock', icon: 'mic', label: '模拟面试' },
    { section: '系统' },
    { route: 'profile', href: '#/profile', icon: 'user', label: '个人中心' },
  ];

  const PAPER_STATUS = { generating: '出题中', answering: '答题中', grading: '批改中', graded: '已交卷' };
  const TIER_ORDER = ['基础', '中档', '大题'];
  const TIER_CLS = { '基础': 'tier-base', '中档': 'tier-mid', '大题': 'tier-major' };
  /** 组卷模式：控制这卷出哪些难度档（或哪些题型）、出几道题。与知识点强化链路无关 */
  const MODE_LABELS = { regular: '常规', concept: '概念题', scene: '场景题', exam: '考试模式' };
  const MODE_TIPS = {
    regular: '综合',
    concept: '基础',
    scene: '实战',
    exam: '题型自选 · 自动判分'
  };
  /** 考试模式默认题量（用户可在出卷时改，每种可为 0） */
  const EXAM_MIX_DEFAULTS = { single: 5, multi: 3, blank: 5, shortAnswer: 2, code: 1 };
  const EXAM_MIX_LABELS = { single: '单选', multi: '多选', blank: '填空', shortAnswer: '简答', code: '代码' };

  let renderSeq = 0;
  /** 当前登录用户（/api/auth/me 返回的同构对象）；null = 未登录 */
  let sessionUser = null;
  /** 当前字号档位，用于重渲染后恢复分段控件的选中态 */
  let fontSize = 'standard';
  let practiceStageId = '';
  /** 从阶段详情点「新建练习」进来时，页面渲染后自动打开弹窗 */
  let pendingOpenPractice = false;
  let sessionState = null;
  let paperIdx = -1;
  let questionIdx = 0;
  /** 下一张卷的组卷模式（点「开始出题/继续出题」时随请求提交） */
  let paperMode = 'regular';
  /** 错题本分页（一页 5 条——几百条时不能全糊在一屏里） */
  let mistakePage = 1;
  const MISTAKE_PAGE_SIZE = 5;
  /** 阶段列表 / 练习列表分页（客户端分页，卡片网格） */
  let stagePage = 1;
  const STAGE_PAGE_SIZE = 12;
  let practicePage = 1;
  const PRACTICE_PAGE_SIZE = 9;
  /** 错题抽屉：当前展开的错题 id，null=关着 */
  let mistakeDrawerId = null;
  /** 复盘页当前 tab：knowledge / mistakes / history / archive */
  let reviewTab = 'knowledge';
  /** 从错题本「去看这道题」：先记下来，切到答题页、会话加载完后再定位（hash 切换是异步的） */
  let jumpToQuestionId = null;
  /** 追问聊天：按题隔离的缓存（qid → 消息数组），切题不串 */
  const followUpsCache = {};
  const followUpsLoading = {};
  /** 考前复习提纲：当前练习的文档 + 轮询 */
  let studyDoc = null;
  let studyPollTimer = null;
  let studyOnlyHigh = false;
  let studyObserver = null;
  let pollTimer = null;
  let knowledgeData = null;   // 知识点清单 + 掌握度
  let finalExamData = null;   // 期末考解锁状态 + 历次成绩单
  const drafts = {};
  /** 代码输入模式（按题记忆）：任何文字作答都能切换成代码编辑器，代码题永远是编辑器 */
  const codeMode = {};
  /** 语音作答状态：idle / recording / transcribing */
  let speechState = 'idle';
  let speechQid = null;
  let speechReady = false;
  let speechReason = '';
  let micTimer = null;
  /** 可折叠面板的收起状态（跨渲染、跨刷新保留） */
  const collapsed = (() => {
    try { return JSON.parse(sessionStorage.getItem('mr.collapsed') || '{}'); } catch (e) { return {}; }
  })();

  /**
   * 折叠状态：`collapsed[key]` 三态——undefined=没动过、true=收起、false=展开。
   * 没动过时按 defaultOpen 走，这样「知识点大类默认收起（有待巩固的自动展开）」
   * 和「面板默认展开」可以共用一套机制。
   */
  function isOpen(key, defaultOpen = true) {
    const v = collapsed[key];
    return v === undefined ? defaultOpen : !v;
  }
  function isCollapsed(key) { return !isOpen(key, true); }
  /**
   * `collapsed[key]` 存的是「是否收起」：true=收起、false=展开。
   * 所以「现在开着 → 记成收起」就是 `= isOpen(...)`，不能写成 `!isOpen(...)`——
   * 那样翻来翻去永远翻不动（我踩过一次）。
   */
  function toggleCollapsed(key, defaultOpen = true) {
    collapsed[key] = isOpen(key, defaultOpen);
    try { sessionStorage.setItem('mr.collapsed', JSON.stringify(collapsed)); } catch (e) { /* 忽略 */ }
  }

  /* ============================================================
   *  字号：html[data-fs] 切三档（小/标准/大），存 localStorage
   *  存浏览器本地而不是服务端——字号跟屏幕和坐姿有关，本来就该每台机器各调各的
   * ============================================================ */
  function applyFontSize(size) {
    const s = (size === 'small' || size === 'large') ? size : 'standard';
    fontSize = s;
    document.documentElement.dataset.fs = s;
    try { localStorage.setItem('mr.fontSize', s); } catch (e) { /* 隐私模式等，忽略 */ }
    $$('.seg[data-seg="fontSize"] button').forEach((b) => {
      b.classList.toggle('active', b.dataset.value === s);
    });
  }

  function initFontSize() {
    let s = 'standard';
    try { s = localStorage.getItem('mr.fontSize') || 'standard'; } catch (e) { /* 忽略 */ }
    applyFontSize(s);
  }

  /* ============================================================
   *  REST
   * ============================================================ */
  /* ---- 登录 token（localStorage，默认 6 小时） ---- */
  const TOKEN_KEY = 'mr.token';
  function getToken() {
    try { return localStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }
  function setToken(t) {
    try { if (t) localStorage.setItem(TOKEN_KEY, t); else localStorage.removeItem(TOKEN_KEY); } catch (e) { /* 隐私模式忽略 */ }
  }

  /* 头像加载：<img> 不会带 Authorization，改为带 token fetch → blob URL */
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
  function hydrateAvatars() {
    $$('img[data-avatar]').forEach(async (img) => {
      const url = await loadAvatar(img.dataset.avatar);
      if (url && img.isConnected) img.src = url;
    });
  }

  async function api(path, options = {}) {
    const opts = Object.assign({}, options);
    const isForm = opts.body instanceof FormData;
    // multipart 的 Content-Type 必须交给浏览器带 boundary，不能手动指定
    opts.headers = isForm
      ? Object.assign({}, options.headers)
      : Object.assign({ 'Content-Type': 'application/json' }, options.headers);
    // 每个请求带 Bearer token（登录/注册接口不需要，带了也无害）
    const token = getToken();
    if (token) opts.headers.Authorization = 'Bearer ' + token;
    // body 允许传对象——这里统一 JSON.stringify，防止有人直接丢对象给 fetch
    // （fetch 会把对象转成 "[object Object]"，Jackson 看到开头 [ 就报 START_ARRAY）
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
    if (res.status === 401 && path !== '/api/auth/login' && path !== '/api/auth/register') {
      // token 无效/过期：清掉本地 token，回到登录页
      setToken('');
      sessionUser = null;
      renderNav();
      if (!location.hash.startsWith('#/login')) location.hash = '#/login';
    }
    if (!res.ok) throw new Error((data && data.error) ? data.error : `请求失败（HTTP ${res.status}）`);
    return data;
  }

  function syncDraft() {
    const ta = $('#answer-input');
    if (ta && ta.dataset.qid) drafts[ta.dataset.qid] = ta.value;
  }

  /* ============================================================
   *  进度轮询
   * ============================================================ */
  function clearPoll() {
    if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
  }
  /** 卷的关键状态指纹：没变化就完全不动 DOM，避免每 2.5 秒闪一次 */
  function paperFingerprint(p) {
    if (!p) return '';
    const pr = p.progress || {};
    return [p.status, p.score, pr.planned, pr.done, (pr.failed || []).length,
            pr.error || '', (p.questions || []).length].join('|');
  }

  function schedulePoll() {
    clearPoll();
    const p = currentPaper();
    if (!p || (p.status !== 'generating' && p.status !== 'grading')) return;
    const before = p.status;
    const beforeFp = paperFingerprint(p);
    pollTimer = setTimeout(async () => {
      if (!sessionState) return;
      try {
        sessionState = await api('/api/practices/' + enc(sessionState.id));
        if (!location.hash.startsWith('#/session/')) return schedulePoll();
        const now = currentPaper();
        const fp = paperFingerprint(now);
        if (fp === beforeFp) {
          // 没有任何变化：不碰 DOM
        } else if (now && now.status === before) {
          // 还在同一阶段（比如出题中进度涨了）：只刷题目区/进度区
          renderQuestion();
        } else {
          // 批改完成 → 掌握度/待巩固变了，重新拉一次知识点统计再渲染
          if (now && now.status === 'graded') loadKnowledge(sessionState.id);
          renderSessionKeepScroll();
        }
      } catch (e) { /* 网络抖动继续轮询 */ }
      schedulePoll();
    }, 2500);
  }

  /* ============================================================
   *  路由
   * ============================================================ */
  function render() {
    renderSeq += 1;
    const seq = renderSeq;
    clearPoll();
    if (summaryPollTimer) { clearTimeout(summaryPollTimer); summaryPollTimer = null; }

    const hash = location.hash || '#/stages';
    const parts = hash.replace(/^#\/?/, '').split('/');
    const head = parts[0] || 'stages';
    const arg = parts[1] ? decodeURIComponent(parts[1]) : '';
    const sub = parts[2] ? decodeURIComponent(parts[2]) : '';

    let route = head;
    if (head === 'stages' && arg) route = 'stage-detail';
    if (head === 'session' && arg) route = 'session';

    let navRoute = head;
    if (route === 'stage-detail') navRoute = 'stages';
    if (route === 'session') navRoute = 'practice';
    $$('.nav-item').forEach((el) => el.classList.toggle('active', el.dataset.route === navRoute));

    const view = $('#view');
    view.classList.remove('view-enter');
    void view.offsetWidth;
    view.classList.add('view-enter');

    // 未登录：整个工作台让位给登录页（侧栏收起）
    if (!sessionUser) {
      document.body.classList.add('auth-mode');
      view.innerHTML = loginView();
      window.scrollTo({ top: 0 });
      return;
    }
    document.body.classList.remove('auth-mode');
    // 北欧极简试点页（个人中心 / 押题 / 模拟面试 / 阶段），用 body 类隔离暖色系样式
    document.body.classList.toggle('route-profile', route === 'profile');
    document.body.classList.toggle('route-soon', route === 'drill' || route === 'mock');
    document.body.classList.toggle('route-stages', route === 'stages' || route === 'stage-detail');
    document.body.classList.toggle('route-practice', route === 'practice' || route === 'session');

    if (route === 'stage-detail') {
      view.innerHTML = stageDetailView();
      loadStageDetail(arg, seq);
    } else if (route === 'practice') {
      view.innerHTML = practiceView();
      loadPracticeStages(seq);
      if (pendingOpenPractice) {
        pendingOpenPractice = false;
        openPracticeDialog();
      }
    } else if (route === 'session') {
      view.innerHTML = sessionShell(sub === 'review' ? 'review' : sub === 'study' ? 'study' : sub === 'drill' ? 'drill' : 'answer');
      loadSession(arg, seq);
    } else if (route === 'drill') {
      view.innerHTML = placeholderView('押题', '根据简历与目标岗位，练习最可能被问到的问题。', 'target');
    } else if (route === 'mock') {
      view.innerHTML = placeholderView('模拟面试', '贴近真实面试的多轮追问，全程不评分，结束后归档记录。', 'mic');
    } else if (route === 'settings') {
      location.replace('#/profile');   // 设置页已并入个人中心，旧书签跳过去
      return;
    } else if (route === 'profile') {
      view.innerHTML = profileView();
      hydrateAvatars();
      loadProfile(seq);
      applyFontSize(fontSize);
    } else {
      stagePage = 1;
      view.innerHTML = stagesView();
      loadStages(seq);
    }
    window.scrollTo({ top: 0, behavior: 'instant' in window ? 'instant' : 'auto' });
    measureStickyHead();
  }

  /* ============================================================
   *  吸顶页头：滚过页头原始位置后切换 mini 条
   * ============================================================ */
  /** 页头顶部距文档顶部的距离；mini 只改变页头自身高度，不动这个值，所以判定不会抖 */
  let stickyHeadTop = Infinity;
  let stickyRaf = 0;
  /** mini 时页头脱离文档流（fixed 横跨全宽），用这个占位块补上高度 */
  let stickySpacer = null;

  function stickyHeadEl() {
    return document.querySelector('#view .page-head, #view .session-head');
  }

  function measureStickyHead() {
    const h = stickyHeadEl();
    stickySpacer = document.querySelector('.sticky-head-spacer');
    stickyHeadTop = h ? h.getBoundingClientRect().top + window.scrollY : Infinity;
    updateStickyHead();
  }

  function updateStickyHead() {
    const h = stickyHeadEl();
    if (!h) return;
    // 判定带迟滞（进入 1px / 解除 40px）：贴着阈值反复进出会让标题吸顶来回切，
    // 每次切换都伴随高度读写，是滚动抖动的来源之一。
    const isMini = h.classList.contains('mini');
    const mini = isMini ? window.scrollY >= stickyHeadTop - 40 : window.scrollY >= stickyHeadTop - 1;
    if (mini === isMini) return;
    if (mini) {
      // 先量文档流里的高度（含上下 margin），脱离前写进占位块，内容才不会往上跳
      const cs = getComputedStyle(h);
      const height = h.getBoundingClientRect().height
        + (parseFloat(cs.marginTop) || 0) + (parseFloat(cs.marginBottom) || 0);
      if (!stickySpacer) {
        stickySpacer = document.createElement('div');
        stickySpacer.className = 'sticky-head-spacer';
        h.parentNode.insertBefore(stickySpacer, h.nextSibling);
      }
      stickySpacer.style.height = height + 'px';   // 不取整：分数像素残差会让文档高度差 1px
      h.classList.add('mini');
    } else {
      h.classList.remove('mini');
      if (stickySpacer) stickySpacer.style.height = '0px';
    }
  }

  window.addEventListener('scroll', () => {
    if (stickyRaf) return;
    stickyRaf = requestAnimationFrame(() => {
      stickyRaf = 0;
      updateStickyHead();
      updateQuestionSticky();
    });
  }, { passive: true });

  /* ============================================================
   *  复习提纲目录跟随：右侧滚到哪，左侧对应项高亮（分类 + 知识点两级）
   *  当前项 = 顶部已经越过视口顶部阈值线的最后一个 section / 卡片；
   *  阈值线避开吸顶 mini 标题。高亮变化时把目录项滚进目录框，
   *  目录长到要内滚时也能跟住。「只看高频」隐藏的卡片不参与判定。
   * ============================================================ */
  let studyTocActiveId = '';
  let studyTocActiveSub = '';
  let studyRaf = 0;

  /** 可见的（only-high 过滤后仍在渲染的）元素——display:none 的 rect 全 0，会冒充「当前」 */
  const visibleEls = (sel) => [...document.querySelectorAll(sel)].filter((el) => el.getClientRects().length);

  function updateStudyTocActive() {
    const toc = document.querySelector('.study-toc');
    if (!toc) { studyTocActiveId = ''; studyTocActiveSub = ''; return; }
    const line = cssPx('--mini-head-h', 44) + 44;
    const currentOf = (list, fallback) => {
      let cur = fallback;
      for (const el of list) {
        if (el.getBoundingClientRect().top <= line) cur = el.id;
        else break;
      }
      return cur;
    };
    const secs = visibleEls('.study-cat');
    const pts = visibleEls('.study-point');
    const curSec = secs.length ? currentOf(secs, secs[0].id) : '';
    const curPt = pts.length ? currentOf(pts, pts[0].id) : '';

    if (curSec !== studyTocActiveId) {
      studyTocActiveId = curSec;
      toc.querySelectorAll('.study-toc-item').forEach((el) => {
        el.classList.toggle('active', el.dataset.target === curSec);
      });
    }
    if (curPt !== studyTocActiveSub) {
      studyTocActiveSub = curPt;
      let active = null;
      toc.querySelectorAll('.study-toc-sub').forEach((el) => {
        const on = el.dataset.target === curPt;
        el.classList.toggle('active', on);
        if (on) active = el;
      });
      // 只在高亮切换时滚一次，避免每帧抖动目录
      if (active) active.scrollIntoView({ block: 'nearest' });
    }
  }

  /* ---- 「只看高频 / 全部展开」滚动后悬浮在提纲区右上角（快速回顶部式） ---- */
  /** 悬浮位置按提纲内容区右缘算（旁边还有记事本栏，钉视口角会压住记事本） */
  function placeStudyToolsFloat(q) {
    const area = document.getElementById('study-content') || document.querySelector('.study-layout');
    if (!area) return;
    q.style.right = Math.max(12, Math.round(window.innerWidth - area.getBoundingClientRect().right)) + 'px';
  }

  function updateStudyToolsFloat() {
    const q = document.getElementById('study-tools-quick');
    const head = document.querySelector('.study-head');
    if (!q || !head) return;
    const line = cssPx('--mini-head-h', 44) + 10;
    const headBottom = head.getBoundingClientRect().bottom;
    // 带迟滞：进出阈值差 24px，停在边界处不会来回闪
    const isFloat = q.classList.contains('is-float');
    const want = isFloat ? headBottom < line + 24 : headBottom < line;
    if (want === isFloat) {
      if (want) placeStudyToolsFloat(q);
      return;
    }
    q.classList.toggle('is-float', want);
    if (want) placeStudyToolsFloat(q);
  }

  window.addEventListener('resize', () => {
    const q = document.getElementById('study-tools-quick');
    if (q && q.classList.contains('is-float')) placeStudyToolsFloat(q);
  });

  window.addEventListener('scroll', () => {
    if (studyRaf) return;
    studyRaf = requestAnimationFrame(() => {
      studyRaf = 0;
      updateStudyTocActive();
      updateStudyToolsFloat();
    });
  }, { passive: true });

  /* ============================================================
   *  题目吸顶：题目卡用 CSS sticky 自己贴住，JS 只负责两件事
   *  ① 控制区贴顶时切到紧凑态，并量出紧凑高度给题目当落点；
   *  ② 题目真正贴住时加 .is-stuck（只改外观，不改位置）。
   *  位置交给浏览器，就不会因为「改高度 → 改阈值 → 再改高度」而抽搐。
   * ============================================================ */
  let barDocTop = null;

  function measureQuestionSticky() {
    const bar = document.querySelector('.answer-sticky');
    const card = $('#question-card');
    barDocTop = bar ? bar.getBoundingClientRect().top + window.scrollY : null;
    if (card) toggleStuckReserve(card, false);
    updateQuestionSticky();
  }

  function cssPx(name, fallback) {
    const v = parseFloat(getComputedStyle(document.documentElement).getPropertyValue(name));
    return Number.isFinite(v) ? v : fallback;
  }

  /**
   * 切换吸顶态时**保住占位高度**：紧凑态会压缩元素（题目卡 max-height:36vh + 缩字号），
   * 文档高度一旦跟着变就会形成自激振荡——表现为滚动抽搐、永远到不了底。
   *
   * 关键不只在「补回高度」，还在**顺序**：先把文档撑大、再切态、最后落定 margin。
   * 如果先切态（文档瞬间变矮）再补，中间那次强制回流会把**底部的 scrollTop 截断**
   * （文档变矮后 scrollTop 不可能超过新底），截断又触发滚动事件把切态打回去——
   * 大屏上「可滚动距离 < 压缩量」时每次切态都吃滚动位置，这就是换显示器才炸的原因。
   * 撑大先行则任何中间布局都只会变大，截断无从发生；
   * 落定后的总高度切态前后恒为原值（margin 补回压缩差额）。
   */
  function toggleStuckReserve(el, stuck) {
    if (stuck === el.classList.contains('is-stuck')) return;
    if (el.dataset.stuckMb === undefined) {
      el.dataset.stuckMb = String(parseFloat(getComputedStyle(el).marginBottom) || 0);
    }
    const base = parseFloat(el.dataset.stuckMb);
    // ① 撑大（高度不可能压过元素自身高度，+200 余量）
    el.style.marginBottom = (base + el.offsetHeight + 200) + 'px';
    // ② 切态并量压缩差额（此刻文档仍比原值大，任何回流都不会截断）
    const before = el.getBoundingClientRect().height;
    el.classList.toggle('is-stuck', stuck);
    const after = el.getBoundingClientRect().height;
    // ③ 落定：补回压缩量，总高度回到切态前（保留分数像素，取整会留 1px 残差）
    el.style.marginBottom = (base + Math.max(0, before - after)) + 'px';
  }

  function updateQuestionSticky() {
    const bar = document.querySelector('.answer-sticky');
    const card = $('#question-card');
    if (!bar && !card) return;
    const miniH = cssPx('--mini-head-h', 44);
    const barTop = miniH + 8;          // 控制区贴顶后的位置

    if (bar && !bar.classList.contains('is-stuck')) {
      // 未贴顶时每帧重新取真实文档位置：页面高度变化（切题、图片加载）也不会用过期值
      barDocTop = bar.getBoundingClientRect().top + window.scrollY;
      if (window.scrollY + barTop >= barDocTop - 1) {
        toggleStuckReserve(bar, true);
        qnavFadeUpdate();   // 溢出模式随紧凑态切换，遮罩要重算
        refreshQnav();      // 贴顶态可能切成翻页条式题号（题量大时）
        // 紧凑态生效后同步落点高度（题目吸顶 top 依赖它）
        requestAnimationFrame(() => {
          if (!bar.classList.contains('is-stuck')) return;
          document.documentElement.style.setProperty(
            '--answer-sticky-stuck-h', Math.round(bar.getBoundingClientRect().height) + 'px');
        });
      }
    } else if (bar && barDocTop != null && window.scrollY + barTop < barDocTop - 80) {
      toggleStuckReserve(bar, false);
      qnavFadeUpdate();
      refreshQnav();
    }

    if (!card) return;
    // 题目卡本身是 position: sticky，贴住后 rect.top 恒等于落点；
    // 这里只判断它是否已经贴住，用来切换紧凑外观。
    // 判定带迟滞（贴住后要越过 28px 才解除），高度变化由占位块兜底。
    // 吸顶区高度：答题页是控制区、刷题页是翻页条（observeAnswerSticky 统一写入），没有就是 0
    const stuckH = cssPx('--answer-sticky-stuck-h', 0);
    const stickTop = miniH + stuckH + 8;
    const stuckNow = card.classList.contains('is-stuck');
    const top = card.getBoundingClientRect().top;
    const hit = stuckNow ? top <= stickTop + 28 : top <= stickTop + 2;
    if (hit !== stuckNow) {
      toggleStuckReserve(card, hit);
    }
  }

  /* ============================================================
   *  通用片段
   * ============================================================ */
  function listBlock(title, arr, iconName) {
    if (!arr || !arr.length) return '';
    return `
      <div class="result-block">
        <h4>${I.get(iconName || 'check', 13)} ${esc(title)}</h4>
        <div class="rb-body"><ul>${arr.map((x) => `<li>${esc(x)}</li>`).join('')}</ul></div>
      </div>`;
  }

  /**
   * 短语类清单用 tag 流式排（一行若干个自动换行），不占竖向空间。
   * 只适合「名字」型条目——句子型的（点评里的 strengths/improvements）继续用 {@link listBlock}。
   * @param tone '' | 'ok' | 'weak' | 'muted'
   */
  function tagList(title, arr, iconName, tone = '') {
    if (!arr || !arr.length) return '';
    return `
      <div class="result-block">
        <h4>${I.get(iconName || 'check', 13)} ${esc(title)} <span class="muted">${arr.length}</span></h4>
        <div class="tag-list">${arr.map((x) => `<span class="tag ${tone}">${esc(x)}</span>`).join('')}</div>
      </div>`;
  }

  function statItem(label, value, iconName) {
    return `
      <div class="stat-item">
        <div class="s-label">${I.get(iconName || 'chart', 12)} ${esc(label)}</div>
        <div class="s-value">${value === null || value === undefined ? '—' : esc(value)}</div>
      </div>`;
  }

  function statusBadge(status) {
    return status === 'completed'
      ? '<span class="badge badge-green">已归档</span>'
      : '<span class="badge badge-blue">进行中</span>';
  }

  function tierChip(tier) {
    return `<span class="tier-chip ${TIER_CLS[tier] || 'tier-base'}">${esc(tier || '基础')}</span>`;
  }

  function kpLine(q) {
    // assignedPoints 是后端从清单挑的（掌握度按它回写），优先展示；
    // knowledgePoints 是 AI 自己的叫法，只是补充说明
    const kps = (q.assignedPoints && q.assignedPoints.length) ? q.assignedPoints : (q.knowledgePoints || []);
    if (!kps.length) return '';
    const link = (q.assignedPoints || []).length > 1
      ? '<span class="badge badge-purple">串联</span>' : '';
    return `<div class="q-kp">${I.get('target', 12)} ${kps.map(esc).join(' · ')} ${link}</div>`;
  }

  /** 本题要求（批改清单）：答题时看清侧重点，批改后能对账 */
  function demandsLine(q) {
    const ds = q.demands || [];
    if (!ds.length) return '';
    return `<div class="q-demands"><b>题面问点</b>${ds.map((d, i) => `<div class="qd-item">${i + 1}. ${rich(d)}</div>`).join('')}</div>`;
  }

  /* ---------- 错题本 / 笔记（per-practice） ---------- */

  const REASON_LABEL = { 'low-score': '低分', unanswered: '未作答', revealed: '看了答案', manual: '手动收录' };

  /** questionId → {paperSeq, seq, prompt}，渲染面板时联查题面 */
  function questionIndex() {
    const map = new Map();
    for (const paper of (sessionState && sessionState.papers) || []) {
      for (const q of paper.questions || []) {
        map.set(q.id, { paperSeq: paper.sequence, seq: q.sequence, prompt: q.prompt });
      }
    }
    return map;
  }

  function mistakeOfQuestion(qid) {
    return ((sessionState && sessionState.mistakes) || []).find((m) => m.questionId === qid);
  }

  /** 快记按钮 + 收错题按钮（答题页/批改页共用） */
  function captureBtns(qid) {
    const m = mistakeOfQuestion(qid);
    return `
      <button class="btn btn-ghost btn-sm" data-action="quick-note" title="追加到右侧记事本末尾">
        ${I.get('pencil', 14)} 记笔记
      </button>
      ${m
        ? `<button class="btn btn-quiet btn-sm" disabled>${I.get('bookmark', 14)} 已在错题本</button>`
        : `<button class="btn btn-soft btn-sm" data-action="add-mistake" data-qid="${esc(qid)}">
             ${I.get('bookmark', 14)} 加入错题本
           </button>`}`;
  }

  /** 错题本面板：per-practice，一题一条。条数多了分页，免得一屏拉到天荒地老 */
  /**
   * 错题本：主视图是**紧凑表格**（5/页），点行开右侧抽屉看详情。
   * 表格只留「看得见就懂」的列，操作全收进抽屉——列表视线不被打断。
   */
  function mistakesPanel(p) {
    const list = (p.mistakes || []);
    const open = list.filter((m) => m.status !== 'resolved').length;
    const pages = Math.max(1, Math.ceil(list.length / MISTAKE_PAGE_SIZE));
    if (mistakePage > pages) mistakePage = pages;
    if (mistakePage < 1) mistakePage = 1;
    const pageList = list.slice((mistakePage - 1) * MISTAKE_PAGE_SIZE, mistakePage * MISTAKE_PAGE_SIZE);
    const qmap = questionIndex();
    const drawer = mistakeDrawerId ? (list.find((m) => m.id === mistakeDrawerId) || null) : null;

    const pager = list.length > MISTAKE_PAGE_SIZE ? `
      <div class="list-pager">
        <button class="btn btn-ghost btn-sm" data-action="mistake-page" data-page="${mistakePage - 1}" ${mistakePage <= 1 ? 'disabled' : ''}>
          ${I.get('back', 13)}
        </button>
        <span class="muted num">${mistakePage} / ${pages}</span>
        <button class="btn btn-ghost btn-sm" data-action="mistake-page" data-page="${mistakePage + 1}" ${mistakePage >= pages ? 'disabled' : ''}>
          <span class="flip-x">${I.get('back', 13)}</span>
        </button>
      </div>` : '';

    const table = list.length ? `
      <div class="tbl-wrap">
        <table class="tbl">
          <thead>
            <tr><th class="w-2">#</th><th>来源</th><th>题目</th><th class="w-4 num">得分</th><th class="w-5">状态</th><th class="w-5">时间</th></tr>
          </thead>
          <tbody>
            ${pageList.map((m, i) => {
              const info = qmap.get(m.questionId) || {};
              const resolved = m.status === 'resolved';
              const sel = mistakeDrawerId === m.id ? ' class="active"' : '';
              return `<tr${sel} data-action="open-mistake-drawer" data-mid="${esc(m.id)}" style="cursor:pointer">
                <td class="muted num">${(mistakePage - 1) * MISTAKE_PAGE_SIZE + i + 1}</td>
                <td><span class="dot ${m.reason === 'manual' ? 'dot-violet' : m.reason === 'revealed' ? 'dot-orange' : m.reason === 'unanswered' ? 'dot-gray' : 'dot-red'}"></span>${esc(REASON_LABEL[m.reason] || m.reason)}</td>
                <td class="ellipsis">${esc(m.prompt || info.prompt || '（题目已不存在）')}</td>
                <td class="num">${m.score === null || m.score === undefined ? '—' : esc(m.score)}</td>
                <td>${resolved ? '<span class="badge badge-green">已订正</span>' : '<span class="badge badge-blue">待订正</span>'}</td>
                <td class="muted num">${fmtTime(m.createdAt)}</td>
              </tr>`;
            }).join('')}
          </tbody>
        </table>
      </div>${pager}`
      : emptyState('还没有错题');

    return collapsiblePanel('mistakes', 'panel-accent', 'bookmark',
      '错题本', `${list.length} 条 · ${open} 待订正`, `
        ${open > 0 ? `
        <div class="btn-row" style="margin:0 0 12px">
          <button class="btn btn-primary btn-sm" data-action="retry-mistakes" data-id="${esc(p.id)}">
            ${I.get('play', 14)} 对待订正 ${open} 题出卷
          </button>
        </div>` : ''}
        <div class="mistake-layout">
          <div class="mistake-main">${table}</div>
          ${drawer ? mistakeDrawer(drawer, qmap) : ''}
        </div>`);
  }

  /** 右侧抽屉：错题详情（题目、作答、点评、操作）。列表不动，视线不断 */
  function mistakeDrawer(m, qmap) {
    const info = qmap.get(m.questionId) || {};
    const resolved = m.status === 'resolved';
    const q = ((sessionState && sessionState.papers) || [])
      .flatMap((p) => p.questions || []).find((x) => x.id === m.questionId);
    const a = q ? latestAttempt(q) : null;
    const ev = a && a.evaluation;
    // 题目/作答/点评：优先实时数据；刷题题不在考卷列表里，用错题自带的快照
    const prompt = (q && q.prompt) || m.prompt || info.prompt || '';
    const answer = a ? (a.answer || '') : (m.answer || '');
    const feedback = ev ? (ev.feedback || '') : (m.feedback || '');
    return `
      <aside class="drawer">
        <div class="drawer-head">
          <div class="drawer-title">
            <span class="badge ${m.reason === 'revealed' ? 'badge-orange' : m.reason === 'unanswered' ? 'badge-gray' : m.reason === 'manual' ? 'badge-purple' : 'badge-red'}">${esc(REASON_LABEL[m.reason] || m.reason)}</span>
            ${resolved ? '<span class="badge badge-green">已订正</span>' : '<span class="badge badge-blue">待订正</span>'}
            ${m.score !== null && m.score !== undefined ? `<span class="muted num">表现 ${esc(m.score)}</span>` : ''}
          </div>
          <button class="btn btn-quiet btn-sm" data-action="close-mistake-drawer">${I.get('close', 14)}</button>
        </div>
        <div class="drawer-body">
          <div class="drawer-section">
            <div class="drawer-label">题目</div>
            <div class="drawer-text">${rich(prompt || '（题目已不存在）')}</div>
          </div>
          ${a || m.answer ? `
          <div class="drawer-section">
            <div class="drawer-label">我的作答</div>
            <div class="drawer-text muted">${esc(answer || '（未作答）')}</div>
          </div>` : ''}
          ${feedback ? `
          <div class="drawer-section">
            <div class="drawer-label">点评${ev && ev.score !== null && ev.score !== undefined ? ` · ${esc(ev.score)}/10` : ''}</div>
            <div class="drawer-text">${rich(feedback)}</div>
          </div>` : ''}
        </div>
        <div class="drawer-actions">
          ${prompt ? `<button class="btn btn-ghost btn-sm" data-action="goto-mistake-question" data-qid="${esc(m.questionId)}">看原题</button>` : ''}
          ${resolved
            ? `<button class="btn btn-quiet btn-sm" data-action="reopen-mistake" data-mid="${esc(m.id)}">重新打开</button>`
            : `<button class="btn btn-success btn-sm" data-action="resolve-mistake" data-mid="${esc(m.id)}">${I.get('check', 13)} 已订正</button>`}
          <button class="btn btn-danger btn-sm" data-action="delete-mistake" data-mid="${esc(m.id)}">${I.get('trash', 13)}</button>
        </div>
      </aside>`;
  }

  /**
   * 右侧固定笔记栏——**一整块记事本**，不是一条条卡片。
   * 就是个 textarea：随手记、随手改、自动保存。追加就是在末尾换行，不掺题号（用户明确不要）。
   *
   * 为什么用 textarea 不用 contenteditable：中文输入法跟 contenteditable 是出了名的不对付，
   * IME 出 bug 比「拿不到逐条日期」糟得多。所以日期只有整篇一份，悬停可见。
   */
  function noteRail(p) {
    const text = p.notesText || '';
    const when = p.notesUpdatedAt ? fmtTime(p.notesUpdatedAt) : '还没写过';
    return `
      <div class="note-rail-box">
        <div class="note-rail-head">
          <h3>${I.get('pencil', 15)} 记事本</h3>
          <span class="count" title="最后修改 ${esc(when)}">${esc(when)}</span>
          <button class="btn btn-ghost btn-sm" data-action="note-jump-latest" title="跳到最新">
            ↓ 跳到最新
          </button>
        </div>
        <textarea id="note-pad" class="note-pad"
                  title="记事本 · 最后修改 ${esc(when)}&#10;"
                  placeholder="随手记一笔…">${esc(text)}</textarea>
        <div class="note-pad-bar">
          <span class="muted" id="note-pad-status">自动保存</span>
        </div>
      </div>`;
  }

  const INTENSITY_LABELS = { quick: '速通', standard: '标准', deep: '深挖' };
  const LEVEL_LABELS = { junior: '初级', mid: '中级', senior: '高级' };

  function levelLabel(level) {
    return LEVEL_LABELS[level] || '中级';
  }

  function scopeLabel(p) {
    const mode = p.scopeMode === 'range' ? '范围模式' : '主题模式';
    const intensity = INTENSITY_LABELS[p.intensity] || '标准';
    return `${mode} · ${intensity}`;
  }

  /**
   * 页头：只给标题和动作，**不放描述段落**——产品不自我介绍，
   * 状态和数字会说话。eyebrow 用于页面层级（Workspace / Practice）。
   */
  function pageHead(eyebrow, title, desc, actions) {
    return `
      <div class="page-head">
        <div class="ph-main">
          ${eyebrow ? `<div class="eyebrow">${esc(eyebrow)}</div>` : ''}
          <h1>${esc(title)}</h1>
          ${desc ? `<p>${esc(desc)}</p>` : ''}
        </div>
        ${actions ? `<div class="page-actions">${actions}</div>` : ''}
      </div>`;
  }

  /** 空态只说事实（「还没有错题」），不写「怎么才能有」 */
  function emptyState(title, desc, actionHtml) {
    return `
      <div class="empty">
        <div class="empty-icon">${I.get('list', 22)}</div>
        <div class="empty-title">${esc(title)}</div>
        ${actionHtml || ''}
      </div>`;
  }

  /** 通用翻页条：target 标明翻的是哪个列表（stage / practice），总页数 ≤1 时不显示 */
  function pagerHtml(target, page, pages) {
    if (pages <= 1) return '';
    return `
      <div class="list-pager">
        <button class="btn btn-ghost btn-sm" data-action="page-go" data-target="${target}" data-page="${page - 1}" ${page <= 1 ? 'disabled' : ''}>
          ${I.get('back', 13)}
        </button>
        <span class="muted num">${page} / ${pages}</span>
        <button class="btn btn-ghost btn-sm" data-action="page-go" data-target="${target}" data-page="${page + 1}" ${page >= pages ? 'disabled' : ''}>
          <span class="flip-x">${I.get('back', 13)}</span>
        </button>
      </div>`;
  }

  /** 取当前页切片，并把越界的页码拉回合法范围 */
  function paginate(list, page, size) {
    const pages = Math.max(1, Math.ceil(list.length / size));
    const cur = Math.min(Math.max(1, page), pages);
    return { cur, pages, items: list.slice((cur - 1) * size, cur * size) };
  }

  /* ============================================================
   *  页面：阶段
   * ============================================================ */
  /** 阶段列表（北欧极简）：去外层套娃卡，卡片直接铺在暖底上 */
  function stagesView() {
    return `
      ${pageHead('Workspace', '阶段', '', `<button class="btn btn-strong" data-action="new-stage">新建阶段</button>`)}
      <div class="stages-head">
        <span class="stages-label">全部阶段</span>
        <span class="stages-count" id="stage-count"></span>
      </div>
      <div id="stage-list">${emptyState('加载中…')}</div>`;
  }

  /** 新建阶段弹窗（只能 X / 取消关闭） */
  function openStageDialog() {
    UI.formDialog({
      title: '新建阶段',
      icon: 'plus',
      okText: '创建阶段',
      formId: 'stage-create-form',
      body: `
        <form id="stage-create-form" data-form="stage-create">
          <div class="form-grid">
            <div class="field">
              <label>阶段名称 <span class="req">*</span></label>
              <input type="text" name="name" placeholder="例：Java 后端秋招冲刺" />
            </div>
          </div>
        </form>`,
    });
  }

  /** 考试模式出卷弹窗：题型数量自己定（每种 0-30，可为 0，合计至少 1） */
  function openExamMixDialog(practiceId, mode) {
    UI.formDialog({
      title: '考试模式 · 题型数量',
      icon: 'list',
      okText: '开始出题',
      formId: 'exam-mix-form',
      body: `
        <form id="exam-mix-form" data-form="exam-mix"
          data-practice="${esc(practiceId)}" data-mode="${esc(mode)}">
          <div class="form-grid">
            ${Object.keys(EXAM_MIX_LABELS).map((k) => `
              <div class="field">
                <label>${EXAM_MIX_LABELS[k]}</label>
                <input type="number" name="${k}" min="0" max="30" value="${EXAM_MIX_DEFAULTS[k]}" />
              </div>`).join('')}
          </div>
          <p class="tip" style="margin-top:12px">每种题型 0 到 30 道，可以为 0；合计至少 1 道。满分 100，分值按题型加权。</p>
        </form>`,
    });
  }

  /** 阶段列表缓存：翻页只重绘，不重新请求 */
  let stageListData = [];

  async function loadStages(seq) {
    const box = $('#stage-list');
    if (!box) return;
    try {
      const stages = await api('/api/stages');
      if (seq !== renderSeq) return;
      stageListData = stages;
      const sub = $('#stage-count');
      if (sub) sub.textContent = stages.length ? `共 ${stages.length} 个` : '';
      if (stages.length) renderStageList();
      else box.innerHTML = emptyState('还没有阶段', '',
        `<div class="btn-row" style="justify-content:center"><button class="btn btn-strong" data-action="new-stage">新建阶段</button></div>`);
    } catch (err) {
      if (seq !== renderSeq) return;
      box.innerHTML = emptyState('加载失败', err.message);
    }
  }

  function renderStageList() {
    const box = $('#stage-list');
    if (!box) return;
    const pg = paginate(stageListData, stagePage, STAGE_PAGE_SIZE);
    stagePage = pg.cur;
    box.innerHTML = stageListHtml(pg.items) + pagerHtml('stage', pg.cur, pg.pages);
  }

  function stageListHtml(stages) {
    return `<div class="card-grid">${stages.map((s) => {
      const st = s.stats || {};
      return `
        <div class="item-card" data-action="open-stage" data-id="${esc(s.id)}">
          <button class="btn btn-quiet btn-sm item-card-del" data-action="delete-stage" data-id="${esc(s.id)}" title="删除阶段">
            ${I.get('trash', 14)}
          </button>
          <div class="item-card-head">
            <div class="li-icon">${I.get('folder', 17)}</div>
            <div class="li-main">
              <div class="li-title">${esc(s.name)}</div>
              <div class="li-sub">${statusBadge(s.status)}${s.summary ? ' <span class="badge badge-gray">已有总结</span>' : ''}</div>
            </div>
          </div>
          <div class="item-card-stats">
            <span>练习 <b>${num(st.practiceCount)}</b></span>
            <span>卷 <b>${num(st.paperCount)}</b></span>
            <span>题 <b>${num(st.questionCount)}</b></span>
            <span>均分 <b>${st.averageScore === null || st.averageScore === undefined ? '—' : esc(st.averageScore)}</b></span>
            ${st.drillCount ? `<span>刷题 <b>${num(st.drillCount)}</b> · 均 <b>${st.drillAvgScore == null ? '—' : esc(st.drillAvgScore)}</b></span>` : ''}
          </div>
          <div class="item-card-foot">更新于 ${fmtTime(s.updatedAt)}</div>
        </div>`;
    }).join('')}</div>`;
  }

  /* ============================================================
   *  页面：阶段详情
   * ============================================================ */
  function stageDetailView() {
    return `<div id="stage-detail-body">${emptyState('加载中…')}</div>`;
  }

  /** 阶段详情页的练习缓存：翻页只重绘卡片区 */
  let detailPractices = [];
  let detailPracticePage = 1;

  async function loadStageDetail(id, seq) {
    const box = $('#stage-detail-body');
    if (!box) return;
    try {
      const stage = await api('/api/stages/' + enc(id));
      if (seq !== renderSeq) return;
      detailPractices = stage.practices || [];
      detailPracticePage = 1;
      box.innerHTML = stageDetailHtml(stage);
      measureStickyHead();   // 页头是异步插入的，要重测一次才吸得住
    } catch (err) {
      if (seq !== renderSeq) return;
      box.innerHTML = `
        <div class="session-head">
          <a class="back-link" href="#/stages">${I.get('back', 15)} 返回</a>
          <h1>阶段详情</h1>
        </div>
        ${emptyState('加载失败', err.message)}`;
    }
  }

  function stageDetailHtml(s) {
    const st = s.stats || {};
    const hasSummary = !!(s.summary && s.summary.overall);
    return `
      <div class="session-head">
        <a class="back-link" href="#/stages">${I.get('back', 15)} 返回阶段</a>
        <h1>${esc(s.name)}</h1>
        ${statusBadge(s.status)}
        <div class="page-actions" style="margin-left:auto">
          <button class="btn btn-ghost" data-action="gen-summary" data-id="${esc(s.id)}">
            ${I.get('star', 15)} ${hasSummary ? '重出阶段总结' : '生成阶段总结'}
          </button>
          <select id="review-intensity" class="btn btn-ghost btn-sm" title="全复习的深度档"
            style="width:auto;min-width:0">
            <option value="quick">速通</option>
            <option value="standard" selected>标准</option>
            <option value="deep">深挖</option>
          </select>
          <button class="btn btn-primary" data-action="start-review" data-id="${esc(s.id)}"
            ${hasSummary ? '' : 'disabled title="需先生成阶段总结"'}>
            ${I.get('play', 15)} 全复习练习
          </button>
        </div>
      </div>

      <div class="stagger">
        <!-- KPI 一行 + 趋势 sparkline -->
        <div class="card">
          <div class="kpi-row">
            <div class="kpi">
              <div class="kpi-val num">${st.averageScore === null ? '—' : esc(st.averageScore)}</div>
              <div class="kpi-label">平均分</div>
            </div>
            <div class="kpi">
              <div class="kpi-val num">${st.lastScore === null ? '—' : esc(st.lastScore)}</div>
              <div class="kpi-label">最近</div>
            </div>
            <div class="kpi">
              <div class="kpi-val num">${num(st.paperCount)}</div>
              <div class="kpi-label">卷</div>
            </div>
            <div class="kpi">
              <div class="kpi-val num">${num(st.practiceCount)}</div>
              <div class="kpi-label">练习</div>
            </div>
            <div class="kpi">
              <div class="kpi-val num">${num(st.questionCount)}</div>
              <div class="kpi-label">题目</div>
            </div>
            <div class="kpi-spark">
              ${sparkline(st.recentScores || []) || '<span class="kpi-spark-empty">还没有得分趋势</span>'}
            </div>
          </div>
        </div>

        ${stageSummaryHtml(s.summary, hasSummary)}

        <div class="card">
          <h2>练习 <span class="sub">${(s.practices || []).length}</span></h2>
          <div id="detail-practice-list">${practiceCardsHtml(s.practices || [], detailPracticePage, 'detail-practice')}</div>
          <div class="btn-row">
            <button class="btn btn-strong" data-action="new-practice" data-id="${esc(s.id)}">${I.get('plus', 15)} 新建练习</button>
          </div>
        </div>
      </div>`;
  }

  /** 近 8 卷得分 sparkline（SVG 折线，无依赖） */
  function sparkline(scores) {
    if (!scores || scores.length < 2) return '';
    const w = 160, h = 40, pad = 3;
    const min = Math.min(...scores, 0), max = Math.max(...scores, 100);
    const range = Math.max(1, max - min);
    const pts = scores.map((v, i) => {
      const x = pad + (i / (scores.length - 1)) * (w - pad * 2);
      const y = h - pad - ((v - min) / range) * (h - pad * 2);
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    });
    return `<svg class="spark-svg" viewBox="0 0 ${w} ${h}" width="${w}" height="${h}" aria-hidden="true">
      <polyline fill="none" stroke="var(--accent)" stroke-width="1.8" stroke-linejoin="round" stroke-linecap="round" points="${pts.join(' ')}"/>
    </svg>`;
  }

  function stageSummaryHtml(sm, has) {
    if (!has) {
      return `
        <div class="card">
          <h2>阶段总结</h2>
          ${emptyState('暂无总结')}
        </div>`;
    }
    return `
      <div class="panel panel-accent">
        <h3>${I.get('star', 15)} 阶段总结
          <span class="muted">生成于 ${fmtTime(sm.generatedAt)}</span>
        </h3>
        <div class="result-block">
          <h4>总体评估</h4>
          <div class="rb-body">${esc(sm.overall)}</div>
        </div>
        <div class="cols-2" style="margin-top:12px">
          <div>${listBlock('掌握较好', sm.strengths, 'check')}</div>
          <div>${listBlock('待补短板', sm.keyWeaknesses, 'close')}</div>
        </div>
        ${listBlock('下一步', sm.nextSteps, 'target')}
        ${sm.reviewScope ? `
          <div class="result-block">
            <h4>全复习范围</h4>
            <div class="rb-body">${esc(sm.reviewScope)}</div>
          </div>` : ''}
      </div>`;
  }

  /* ============================================================
   *  页面：练习
   * ============================================================ */
  function practiceView() {
    return `
      ${pageHead('Practice', '练习', '', `<button class="btn btn-strong" data-action="new-practice">${I.get('plus', 15)} 新建练习</button>`)}
      <div class="stagger">
        <div class="card">
          <h2 style="margin:0">练习列表 <span class="sub" id="practice-list-sub"></span></h2>
          <div id="stage-chips" class="stage-chips">${emptyState('加载中…')}</div>
          <div id="practice-list" style="margin-top:14px">${emptyState('加载中…')}</div>
        </div>
      </div>`;
  }

  /** 阶段切换：横向滚动胶囊，点击平滑滚到可见位置并高亮，替代原先的下拉选择 */
  function stageChipsHtml(stages, activeId) {
    if (!stages.length) return emptyState('暂无阶段');
    return stages.map((s) => `
      <button type="button" class="stage-chip${s.id === activeId ? ' active' : ''}"
        data-action="select-stage-chip" data-id="${esc(s.id)}">
        ${esc(s.name)}
        <span class="stage-chip-count">${num((s.stats || {}).practiceCount)}</span>
      </button>`).join('');
  }

  /** 把当前选中的 chip 平滑滚入可见区域（横向滚动条带） */
  function scrollActiveChipIntoView(smooth) {
    const rail = $('#stage-chips');
    const active = rail && rail.querySelector('.stage-chip.active');
    if (active) active.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', inline: 'center', block: 'nearest' });
  }

  /** 新建练习弹窗（只能 X / 取消关闭） */
  function openPracticeDialog(stageId) {
    if (stageId) practiceStageId = stageId;
    UI.formDialog({
      title: '新建练习',
      icon: 'play',
      okText: '创建练习',
      formId: 'practice-create-form',
      submitId: 'practice-submit',
      body: `
        <form id="practice-create-form" data-form="practice-create">
          <div class="form-grid">
            <div class="field">
              <label>所属阶段 <span class="req">*</span></label>
              <div class="inline-row">
                <select name="stageId" id="stage-select"></select>
                <button type="button" class="btn btn-ghost btn-sm" data-action="reload-stages">
                  ${I.get('refresh', 14)} 刷新
                </button>
                <button type="button" class="link" data-action="new-stage">${I.get('plus', 13)} 新建阶段</button>
              </div>
              <span class="tip" id="stage-select-tip">加载中…</span>
            </div>
            <div class="field">
              <label>范围</label>
              <div class="seg" data-seg="scopeMode">
                <button type="button" data-value="topic" class="active">主题</button>
                <button type="button" data-value="range">范围</button>
              </div>
            </div>
            <div class="field">
              <label>难度</label>
              <div class="seg" data-seg="level">
                <button type="button" data-value="junior">初级</button>
                <button type="button" data-value="mid" class="active">中级</button>
                <button type="button" data-value="senior">高级</button>
              </div>
            </div>
            <div class="field">
              <label>强度</label>
              <div class="seg" data-seg="intensity">
                <button type="button" data-value="quick">速通</button>
                <button type="button" data-value="standard" class="active">标准</button>
                <button type="button" data-value="deep">深挖</button>
              </div>
            </div>
            <div class="field">
              <label>题量</label>
              <div class="seg" data-seg="paperSize">
                <button type="button" data-value="6">6</button>
                <button type="button" data-value="10" class="active">10</button>
                <button type="button" data-value="15">15</button>
              </div>
              <span class="tip">仅常规</span>
            </div>
            <div class="field full" id="scope-topic-field">
              <label>主题 <span class="req">*</span></label>
              <input type="text" name="topic" placeholder="集合、泛型、反射" />
            </div>
            <div class="field full" id="scope-range-field" style="display:none">
              <label>标题 <span class="req">*</span></label>
              <input type="text" name="title" placeholder="javaguide 基础篇" />
            </div>
            <div class="field full" id="scope-range-text" style="display:none">
              <label>范围 <span class="req">*</span></label>
              <textarea name="scopeText" class="tall"
                placeholder="范围是 javaguide 的基础篇上中下，参照 https://javaguide.cn/java/basics/"></textarea>
            </div>
          </div>
        </form>`,
      onOpen: () => loadPracticeStages(renderSeq),
    });
  }

  async function loadPracticeStages(seq) {
    const sel = $('#stage-select');       // 弹窗没打开时为 null，此时只刷新练习列表
    const chips = $('#stage-chips');
    const tip = $('#stage-select-tip');
    const submit = $('#practice-submit');
    let stages;
    try {
      stages = await api('/api/stages');
    } catch (err) {
      if (seq !== renderSeq) return;
      if (tip) tip.textContent = '阶段列表加载失败：' + err.message;
      return;
    }
    if (seq !== renderSeq) return;

    const all = stages || [];
    const actives = all.filter((s) => s.status === 'active');
    if (!all.length) {
      if (sel) {
        sel.innerHTML = '<option value="">暂无阶段</option>';
        sel.disabled = true;
      }
      if (chips) chips.innerHTML = '';
      if (submit) submit.disabled = true;
      if (tip) tip.textContent = '请先创建阶段。';
      const box = $('#practice-list');
      if (box) box.innerHTML = emptyState('还没有阶段');
      return;
    }
    // 页面顶部的阶段筛选：展示所有阶段（含已完成）的练习
    if (!all.some((s) => s.id === practiceStageId)) {
      practiceStageId = (actives[0] || all[0]).id;
    }
    if (chips) {
      chips.innerHTML = stageChipsHtml(all, practiceStageId);
      scrollActiveChipIntoView(false);
    }
    // 新建练习弹窗：只列进行中的阶段
    if (sel) {
      if (!actives.length) {
        sel.innerHTML = '<option value="">暂无进行中的阶段</option>';
        sel.disabled = true;
        if (submit) submit.disabled = true;
        if (tip) tip.textContent = '请先创建阶段。';
      } else {
        // 页面筛选器选的是已完成阶段时，弹窗里退回到第一个进行中的阶段
        const dlgSelected = actives.some((s) => s.id === practiceStageId)
          ? practiceStageId : actives[0].id;
        sel.disabled = false;
        if (submit) submit.disabled = false;
        sel.innerHTML = actives.map((s) => (
          `<option value="${esc(s.id)}" ${s.id === dlgSelected ? 'selected' : ''}>${esc(s.name)}</option>`
        )).join('');
        sel.value = dlgSelected;
        if (tip) tip.textContent = '练习归属到所选阶段';
      }
    }
    loadPracticeList(seq);
  }

  async function loadPracticeList(seq) {
    const box = $('#practice-list');
    if (!box || !practiceStageId) return;
    box.innerHTML = emptyState('加载中…');
    try {
      const stage = await api('/api/stages/' + enc(practiceStageId));
      if (seq !== renderSeq) return;
      pagePractices = stage.practices || [];
      practicePage = 1;
      renderPracticeList();
      const sub = $('#practice-list-sub');
      if (sub) sub.textContent = stage.name || '';
    } catch (err) {
      if (seq !== renderSeq) return;
      box.innerHTML = emptyState('加载失败', err.message);
    }
  }

  /** 练习页的练习缓存：翻页只重绘，不重新请求 */
  let pagePractices = [];

  function renderPracticeList() {
    const box = $('#practice-list');
    if (box) box.innerHTML = practiceCardsHtml(pagePractices, practicePage, 'practice');
  }

  /**
   * 练习卡片网格 + 翻页条。练习页与阶段详情页共用，
   * target（practice / detail-practice）决定翻页条作用于哪份缓存。
   */
  function practiceCardsHtml(list, page, target) {
    if (!list || !list.length) return emptyState('还没有练习');
    const pg = paginate(list, page, PRACTICE_PAGE_SIZE);
    const cards = pg.items.map((p) => {
      const st = p.stats || {};
      const mistakes = p.openMistakeCount
        ? `<span>错题 <b style="color:var(--danger)">${num(p.openMistakeCount)}</b></span>` : '';
      return `
        <div class="item-card" data-action="open-practice" data-id="${esc(p.id)}">
          <div class="item-card-head">
            <div class="li-main">
              <div class="li-title">${esc(p.topic)}</div>
              <div class="li-sub">
                ${p.review ? '<span class="badge badge-orange">全复习</span> ' : ''}
                <span class="badge badge-blue">${levelLabel(p.level)}</span>
                <span class="badge badge-gray">${scopeLabel(p)}</span>
              </div>
            </div>
          </div>
          <div class="item-card-stats">
            <span>卷 <b>${num(st.paperCount)}</b></span>
            <span>均分 <b>${st.averageScore === null || st.averageScore === undefined ? '—' : esc(st.averageScore)}</b></span>
            <span>最近 <b>${st.lastScore === null || st.lastScore === undefined ? '—' : esc(st.lastScore)}</b></span>
            ${st.drillCount ? `<span>刷题 <b>${num(st.drillCount)}</b> · 均 <b>${st.drillAvgScore == null ? '—' : esc(st.drillAvgScore)}</b></span>` : ''}
            ${mistakes}
          </div>
          <div class="item-card-foot">更新于 ${fmtTime(p.updatedAt)}</div>
        </div>`;
    }).join('');
    return `<div class="card-grid">${cards}</div>${pagerHtml(target, pg.cur, pg.pages)}`;
  }

  /* ============================================================
   *  页面：练习进行中
   * ============================================================ */
  async function loadKnowledge(practiceId) {
    try {
      const k = await api('/api/practices/' + enc(practiceId) + '/knowledge');
      knowledgeData = k;
      if (location.hash.startsWith('#/session/')) renderSessionKeepScroll();
    } catch (e) { /* 拿不到就不显示，不影响答题 */ }
  }

  /** 期末考解锁状态 + 历次成绩单 */
  async function loadFinalExam(practiceId) {
    try {
      const gate = await api('/api/practices/' + enc(practiceId) + '/final-exam/gate');
      let exams = [];
      try { exams = await api('/api/practices/' + enc(practiceId) + '/final-exams'); } catch (e) { /* 还没考过 */ }
      finalExamData = { gate, exams: exams || [] };
      if (location.hash.startsWith('#/session/')) renderSessionKeepScroll();
    } catch (e) { /* 拿不到就不显示 */ }
  }

  /**
   * 会话页骨架：页头通栏 + 两栏（主区 / 固定笔记栏）。
   * 主区内容随「答题 / 复盘」视图切换，笔记栏两视图都在——随手记不分场景。
   */
  function sessionShell(view) {
    const isReview = view === 'review';
    const isStudy = view === 'study';
    const isDrill = view === 'drill';
    return `
      <div class="session-head">
        <a class="back-link" href="#/practice">${I.get('back', 15)} 返回练习</a>
        <h1 id="session-title">练习</h1>
        <span id="session-badge"></span>
        <div class="seg session-view-seg" style="margin-left:16px">
          <a href="#/session/${esc(currentSessionId())}" class="${!isReview && !isStudy && !isDrill ? 'active' : ''}">答题</a>
          <a href="#/session/${esc(currentSessionId())}/study" class="${isStudy ? 'active' : ''}">复习</a>
          <a href="#/session/${esc(currentSessionId())}/review" class="${isReview ? 'active' : ''}">复盘</a>
          <a href="#/session/${esc(currentSessionId())}/drill" class="${isDrill ? 'active' : ''}">刷题</a>
        </div>
        <div class="page-actions" style="margin-left:auto" id="session-actions"></div>
      </div>
      <div id="session-scope"></div>
      <div class="session-cols">
        <div class="session-main" id="session-body">${emptyState('加载中…')}</div>
        <aside class="session-aside" id="session-notes">${emptyState('加载中…')}</aside>
      </div>`;
  }

  /** 当前会话页的练习 id（从 hash 取，供视图切换链接用） */
  function currentSessionId() {
    const parts = (location.hash || '').replace(/^#\/?/, '').split('/');
    return parts[1] ? decodeURIComponent(parts[1]) : '';
  }

  /** 当前是「答题 / 复习 / 复盘」——视图状态存在 URL 里，轮询重绘天然保持 */
  function sessionView() {
    const parts = (location.hash || '').replace(/^#\/?/, '').split('/');
    if (parts[2] === 'review') return 'review';
    if (parts[2] === 'study') return 'study';
    if (parts[2] === 'drill') return 'drill';
    return 'answer';
  }

  async function loadSession(id, seq) {
    sessionState = null;
    mistakePage = 1;
    // 语音识别状态：就绪才显示麦克风按钮，否则给明确原因
    if (window.Speech) {
      window.Speech.status().then((s) => {
        speechReady = !!(s && s.ready);
        speechReason = (s && s.reason) || '';
        if (location.hash.startsWith('#/session/')) renderQuestion();
      }).catch(() => {});
    }
    try {
      const p = await api('/api/practices/' + enc(id));
      if (seq !== renderSeq) return;
      sessionState = p;
      pickDefaultPaper();
      // 错题本跳题：hash 切到答题页后走到这里，定位到目标题再渲染
      const jumpQid = jumpToQuestionId;
      jumpToQuestionId = null;
      const located = jumpQid ? locateQuestion(jumpQid) : false;
      if (jumpQid && !located) jumpToQuestionId = jumpQid;   // 考卷里没有：刷题题留给刷题页定位
      renderSession();
      if (located) {
        const card = $('#question-card');
        if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
      schedulePoll();
      // 知识点掌握度与期末考是旁路数据，拿不到不影响答题
      loadKnowledge(id);
      loadFinalExam(id);
      if (sessionView() === 'study') loadStudyDoc(id);
    } catch (err) {
      if (seq !== renderSeq) return;
      const box = $('#session-body');
      if (box) {
        box.innerHTML = `
          <div class="session-head">
            <a class="back-link" href="#/practice">${I.get('back', 15)} 返回练习</a>
            <h1>练习</h1>
          </div>
          ${emptyState('加载失败', err.message)}`;
      }
    }
  }

  function pickDefaultPaper() {
    const papers = (sessionState && sessionState.papers) || [];
    if (!papers.length) { paperIdx = -1; questionIdx = 0; return; }
    const open = papers.findIndex((p) => p.status !== 'graded');
    paperIdx = open >= 0 ? open : papers.length - 1;
    questionIdx = 0;
  }

  /** 按题 id 定位纸与题号。找到则更新 paperIdx/questionIdx 并返回 true */
  function locateQuestion(qid) {
    const papers = (sessionState && sessionState.papers) || [];
    for (let pi = 0; pi < papers.length; pi++) {
      const qi = (papers[pi].questions || []).findIndex((q) => q.id === qid);
      if (qi >= 0) {
        paperIdx = pi;
        questionIdx = qi;
        return true;
      }
    }
    return false;
  }

  function currentPaper() {
    const papers = (sessionState && sessionState.papers) || [];
    return paperIdx >= 0 && paperIdx < papers.length ? papers[paperIdx] : null;
  }

  function currentQuestion() {
    const paper = currentPaper();
    const qs = (paper && paper.questions) || [];
    return qs.length ? qs[Math.min(questionIdx, qs.length - 1)] : null;
  }

  function replaceQuestion(q) {
    if (!q) return;
    if (drillState) {
      let hit = false;
      if (drillState.question && drillState.question.id === q.id) {
        drillState.question = q;
        hit = true;
      }
      const list = drillState.questions || [];
      const i = list.findIndex((x) => x.id === q.id);
      if (i >= 0) { list[i] = q; hit = true; }
      if (hit) return;
    }
    if (!sessionState) return;
    for (const paper of sessionState.papers || []) {
      const list = paper.questions || [];
      const idx = list.findIndex((x) => x.id === q.id);
      if (idx >= 0) { list[idx] = q; return; }
    }
  }

  function latestAttempt(q) {
    const as = (q && q.attempts) || [];
    return as.length ? as[as.length - 1] : null;
  }
  const isAnswered = (q) => !!latestAttempt(q);

  function renderSession() {
    const p = sessionState;
    if (!p || !$('#session-body')) return;
    $('#session-title').textContent = (p.review ? '全复习 · ' : '') + (p.topic || '');
    $('#session-badge').innerHTML = `<span class="badge badge-gray">${scopeLabel(p)}</span>`;
    const scopeBox = $('#session-scope');
    if (scopeBox) scopeBox.innerHTML = scopeLineHtml(p);
    const acts = $('#session-actions');
    if (acts) {
      acts.innerHTML = `
        <button class="btn btn-danger btn-sm" data-action="delete-practice" data-id="${esc(p.id)}">
          ${I.get('trash', 14)} 删除练习
        </button>`;
    }
    const view = sessionView();
    disposeCodeEditor();   // 卸旧编辑器再换 DOM，避免 Monaco 实例泄漏
    $('#session-body').innerHTML = view === 'review' ? reviewBody(p)
      : view === 'study' ? studyBody(p)
      : view === 'drill' ? drillBody(p) : answerBody(p);
    const notesBox = $('#session-notes');
    if (notesBox) notesBox.innerHTML = noteRail(p);
    restoreAnswer();
    initCodeEditor();
    observeAnswerSticky();
    measureQuestionSticky();
    qnavAfterRender($('#qnav-box'));   // 工具栏整段重绘：刷遮罩 + 当前题号滚进可视区
    if (view === 'study') initStudyInteractions(p);
    else maybeLoadFollowUps();
  }

  /**
   * 答题页吸顶控制区（卷切换 + 模式标签 + 继续出题 + 题号导航）的高度，
   * 写进 CSS 变量供题号跳转的 scroll-margin 使用；高度随换行/题数变化自动更新。
   */
  let answerStickyRo = null;
  function observeAnswerSticky() {
    const el = document.querySelector('.answer-sticky');
    if (!el) {
      // 没有答题页控制区：刷题页的翻页条吸顶，题目吸顶落点让到翻页条下面
      const pager = document.querySelector('.drill-pager');
      const setPager = () => {
        const h = pager ? Math.round(pager.getBoundingClientRect().height) : 0;
        document.documentElement.style.setProperty('--answer-sticky-stuck-h', h + 'px');
        document.documentElement.style.setProperty('--answer-sticky-h', h + 'px');
      };
      setPager();
      if (answerStickyRo) answerStickyRo.disconnect();
      if (pager) {
        answerStickyRo = new ResizeObserver(setPager);
        answerStickyRo.observe(pager);
      } else {
        answerStickyRo = null;
      }
      return;
    }
    const set = () => {
      const h = Math.round(el.getBoundingClientRect().height);
      document.documentElement.style.setProperty('--answer-sticky-h', h + 'px');
      // 贴顶紧凑态的高度就是题目吸顶的落点，贴住时实时同步一次
      if (el.classList.contains('is-stuck')) {
        document.documentElement.style.setProperty('--answer-sticky-stuck-h', h + 'px');
      }
    };
    // 首帧同步量一次「紧凑态」高度：临时加类、读数、移除，中间没有绘制不会闪
    if (!el.classList.contains('is-stuck')) {
      el.classList.add('is-stuck');
      const compact = Math.round(el.getBoundingClientRect().height);
      el.classList.remove('is-stuck');
      document.documentElement.style.setProperty('--answer-sticky-stuck-h', compact + 'px');
    }
    set();
    if (answerStickyRo) answerStickyRo.disconnect();
    answerStickyRo = new ResizeObserver(set);
    answerStickyRo.observe(el);
  }

  /**
   * 标题下的范围说明。范围模式显示用户写的 scopeText 全文（默认收起成两行，点击展开）；
   * 主题模式没有范围文本，只补上难度徽章（level 之前在任何页面都看不到）。
   */
  function scopeLineHtml(p) {
    const LEVEL_LABELS = { junior: '初级', mid: '中级', senior: '高级' };
    const badges = `<span class="badge badge-gray">${LEVEL_LABELS[p.level] || '中级'}</span>`;
    const text = (p.scopeText || '').trim();
    if (p.scopeMode !== 'range' || !text) {
      return `<div class="scope-line"><span class="scope-badges">${badges}</span></div>`;
    }
    const key = 'scope:' + p.id;
    const open = isOpen(key, false);
    const long = text.length > 80 || text.includes('\n');
    return `
      <div class="scope-line">
        <span class="scope-badges">${badges}</span>
        <div class="scope-text${open ? ' open' : ''}">${esc(text)}</div>
        ${long ? `<button type="button" class="panel-toggle${open ? '' : ' collapsed'}"
          data-action="toggle-panel" data-key="${esc(key)}" data-default-open="0">
          ${open ? '收起' : '展开全部'}</button>` : ''}
      </div>`;
  }

  /** 整页重绘但保持滚动位置（折叠面板、重出总结这类不该把人弹回顶部的操作） */
  function renderSessionKeepScroll() {
    const y = window.scrollY;
    renderSession();
    window.scrollTo({ top: y, behavior: 'instant' in window ? 'instant' : 'auto' });
  }

  /** 只重绘题目区与题号导航，滚动位置天然保持（切题用，避免被弹回顶部） */
  function renderQuestion() {
    const paper = currentPaper();
    if (!paper) return;
    const qs = paper.questions || [];
    const body = $('#paper-body');
    if (body) {
      disposeCodeEditor();
      body.innerHTML = paper.status === 'answering' ? answeringBody(paper)
        : paper.status === 'graded' ? gradedPaperBody(paper)
        : paper.status === 'generating' ? generatingBody(paper)
        : gradingBody(paper);
    }
    const nav = $('#qnav-box');
    if (nav && qs.length) {
      nav.innerHTML = qnavContentHtml(qs);
      qnavAfterRender(nav);
    }
    restoreAnswer();
    initCodeEditor();
    maybeLoadFollowUps();
    measureQuestionSticky();
  }

  /* ============================================================
   *  题号导航：两态 + 渐变遮罩
   *  未贴顶：换行显示全部题号（qnav-wrap 独占一行，无横向溢出）；
   *  贴顶：单行横向滚动，两侧渐变遮罩替代隐藏的滚动条，
   *  切题后当前题号自动滚进可视区。
   * ============================================================ */

  /** 内容/题号变化后调用：刷新遮罩；横向溢出时把当前题号滚到中间 */
  function qnavAfterRender(box) {
    qnavFadeUpdate();
    if (!box) return;
    const cur = box.querySelector('.qnav-btn.current');
    // 用 scrollLeft 而不是 scrollIntoView：后者在按钮被吸顶条遮住时会连带滚动整页，
    // 和用户的手动滚动打架（抽搐来源之一）。
    if (cur && box.scrollWidth > box.clientWidth + 2) {
      const target = cur.offsetLeft - (box.clientWidth - cur.offsetWidth) / 2;
      box.scrollTo({ left: Math.max(0, target), behavior: 'auto' });
    }
  }

  /** 题号区内容：未贴顶=换行全显；贴顶且题量>20=翻页条；贴顶小卷=单行滚动 */
  function qnavContentHtml(qs) {
    const bar = document.querySelector('.answer-sticky');
    const stuck = !!(bar && bar.classList.contains('is-stuck'));
    return stuck && qs.length > 20 ? qnavPagerHtml(qs) : qnavHtml(qs);
  }

  /** 只重绘题号区（贴顶态切换时用，不动整页） */
  function refreshQnav() {
    const nav = $('#qnav-box');
    if (!nav) return;
    const qs = (currentPaper() || {}).questions || [];
    if (!qs.length) return;
    nav.innerHTML = qnavContentHtml(qs);
    qnavAfterRender(nav);
  }

  /**
   * 贴顶 + 题量大时的翻页条式题号：← 1 … 4 5 6 … 50 →
   * 只显示当前题附近的窗口，首尾题常驻；平时（未贴顶）仍是换行全显。
   */
  function qnavPagerHtml(qs) {
    const n = qs.length;
    const cur = questionIdx;
    const numBtn = (i) => {
      const q = qs[i];
      const cls = 'qnav-pager-btn'
        + (i === cur ? ' current' : '')
        + (isAnswered(q) ? ' done' : '')
        + (q.revealedAt ? ' revealed' : '');
      return `<button class="${cls}" data-action="goto-question" data-idx="${i}" title="第 ${q.sequence} 题">${q.sequence}</button>`;
    };
    const win = [];
    for (let i = Math.max(0, cur - 2); i <= Math.min(n - 1, cur + 2); i++) win.push(i);
    const parts = [];
    parts.push(`<button class="qnav-pager-btn arrow" data-action="goto-question" data-idx="${Math.max(0, cur - 1)}"
      ${cur === 0 ? 'disabled' : ''} title="上一题">${I.get('back', 12)}</button>`);
    if (win[0] > 0) parts.push(numBtn(0));
    if (win[0] > 1) parts.push('<span class="qnav-dots">…</span>');
    win.forEach((i) => parts.push(numBtn(i)));
    if (win[win.length - 1] < n - 2) parts.push('<span class="qnav-dots">…</span>');
    if (win[win.length - 1] < n - 1) parts.push(numBtn(n - 1));
    parts.push(`<button class="qnav-pager-btn arrow" data-action="goto-question" data-idx="${Math.min(n - 1, cur + 1)}"
      ${cur === n - 1 ? 'disabled' : ''} title="下一题"><span class="flip-x">${I.get('back', 12)}</span></button>`);
    return `<div class="qnav-pager">${parts.join('')}</div>`;
  }

  /** 只有真的还有溢出的一侧才亮遮罩（scrollWidth>clientWidth 时两侧才可能同时亮） */
  function qnavFadeUpdate() {
    document.querySelectorAll('.qnav-wrap').forEach((wrap) => {
      const box = wrap.querySelector('.qnav-box');
      if (!box) return;
      const slack = box.scrollWidth - box.clientWidth;
      wrap.classList.toggle('more-right', slack > box.scrollLeft + 2);
      wrap.classList.toggle('more-left', box.scrollLeft > 2);
    });
  }

  // scroll 事件不冒泡，只有捕获阶段拿得到；贴顶单行滚动时刷新遮罩
  document.addEventListener('scroll', (e) => {
    const t = e.target;
    if (t && t.classList && t.classList.contains('qnav-box')) qnavFadeUpdate();
  }, true);
  window.addEventListener('resize', qnavFadeUpdate);

  /** 当前作答的题：刷题视图看刷题题（含翻页正在看的那道），其余看当前卷的题 */
  function activeQuestion() {
    return sessionView() === 'drill' ? drillViewQuestion() : currentQuestion();
  }

  /** 刷题正在看的题：drillIdx 为 null 看最新题，翻页后看历史题 */
  function drillViewQuestion() {
    const d = drillState;
    if (!d) return null;
    const qs = d.questions || [];
    if (!qs.length) return d.question || null;
    const idx = drillIdx == null ? qs.length - 1 : Math.min(Math.max(0, drillIdx), qs.length - 1);
    return qs[idx] || d.question;
  }

  function restoreAnswer() {
    const q = activeQuestion();
    const ta = $('#answer-input');
    if (ta && q) {
      ta.value = drafts[q.id] !== undefined ? drafts[q.id] : (latestAttempt(q) ? latestAttempt(q).answer : '');
    }
    syncCodeEditorFromTextarea();
  }

  /**
   * 答题视图：只放「正在用」的东西——一条进度、卷切换、卷子本体。
   * 回顾类内容（知识点/期末考/错题/历史/记忆/摘要）全在 reviewBody。
   */
  function answerBody(p) {
    const paper = currentPaper();
    return `
      <div class="stagger">
        ${progressStrip(p)}
        <div class="answer-sticky">${answerToolbar(p)}</div>
        ${paper ? paperSection(paper, p) : `
          <div class="card">
            ${emptyState('尚未出题', '',
              `<div class="muted" style="margin-bottom:10px">选好组卷模式后，点上方「开始出题」。</div>
               <a class="link" href="#/session/${esc(p.id)}/study">第一次考？先看复习提纲</a>`)}
          </div>`}
      </div>`;
  }

  /**
   * 答题页常驻工具栏：卷切换 + 题号导航 + 组卷模式 + 开始/继续出题，全部收在一行。
   * 吸顶后进入紧凑态（更小的按钮/隐藏说明），把竖向空间尽量留给正文。
   */
  function answerToolbar(p) {
    const papers = p.papers || [];
    const qs = (currentPaper() || {}).questions || [];
    // 后端 findOpenPaper 是看**整个练习**里有没有未交卷的卷，不只看当前卷；
    // 所以这里也必须按任意开放卷判断，否则切到旧卷会误以为可以继续出题
    const open = papers.find((x) => x.status !== 'graded');
    const label = papers.length ? '继续出题' : '开始出题';
    const disabled = open
      ? `disabled title="第 ${open.sequence} 张卷还没交卷，先交卷才能继续出题"`
      : '';
    return `
      <div class="answer-toolbar">
        ${papers.length ? paperSwitcher(p) : '<span class="ps-pos muted">还没有卷</span>'}
        <div class="answer-mode">
          <div class="seg" data-seg="paperMode">
            ${Object.keys(MODE_LABELS).map((k) => `<button type="button" data-value="${k}" class="${k === paperMode ? 'active' : ''}">${MODE_LABELS[k]}</button>`).join('')}
          </div>
          <span class="tip" id="paper-mode-tip">${MODE_TIPS[paperMode] || ''}</span>
          ${open ? `<span class="gen-blocked">第 ${open.sequence} 张卷未交卷</span>` : ''}
        </div>
        ${qs.length ? `<div class="qnav-wrap"><div id="qnav-box" class="qnav-box">${qnavContentHtml(qs)}</div></div>` : '<span class="qnav-box"></span>'}
        <div class="answer-gen">
          <button class="btn btn-primary btn-sm" data-action="generate-paper" data-id="${esc(p.id)}" ${disabled}>
            ${I.get('play', 13)} ${label}
          </button>
        </div>
      </div>`;
  }

  /**
   * 复盘视图：**4 个 Tab**，一次只渲染当前页。
   * 默认「知识点」——复习时先看掌握情况，再翻错题。
   */
  function reviewBody(p) {
    const tabs = [
      ['knowledge', '知识点'],
      ['mistakes', '错题'],
      ['history', '历史'],
      ['archive', '档案'],
    ];
    let panel = '';
    if (reviewTab === 'mistakes') panel = mistakesPanel(p);
    else if (reviewTab === 'history') panel = historySection(p.papers || [], currentPaper());
    else if (reviewTab === 'archive') panel = `<div class="stagger">${studyArchivePanel(p)}${finalExamPanel(finalExamData, p)}</div>`;
    else panel = knowledgePanel(knowledgeData);
    return `
      <div class="tabs">
        ${tabs.map(([k, label]) => `
          <button class="tab${reviewTab === k ? ' active' : ''}" data-action="review-tab" data-tab="${k}">
            ${label}${k === 'mistakes' && (p.mistakes || []).length ? `<span class="tab-badge num">${(p.mistakes || []).length}</span>` : ''}
          </button>`).join('')}
      </div>
      <div class="tab-panel">${panel}</div>`;
  }

  /** 答题视图顶部的一行进度：够用就好，不再占一整块面板 */
  function progressStrip(p) {
    const st = (knowledgeData && knowledgeData.stats) || null;
    const paper = currentPaper();
    // 今日进度：覆盖 → 掌握 → 待巩固
    return `
      <div class="progress-strip">
        <span>覆盖 <b>${st ? st.covered : '—'}/${st ? st.total : '—'}</b></span>
        <span>掌握 <b>${st ? st.mastered : '—'}</b></span>
        <span>待巩固 <b>${st ? st.weak : '—'}</b></span>
        ${paper ? `<span>第 <b>${paper.sequence}</b> 张卷${paper.score != null ? ` · 得分 <b>${paper.score}</b>` : ''}</span>` : ''}
        <a class="spacer link" href="#/session/${esc(p.id)}/review">看复盘 <span class="flip-x">${I.get('back', 13)}</span></a>
      </div>`;
  }

  /* ============================================================
   *  考前复习提纲：全量内容，按「必学 / 理解 / 选学」分层展示
   * ============================================================ */

  function studyBody(p) {
    if (!studyDoc) return `<div class="card">${emptyState('加载中…')}</div>`;
    if (studyDoc.status === 'none') return studyIntro(p);
    if (studyDoc.status === 'generating') return studyProgressCard(p);
    if (studyDoc.status === 'failed') return studyFailed(p);
    return studyReady(p);
  }

  function studyIntro(p) {
    const total = (studyDoc && studyDoc.planned)
      || (knowledgeData && knowledgeData.stats && knowledgeData.stats.total) || 0;
    return `
      <div class="card study-intro">
        <h2>考前复习</h2>
        <p class="muted">${total ? `约 ${total} 个点 · 10-15 分钟` : ''} · 按「必学 / 理解 / 选学」分层展示，内容不受复习强度限制</p>
        <div class="btn-row">
          <button class="btn btn-primary" data-action="study-generate" data-id="${esc(p.id)}">
            ${I.get('play', 15)} 生成复习提纲
          </button>
          <span class="tip">生成中可以先去答题，回来继续看</span>
        </div>
      </div>`;
  }

  function studyProgressCard(p) {
    const pr = studyDoc.progress || {};
    const done = pr.done || studyDoc.done || 0;
    const planned = pr.planned || studyDoc.planned || 0;
    const pct = planned ? Math.min(100, Math.round(done * 100 / planned)) : 0;
    // 后端返回的 running 是进程内实时状态：false = 上次中断了，给续跑入口
    if (pr.running === false) {
      return `
        <div class="card">
          <h2>复习提纲生成已中断</h2>
          <div class="study-progress-line">
            <span>已完成 <b>${done}</b> / ${planned || '—'}</span>
          </div>
          <p class="tip">继续生成只补没完成的点，已完成的不重做。</p>
          <div class="btn-row">
            <button class="btn btn-primary" data-action="study-generate" data-id="${esc(p.id)}">继续生成</button>
            <button class="btn btn-ghost" data-action="study-regenerate" data-id="${esc(p.id)}">重新生成</button>
          </div>
        </div>`;
    }
    return `
      <div class="card">
        <h2>正在生成复习提纲</h2>
        <div class="study-progress-line">
          <span>已完成 <b>${done}</b> / ${planned || '—'}</span>
          <span class="num">${pct}%</span>
        </div>
        <div class="study-bar"><i style="width:${pct}%"></i></div>
        <p class="tip">可以去答题，生成会自动继续；回到这个页面会自动刷新。</p>
      </div>`;
  }

  function studyFailed(p) {
    const pr = studyDoc.progress || {};
    const done = (studyDoc.content && studyDoc.content.points || []).length;
    return `
      <div class="card">
        <h2>复习提纲生成不完整</h2>
        <div class="progress-error">${esc(pr.error || '生成失败，请重试')}</div>
        <div class="btn-row">
          <button class="btn btn-primary" data-action="study-generate" data-id="${esc(p.id)}">继续生成</button>
          <button class="btn btn-ghost" data-action="study-regenerate" data-id="${esc(p.id)}">重新生成</button>
        </div>
      </div>
      ${done ? studyReady(p) : ''}`;
  }

  function studyReady(p) {
    const points = (studyDoc.content && studyDoc.content.points) || [];
    if (!points.length) return `<div class="card">${emptyState('还没有内容')}</div>`;
    const read = readStudySet(p.id);
    const groups = new Map();
    points.forEach((pt) => {
      const cat = pt.category || '综合';
      if (!groups.has(cat)) groups.set(cat, []);
      groups.get(cat).push(pt);
    });
    // 目录两级：大标题=分类，小标题=每个知识点（带高频/常见/偏门标注），点哪跳哪
    let base = 0;
    const toc = [...groups.entries()].map(([cat, list], i) => {
      const subs = list.map((pt, k) => {
        const pr = pt.priority || 'medium';
        const label = pr === 'high' ? '高频' : pr === 'low' ? '偏门' : '常见';
        return `
          <button class="study-toc-sub" data-action="study-jump" data-target="sp-${base + k}"
            data-priority="${pr}" title="${esc(pt.name)} · ${label}">
            <span>${esc(pt.name)}</span>
            <i class="prio-dot prio-${pr}" title="优先级：${label}"></i>
          </button>`;
      }).join('');
      base += list.length;
      return `
        <div class="toc-group">
          <button class="study-toc-item" data-action="study-jump" data-target="study-cat-${i}">
            <span>${esc(cat)}</span><b>${list.length}</b>
          </button>
          ${subs}
        </div>`;
    }).join('');
    let idx = 0;
    const sections = [...groups.entries()].map(([cat, list], i) => `
      <section class="study-cat" id="study-cat-${i}">
        <h3>${esc(cat)} <span class="sub">${list.length}</span></h3>
        ${list.map((pt) => studyPointCard(pt, idx++, read)).join('')}
      </section>`).join('');
    const highCount = points.filter((x) => x.priority === 'high').length;
    const readN = Math.min(read.size, points.length);
    return `
      <div class="study-head">
        <div>
          <h2>复习提纲 <span class="sub">${points.length} 个点 · 高频 ${highCount}</span></h2>
          <div class="study-read">
            已读 <b id="study-read-n">${readN}</b> / ${points.length}
            <span class="study-read-bar"><i id="study-read-bar" style="width:${points.length ? readN * 100 / points.length : 0}%"></i></span>
          </div>
        </div>
        <div class="study-tools">
          <div class="study-tools-quick" id="study-tools-quick">
            <button class="btn btn-ghost btn-sm${studyOnlyHigh ? ' active' : ''}" data-action="study-only-high">只看高频</button>
            <button class="btn btn-ghost btn-sm" data-action="study-toggle-all">全部展开</button>
          </div>
          <button class="btn btn-ghost btn-sm" data-action="study-regenerate" data-id="${esc(p.id)}">重新生成</button>
        </div>
      </div>
      <div class="study-legend">
        <span><i class="depth-dot depth-quick"></i>必学</span>
        <span><i class="depth-dot depth-std"></i>理解</span>
        <span><i class="depth-dot depth-deep"></i>选学</span>
        <i class="legend-sep"></i>
        <span><i class="prio-dot prio-high"></i>高频</span>
        <span><i class="prio-dot prio-medium"></i>常见</span>
        <span><i class="prio-dot prio-low"></i>偏门</span>
      </div>
      <div class="study-layout">
        <aside class="study-toc${studyOnlyHigh ? ' only-high' : ''}">${toc}</aside>
        <div class="study-content${studyOnlyHigh ? ' only-high' : ''}" id="study-content">${sections}</div>
      </div>
      <div class="study-cta">
        <button class="btn btn-primary btn-lg" data-action="study-exam" data-id="${esc(p.id)}">学完了，去考试</button>
      </div>`;
  }

  function studyPointCard(pt, idx, read) {
    const pr = pt.priority || 'medium';
    const cls = pr === 'high' ? 'badge-red' : pr === 'low' ? 'badge-gray' : 'badge-blue';
    const label = pr === 'high' ? '高频' : pr === 'low' ? '偏门' : '常见';
    const params = pt.params || [];
    const pitfalls = pt.pitfalls || [];
    const sc = pt.selfCheck;
    return `
      <article class="study-point${read.has(pt.name) ? ' read' : ''}"
        data-priority="${pr}" data-name="${esc(pt.name)}" id="sp-${idx}">
        <div class="study-point-head">
          <h4>${esc(pt.name)}</h4>
          <span class="badge ${cls}">${label}</span>
        </div>
        <div class="study-block depth-quick">
          <div class="study-block-label">必学 · 一句话</div>
          <div class="study-block-text">${rich(pt.summary || '')}</div>
        </div>
        ${pt.interview ? `
        <div class="study-block depth-quick">
          <div class="study-block-label">面试怎么问</div>
          <div class="study-block-text">${rich(pt.interview)}</div>
        </div>` : ''}
        <details class="study-more">
          <summary>展开理解与选学</summary>
          ${pt.principle ? `
          <div class="study-block depth-std">
            <div class="study-block-label">理解 · 原理</div>
            <div class="study-block-text">${rich(pt.principle)}</div>
          </div>` : ''}
          ${params.length ? `
          <div class="study-block depth-std">
            <div class="study-block-label">理解 · 关键参数</div>
            <ul class="study-list">${params.map((x) => `<li>${rich(x)}</li>`).join('')}</ul>
          </div>` : ''}
          ${pitfalls.length ? `
          <div class="study-block depth-deep">
            <div class="study-block-label">选学 · 常见坑</div>
            <ul class="study-list">${pitfalls.map((x) => `<li>${rich(x)}</li>`).join('')}</ul>
          </div>` : ''}
          ${sc && sc.q ? `
          <div class="study-block depth-deep">
            <div class="study-block-label">选学 · 自测</div>
            <div class="study-block-text">${rich(sc.q)}</div>
            ${sc.a ? `<details class="self-answer"><summary>看答案</summary><div>${rich(sc.a)}</div></details>` : ''}
          </div>` : ''}
        </details>
      </article>`;
  }

  /** 卡片进入视口 60% 即记「已读」（localStorage，按练习存） */
  function initStudyInteractions(p) {
    if (studyObserver) { studyObserver.disconnect(); studyObserver = null; }
    studyTocActiveId = '';   // 工具栏/正文整段重绘后按新 DOM 重算目录高亮
    studyTocActiveSub = '';
    updateStudyTocActive();
    updateStudyToolsFloat();
    if (!studyDoc || studyDoc.status !== 'ready') return;
    if (!('IntersectionObserver' in window)) return;
    const cards = $$('.study-point');
    if (!cards.length) return;
    const read = readStudySet(p.id);
    studyObserver = new IntersectionObserver((entries) => {
      let changed = false;
      entries.forEach((en) => {
        if (!en.isIntersecting) return;
        const name = en.target.dataset.name;
        if (!name || read.has(name)) return;
        read.add(name);
        en.target.classList.add('read');
        changed = true;
      });
      if (changed) {
        saveStudyRead(p.id, read);
        updateStudyReadCount(read.size, cards.length);
      }
    }, { threshold: 0.6 });
    cards.forEach((c) => studyObserver.observe(c));
  }

  function updateStudyReadCount(done, total) {
    const n = document.getElementById('study-read-n');
    if (n) n.textContent = String(Math.min(done, total));
    const bar = document.getElementById('study-read-bar');
    if (bar) bar.style.width = (total ? Math.min(100, done * 100 / total) : 0) + '%';
  }

  function studyReadKey(id) { return 'mr.study.read.' + id; }
  function readStudySet(id) {
    try { return new Set(JSON.parse(localStorage.getItem(studyReadKey(id)) || '[]')); }
    catch (e) { return new Set(); }
  }
  function saveStudyRead(id, set) {
    try { localStorage.setItem(studyReadKey(id), JSON.stringify([...set])); } catch (e) { /* 忽略 */ }
  }

  function clearStudyPoll() {
    if (studyPollTimer) { clearTimeout(studyPollTimer); studyPollTimer = null; }
  }

  async function loadStudyDoc(id) {
    try {
      studyDoc = await api('/api/practices/' + enc(id) + '/review-doc');
    } catch (e) {
      studyDoc = null;
    }
    if (sessionView() === 'study') renderSessionKeepScroll();
    scheduleStudyPoll(id);
  }

  function scheduleStudyPoll(id) {
    clearStudyPoll();
    if (!studyDoc || studyDoc.status !== 'generating') return;
    studyPollTimer = setTimeout(async () => {
      try {
        studyDoc = await api('/api/practices/' + enc(id) + '/review-doc');
      } catch (e) { /* 网络抖动继续轮询 */ }
      if (sessionView() === 'study') renderSessionKeepScroll();
      scheduleStudyPoll(id);
    }, 3000);
  }

  /** 紧凑卷切换器：答题时切卷用，完整历史在复盘视图。有 1 张卷也显示位置 */
  function paperSwitcher(p) {
    const papers = p.papers || [];
    if (!papers.length) return '';
    const paper = currentPaper();
    const idx = paper ? papers.findIndex((x) => x.id === paper.id) : -1;
    const prev = idx > 0 ? papers[idx - 1] : null;
    const next = idx >= 0 && idx < papers.length - 1 ? papers[idx + 1] : null;
    return `
      <div class="paper-switch">
        <button class="btn btn-ghost btn-sm ps-arrow" data-action="goto-paper" data-idx="${idx - 1}"
          ${prev ? 'title="上一张"' : 'disabled title="已经是第一张"'}>
          ${I.get('back', 12)}
        </button>
        <span class="ps-pos">第 ${paper ? paper.sequence : '—'}/${papers.length} 张</span>
        <button class="btn btn-ghost btn-sm ps-arrow" data-action="goto-paper" data-idx="${idx + 1}"
          ${next ? 'title="下一张"' : 'disabled title="已经是最后一张"'}>
          <span class="flip-x">${I.get('back', 12)}</span>
        </button>
        <select id="paper-select" title="切换卷">
          ${papers.map((x, i) => `<option value="${i}" ${i === idx ? 'selected' : ''}>第 ${x.sequence} 张 · ${PAPER_STATUS[x.status] || x.status}${x.score != null ? ` · ${x.score} 分` : ''}</option>`).join('')}
        </select>
      </div>`;
  }

  /** 学习档案：长期记忆 + 会话摘要。本质是喂给 AI 的出题上下文，默认收起 */
  function studyArchivePanel(p) {
    // 注意：memoryBody / digestBody 自己就带 .result-block + .rb-body 外壳，
    // 这里**不能**再包一层 .rb-body——那层是 white-space:pre-wrap，
    // 会把模板字符串里的换行缩进全渲染成空白行（我踩过一次，整块大留白）。
    return collapsiblePanel('archive', 'panel-neutral', 'folder',
      '学习档案', '长期记忆 · 会话摘要',
      memoryBody(p.memory) + digestBody(p.digest), { defaultOpen: false });
  }

  /** 可折叠面板：收起时只留标题行 */
  /**
   * 可折叠面板。
   * @param opts.defaultOpen 没动过时是展开还是收起（默认展开）
   */
  function collapsiblePanel(key, cls, icon, title, meta, contentHtml, opts = {}) {
    const defaultOpen = opts.defaultOpen !== false;
    const open = isOpen(key, defaultOpen);
    return `
      <div class="panel ${cls}${open ? '' : ' collapsed'}">
        <h3>
          ${I.get(icon, 15)} ${esc(title)}
          ${meta ? `<span class="muted">${meta}</span>` : ''}
          <button class="panel-toggle${open ? '' : ' collapsed'}" data-action="toggle-panel"
                  data-key="${esc(key)}" data-default-open="${defaultOpen ? '1' : '0'}">
            ${open ? '收起' : '展开'}
          </button>
        </h3>
        <div class="panel-content">${contentHtml}</div>
      </div>`;
  }

  /** 知识点掌握度：进度环 + 逐点条 + 小趋势 */
  function knowledgePanel(k) {
    if (!k || !k.stats) return '';
    const st = k.stats;
    const pts = k.points || [];
    const pct = st.percent || 0;

    // 按大类分组
    const byCat = new Map();
    pts.forEach((x) => {
      const c = x.category || '综合';
      if (!byCat.has(c)) byCat.set(c, []);
      byCat.get(c).push(x);
    });

    // 100+ 个点的清单全铺开会吃掉整屏，所以大类默认收起、可逐个展开。
    // 「有待巩固」的优先展开，但**最多自动展开 2 个**——否则早期到处都有弱项，
    // 等于全展开，还是看不到头。用户手动点开的会记在 sessionStorage 里。
    const catStats = [...byCat.entries()].map(([cat, list]) => {
      const counts = { 已掌握: 0, 学习中: 0, 待巩固: 0, 未学: 0 };
      let sum = 0;
      list.forEach((p) => {
        counts[p.status] = (counts[p.status] || 0) + 1;
        sum += p.mastery || 0;
      });
      return {
        cat, list, counts,
        avg: list.length ? Math.round(sum / list.length) : 0,
        weak: counts['待巩固'] || 0,
      };
    });
    // 待巩固多的排前，同数时平均掌握度低的排前
    const autoOpen = new Set(catStats
      .filter((s) => s.weak > 0)
      .sort((a, b) => (b.weak - a.weak) || (a.avg - b.avg))
      .slice(0, 2)
      .map((s) => s.cat));

    const groups = catStats.map(({ cat, list, counts, avg, weak }) => {
      const open = isOpen('kp:' + cat, autoOpen.has(cat));
      const barCls = avg >= 80 ? 'bar-ok' : avg >= 50 ? 'bar-mid' : 'bar-low';
      return `
        <div class="kp-group${open ? ' open' : ''}">
          <div class="kp-cat" data-action="toggle-kp-group" data-cat="${esc(cat)}"
               data-default-open="${autoOpen.has(cat) ? '1' : '0'}" role="button" tabindex="0">
            <span class="caret">▶</span>
            <span class="cat-name">${esc(cat)}</span>
            <span class="cat-count">${list.length} 个点</span>
            <span class="cat-bar"><i class="${barCls}" style="width:${Math.max(2, avg)}%"></i></span>
            <span class="cat-stats">
              ${counts['已掌握'] ? `<b style="color:var(--success)">${counts['已掌握']}掌握</b>` : ''}
              ${counts['学习中'] ? `<b style="color:var(--accent)">${counts['学习中']}学习中</b>` : ''}
              ${weak ? `<b style="color:var(--warning)">${weak}待巩固</b>` : ''}
              ${counts['未学'] ? `<b style="color:var(--text-3)">${counts['未学']}未学</b>` : ''}
            </span>
          </div>
          <div class="kp-rows">${list.map(knowledgeRow).join('')}</div>
        </div>`;
    }).join('');

    // 覆盖顺序：高频 → 常见 → 偏门。强度只决定每道题考多深，不再把某些点排除在外
    const intensity = (sessionState && sessionState.intensity) || 'standard';
    const lowCount = pts.filter((p) => p.priority === 'low').length;
    const scopeNote = intensity === 'deep'
      ? `全部 ${pts.length} 个点都覆盖，每个点按深挖规格考`
      : `覆盖顺序：高频 → 常见${lowCount ? ` → 偏门（${lowCount} 个，排最后）` : ''}；当前强度：${INTENSITY_LABELS[intensity] || '标准'}`;

    return collapsiblePanel('knowledge', 'panel-neutral', 'chart', '知识点掌握度',
      `已掌握 ${st.mastered}/${st.total} · 平均 ${st.avgMastery}`, `
        <div class="kp-overview">
          <div class="kp-ring" style="--pct:${pct}">
            <span class="kp-ring-num">${pct}<i>%</i></span>
            <span class="kp-ring-label">已掌握</span>
          </div>
          <div class="kp-legend">
            <div><b>${st.total}</b><span>知识点总数</span></div>
            <div><b>${st.covered}</b><span>已覆盖</span></div>
            <div><b>${st.mastered}</b><span>已掌握</span></div>
            <div><b>${st.avgMastery}</b><span>平均掌握度</span></div>
          </div>
        </div>
        <div class="kp-scope-note">${esc(scopeNote)}</div>
        ${pts.length ? groups : '<div class="empty" style="padding:20px 0">还没有知识点清单，出一次题后会自动建立。</div>'}`);
  }

  /**
   * 一个知识点：名称 + 掌握度条 + 出现次数 + 状态 + 小趋势 + **可直接背诵的记忆点**。
   *
   * 记忆点是批改时 AI 给的「一句话背下来就够」的要点。以前只拼进点评文本、看完就散，
   * 现在挂到知识点下面——复习某个点时能直接看到「这个点该背什么」。
   */
  function knowledgeRow(kp) {
    const ok = kp.status === '已掌握';
    const learning = kp.status === '学习中';
    const cls = ok ? 'kp-ok' : kp.status === '待巩固' ? 'kp-weak' : learning ? 'kp-learn' : 'kp-new';
    const badge = ok ? 'badge-green' : kp.status === '待巩固' ? 'badge-orange'
      : learning ? 'badge-blue' : 'badge-gray';
    const hist = kp.history || [];
    const angles = kp.angles || [];
    const angleTip = angles.length ? '已用过的问法：\n' + angles.join('\n') : '还没出过题';
    const mems = (knowledgeData && knowledgeData.memoryPoints && knowledgeData.memoryPoints[kp.name]) || [];
    return `
      <div class="kp-row${mems.length ? ' has-mem' : ''}">
        <div class="kp-name" title="${esc(kp.name)}">${esc(kp.name)}</div>
        <div class="kp-bar"><div class="kp-fill ${cls}" style="width:${Math.max(2, Math.round(kp.mastery))}%"></div></div>
        <div class="kp-meta">
          <span class="kp-pct num">${Math.round(kp.mastery)}</span>
          <span class="kp-seen" title="${esc(angleTip)}">×${kp.timesSeen}${angles.length ? '·' + angles.length + '式' : ''}</span>
          ${kp.priority ? `<span class="badge ${kp.priority === 'high' ? 'badge-red' : kp.priority === 'low' ? 'badge-gray' : 'badge-blue'}" title="${esc((kp.boundStd || '') + (kp.boundDeep ? '\n深挖：' + kp.boundDeep : ''))}">${kp.priority === 'high' ? '高频' : kp.priority === 'low' ? '偏门' : '常见'}</span>` : ''}
          <span class="badge ${badge}" title="${esc(statusHint(kp, hist))}">${esc(kp.status)}</span>
          ${spark(hist)}
        </div>
      </div>
      ${mems.length ? `
      <div class="kp-mem">
        <div class="kp-mem-title">📌 该背什么</div>
        ${mems.map((t) => `<div class="kp-mem-item">${rich(t)}</div>`).join('')}
      </div>` : ''}`;
  }

  /** 这个点还差什么才能算「已掌握」——写出来，免得只看徽章瞎猜 */
  function statusHint(kp, hist) {
    if (kp.status === '已掌握') return '最近 2 次变种题都及格，稳了';
    if (kp.status === '未学') return '还没出过题';
    // 数一下连续及格了几次（及格线 6 分）
    let streak = 0;
    for (let i = hist.length - 1; i >= 0; i--) {
      if ((hist[i] || 0) >= 6) streak++; else break;
    }
    const need = Math.max(0, 2 - streak);
    if (kp.status === '学习中') return `目前都及格，但只考过 ${kp.timesSeen} 次。再连续考对 ${need} 次变种题就算掌握`;
    return `近几次有失手。把连对次数补到 2 次就算掌握（当前连续对 ${streak} 次）`;
  }

  /** 迷你趋势：历次得分的柱状小图 */
  function spark(history) {
    if (!history || history.length < 2) return '';
    const max = 10;
    return '<span class="spark">' + history.slice(-8).map((v) => {
      const h = Math.max(8, Math.round(((v || 0) / max) * 100));
      return `<i style="height:${h}%" class="${(v || 0) >= 6 ? 'up' : 'down'}"></i>`;
    }).join('') + '</span>';
  }

  /* ---------- 期末综合考 ---------- */

  /**
   * 期末考是**总结性测量**，跟普通卷（形成性学习）分开：
   * 不带记忆/摘要/历史出题、不回写掌握度、不能看参考答案。可多次申请。
   */
  function finalExamPanel(f, practice) {
    if (!f || !f.gate) return '';
    const g = f.gate;
    const exams = f.exams || [];

    const action = `
      <div class="btn-row" style="justify-content:center; margin:10px 0 4px">
        <button class="btn btn-primary btn-lg"
                data-action="final-exam" data-id="${esc(practice.id)}">
          ${I.get('award', 15)} 申请期末考
        </button>
      </div>
      <div class="empty" style="padding:6px 0 12px">${esc(g.hint || '')}</div>`;

    const list = exams.length ? exams.map(finalExamCard).join('')
      : '<div class="empty" style="padding:12px 0">还没考过期末考。</div>';

    return collapsiblePanel('final-exam', 'panel-neutral', 'award', '期末综合考',
      `随时可考 · 已覆盖 ${g.coveredPoints || 0}/${g.totalPoints || 0} · 共 ${exams.length} 次`, `
        ${action}
        ${list}
        <div class="muted" style="padding:8px 0; font-size:var(--fs-md)">
          期末考是全范围综合测验：不带记忆与历史出题、不回写掌握度、答题时不能看参考答案。
          成绩单只做验收，学习进度仍以「知识点掌握度」为准。
        </div>`);
  }

  /** 一张成绩单：总分 + 抽样覆盖率 + 按大类得分率 + 与上次对比 */
  function finalExamCard(ex, idx, all) {
    const prev = (finalExamData?.exams || [])[idx + 1]; // 列表是最新在前
    const delta = (ex.score != null && prev && prev.score != null)
      ? Math.round((ex.score - prev.score) * 10) / 10 : null;
    const cov = ex.coverage || {};
    const rateCls = (r) => (r >= 80 ? 'bar-ok' : r >= 60 ? 'bar-mid' : 'bar-low');

    const cats = (ex.categories || []).map((c) => `
      <div class="fe-cat">
        <div class="fe-cat-name">${esc(c.category)} <span class="muted">${c.asked} 题</span></div>
        <div class="kp-bar"><div class="kp-fill ${rateCls(c.rate)}" style="width:${Math.max(2, Math.round(c.rate))}%"></div></div>
        <div class="fe-cat-rate num">${Math.round(c.rate)}%</div>
      </div>`).join('');

    return `
      <div class="result-block fe-card">
        <h4>第 ${ex.sequence} 卷 · ${ex.status === 'graded' ? '已出分' : '进行中'}
          ${delta != null ? `<span class="badge ${delta >= 0 ? 'badge-green' : 'badge-orange'}">${delta >= 0 ? '+' : ''}${delta}</span>` : ''}
        </h4>
        <div class="fe-head">
          <div class="fe-score num">${ex.score == null ? '—' : Math.round(ex.score * 10) / 10}<i>/100</i></div>
          <div class="fe-cov">
            <div>抽样覆盖 <b>${cov.coveredPoints ?? '—'}/${cov.totalPoints ?? '—'}</b> 个点</div>
            <div>大类覆盖 <b>${cov.coveredCategories ?? '—'}/${cov.totalCategories ?? '—'}</b></div>
            <div class="muted">20 道题物理上盖不住全部点，此处如实标注抽样率</div>
          </div>
        </div>
        ${cats ? `<div class="fe-cats">${cats}</div>` : ''}
      </div>`;
  }

  /** 会话摘要块（自带 .result-block 外壳，可直接拼进任意容器） */
  function digestBody(dig) {
    if (!dig) return '';
    return `<div class="result-block">
        <h4>${I.get('list', 13)} 会话摘要</h4>
        ${dig.overview ? `<div class="rb-body">${esc(dig.overview)}</div>` : ''}
        ${dig.level ? `<div class="rb-body" style="margin-top:8px">${esc(dig.level)}</div>` : ''}
        ${tagList('已覆盖大块', dig.coveredAreas, 'check', 'ok')}
        ${tagList('尚未覆盖', dig.uncoveredAreas, 'close', 'muted')}
      </div>`;
  }

  /** 长期记忆块（自带 .result-block 外壳，可直接拼进任意容器） */
  function memoryBody(mem) {
    if (!mem) return '';
    return `<div class="result-block">
        <h4>${I.get('star', 13)} 长期记忆</h4>
        ${tagList('已掌握', mem.mastered, 'check', 'ok')}
        ${tagList('薄弱点', mem.weakPoints, 'close', 'weak')}
        ${tagList('理解偏差', mem.misconceptions, 'info', 'weak')}
        ${tagList('下一步', mem.nextFocus, 'target')}
        ${tagList('已覆盖知识点', mem.coverage, 'list', 'muted')}
      </div>`;
  }

  function paperSection(paper, p) {
    const qs = paper.questions || [];
    const answered = qs.filter(isAnswered).length;
    const isGraded = paper.status === 'graded';
    return `
      <div class="card">
        <div class="paper-head">
          <h2>${I.get('list', 15)} 第 ${paper.sequence} 张卷
            <span class="sub">
              ${PAPER_STATUS[paper.status] || paper.status} · ${qs.length} 题 ·
              满分 ${num(paper.maxScore)}${paper.score !== null ? ` · 得分 <b>${esc(paper.score)}</b>` : ''}
              ${paper.compositionMode && paper.compositionMode !== 'regular' ? ` · <b>${MODE_LABELS[paper.compositionMode] || paper.compositionMode}</b>` : ''}
            </span>
          </h2>
          ${paperActions(paper, p, answered)}
        </div>
        ${tierBreakdown(qs)}
        ${isGraded ? paperSummaryHtml(paper) : ''}
        <div id="paper-body">
          ${paper.status === 'generating' ? generatingBody(paper) : ''}
          ${paper.status === 'grading' ? gradingBody(paper) : ''}
          ${paper.status === 'answering' ? answeringBody(paper) : ''}
          ${isGraded ? gradedPaperBody(paper) : ''}
        </div>
      </div>`;
  }

  function paperActions(paper, p, answered) {
    if (paper.status === 'generating' || paper.status === 'grading') {
      const pr = paper.progress || {};
      return pr.running === false ? `
        <div class="page-actions">
          <button class="btn btn-primary" data-action="resume-paper" data-id="${esc(paper.id)}">
            ${I.get('play', 15)} 续跑
          </button>
        </div>` : '';
    }
    if (paper.status === 'answering') {
      return `
        <div class="page-actions">
          <button class="btn btn-primary" data-action="submit-paper" data-id="${esc(paper.id)}">
            ${I.get('send', 15)} 交卷
          </button>
        </div>`;
    }
    // 已交卷：出下一张卷的入口在答复页顶部常驻工具栏（与卷切换放在一起）
    return '';
  }

  function tierBreakdown(qs) {
    if (!qs.length) return '';
    return `<div class="tier-bar">${TIER_ORDER.map((tier) => {
      const list = qs.filter((q) => (q.tier || '基础') === tier);
      if (!list.length) return '';
      const pts = list.reduce((a, q) => a + (q.points || 0), 0);
      return `<span class="tier-stat">${tierChip(tier)} ${list.length} 题 · ${pts} 分</span>`;
    }).filter(Boolean).join('')}</div>`;
  }

  function progressBox(paper, label) {
    const pr = paper.progress || {};
    const planned = pr.planned || 0;
    const done = pr.done || 0;
    const pct = planned ? Math.round(done / planned * 100) : 6;
    return `
      <div class="progress-box">
        <div class="progress-line">
          <span>${esc(pr.stage || label)}${pr.running ? '中' : ''} · ${done}${planned ? ' / ' + planned : ''}</span>
          <span class="num">${planned ? pct + '%' : ''}</span>
        </div>
        <div class="progress-track">
          <div class="progress-fill" style="width:${planned ? pct : 6}%"></div>
        </div>
        ${pr.error ? `<div class="progress-error">${I.get('close', 14)} ${esc(pr.error)}</div>` : ''}
        ${(pr.failed || []).length ? `<div class="progress-error">${I.get('close', 14)} 第 ${(pr.failed || []).join('、')} 批失败，点「续跑」</div>` : ''}
        ${!pr.error && !(pr.failed || []).length
          ? `<div class="hint">${pr.running ? '任务进行中，可切换到其他页面，回来仍可看到进度。' : '任务已停止，点右上角「续跑」从断点继续。'}</div>`
          : ''}
      </div>
      ${qsPreview(paper)}`;
  }

  const generatingBody = (paper) => progressBox(paper, '出题');
  const gradingBody = (paper) => progressBox(paper, '批改');

  function qsPreview(paper) {
    const qs = paper.questions || [];
    if (!qs.length) return '';
    return `
      <div class="result-block">
        <h4>已生成题目（${qs.length}）</h4>
        <div class="rb-body"><ul>${qs.map((q) => (
          `<li>${tierChip(q.tier)} ${q.sequence}.（${esc(q.points)} 分）${esc((q.prompt || '').slice(0, 58))}…</li>`
        )).join('')}</ul></div>
      </div>`;
  }

  /* ---------- 答题中 ---------- */
  /* ---------- 语音作答 ---------- */
  function micButtonHtml(q) {
    // 浏览器只在 localhost / HTTPS 下开放麦克风；用局域网 IP 打开时直接说明白，别让人干等权限弹窗
    const h = location.hostname;
    const insecure = location.protocol !== 'https:'
      && h !== 'localhost' && h !== '127.0.0.1' && h !== '[::1]';
    if (insecure) {
      return `<button class="btn btn-ghost" disabled
        title="浏览器只在 localhost 或 HTTPS 下允许使用麦克风，你现在是 ${esc(location.host)}">
        ${I.get('mic', 15)} 语音作答
      </button>`;
    }
    if (!speechReady) {
      return `<button class="btn btn-ghost" disabled title="${esc(speechReason || '语音识别未就绪')}">
        ${I.get('mic', 15)} 语音作答
      </button>`;
    }
    if (speechState === 'recording') {
      return `<button class="btn btn-danger mic-on" data-action="mic" data-qid="${esc(q.id)}">
        <span class="mic-dot"></span> 录音中 · <span class="mic-elapsed num">0:00</span> · 点此结束
      </button>`;
    }
    if (speechState === 'transcribing') {
      return `<button class="btn btn-ghost" disabled>
        <span class="spinner"></span> 转写中…
      </button>`;
    }
    return `<button class="btn btn-ghost" data-action="mic" data-qid="${esc(q.id)}">
      ${I.get('mic', 15)} 语音作答
    </button>`;
  }

  /* ---- 考试模式：按题型出作答控件（单选/多选/填空/简答/代码） ---- */

  /** 客观题（单选/多选/填空）：控件作答 + 服务端自动判分 */
  function isObjectiveQ(q) {
    return q.qtype === 'single' || q.qtype === 'multi' || q.qtype === 'blank';
  }

  function parseOptions(json) {
    try { return JSON.parse(json || '[]'); } catch (e) { return []; }
  }

  function blankCount(q) {
    try {
      const ans = JSON.parse(q.answerJson || '{}');
      if (ans.blanks && ans.blanks.length) return ans.blanks.length;
    } catch (e) { /* 坏数据走题面兜底 */ }
    return (String(q.prompt || '').match(/___/g) || []).length || 1;
  }

  /** 作答区：客观题出控件，简答/代码/常规文字题出文本框 */
  function answerInputHtml(q) {
    const a = latestAttempt(q);
    const draft = drafts[q.id] !== undefined ? drafts[q.id] : (a ? a.answer : '');
    if (q.qtype === 'single' || q.qtype === 'multi') {
      const opts = parseOptions(q.optionsJson);
      const picked = new Set(String(draft || '').toUpperCase().split(/[,，、;\s]+/).filter(Boolean));
      return `
        <div class="exam-opts" data-qtype="${esc(q.qtype)}" data-qid="${esc(q.id)}">
          ${opts.map((o) => `
            <label class="exam-opt">
              <input type="${q.qtype === 'single' ? 'radio' : 'checkbox'}" name="exam-opt-${esc(q.id)}"
                value="${esc(o.key)}" ${picked.has(String(o.key).toUpperCase()) ? 'checked' : ''} />
              <span class="opt-key">${esc(o.key)}</span>
              <span class="opt-text">${esc(o.text)}</span>
            </label>`).join('')}
        </div>`;
    }
    if (q.qtype === 'blank') {
      const n = blankCount(q);
      const lines = String(draft || '').split('\n');
      return `
        <div class="exam-blanks" data-qid="${esc(q.id)}">
          ${Array.from({ length: n }, (_, i) => `
            <label class="exam-blank">
              <span class="blank-no">第 ${i + 1} 空</span>
              <input type="text" data-blank="${i}" value="${esc(lines[i] || '')}" placeholder="答案" />
            </label>`).join('')}
        </div>`;
    }
    const isCode = q.qtype === 'code';
    const codeOn = isCode || codeMode[q.id];
    return `
      ${codeOn ? '<div id="code-editor" class="code-editor"></div>' : ''}
      <textarea id="answer-input" data-qid="${esc(q.id)}" class="${isCode ? 'exam-code' : ''}"
        ${codeOn ? 'hidden' : ''}
        placeholder="${codeOn ? '写下你的代码…' : '写下你的回答…'}"></textarea>`;
  }

  /** 收集当前作答文本：客观题从控件取（单选/多选逗号分隔，填空每空一行），文本题从 textarea 取 */
  function collectAnswerValue(q) {
    if (!q) return '';
    if (q.qtype === 'single' || q.qtype === 'multi') {
      return [...document.querySelectorAll('.exam-opts input:checked')].map((i) => i.value).join(',');
    }
    if (q.qtype === 'blank') {
      return [...document.querySelectorAll('.exam-blanks input')].map((i) => i.value.trim()).join('\n');
    }
    const ta = $('#answer-input');
    return ta && ta.value ? ta.value : '';
  }

  /* ---- 代码编辑器（Monaco）：IDE 级输入体验 ----
   * textarea 始终是权威草稿存储（语音、术语纠正等直写 textarea 的路径不用改），
   * Monaco 只是挂在它上面的视觉层，双向同步：编辑器改动写回 textarea（走既有 input 事件），
   * textarea 被程序改写后调 syncCodeEditorFromTextarea 推给编辑器。
   * 懒加载：只有打开代码题才拉起；加载失败自动回退纯文本框。 */
  let codeEditor = null;
  let monacoLoader = null;

  function ensureMonaco() {
    if (window.monaco) return Promise.resolve(window.monaco);
    if (monacoLoader) return monacoLoader;
    monacoLoader = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = 'lib/monaco/vs/loader.js';
      s.onload = () => {
        window.require.config({ paths: { vs: 'lib/monaco/vs' } });
        window.require(['vs/editor/editor.main'], () => resolve(window.monaco), reject);
      };
      s.onerror = () => reject(new Error('monaco loader failed'));
      document.head.appendChild(s);
    });
    return monacoLoader;
  }

  /** Java 智能提示：关键字 / 常用类 / 点号成员 / 代码片段（无需后端语言服务） */
  function registerJavaCompletion(monaco) {
    if (monaco.__javaCompletion) return;
    monaco.__javaCompletion = true;

    const KEYWORDS = ('abstract assert boolean break byte case catch char class const continue default do double else enum '
      + 'extends final finally float for if implements import instanceof int interface long native new package private '
      + 'protected public return short static super switch synchronized this throw throws transient try void volatile while '
      + 'true false null var record').split(' ');

    const TYPES = ('String Filter Integer Long Double Boolean Character Object Class System Math Exception RuntimeException '
      + 'StringBuilder Comparable Iterable List Map Set ArrayList HashMap HashSet LinkedList TreeMap LinkedHashMap '
      + 'Arrays Collections Objects Stream Optional Collectors LocalDate LocalDateTime BigDecimal Thread Runnable '
      + 'Callable ExecutorService Executors File IOException RuntimeException Error Comparator Queue Stack '
      + 'ArrayList<Integer> ArrayList<String> HashMap<String').split(' ');

    const SNIPPETS = [
      { label: 'main', detail: '主方法', body: 'public static void main(String[] args) {\n\t$0\n}' },
      { label: 'sout', detail: '打印', body: 'System.out.println($0);' },
      { label: 'soutf', detail: '格式化打印', body: 'System.out.printf("$1", $0);' },
      { label: 'fori', detail: 'for 循环', body: 'for (int ${1:i} = 0; ${1:i} < ${2:n}; ${1:i}++) {\n\t$0\n}' },
      { label: 'foreach', detail: 'for-each', body: 'for (${1:Object} ${2:e} : ${3:items}) {\n\t$0\n}' },
      { label: 'while', detail: 'while 循环', body: 'while (${1:cond}) {\n\t$0\n}' },
      { label: 'if', detail: 'if', body: 'if (${1:cond}) {\n\t$0\n}' },
      { label: 'ifelse', detail: 'if-else', body: 'if (${1:cond}) {\n\t$0\n} else {\n\t\n}' },
      { label: 'try', detail: 'try-catch', body: 'try {\n\t$0\n} catch (${1:Exception} e) {\n\te.printStackTrace();\n}' },
      { label: 'tryres', detail: 'try-with-resources', body: 'try (${1:var} = ${2:expr}) {\n\t$0\n} catch (${3:Exception} e) {\n\te.printStackTrace();\n}' },
      { label: 'switch', detail: 'switch', body: 'switch (${1:expr}) {\n    case ${2:value}:\n        $0\n        break;\n    default:\n        break;\n}' },
      { label: 'class', detail: '类定义', body: 'public class ${1:Name} {\n\t$0\n}' },
      { label: 'method', detail: '方法定义', body: 'public ${1:void} ${2:name}(${3:params}) {\n\t$0\n}' },
      { label: 'ctor', detail: '构造器', body: 'public ${1:Name}($2) {\n\t$0\n}' },
      { label: 'new', detail: 'new 对象', body: '${1:Type} ${2:var} = new ${1:Type}($0);' },
      { label: 'newlist', detail: 'new ArrayList', body: 'List<${1:String}> ${2:list} = new ArrayList<>();$0' },
      { label: 'newmap', detail: 'new HashMap', body: 'Map<${1:String}, ${2:Object}> ${3:map} = new HashMap<>();$0' },
      { label: 'strb', detail: 'StringBuilder', body: 'StringBuilder ${1:sb} = new StringBuilder();$0' },
    ];

    /** 点号后面的常用成员（按接收者末段名粗匹配；变量类型推不了，够用即可） */
    const MEMBERS = {
      'System.out': ['println', 'print', 'printf', 'flush'],
      'System.err': ['println', 'print', 'printf'],
      out: ['println', 'print', 'printf'],
      Arrays: ['asList', 'sort', 'copyOf', 'fill', 'stream', 'binarySearch', 'toString', 'equals'],
      Collections: ['sort', 'reverse', 'max', 'min', 'unmodifiableList', 'emptyList', 'singleton', 'shuffle', 'frequency'],
      Math: ['abs', 'max', 'min', 'pow', 'sqrt', 'cbrt', 'round', 'floor', 'ceil', 'random', 'log', 'log10', 'exp'],
      Objects: ['equals', 'hash', 'requireNonNull', 'toString', 'isNull', 'nonNull', 'deepEquals'],
      Optional: ['of', 'ofNullable', 'empty', 'isPresent', 'isEmpty', 'orElse', 'orElseGet', 'orElseThrow', 'map', 'filter', 'flatMap'],
      Collectors: ['toList', 'toSet', 'toMap', 'joining', 'counting', 'summarizingInt', 'groupingBy', 'partitioningBy'],
      String: ['length', 'isEmpty', 'isBlank', 'equals', 'equalsIgnoreCase', 'compareTo', 'indexOf', 'lastIndexOf',
        'substring', 'replace', 'replaceAll', 'split', 'trim', 'strip', 'toUpperCase', 'toLowerCase', 'charAt',
        'startsWith', 'endsWith', 'contains', 'repeat', 'chars', 'concat', 'valueOf', 'format', 'join', 'matches'],
      str: ['length', 'isEmpty', 'equals', 'indexOf', 'substring', 'replace', 'split', 'trim', 'toUpperCase', 'toLowerCase', 'charAt', 'contains'],
      sb: ['append', 'insert', 'delete', 'deleteCharAt', 'replace', 'reverse', 'toString', 'length', 'setLength', 'charAt', 'indexOf'],
      StringBuilder: ['append', 'insert', 'delete', 'reverse', 'toString', 'length'],
      list: ['add', 'remove', 'get', 'set', 'size', 'isEmpty', 'clear', 'contains', 'indexOf', 'lastIndexOf', 'subList', 'stream', 'forEach', 'sort', 'toArray'],
      items: ['add', 'remove', 'get', 'size', 'isEmpty', 'clear', 'contains', 'stream', 'forEach', 'iterator'],
      List: ['of', 'copyOf'],
      ArrayList: ['add', 'remove', 'get', 'set', 'size', 'isEmpty', 'clear', 'contains', 'sort', 'stream', 'forEach', 'toArray'],
      map: ['put', 'get', 'remove', 'containsKey', 'containsValue', 'keySet', 'values', 'entrySet', 'size', 'isEmpty', 'clear', 'computeIfAbsent', 'getOrDefault', 'forEach', 'putIfAbsent'],
      Map: ['of', 'copyOf', 'entry'],
      HashMap: ['put', 'get', 'remove', 'containsKey', 'keySet', 'values', 'entrySet', 'size', 'isEmpty', 'clear'],
      set: ['add', 'remove', 'contains', 'size', 'isEmpty', 'clear', 'stream', 'forEach'],
      Set: ['of', 'copyOf'],
      stream: ['map', 'filter', 'forEach', 'collect', 'count', 'findFirst', 'findAny', 'sorted', 'distinct', 'limit', 'skip', 'flatMap', 'anyMatch', 'allMatch', 'noneMatch', 'reduce', 'toList', 'min', 'max'],
      System: ['out', 'err', 'currentTimeMillis', 'nanoTime', 'arraycopy', 'getProperty', 'exit'],
      e: ['getMessage', 'getStackTrace', 'printStackTrace', 'toString', 'getCause'],
      ex: ['getMessage', 'getStackTrace', 'printStackTrace', 'toString', 'getCause'],
    };

    monaco.languages.registerCompletionItemProvider('java', {
      triggerCharacters: ['.'],
      provideCompletionItems(model, position) {
        const line = model.getValueInRange({
          startLineNumber: position.lineNumber, startColumn: 1,
          endLineNumber: position.lineNumber, endColumn: position.column,
        });
        const word = (line.match(/[A-Za-z_$][A-Za-z0-9_$]*$/) || [''])[0];
        const range = {
          startLineNumber: position.lineNumber, startColumn: position.column - word.length,
          endLineNumber: position.lineNumber, endColumn: position.column,
        };
        const raw = line.slice(0, position.column - word.length - 1).replace(/\s+$/, '');

        // 点号成员补全（接收者 = 点号前的标识符链，尾部的点不算在内）
        if (raw.endsWith('.')) {
          const dm = raw.slice(0, -1).replace(/\s+$/, '').match(/([A-Za-z_$][A-Za-z0-9_$.]*)$/);
          if (dm) {
            const key = dm[1];
            const tail = key.split('.').pop();
            const list = MEMBERS[key] || MEMBERS[tail];
            if (list) {
              return {
                suggestions: list.map((name) => ({
                  label: name,
                  kind: monaco.languages.CompletionItemKind.Method,
                  insertText: name,
                  sortText: '0' + name,
                  range,
                })),
              };
            }
          }
          return { suggestions: [] };
        }

        // 词首补全：片段 → 类 → 关键字
        const sn = SNIPPETS.map((x) => ({
          label: x.label,
          kind: monaco.languages.CompletionItemKind.Snippet,
          insertText: x.body,
          insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
          detail: x.detail,
          sortText: '0' + x.label,
          range,
        }));
        const ty = TYPES.map((x) => ({
          label: x,
          kind: monaco.languages.CompletionItemKind.Class,
          insertText: x,
          sortText: '1' + x,
          range,
        }));
        const kw = KEYWORDS.map((x) => ({
          label: x,
          kind: monaco.languages.CompletionItemKind.Keyword,
          insertText: x,
          sortText: '2' + x,
          range,
        }));
        return { suggestions: [...sn, ...ty, ...kw] };
      },
    });
  }

  /** 把当前题的作答框升级成 Monaco（仅代码题）；已建好则只同步内容 */
  function initCodeEditor() {
    const q = activeQuestion();
    const host = $('#code-editor');
    const ta = $('#answer-input');
    if (!q || !(q.qtype === 'code' || codeMode[q.id]) || !host || !ta) return;
    if (codeEditor && codeEditor.__host === host) {
      if (codeEditor.getValue() !== ta.value) codeEditor.setValue(ta.value);
      return;
    }
    const wanted = host;
    ensureMonaco().then((monaco) => {
      if (!document.body.contains(wanted)) return;   // 已切题/切页，丢弃
      disposeCodeEditor();
      codeEditor = monaco.editor.create(wanted, {
        value: ta.value || '',
        language: 'java',
        theme: 'vs',
        fontFamily: 'JetBrains Mono, SF Mono, Consolas, monospace',
        fontSize: 13,
        minimap: { enabled: false },
        automaticLayout: true,
        scrollBeyondLastLine: false,
        wordWrap: 'on',
        tabSize: 4,
        renderWhitespace: 'none',
        quickSuggestions: { other: true, comments: false, strings: true },
        tabCompletion: 'on',
        acceptSuggestionOnEnter: 'on',
        suggestSelection: 'first',
        bracketPairColorization: { enabled: true },
      });
      codeEditor.__host = wanted;
      registerJavaCompletion(monaco);
      codeEditor.onDidChangeModelContent(() => {
        const v = codeEditor.getValue();
        if (ta.value !== v) {
          ta.value = v;
          ta.dispatchEvent(new Event('input', { bubbles: true }));
        }
      });
    }).catch(() => {
      // Monaco 起不来（离线/加载失败）：退回纯文本框，功能不受影响
      if (document.body.contains(wanted)) ta.hidden = false;
    });
  }

  /** textarea 被程序改写（语音追加 / 术语纠正 / 恢复草稿）后，推给编辑器 */
  function syncCodeEditorFromTextarea() {
    const ta = $('#answer-input');
    if (codeEditor && ta && codeEditor.getValue() !== ta.value) {
      codeEditor.setValue(ta.value);
    }
  }

  function disposeCodeEditor() {
    if (codeEditor) {
      try { codeEditor.dispose(); } catch (e) { /* 已随 DOM 卸载 */ }
      codeEditor = null;
    }
  }

  /* ============================================================
   *  刷题：无题量限制、一题一题流式（出题 → 作答 → 完成批改 → 下一题）
   * ============================================================ */
  let drillState = null;
  let drillLoadedFor = null;
  let drillLoading = false;
  /** 统计窗口：近 X 题（用户可选），驱动曲线与「近 X 题平均」 */
  let drillWindow = 10;
  /** 翻页：正在看第几题（null = 最新题）；历史题可回看，没答过的还能补答 */
  let drillIdx = null;
  /** 趋势图模式：score = 每题得分，avg = 累计平均分（看进步路线，单题失手不至断崖） */
  let drillChartMode = 'score';
  /** 上次渲染的连对值：只在增长时给数字加跳动 */
  let lastStreakShown = null;
  /** 上次快照：跨过阈值时触发里程碑庆祝 */
  let drillPrev = null;

  function drillBody(p) {
    if (drillLoadedFor !== p.id) {
      loadDrillState(p);
      return `<div class="card">${emptyState('加载中…')}</div>`;
    }
    const d = drillState || {};
    if (!d.started) {
      return `
        <div class="card drill-intro">
          <h2>刷题</h2>
          <p class="muted" style="margin:10px 0 16px;max-width:62ch">
            AI 按你的薄弱知识点逐题出题，答完点「完成」立刻批改评分，掌握度与错题本照常更新。
            掌握越稳题越深：没考过/露怯先出<b>概念辨析</b>打记忆，学习中出<b>原理追问</b>讲机制，已掌握出<b>场景应用</b>实战确认。
            没有题量限制；得分 6 分以上算掌握一次。
          </p>
          <div class="btn-row">
            <button class="btn btn-primary" data-action="drill-start" data-id="${esc(p.id)}">开始刷题</button>
          </div>
        </div>`;
    }
    const qs = (d.questions && d.questions.length) ? d.questions : (d.question ? [d.question] : []);
    // 错题本「看原题」跳题：状态已就绪，在这里定位到目标题
    if (jumpToQuestionId) {
      const j = qs.findIndex((x) => x.id === jumpToQuestionId);
      jumpToQuestionId = null;
      if (j >= 0) {
        drillIdx = j;
        requestAnimationFrame(() => {
          const card = $('#question-card');
          if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
        });
      } else {
        UI.toast('没找到这道题', 'warning');
      }
    }
    const total = qs.length;
    const idx = total ? (drillIdx == null ? total - 1 : Math.min(Math.max(0, drillIdx), total - 1)) : 0;
    const q = qs[idx] || d.question;
    const isTail = !total || idx === total - 1;
    const a = q ? latestAttempt(q) : null;
    const ev = a && a.evaluation;
    return `
      <div class="drill-stack">
        ${drillStatsBlock(d)}
        ${drillTodayRow(d)}
        ${q ? `
          ${drillPagerHtml(idx, total, isTail)}
          <div class="question-card ${TIER_CLS[q.tier] || ''}" id="question-card">
            <div class="q-seq">
              <span>刷题 · 第 ${idx + 1} / ${total} 题</span>
              ${tierChip(q.tier)}
              <span class="pts-chip">${esc(q.questionType || '简答')}</span>
              ${!isTail && ev ? `<span class="score-chip ${scoreCls(ev.points / Math.max(1, q.points))}">得分 ${esc(ev.points)} / ${num(q.points)}</span>` : ''}
            </div>
            <div class="q-text">${rich(q.prompt)}</div>
          </div>
          ${ev ? drillResultHtml(q, a, ev, isTail, total) : drillAnswerHtml(q)}
        ` : `<div class="card">${emptyState('出题中…')}</div>`}
      </div>
    `;
  }

  /** 刷题翻页条：历史题随便翻（没答过的还能补答），出新题只在最新题答完后 */
  function drillPagerHtml(idx, total, isTail) {
    if (total <= 1) return '';
    return `
      <div class="drill-pager">
        <button class="btn btn-ghost btn-sm" data-action="drill-view" data-idx="${idx - 1}" ${idx <= 0 ? 'disabled' : ''}>
          ${I.get('back', 13)} 上一题
        </button>
        <span class="drill-pager-no">第 ${idx + 1} / ${total} 题</span>
        <button class="btn btn-ghost btn-sm" data-action="drill-view" data-idx="${idx + 1}" ${isTail ? 'disabled' : ''}>
          下一题 <span class="flip-x">${I.get('back', 13)}</span>
        </button>
        ${isTail ? '' : `
          <span style="flex:1"></span>
          <button class="btn btn-soft btn-sm" data-action="drill-view" data-idx="${total - 1}">
            ${I.get('target', 13)} 回到最新
          </button>`}
      </div>`;
  }

  function drillAnswerHtml(q) {
    return `
      <div class="card answer-area">
        <h2>作答
          <button class="btn btn-ghost btn-sm code-toggle${codeMode[q.id] ? ' active' : ''}"
            data-action="toggle-code-mode" data-qid="${esc(q.id)}"
            title="切换代码输入（语法高亮 / 自动缩进 / 提示）">
            ${I.get('code', 13)} 代码
          </button>
          <button class="btn btn-ghost btn-sm" data-action="insert-table" data-qid="${esc(q.id)}"
            title="插入 Markdown 表格模板（批改能读懂表格内容）">
            ${I.get('table', 13)} 表格
          </button>
        </h2>
        ${answerInputHtml(q)}
        <div class="btn-row">
          ${micButtonHtml(q)}
          <button class="btn btn-strong" data-action="drill-submit" data-qid="${esc(q.id)}">完成</button>
          <span style="flex:1"></span>
          <button class="btn btn-fjord" data-action="reveal" data-qid="${esc(q.id)}" ${q.revealedAt ? 'disabled' : ''}>
            ${I.get('eye', 13)} ${q.revealedAt ? '已看答案' : '看参考答案'}
          </button>
        </div>
      </div>
      ${q.revealedAt ? `
        ${referenceAnswerPanel(q)}
        <div class="card peek-note-bar">
          <span class="sub">看完了，记一笔</span>
          <button class="btn btn-ghost btn-sm" data-action="quick-note" data-qid="${esc(q.id)}" data-scene="answering">
            ${I.get('pencil', 14)} 记笔记
          </button>
        </div>` : ''}`;
  }

  /** 统计栏：一行概览常驻，点开看趋势图与近 X 题选择 */
  function drillStatsBlock(d) {
    const open = isOpen('drill-stats', false);
    const scores = d.scores || [];
    const win = drillWindow;
    const recent = scores.slice(-win);
    const recentAvg = recent.length
      ? Math.round(recent.reduce((a, b) => a + b, 0) / recent.length * 10) / 10
      : null;
    return `
      <div class="drill-stats">
        <button class="drill-stats-head${open ? ' open' : ''}" data-action="toggle-drill-stats">
          <span>已刷 <b>${d.count}</b> 题</span>
          <span>平均 <b>${d.avgScore == null ? '—' : esc(d.avgScore)}</b>/10</span>
          <span>近 ${win} 题 <b>${recentAvg == null ? '—' : esc(recentAvg)}</b>/10</span>
          <span>连对 <b class="${streakBumpClass(d.streak)}">${d.streak}</b></span>
          <i class="drill-caret"></i>
        </button>
        <div class="drill-stats-body" ${open ? '' : 'hidden'}>
          <div class="drill-win">
            <span class="drill-win-label">统计窗口</span>
            ${[10, 20, 30, 50].map((n) => `
              <button class="drill-win-btn${n === win ? ' active' : ''}"
                data-action="drill-window" data-n="${n}">近 ${n} 题</button>`).join('')}
          </div>
          <div class="drill-stats-split">
            ${scoreTrendHtml(recent, scores.length - recent.length)}
            ${drillAchievementsHtml(d)}
          </div>
        </div>
      </div>`;
  }

  /** 连对跳动：值比上次渲染大时给数字一个弹跳 */
  function streakBumpClass(streak) {
    const prev = lastStreakShown;
    lastStreakShown = streak;
    return prev != null && streak > prev ? 'streak-bump' : '';
  }

  /** 今日目标条：今天已刷 X / 10，达成后常亮 */
  function drillTodayRow(d) {
    const done = d.todayCount || 0;
    const target = 10;
    const pct = Math.min(100, Math.round(done / target * 100));
    return `
      <div class="drill-today${done >= target ? ' done' : ''}">
        <span class="drill-today-label">今日目标</span>
        <div class="drill-today-bar"><i style="width:${pct}%"></i></div>
        <span class="drill-today-num"><b>${done}</b> / ${target}</span>
      </div>`;
  }

  /** 成就墙：全部从刷题数据推出来（点亮 / 置灰） */
  function drillAchievementsHtml(d) {
    const n = d.count || 0;
    const ms = d.maxStreak || 0;
    const today = d.todayCount || 0;
    const avg = d.avgScore;
    const items = [
      { icon: 'flag', label: '首次刷题', ok: n >= 1 },
      { icon: 'star', label: '刷题 10', ok: n >= 10 },
      { icon: 'star', label: '刷题 50', ok: n >= 50 },
      { icon: 'award', label: '刷题 100', ok: n >= 100 },
      { icon: 'award', label: '刷题 500', ok: n >= 500 },
      { icon: 'target', label: '连对 3', ok: ms >= 3 },
      { icon: 'target', label: '连对 5', ok: ms >= 5 },
      { icon: 'target', label: '连对 10', ok: ms >= 10 },
      { icon: 'chart', label: '均分破 7', ok: avg != null && avg >= 7 },
      { icon: 'check', label: '今日达标', ok: today >= 10 },
    ];
    return `
      <div class="drill-ach">
        <div class="drill-trend-sub">成就</div>
        <div class="drill-ach-grid">
          ${items.map((a) => `
            <div class="drill-ach-item${a.ok ? ' on' : ''}" title="${a.label}">
              <span class="drill-ach-icon">${I.get(a.icon, 15)}</span>
              <span class="drill-ach-label">${a.label}</span>
            </div>`).join('')}
        </div>
      </div>`;
  }

  /** 里程碑庆祝：跨过阈值就 toast / 连对庆祝动画（不弹窗） */
  function celebrateDrill(d) {
    const prev = drillPrev || { count: 0, streak: 0, todayCount: 0 };
    const crossed = (now, was, t) => now >= t && was < t;
    [10, 50, 100, 500].forEach((t) => {
      if (crossed(d.count || 0, prev.count, t)) {
        UI.toast(`已刷 ${t} 题，里程碑达成 🎉`, 'success', '里程碑');
      }
    });
    [3, 5, 10].forEach((t) => {
      if (crossed(d.streak || 0, prev.streak, t)) celebrateStreak(t);
    });
    if (crossed(d.todayCount || 0, prev.todayCount, 10)) {
      UI.toast('今日目标达成 🎉', 'success', '里程碑');
    }
    drillPrev = { count: d.count || 0, streak: d.streak || 0, todayCount: d.todayCount || 0 };
  }

  /** 连对庆祝：中央大字一闪 + 少量彩色纸屑，不打断刷题 */
  function celebrateStreak(n) {
    const colors = ['#5a7a6b', '#7ba0b8', '#c9a88c', '#607683'];
    const dots = Array.from({ length: 12 }, (_, i) =>
      `<i style="--i:${i};background:${colors[i % colors.length]}"></i>`).join('');
    const host = document.createElement('div');
    host.className = 'streak-burst';
    host.innerHTML = `<span class="streak-burst-text">🔥 连对 ${n}</span>${dots}`;
    document.body.appendChild(host);
    setTimeout(() => host.remove(), 1700);
  }

  /** 得分趋势：折线 + 掌握线；画线动画、逐点入场、悬停出明细。
   *  可切「平均分」：每题后的累计平均（总分 ÷ 题数）——看进步路线，单题失手不至断崖。 */
  function scoreTrendHtml(scores, offset) {
    if (!scores || scores.length < 2) return '';
    const isAvg = drillChartMode === 'avg';
    // 累计平均：第 i 题答完后的平均分（例：得 8 分、再得 6 分 → 8、7）
    const vals = isAvg
      ? scores.map((_, i) => Math.round(scores.slice(0, i + 1).reduce((a, b) => a + b, 0) / (i + 1) * 10) / 10)
      : scores;
    const n = vals.length;
    const W = 640, H = 170, padX = 30, padY = 16;
    const x = (i) => padX + (i * (W - padX * 2)) / Math.max(1, n - 1);
    const y = (v) => H - padY - (Math.max(0, Math.min(10, v)) / 10) * (H - padY * 2);
    const pts = vals.map((v, i) => x(i).toFixed(1) + ',' + y(v).toFixed(1)).join(' ');
    const dots = vals.map((v, i) => `
      <circle class="chart-dot" style="animation-delay:${240 + i * 55}ms"
        cx="${x(i).toFixed(1)}" cy="${y(v).toFixed(1)}" r="3.5"
        fill="${v >= 6 ? '#5a7a6b' : '#c17767'}">
        <title>第 ${offset + i + 1} 题 · ${isAvg ? `平均 ${v}（本题得 ${scores[i]} 分）` : `${scores[i]} / 10`}</title>
      </circle>`).join('');
    return `
      <div class="drill-trend">
        <div class="drill-trend-sub">
          <span>${isAvg ? '平均分趋势 · 每题后的累计平均（总分 ÷ 题数）' : '得分趋势 · 虚线为掌握线（6 分）'} · 悬停看点明细</span>
          <span class="drill-chart-seg">
            <button class="drill-win-btn${isAvg ? '' : ' active'}" data-action="drill-chart" data-mode="score">得分</button>
            <button class="drill-win-btn${isAvg ? ' active' : ''}" data-action="drill-chart" data-mode="avg">平均分</button>
          </span>
        </div>
        <svg class="score-chart" viewBox="0 0 ${W} ${H}">
          <line x1="${padX}" y1="${y(6)}" x2="${W - padX}" y2="${y(6)}"
            stroke="rgba(201,168,140,.9)" stroke-dasharray="5 5" stroke-width="1.5"/>
          <polyline class="chart-line" pathLength="1" points="${pts}" fill="none" stroke="#5a7a6b" stroke-width="2"
            stroke-linejoin="round" stroke-linecap="round"/>
          ${dots}
        </svg>
      </div>`;
  }

  function drillResultHtml(q, a, ev, isTail, total) {
    return `
      <div class="card answer-area">
        <h2>批改结果
          <span class="score-chip ${scoreCls(ev.points / Math.max(1, q.points))}">得分 ${esc(ev.points)} / ${num(q.points)}</span>
        </h2>
        <div class="result-block">
          <h4>我的作答</h4>
          <div class="answer-box">${esc(a.answer || '')}</div>
        </div>
        <div class="result-block">
          <h4>点评</h4>
          <div class="review-box">${richFeedback(ev.feedback)}</div>
        </div>
        <div class="btn-row">
          ${isTail
            ? `<button class="btn btn-primary" data-action="drill-next">${I.get('star', 14)} 出下一题</button>`
            : `<button class="btn btn-primary" data-action="drill-view" data-idx="${total - 1}">${I.get('target', 14)} 回到最新</button>`}
        </div>
      </div>
      ${referenceAnswerPanel(q)}
      ${followUpPanel(q)}
      <div class="card peek-note-bar">
        <span class="sub">看完了，记一笔</span>
        <button class="btn btn-ghost btn-sm" data-action="quick-note" data-qid="${esc(q.id)}" data-scene="grading">
          ${I.get('pencil', 14)} 记笔记
        </button>
      </div>`;
  }

  function loadDrillState(p) {
    if (drillLoading) return;
    drillLoading = true;
    api('/api/practices/' + enc(p.id) + '/drill').then((d) => {
      drillLoading = false;
      drillState = d;
      drillLoadedFor = p.id;
      if (sessionView() === 'drill') renderSessionKeepScroll();
    }).catch((err) => {
      drillLoading = false;
      UI.toast(err.message, 'error', '刷题状态加载失败');
    });
  }

  function answeringBody(paper) {
    const q = currentQuestion();
    if (!q) return emptyState('出题中');
    const qs = paper.questions || [];
    const a = latestAttempt(q);
    const revealed = !!q.revealedAt;

    return `
      <div class="question-card ${TIER_CLS[q.tier] || ''}" id="question-card">
        <div class="q-seq">
          <span>第 ${q.sequence} / ${qs.length} 题</span>
          ${tierChip(q.tier)}
          <span class="pts-chip">${esc(q.points)} 分</span>
          <span class="pts-chip">${esc(q.questionType)}</span>
          ${revealed ? '<span class="badge badge-orange">已看答案 · 计 0 分</span>' : ''}
        </div>
        <div class="q-text">${rich(q.prompt)}</div>
        <!-- 答题时不显示「本题要求」「考察点」：题面必须自足，用户看题面就该知道答什么。
             要求是批改契约，不是答题提示——批改后才展示。 -->
      </div>

      <div class="card answer-area">
        <h2>作答 <span class="sub">${a ? `已保存 ${a.sequence} 次` : ''}</span>
          ${!isObjectiveQ(q) && q.qtype !== 'code' ? `
            <button class="btn btn-ghost btn-sm code-toggle${codeMode[q.id] ? ' active' : ''}"
              data-action="toggle-code-mode" data-qid="${esc(q.id)}"
              title="切换代码输入（语法高亮 / 自动缩进）">
              ${I.get('code', 13)} 代码
            </button>
            <button class="btn btn-ghost btn-sm" data-action="insert-table" data-qid="${esc(q.id)}"
              title="插入 Markdown 表格模板（批改能读懂表格内容）">
              ${I.get('table', 13)} 表格
            </button>` : ''}
        </h2>
        ${answerInputHtml(q)}
        <div class="btn-row">
          ${isObjectiveQ(q) ? '' : micButtonHtml(q)}
          <button class="btn btn-strong" data-action="save-answer" data-qid="${esc(q.id)}">
            ${I.get('check', 15)} 保存并下一题
          </button>
          <button class="btn btn-ghost" data-action="goto-question" data-idx="${Math.max(0, questionIdx - 1)}">
            ${I.get('back', 15)} 上一题
          </button>
          <button class="btn btn-ghost" data-action="goto-question" data-idx="${Math.min(qs.length - 1, questionIdx + 1)}">
            下一题 <span class="flip-x">${I.get('back', 15)}</span>
          </button>
          <span style="flex:1"></span>
          ${captureBtns(q.id)}
          <button class="btn btn-fjord" data-action="reveal" data-qid="${esc(q.id)}" ${revealed ? 'disabled' : ''}>
            ${I.get('eye', 15)} ${revealed ? '已看答案' : '看参考答案'}
          </button>
        </div>
      </div>

      ${revealed ? `
        ${referenceAnswerPanel(q)}
        <div class="card peek-note-bar">
          <span class="sub">看完了，记一笔</span>
          <button class="btn btn-ghost btn-sm" data-action="quick-note" data-qid="${esc(q.id)}" data-scene="answering">
            ${I.get('pencil', 14)} 记笔记
          </button>
        </div>` : ''}`;
  }

  /* ---------- 已交卷：同样一题一屏，别把所有题的答案糊在一起 ---------- */
  function gradedPaperBody(paper) {
    const q = currentQuestion();
    return q ? gradedQuestionBlock(paper, q) : '';
  }

  function gradedQuestionBlock(paper, q) {
    const qs = paper.questions || [];
    const a = latestAttempt(q);
    const ev = a && a.evaluation;
    return `
      <div class="graded-stack">
      <div class="question-card ${TIER_CLS[q.tier] || ''}" id="question-card">
        <div class="q-seq">
          <span>第 ${q.sequence} / ${qs.length} 题</span>
          ${tierChip(q.tier)}
          <span class="pts-chip">满分 ${esc(q.points)}</span>
          ${ev && ev.points !== null && ev.points !== undefined
            ? `<span class="score-chip ${scoreCls(ev.points / Math.max(1, q.points))}">实得 ${esc(ev.points)} 分</span>`
            : '<span class="badge badge-gray">未作答</span>'}
          ${q.revealedAt ? '<span class="badge badge-orange">已看答案</span>' : ''}
        </div>
        <div class="q-text">${rich(q.prompt)}</div>
        ${kpLine(q)}
        ${demandsLine(q)}
      </div>

      <div class="panel answer-card">
        <h3>${I.get('send', 15)} 我的作答</h3>
        ${a
          ? `<div class="answer-box">${esc(a.answer)}</div>`
          : `<div class="empty" style="padding:22px 0">未作答</div>`}
      </div>

      ${ev ? `
        <div class="panel review-card">
          <h3>${I.get('star', 15)} AI 点评
            ${ev.score !== null && ev.score !== undefined
              ? `<span class="muted">表现 ${esc(ev.score)} / 10</span>` : ''}
            <button class="btn btn-soft btn-sm" style="margin-left:auto"
              data-action="quick-note" data-qid="${esc(q.id)}" data-scene="grading">
              ${I.get('pencil', 13)} 记笔记
            </button>
          </h3>
          <div class="review-box">${richFeedback(ev.feedback)}</div>
          ${Object.keys(ev.dimensions || {}).length ? `
            <div class="dims" style="margin-top:10px">
              ${Object.entries(ev.dimensions || {}).map(([k, v]) => (
                `<span class="dim-chip">${esc(k)} <b>${esc(v)}</b></span>`
              )).join('')}
            </div>` : ''}
        </div>` : ''}

      ${referenceAnswerPanel(q)}

      ${followUpPanel(q)}

      <div class="btn-row">
        ${captureBtns(q.id)}
        <span style="flex:1"></span>
        <button class="btn btn-ghost" data-action="goto-question" data-idx="${Math.max(0, questionIdx - 1)}">
          ${I.get('back', 15)} 上一题
        </button>
        <button class="btn btn-ghost" data-action="goto-question" data-idx="${Math.min(qs.length - 1, questionIdx + 1)}">
          下一题 <span class="flip-x">${I.get('back', 15)}</span>
        </button>
      </div>
      </div>`;
  }

  function referenceAnswerPanel(q) {
    return `
      <div class="panel ref-card">
        <h3>${I.get('eye', 15)} 参考答案</h3>
        <div class="rb-body" style="background:var(--surface);border-color:#abefc6;line-height:1.8">${rich(q.referenceAnswer || '（本题没有参考答案）')}</div>
      </div>`;
  }

  /**
   * 追问聊天：**每道题一段独立会话**，AI 只拿到这道题的题面/作答/批改/答案。
   * 只在批改后的题上展示（追问需要批改与参考答案做上下文）。
   */
  function followUpPanel(q) {
    return `
      <div class="panel followup-card">
        <h3>追问
          <button class="btn btn-quiet btn-sm" style="margin-left:auto"
            data-action="clear-followups" data-qid="${esc(q.id)}">清空</button>
        </h3>
        <div class="chat-list" id="followup-list">${followUpMessagesHtml(q.id)}</div>
        <div class="chat-input-row">
          <textarea id="followup-input" rows="2" placeholder="继续问这道题，Enter 发送 / Shift+Enter 换行"></textarea>
          <button class="btn btn-primary" data-action="followup-send" data-qid="${esc(q.id)}">发送</button>
        </div>
      </div>`;
  }

  function followUpMessagesHtml(qid) {
    const msgs = followUpsCache[qid];
    if (!msgs) return '<div class="chat-hint">加载中…</div>';
    if (!msgs.length) {
      return '<div class="chat-hint">对这道题还有疑问？</div>';
    }
    return msgs.map((m) => `
      <div class="chat-msg ${m.role === 'user' ? 'user' : 'assistant'}">
        <div class="chat-bubble">${m.role === 'user' ? esc(m.content) : rich(m.content)}</div>
      </div>`).join('');
  }

  function renderFollowUps(qid) {
    const q = activeQuestion();
    if (!q || q.id !== qid) return; // 已经切到别的题，不覆盖
    const list = document.getElementById('followup-list');
    if (list) list.innerHTML = followUpMessagesHtml(qid);
    const box = document.querySelector('.followup-card .chat-list');
    if (box) box.scrollTop = box.scrollHeight;
  }

  async function maybeLoadFollowUps() {
    const q = activeQuestion();
    if (!q) return;
    if (sessionView() === 'drill') {
      // 刷题：这道题批改后才能追问（刷题卷常开，不能看卷状态）
      const a = latestAttempt(q);
      if (!a || !a.evaluation) return;
    } else {
      const paper = currentPaper();
      if (!paper || paper.status !== 'graded') return;
    }
    if (followUpsCache[q.id] !== undefined || followUpsLoading[q.id]) return;
    followUpsLoading[q.id] = true;
    try {
      followUpsCache[q.id] = await api('/api/questions/' + enc(q.id) + '/followups');
    } catch (e) {
      followUpsCache[q.id] = []; // 加载失败不打断答题，留空即可
    } finally {
      followUpsLoading[q.id] = false;
    }
    const now = activeQuestion();
    if (now && now.id === q.id) renderFollowUps(q.id);
  }

  function paperSummaryHtml(paper) {
    const sm = paper.summary;
    const failed = !!(sm && sm.failed);
    return `
      <div class="panel panel-success paper-summary">
        <h3>${I.get('star', 15)} 本卷成绩 <span class="muted">${sm ? '批改于 ' + fmtTime(sm.gradedAt) : ''}</span></h3>
        <div class="score-row">
          <div class="score-big ${scoreCls((paper.score || 0) / 100)}">${esc(paper.score === null ? '—' : paper.score)}</div>
          <div class="score-max">/ ${num(paper.maxScore)}</div>
          ${failed ? `
            <div style="margin-left:auto">
              <button class="btn btn-ghost btn-sm" data-action="regen-summary" data-id="${esc(paper.id)}">
                ${I.get('refresh', 14)} 重新生成总结
              </button>
            </div>` : ''}
        </div>
        ${failed ? `
          <div class="progress-error">${I.get('close', 14)} 本卷总结生成失败，成绩不受影响。点右上角可重出。</div>
        ` : ''}
        ${sm && sm.stageSummaryFailed ? `
          <div class="progress-error">
            ${I.get('close', 14)} 阶段总结更新失败，成绩不受影响。
            <button class="btn btn-ghost btn-sm" style="margin-left:auto"
              data-action="gen-summary" data-id="${esc(sessionState && sessionState.stageId || '')}">
              重新生成阶段总结
            </button>
          </div>
        ` : ''}
        ${!failed && sm ? `
          <div class="result-block">
            <h4>总体评价</h4>
            <div class="rb-body">${esc(sm.overall)}</div>
          </div>
          <div class="cols-2">
            <div>${listBlock('表现亮点', sm.strengths, 'check')}</div>
            <div>${listBlock('改进建议', sm.improvements, 'target')}</div>
          </div>` : ''}
      </div>`;
  }

  function qnavHtml(qs) {
    return `<div class="qnav">${qs.map((q, i) => {
      const cls = ['qnav-btn'];
      if (i === questionIdx) cls.push('current');
      else if (q.revealedAt) cls.push('revealed');
      else if (isAnswered(q)) cls.push('done');
      return `<button class="${cls.join(' ')}" data-action="goto-question" data-idx="${i}"
        title="第 ${q.sequence} 题 · ${esc(q.tier)} · ${esc(q.points)} 分">${q.sequence}</button>`;
    }).join('')}</div>`;
  }

  function historySection(papers, current) {
    const others = papers.filter((p) => p !== current);
    if (!others.length) return '';
    return `
      <div class="card">
        <h2>其他卷 <span class="sub">${others.length} 张</span></h2>
        <div class="list">${others.map((p) => {
          const qs = p.questions || [];
          return `
            <div class="list-item" data-action="goto-paper" data-idx="${papers.indexOf(p)}">
              <div class="li-icon">${I.get('list', 17)}</div>
              <div class="li-main">
                <div class="li-title">第 ${p.sequence} 张卷</div>
                <div class="li-sub">${PAPER_STATUS[p.status] || p.status} · ${fmtTime(p.createdAt)}</div>
              </div>
              <div class="li-stats">
                <span>题 <b>${num(qs.length)}</b></span>
                <span>得分 <b>${p.score === null ? '—' : esc(p.score) + ' / ' + num(p.maxScore)}</b></span>
              </div>
            </div>`;
        }).join('')}</div>
      </div>`;
  }

  const scoreCls = (ratio) => (ratio >= .8 ? 'score-high' : ratio >= .6 ? 'score-mid' : 'score-low');

  /* ============================================================
   *  页面：登录 / 注册
   * ============================================================ */
  /**
   * 登录 / 注册页（北欧极简试点）：
   * 桦木白底 + 炭灰文字 + 羊毛灰线条，松绿/峡湾蓝只做点缀；
   * 无阴影、无亮色、无形变动效，字重只用 200/300。
   */
  function loginView() {
    return `
      <div class="login-wrap">
        <div class="login-card">
          <div class="login-brand">
            <h1>myreview</h1>
            <p>面试练习工作台</p>
          </div>
          <div class="seg login-seg" data-seg="authMode">
            <button type="button" data-value="login" class="active">登录</button>
            <button type="button" data-value="register">注册</button>
          </div>
          <form data-form="auth-login">
            <div class="form-grid">
              <div class="field full">
                <label>用户名</label>
                <input type="text" name="username" autocomplete="username" placeholder="3-32 位字母、数字或中文" />
              </div>
              <div class="field full">
                <label>密码</label>
                <input type="password" name="password" autocomplete="current-password" placeholder="••••••••" />
              </div>
            </div>
            <div class="btn-row">
              <button type="submit" class="btn login-btn">登录</button>
            </div>
          </form>
          <form data-form="auth-register" style="display:none">
            <div class="form-grid">
              <div class="field full">
                <label>用户名</label>
                <input type="text" name="username" autocomplete="username" placeholder="3-32 位字母、数字或中文" />
              </div>
              <div class="field full">
                <label>密码 <span class="req">*</span></label>
                <input type="password" name="password" autocomplete="new-password" placeholder="至少 8 位，需含字母和数字" />
              </div>
              <div class="field full">
                <label>确认密码 <span class="req">*</span></label>
                <input type="password" name="password2" autocomplete="new-password" placeholder="再输一遍" />
              </div>
            </div>
            <div class="btn-row">
              <button type="submit" class="btn login-btn">注册并登录</button>
            </div>
          </form>
        </div>
      </div>`;
  }

  /* ============================================================
   *  页面：个人中心
   * ============================================================ */
  function profileView() {
    const u = sessionUser || {};
    const nickname = u.nickname || u.username || '';
    const avatarHtml = u.avatarExt
      ? `<img class="profile-avatar-img" data-avatar="${esc(u.id)}" alt="" src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">`
      : `<div class="profile-avatar">${esc(nickname.charAt(0) || '?')}</div>`;
    return `
      ${pageHead('Profile', '个人中心')}
      <div class="stagger">
        <div class="card profile-card">
          <div class="profile-row">
            ${avatarHtml}
            <div class="profile-meta">
              <div class="profile-name" id="profile-name">${esc(nickname)}</div>
              <div class="muted">@${esc(u.username || '')}</div>
              <label class="btn btn-ghost btn-sm profile-avatar-btn">
                ${I.get('refresh', 14)} 更换头像
                <input type="file" id="avatar-input" accept="image/png,image/jpeg,image/webp,image/gif" style="display:none" />
              </label>
            </div>
          </div>

          <form data-form="profile-save" class="setting-row">
            <label class="setting-label">昵称</label>
            <div class="setting-body inline-row">
              <input type="text" name="nickname" value="${esc(nickname)}" maxlength="24" placeholder="1-24 字" />
              <button type="submit" class="btn btn-strong btn-sm">保存</button>
            </div>
          </form>

          <div class="setting-row">
            <div class="setting-label">字号</div>
            <div class="setting-body inline-row">
              <div class="seg" data-seg="fontSize">
                <button type="button" data-value="small">小</button>
                <button type="button" data-value="standard" class="active">标准</button>
                <button type="button" data-value="large">大</button>
              </div>
              <button type="button" class="btn btn-fjord btn-sm" data-action="test-mic">
                ${I.get('mic', 14)} 检测麦克风
              </button>
              <span id="speech-status" class="hint"></span>
            </div>
          </div>
          <div id="mic-diag" class="hint" style="display:none"></div>

          <div class="setting-row">
            <div class="setting-label">密码</div>
            <div class="setting-body">
              <button type="button" class="btn btn-ghost btn-sm" data-action="open-password-dialog">修改密码</button>
            </div>
          </div>

          <div class="setting-row">
            <div class="setting-label">模型</div>
            <div class="setting-body inline-row">
              <span id="llm-summary" class="hint">读取中…</span>
              <button type="button" class="btn btn-fjord btn-sm" data-action="open-llm-dialog">配置</button>
            </div>
          </div>

          <div class="setting-row">
            <div class="setting-label">求职总结</div>
            <div class="setting-body inline-row" id="profile-summary-box">
              <span class="hint">读取中…</span>
            </div>
          </div>
        </div>
      </div>`;
  }

  /** 求职总结最近一次的服务端状态，供按钮与弹窗共用 */
  let summaryState = null;
  let summaryPollTimer = null;

  /**
   * 头像裁剪：选图后弹框，拖动 + 缩放框选正方形区域，确认后前端裁成 512×512 再上传。
   * 不用外部库，canvas 直接导出 JPEG。
   */
  function openAvatarCrop(file) {
    const V = 280;                       // 预览框边长
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const backdrop = document.createElement('div');
      backdrop.className = 'dialog-backdrop';
      backdrop.innerHTML = `
        <div class="dialog avatar-crop-dialog" role="dialog" aria-modal="true">
          <div class="dialog-head">
            <div class="dialog-title" style="justify-content:space-between">
              <span>${I.get('refresh', 16)} 调整头像</span>
              <button type="button" class="dialog-x" data-act="cancel" aria-label="关闭">${I.get('close', 15)}</button>
            </div>
          </div>
          <div class="dialog-body">
            <div class="crop-viewport" style="width:${V}px;height:${V}px">
              <img class="crop-img" alt="">
            </div>
            <div class="crop-zoom">
              <span class="muted">缩放</span>
              <input type="range" id="crop-zoom" min="100" max="300" value="100" />
            </div>
            <div class="hint">拖动图片调整位置，滑动缩放</div>
          </div>
          <div class="dialog-foot">
            <button type="button" class="btn btn-ghost" data-act="cancel">取消</button>
            <button type="button" class="btn btn-primary" data-act="ok">确认</button>
          </div>
        </div>`;
      document.body.appendChild(backdrop);

      const vp = backdrop.querySelector('.crop-viewport');
      const imgEl = backdrop.querySelector('.crop-img');
      const slider = backdrop.querySelector('#crop-zoom');
      imgEl.src = url;
      let base = 1, z = 1, dw = V, dh = V, tx = 0, ty = 0;

      const clamp = () => {
        tx = Math.min(0, Math.max(V - dw, tx));
        ty = Math.min(0, Math.max(V - dh, ty));
      };
      const layout = () => {
        base = Math.max(V / img.naturalWidth, V / img.naturalHeight);
        const s = base * z;
        dw = img.naturalWidth * s;
        dh = img.naturalHeight * s;
        clamp();
        imgEl.style.width = dw + 'px';
        imgEl.style.height = dh + 'px';
        imgEl.style.left = tx + 'px';
        imgEl.style.top = ty + 'px';
      };

      let dragging = false, sx = 0, sy = 0, stx = 0, sty = 0;
      vp.addEventListener('pointerdown', (e) => {
        dragging = true;
        sx = e.clientX; sy = e.clientY; stx = tx; sty = ty;
        vp.setPointerCapture(e.pointerId);
        vp.classList.add('dragging');
      });
      vp.addEventListener('pointermove', (e) => {
        if (!dragging) return;
        tx = stx + (e.clientX - sx);
        ty = sty + (e.clientY - sy);
        clamp();
        imgEl.style.left = tx + 'px';
        imgEl.style.top = ty + 'px';
      });
      const stopDrag = () => { dragging = false; vp.classList.remove('dragging'); };
      vp.addEventListener('pointerup', stopDrag);
      vp.addEventListener('pointercancel', stopDrag);
      slider.addEventListener('input', () => { z = Number(slider.value) / 100; layout(); });
      // 鼠标滚轮缩放：以指针为中心
      vp.addEventListener('wheel', (e) => {
        e.preventDefault();
        const rect = vp.getBoundingClientRect();
        const cx = e.clientX - rect.left;
        const cy = e.clientY - rect.top;
        const oldS = base * z;
        const nz = Math.min(3, Math.max(1, z + (e.deltaY < 0 ? 0.1 : -0.1)));
        if (nz === z) return;
        z = nz;
        const newS = base * z;
        tx = cx - (cx - tx) * (newS / oldS);
        ty = cy - (cy - ty) * (newS / oldS);
        slider.value = Math.round(z * 100);
        layout();
      }, { passive: false });
      layout();

      const cleanup = () => { URL.revokeObjectURL(url); backdrop.classList.add('leaving'); setTimeout(() => backdrop.remove(), 220); };

      backdrop.addEventListener('click', (e) => {
        const act = e.target.closest('[data-act]');
        if (!act) return;
        if (act.dataset.act === 'cancel') { cleanup(); return; }
        if (act.dataset.act !== 'ok') return;

        // 按预览框的取景范围裁到 512×512（cover 模式）
        const canvas = document.createElement('canvas');
        canvas.width = 512; canvas.height = 512;
        const ctx = canvas.getContext('2d');
        // PNG 透明区域转 JPEG 会变黑，先铺白底
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, 512, 512);
        const s = base * z;
        ctx.drawImage(img, -tx / s, -ty / s, V / s, V / s, 0, 0, 512, 512);
        canvas.toBlob(async (blob) => {
          if (!blob) return;
          const fd = new FormData();
          fd.append('file', blob, 'avatar.jpg');
          try {
            const u = await api('/api/me/avatar', { method: 'POST', body: fd });
            sessionUser = u;
            delete avatarCache[String(u.id)];
            cleanup();
            renderNav();
            render();
            hydrateAvatars();
            UI.toast('头像已更新', 'success');
          } catch (err) {
            UI.toast(err.message, 'error', '头像上传失败');
          }
        }, 'image/jpeg', 0.9);
      });
    };
    img.onerror = () => { URL.revokeObjectURL(url); UI.toast('图片读取失败', 'error'); };
    img.src = url;
  }

  async function loadProfile(seq) {
    loadSpeechStatus(seq);
    loadLlmSummary(seq);
    await refreshSummary(seq);
  }

  /** 拉一次总结状态并重绘；生成中则 3 秒后再拉，直到不再是 generating */
  async function refreshSummary(seq) {
    if (summaryPollTimer) { clearTimeout(summaryPollTimer); summaryPollTimer = null; }
    try {
      const r = await api('/api/me/summary');
      if (seq !== renderSeq) return;
      summaryState = r;
      renderSummaryRow();
      if (r.status === 'generating') {
        summaryPollTimer = setTimeout(() => {
          if (seq === renderSeq) refreshSummary(seq);
        }, 3000);
      }
    } catch (err) {
      if (seq !== renderSeq) return;
      const box = $('#profile-summary-box');
      if (box) box.innerHTML = `<span class="hint warn">读取失败：${esc(err.message)}</span>`;
    }
  }

  /** 个人中心只显示状态：生成中 / 已生成（含时间）/ 未生成 / 失败，详情在弹窗里 */
  function renderSummaryRow() {
    const box = $('#profile-summary-box');
    if (!box || !summaryState) return;
    const s = summaryState;
    const has = !!s.summary;
    const generating = s.status === 'generating';
    let statusHtml;
    if (generating) {
      statusHtml = `<span class="hint ok"><span class="spinner"></span> 总结正在生成，请稍候…</span>`;
    } else if (s.status === 'failed') {
      statusHtml = `<span class="hint warn">${I.get('info', 14)} 上次生成失败：${esc(s.error || '未知原因')}</span>`;
    } else if (has) {
      statusHtml = `<span class="hint ok">${I.get('check', 14)} 已生成 · 最近 ${fmtTime(s.summaryAt)}</span>`;
    } else {
      statusHtml = `<span class="hint">未生成</span>`;
    }
    box.innerHTML = `
      ${statusHtml}
      ${has ? `<button type="button" class="btn btn-fjord btn-sm" data-action="view-profile-summary">查看</button>` : ''}
      <button type="button" class="btn btn-primary btn-sm" data-action="gen-profile-summary" ${generating ? 'disabled' : ''}>
        ${generating ? '生成中…' : (has ? '重新生成' : '生成')}
      </button>`;
  }

  /** 模型配置只在行内显示一句摘要：当前启用哪套（含共享来源），具体管理在弹窗里 */
  async function loadLlmSummary(seq) {
    const box = $('#llm-summary');
    if (!box) return;
    try {
      const list = await api('/api/me/llm-configs');
      if (seq !== renderSeq) return;
      llmConfigsState = list;
      const active = list.find((c) => c.active);
      if (!active) {
        box.className = 'hint warn';
        box.textContent = list.length
          ? '未启用任何 API 配置，将用服务端默认。点「配置」选择启用。'
          : '还没有 API 配置，出题、批改、总结将用服务端默认。点「配置」添加。';
        return;
      }
      box.className = 'hint ok';
      const from = active.sharedBy ? `（由 ${active.sharedBy.nickname || active.sharedBy.username} 共享）` : '';
      box.textContent = `启用：${active.name}${from} · ${active.model || '默认模型'}${active.hasApiKey ? ' · Key ' + (active.masked || '已配置') : ''}`;
    } catch (err) {
      if (seq !== renderSeq) return;
      box.textContent = '读取失败：' + err.message;
    }
  }

  /** 求职总结详情：只在弹窗里展示 */
  function profileSummaryHtml(summary, at) {
    const p = summary.profile || {};
    const job = summary.jobAdvice || {};
    const items = (arr, fn) => (arr && arr.length ? arr.map(fn).join('') : '');
    const levelLabel = { junior: '初级', mid: '中级', senior: '高级' };
    return `
      <div class="muted">生成于 ${fmtTime(at || Date.now())}</div>
      <div class="panel panel-accent" style="margin-top:8px">
        <div class="sum-title">${I.get('star', 15)} ${esc(p.title || '')}</div>
        <p>${esc(p.overview || '')}</p>
      </div>
      <div class="sum-grid">
        <div class="sum-block"><h4>${I.get('check', 13)} 强项</h4>
          ${items(summary.strengths, (s) => `
            <div class="sum-item"><b>${esc(s.point)}</b><div class="muted">${esc(s.evidence)}</div></div>`)}
        </div>
        <div class="sum-block"><h4>${I.get('info', 13)} 短板</h4>
          ${items(summary.weaknesses, (s) => `
            <div class="sum-item"><b>${esc(s.point)}</b><div class="muted">${esc(s.advice)}</div></div>`)}
        </div>
      </div>
      <div class="sum-block"><h4>${I.get('target', 13)} 求职建议</h4>
        <div class="sum-item">
          <span class="badge badge-blue">${esc(levelLabel[job.targetLevel] || job.targetLevel || '未定级')}</span>
          ${items(job.positions, (x) => `<span class="badge badge-gray">${esc(x)}</span>`)}
          <div class="muted">${items(job.reasons, (r) => `<div>· ${esc(r)}</div>`)}</div>
        </div>
      </div>
      <div class="sum-block"><h4>${I.get('mic', 13)} 面试重点</h4>
        ${items(summary.interviewFocus, (x) => `<div class="sum-item">${esc(x)}</div>`)}
      </div>
      <div class="sum-block"><h4>${I.get('flag', 13)} 行动计划</h4>
        ${items(summary.actionPlan, (x) => `
          <div class="sum-item"><b>${esc(x.step)}</b><div class="muted">${esc(x.why)}</div></div>`)}
      </div>`;
  }

  /** 弹窗：只读展示，没有提交动作，所以只留一个「关闭」 */
  function openSummaryDialog() {
    if (!summaryState || !summaryState.summary) return;
    UI.formDialog({
      title: '求职总结',
      icon: 'star',
      okText: '关闭',
      closeOnly: true,
      body: profileSummaryHtml(summaryState.summary, summaryState.summaryAt),
    });
  }

  function openPasswordDialog() {
    UI.formDialog({
      title: '修改密码',
      icon: 'check',
      okText: '修改密码',
      formId: 'password-change-form',
      body: `
        <form id="password-change-form" data-form="password-change">
          <div class="form-grid">
            <div class="field full">
              <label>原密码 <span class="req">*</span></label>
              <input type="password" name="oldPassword" autocomplete="current-password" />
            </div>
            <div class="field full">
              <label>新密码 <span class="req">*</span></label>
              <input type="password" name="newPassword" autocomplete="new-password" placeholder="至少 8 位，需含字母和数字" />
            </div>
            <div class="field full">
              <label>确认新密码 <span class="req">*</span></label>
              <input type="password" name="newPassword2" autocomplete="new-password" />
            </div>
          </div>
        </form>`,
    });
  }

  /* ============================================================
   *  API 配置：多套配置 + 启用 + 用户间共享
   * ============================================================ */
  let llmConfigsState = [];     // GET /me/llm-configs 的缓存
  let llmShareConfigId = null;  // 共享弹窗当前操作的配置 id

  /** 模型配置弹窗：多套配置管理（启用/编辑/共享/删除；共享来的只读标注来源） */
  function openLlmDialog() {
    UI.formDialog({
      title: '模型配置',
      icon: 'gear',
      okText: '关闭',
      closeOnly: true,
      body: `
        <div class="llm-config-actions">
          <button type="button" class="btn btn-strong btn-sm" data-action="llm-config-new">
            ${I.get('plus', 14)} 新增配置
          </button>
        </div>
        <div id="llm-config-list" class="llm-config-list">${emptyState('读取中…')}</div>`,
      onOpen: refreshLlmConfigs,
    });
  }

  async function refreshLlmConfigs() {
    const box = $('#llm-config-list');
    try {
      llmConfigsState = await api('/api/me/llm-configs');
      renderLlmConfigList();
      loadLlmSummary(renderSeq);
    } catch (err) {
      if (box) box.innerHTML = emptyState('读取失败', err.message);
    }
  }

  function renderLlmConfigList() {
    const box = $('#llm-config-list');
    if (!box) return;
    const list = llmConfigsState;
    if (!list.length) {
      box.innerHTML = emptyState('还没有 API 配置', '点「新增配置」添加一套，或等别人共享给你');
      return;
    }
    box.innerHTML = list.map((c) => {
      const shared = !!c.sharedBy;
      const from = shared ? `由 ${c.sharedBy.nickname || c.sharedBy.username} 共享` : '';
      return `
        <div class="llm-config-item${c.active ? ' active' : ''}">
          <div class="llm-config-main">
            <div class="llm-config-title">
              ${c.active ? '<span class="badge badge-green">使用中</span>' : ''}
              <b>${esc(c.name)}</b>
              ${shared ? `<span class="badge badge-blue">${esc(from)} · 只读</span>` : ''}
            </div>
            <div class="muted llm-config-meta">${esc(c.apiBase || '默认地址')} · ${esc(c.model || '默认模型')}${c.hasApiKey ? ' · ' + esc(c.masked || 'sk-****') : ' · 无独立 Key'}</div>
          </div>
          <div class="llm-config-btns">
            ${c.active ? '' : `<button type="button" class="btn btn-ghost btn-sm" data-action="llm-config-activate" data-id="${esc(c.id)}">启用</button>`}
            ${shared ? '' : `
              <button type="button" class="btn btn-ghost btn-sm" data-action="llm-config-edit" data-id="${esc(c.id)}">编辑</button>
              <button type="button" class="btn btn-ghost btn-sm" data-action="llm-config-share" data-id="${esc(c.id)}">共享</button>
              <button type="button" class="btn btn-quiet btn-sm" data-action="llm-config-delete" data-id="${esc(c.id)}">删除</button>`}
          </div>
        </div>`;
    }).join('');
  }

  /** 新增/编辑配置弹窗（共享来的只读，进不来） */
  function openLlmConfigForm(id) {
    const c = id ? llmConfigsState.find((x) => String(x.id) === String(id)) : null;
    UI.formDialog({
      title: c ? '编辑配置' : '新增配置',
      icon: 'gear',
      okText: '保存',
      formId: 'llm-config-form',
      body: `
        <form id="llm-config-form" data-form="llm-config-save">
          <input type="hidden" name="configId" value="${c ? esc(c.id) : ''}" />
          <div class="form-grid">
            <div class="field full">
              <label>配置名</label>
              <input type="text" name="name" maxlength="64" placeholder="如 Claude / DeepSeek"
                value="${c ? esc(c.name) : ''}" />
            </div>
            <div class="field full">
              <label>接口协议</label>
              <select name="apiFormat">
                <option value="anthropic" ${!c || c.apiFormat === 'anthropic' ? 'selected' : ''}>Anthropic Messages（Claude / mimo）</option>
                <option value="openai" ${c && c.apiFormat === 'openai' ? 'selected' : ''}>OpenAI Chat Completions（DeepSeek / 通义 / Ollama）</option>
              </select>
            </div>
            <div class="field full">
              <label>API Base URL</label>
              <input type="text" name="apiBase" placeholder="https://api.anthropic.com"
                value="${c && c.apiBase ? esc(c.apiBase) : ''}" />
            </div>
            <div class="field full">
              <label>API Key <span class="hint-inline">加密保存，只回显末四位</span></label>
              <input type="password" name="apiKey" autocomplete="off" placeholder="sk-..." />
              <div class="inline-row">
                <span id="api-key-status" class="tip"></span>
                <button type="button" id="clear-api-key" class="btn btn-quiet btn-sm"
                  data-action="clear-api-key" style="display:none">清除</button>
              </div>
            </div>
            <div class="field">
              <label>模型</label>
              <input type="text" name="model" placeholder="claude-sonnet-5"
                value="${c && c.model ? esc(c.model) : ''}" />
            </div>
            <div class="field">
              <label>温度</label>
              <input type="text" name="temperature" placeholder="1.0"
                value="${c && c.temperature !== null && c.temperature !== undefined ? esc(c.temperature) : ''}" />
            </div>
          </div>
        </form>`,
      onOpen: () => renderApiKeyStatus(c),
    });
  }

  /** 共享弹窗：搜用户共享 + 已共享列表随时取消 */
  function openLlmShareDialog(id) {
    llmShareConfigId = id;
    UI.formDialog({
      title: 'API 共享',
      icon: 'user',
      okText: '完成',
      closeOnly: true,
      body: `
        <div class="muted" style="margin-bottom:10px">
          共享后对方可在「模型配置」里选用这套 API（含 Key，只读），你可随时取消。
        </div>
        <div class="inline-row" style="margin-bottom:10px">
          <input type="text" id="share-search" placeholder="按用户名 / 昵称搜索用户" style="flex:1"
            onkeydown="if(event.key==='Enter'){event.preventDefault();document.querySelector('[data-action=llm-user-search]')?.click();}" />
          <button type="button" class="btn btn-ghost btn-sm" data-action="llm-user-search">搜索</button>
        </div>
        <div id="share-results"></div>
        <div class="muted" style="margin:12px 0 6px;letter-spacing:.12em">已共享</div>
        <div id="share-list">读取中…</div>`,
      onOpen: refreshShareList,
    });
  }

  async function refreshShareList() {
    const box = $('#share-list');
    if (!box || !llmShareConfigId) return;
    try {
      const list = await api('/api/me/llm-configs/' + enc(llmShareConfigId) + '/shares');
      box.innerHTML = list.length ? list.map((s) => `
        <div class="llm-config-item">
          <div class="llm-config-main">
            <b>${esc(s.nickname || s.username)}</b> <span class="muted">@${esc(s.username)}</span>
          </div>
          <div class="llm-config-btns">
            <button type="button" class="btn btn-quiet btn-sm" data-action="llm-share-revoke" data-uid="${esc(s.userId)}">取消共享</button>
          </div>
        </div>`).join('') : '<div class="muted">还没共享给任何人</div>';
    } catch (err) {
      box.innerHTML = '<div class="muted">读取失败：' + esc(err.message) + '</div>';
    }
  }

  /* ============================================================
   *  页面：占位（押题 / 模拟面试）—— 北欧极简「规划中」
   * ============================================================ */
  function placeholderView(title, desc, icon) {
    return `
      ${pageHead('Coming soon', title)}
      <div class="soon-card">
        <div class="soon-icon">${I.get(icon || 'target', 24)}</div>
        <p class="soon-desc">${esc(desc)}</p>
        <span class="soon-tag">规划中</span>
      </div>`;
  }

  /** API Key 状态行：只显示掩码，明文永远不出后端 */
  function renderApiKeyStatus(s) {
    const box = $('#api-key-status');
    if (box) {
      box.innerHTML = s && s.hasApiKey
        ? `${I.get('check', 13)} 已配置 <code>${esc(s.masked || 'sk-****')}</code>，留空保存则保持不变`
        : `${I.get('info', 13)} 未配置独立 Key 时回落服务端默认。`;
    }
    const clear = $('#clear-api-key');
    if (clear) clear.style.display = s && s.hasApiKey ? '' : 'none';
    const input = $('#llm-config-form input[name="apiKey"]');
    if (input) {
      input.placeholder = s && s.hasApiKey ? `${s.masked || 'sk-****'}（留空不改）` : 'sk-...';
    }
  }

  async function loadSpeechStatus(seq) {
    const box = $('#speech-status');
    if (!box || !window.Speech) return;
    try {
      const st = await window.Speech.status();
      if (seq !== renderSeq) return;
      box.className = st.ready ? 'hint ok' : 'hint warn';
      box.innerHTML = st.ready
        ? `${I.get('check', 14)} 已就绪`
        : `${I.get('info', 14)} 语音识别暂不可用`;
    } catch (e) {
      box.textContent = '无法获取语音识别状态';
    }
  }

  /* ============================================================
   *  事件：表单提交
   * ============================================================ */
  document.addEventListener('submit', async (e) => {
    const form = e.target.closest('form[data-form]');
    if (!form) return;
    e.preventDefault();
    const kind = form.dataset.form;
    const dlg = form.closest('.dialog-backdrop');
    // 表单弹窗的提交按钮在 form 外面（靠 form 属性关联），优先取弹窗里的
    const btn = form.querySelector('button[type="submit"]')
      || (dlg && dlg.querySelector('button[data-act="submit"]'));
    // 成功后关闭所有弹窗（含从练习弹窗里再开「新建阶段」的嵌套场景）
    const closeDialog = () => {
      document.querySelectorAll('.dialog-backdrop').forEach((d) => {
        d.classList.add('leaving');
        setTimeout(() => d.remove(), 220);
      });
    };
    const fd = new FormData(form);
    const g = (k) => String(fd.get(k) === null || fd.get(k) === undefined ? '' : fd.get(k)).trim();

    // 只读弹窗（模型配置/共享/求职总结详情）：确定按钮 = 只关自己（嵌套弹窗不误伤外层）
    if (kind === 'noop') {
      if (dlg) {
        dlg.classList.add('leaving');
        setTimeout(() => dlg.remove(), 220);
      } else {
        closeDialog();
      }
      return;
    }

    if (kind === 'exam-mix') {
      // 考试模式：题型数量在此收集并校验，然后发起出卷
      const mix = {};
      Object.keys(EXAM_MIX_LABELS).forEach((k) => {
        mix[k] = Math.max(0, Math.min(30, parseInt(g(k), 10) || 0));
      });
      const total = Object.values(mix).reduce((x, y) => x + y, 0);
      if (total <= 0) return UI.toast('考试模式至少要有 1 道题', 'warning');
      const practiceId = form.dataset.practice;
      const mode = form.dataset.mode || 'exam';
      await UI.busy(btn, '出题中…', async () => {
        const p = await api('/api/practices/' + enc(practiceId) + '/papers', {
          method: 'POST', body: { mode, examMix: mix },
        });
        closeDialog();
        sessionState = p;
        pickDefaultPaper();
        renderSession();
        schedulePoll();
        UI.toast('已开始出题，进度会自动更新', 'info');
      });
      return;
    }

    if (kind === 'stage-create') {
      const name = g('name');
      if (!name) return UI.toast('请填写阶段名称', 'warning');
      await UI.busy(btn, '创建中…', async () => {
        const stage = await api('/api/stages', { method: 'POST', body: JSON.stringify({ name }) });
        UI.toast('阶段已创建', 'success');
        closeDialog();
        location.hash = '#/stages/' + stage.id;
      });
      return;
    }

    if (kind === 'practice-create') {
      const stageId = g('stageId');
      const modeBtn = form.querySelector('.seg[data-seg="scopeMode"] button.active');
      const scopeMode = modeBtn ? modeBtn.dataset.value : 'topic';
      const levelBtn = form.querySelector('.seg[data-seg="level"] button.active');
      const level = levelBtn ? levelBtn.dataset.value : 'mid';
      const intensityBtn = form.querySelector('.seg[data-seg="intensity"] button.active');
      const intensity = intensityBtn ? intensityBtn.dataset.value : 'standard';
      const sizeBtn = form.querySelector('.seg[data-seg="paperSize"] button.active');
      const paperSize = sizeBtn ? Number(sizeBtn.dataset.value) : 10;
      if (!stageId) return UI.toast('请先选择所属阶段', 'warning');
      const body = { scopeMode, level, intensity, paperSize };
      if (scopeMode === 'range') {
        const title = g('title');
        const scopeText = g('scopeText');
        if (!title) return UI.toast('请填写标题', 'warning');
        if (!scopeText) return UI.toast('范围模式需填写范围描述', 'warning');
        body.topic = title;
        body.scopeText = scopeText;
      } else {
        const topic = g('topic');
        if (!topic) return UI.toast('请填写知识点主题', 'warning');
        body.topic = topic;
      }
      await UI.busy(btn, '创建中…', async () => {
        const p = await api('/api/stages/' + enc(stageId) + '/practices', {
          method: 'POST', body: JSON.stringify(body),
        });
        practiceStageId = stageId;   // 回到练习页时筛选器停在新练习所属阶段
        UI.toast('练习已创建', 'success');
        closeDialog();
        location.hash = '#/session/' + p.id;
      });
      return;
    }

    /* —— 登录 / 注册 —— */
    if (kind === 'auth-login' || kind === 'auth-register') {
      const username = g('username');
      const password = g('password');
      if (!username || !password) return UI.toast('请输入用户名和密码', 'warning');
      if (kind === 'auth-register' && password !== g('password2')) {
        return UI.toast('两次输入的密码不一致', 'warning');
      }
      const isLogin = kind === 'auth-login';
      await UI.busy(btn, isLogin ? '登录中…' : '注册中…', async () => {
        // 登录/注册都直接返回 { token, expiresAt, user }
        const r = isLogin
          ? await api('/api/auth/login', { method: 'POST', body: { username, password } })
          : await api('/api/auth/register', { method: 'POST', body: { username, password } });
        setToken(r.token);
        sessionUser = r.user;
        document.body.classList.remove('auth-mode');
        UI.toast(isLogin ? '登录成功' : '注册成功，已自动登录', 'success');
        renderNav();
        if (location.hash === '#/stages') render();
        else location.hash = '#/stages';
      });
      return;
    }

    /* —— 修改密码 —— */
    if (kind === 'password-change') {
      const oldPassword = g('oldPassword');
      const newPassword = g('newPassword');
      if (!oldPassword || !newPassword) return UI.toast('请填写原密码和新密码', 'warning');
      if (newPassword !== g('newPassword2')) return UI.toast('两次输入的新密码不一致', 'warning');
      await UI.busy(btn, '修改中…', async () => {
        // 改密会撤销全部旧 token，返回新 token，前端替换本地保存
        const r = await api('/api/auth/password', { method: 'PUT', body: { oldPassword, newPassword } });
        setToken(r.token);
        if (r.user) sessionUser = r.user;
        closeDialog();
        UI.toast('密码已修改', 'success');
      });
      return;
    }

    /* —— 个人资料 —— */
    if (kind === 'profile-save') {
      const nickname = g('nickname');
      if (!nickname) return UI.toast('昵称不能为空', 'warning');
      await UI.busy(btn, '保存中…', async () => {
        const u = await api('/api/me/profile', { method: 'PUT', body: { nickname } });
        sessionUser = u;
        renderNav();
        const name = $('#profile-name');
        if (name) name.textContent = nickname;
        UI.toast('昵称已保存', 'success');
      });
      return;
    }

    /* —— 设置：LLM 配置 + API Key —— */
    if (kind === 'llm-config-save') {
      const rawTemp = g('temperature');
      const temperature = rawTemp === '' ? null : Number(rawTemp);
      if (rawTemp !== '' && !Number.isFinite(temperature)) {
        return UI.toast('请填写有效的温度数值', 'warning');
      }
      const configId = g('configId');
      // 点过「清除」又填了新 Key 时以新 Key 为准
      const clearKey = form.dataset.clearKey === '1' && !g('apiKey');
      await UI.busy(btn, '保存中…', async () => {
        const body = {
          name: g('name'), apiFormat: g('apiFormat'), apiBase: g('apiBase'),
          apiKey: g('apiKey'), model: g('model'), temperature, clearKey,
        };
        if (configId) {
          await api('/api/me/llm-configs/' + enc(configId), { method: 'PUT', body });
        } else {
          await api('/api/me/llm-configs', { method: 'POST', body });
        }
        // 只关编辑弹窗，管理弹窗留着并刷新列表
        if (dlg) {
          dlg.classList.add('leaving');
          setTimeout(() => dlg.remove(), 220);
        }
        await refreshLlmConfigs();
        UI.toast('配置已保存', 'success');
      });
      return;
    }
  });

  /* ---------- 记事本自动保存：debounce 800ms + 失焦即存 ---------- */
  let noteSaveTimer = null;
  let noteDirty = false;

  function noteStatus(msg, cls) {
    const el = $('#note-pad-status');
    if (el) { el.textContent = msg; el.className = 'muted' + (cls ? ' ' + cls : ''); }
  }

  async function saveNotePad() {
    const ta = $('#note-pad');
    if (!ta || !sessionState) return;
    const text = ta.value;
    if (text === (sessionState.notesText || '')) { noteDirty = false; return; }
    noteStatus('保存中…');
    try {
      const r = await api('/api/practices/' + enc(sessionState.id) + '/notes', {
        method: 'PUT', body: { text },
      });
      sessionState.notesText = r.text;
      sessionState.notesUpdatedAt = r.updatedAt;
      noteDirty = false;
      noteStatus('已保存 ' + fmtTime(r.updatedAt));
    } catch (err) {
      noteStatus('保存失败：' + err.message, 'note-err');
    }
  }

  function scheduleNoteSave() {
    noteDirty = true;
    noteStatus('未保存…');
    clearTimeout(noteSaveTimer);
    noteSaveTimer = setTimeout(saveNotePad, 800);
  }

  document.addEventListener('input', (e) => {
    if (e.target.id === 'answer-input') drafts[e.target.dataset.qid] = e.target.value;
    // 考试模式客观题：控件选择也进草稿（切题/刷新不丢）
    const optBox = e.target.closest && e.target.closest('.exam-opts');
    if (optBox && optBox.dataset.qid) {
      drafts[optBox.dataset.qid] = [...optBox.querySelectorAll('input:checked')].map((i) => i.value).join(',');
    }
    const blankBox = e.target.closest && e.target.closest('.exam-blanks');
    if (blankBox && blankBox.dataset.qid && e.target.dataset.blank !== undefined) {
      drafts[blankBox.dataset.qid] = [...blankBox.querySelectorAll('input')].map((i) => i.value.trim()).join('\n');
    }
    if (e.target.id === 'note-pad') scheduleNoteSave();
  });
  // 失焦立刻存，不留「关页面才想起来没存」的空档
  document.addEventListener('focusout', (e) => {
    if (e.target.id === 'note-pad' && noteDirty) { clearTimeout(noteSaveTimer); saveNotePad(); }
  });
  // 切页/关页前把没存的补上
  window.addEventListener('beforeunload', () => {
    if (noteDirty) { clearTimeout(noteSaveTimer); saveNotePad(); }
  });

  document.addEventListener('change', async (e) => {
    if (e.target.id === 'avatar-input') {
      const file = e.target.files && e.target.files[0];
      e.target.value = '';   // 允许再次选择同一张图
      if (!file) return;
      openAvatarCrop(file);
      return;
    }
    if (e.target.id === 'stage-select') {
      practiceStageId = e.target.value;
      const chips = $('#stage-chips');     // 阶段页的 chips 栏同步高亮
      if (chips) {
        chips.querySelectorAll('.stage-chip').forEach((c) => {
          c.classList.toggle('active', c.dataset.id === practiceStageId);
        });
      }
      loadPracticeList(renderSeq);
    }
    if (e.target.id === 'paper-select') {
      syncDraft();
      paperIdx = Number(e.target.value);
      questionIdx = 0;
      renderSession();
    }
  });

  /* ============================================================
   *  事件：点击
   * ============================================================ */
  document.addEventListener('click', async (e) => {
    const sw = e.target.closest('.switch[data-action]');
    if (sw) {
      sw.classList.toggle('on');
      const jdField = sw.closest('form') && sw.closest('form').querySelector('.jd-field');
      if (jdField) jdField.style.display = sw.classList.contains('on') ? '' : 'none';
      return;
    }
    const segBtn = e.target.closest('.seg[data-seg] button');
    if (segBtn) {
      $$('.seg[data-seg] button', segBtn.closest('.seg')).forEach((b) => b.classList.remove('active'));
      segBtn.classList.add('active');
      if (segBtn.closest('.seg').dataset.seg === 'fontSize') {
        applyFontSize(segBtn.dataset.value);
        UI.toast('字号已切换', 'success');
      }
      if (segBtn.closest('.seg').dataset.seg === 'paperMode') {
        paperMode = segBtn.dataset.value || 'regular';
        const tip = document.getElementById('paper-mode-tip');
        if (tip) tip.textContent = MODE_TIPS[paperMode] || '';
      }
      if (segBtn.closest('.seg').dataset.seg === 'scopeMode') {
        const form = segBtn.closest('form');
        const range = segBtn.dataset.value === 'range';
        const set = (id, show) => { const el = form.querySelector(id); if (el) el.style.display = show ? '' : 'none'; };
        set('#scope-topic-field', !range);
        set('#scope-range-field', range);
        set('#scope-range-text', range);
      }
      if (segBtn.closest('.seg').dataset.seg === 'authMode') {
        const card = segBtn.closest('.login-card');
        const isLogin = segBtn.dataset.value === 'login';
        const loginForm = card && card.querySelector('form[data-form="auth-login"]');
        const regForm = card && card.querySelector('form[data-form="auth-register"]');
        if (loginForm) loginForm.style.display = isLogin ? '' : 'none';
        if (regForm) regForm.style.display = isLogin ? 'none' : '';
      }
      return;
    }

    const el = e.target.closest('[data-action]');
    if (!el) return;
    const action = el.dataset.action;
    const id = el.dataset.id;

    /* —— 阶段 —— */
    if (action === 'open-stage') { location.hash = '#/stages/' + id; return; }
    if (action === 'new-stage') { openStageDialog(); return; }
    if (action === 'select-stage-chip') {
      if (id === practiceStageId) return;
      practiceStageId = id;
      el.closest('#stage-chips').querySelectorAll('.stage-chip').forEach((c) => {
        c.classList.toggle('active', c === el);
      });
      scrollActiveChipIntoView(true);
      const sel = $('#stage-select');
      if (sel) sel.value = practiceStageId;
      loadPracticeList(renderSeq);
      return;
    }
    if (action === 'new-practice') {
      if (id) practiceStageId = id;
      if (location.hash === '#/practice') openPracticeDialog(id);
      else { pendingOpenPractice = true; location.hash = '#/practice'; }
      return;
    }
    if (action === 'delete-stage') {
      const ok = await UI.confirm({
        title: '删除阶段',
        body: '该阶段下的练习、试卷与作答会一并删除，且不可恢复。',
        okText: '删除', danger: true,
      });
      if (!ok) return;
      await UI.busy(el, '删除中…', async () => {
        await api('/api/stages/' + enc(id), { method: 'DELETE' });
        UI.toast('阶段已删除', 'success');
        render();
      });
      return;
    }
    if (action === 'gen-summary') {
      const ok = await UI.confirm({
        title: '生成阶段总结',
        body: '会覆盖当前的阶段总结，全复习的范围以新总结为准。',
        okText: '生成',
      });
      if (!ok) return;
      await UI.busy(el, '生成中…', async () => {
        await api('/api/stages/' + enc(id) + '/summary', { method: 'POST' });
        UI.toast('阶段总结已生成', 'success');
        await loadStageDetail(id, renderSeq);
      });
      return;
    }
    if (action === 'start-review') {
      const sel = document.getElementById('review-intensity');
      const intensity = sel ? sel.value : 'standard';
      await UI.busy(el, '准备中…', async () => {
        const p = await api('/api/stages/' + enc(id) + '/review', {
          method: 'POST', body: { intensity },
        });
        UI.toast('全复习练习已创建', 'success');
        location.hash = '#/session/' + p.id;
      });
      return;
    }

    /* —— 练习列表 —— */
    if (action === 'reload-stages') { loadPracticeStages(renderSeq); return; }
    if (action === 'open-practice') { location.hash = '#/session/' + id; return; }

    /* —— 练习进行中 —— */
    if (action === 'goto-question') {
      syncDraft();
      await stopRecordingIfAny();
      questionIdx = Number(el.dataset.idx) || 0;
      // 只重绘题目区，保持滚动位置——否则切一题就被弹回顶部
      renderQuestion();
      return;
    }
    if (action === 'toggle-panel') {
      syncDraft();
      toggleCollapsed(el.dataset.key, el.dataset.defaultOpen !== '0');
      renderSessionKeepScroll();
      return;
    }
    if (action === 'toggle-kp-group') {
      syncDraft();
      // 大类默认收起；含「待巩固」的默认展开——一眼看到该补哪块
      toggleCollapsed('kp:' + el.dataset.cat, el.dataset.defaultOpen === '1');
      renderSessionKeepScroll();
      return;
    }
    if (action === 'delete-practice') {
      const ok = await UI.confirm({
        title: '删除练习',
        body: '该练习下的全部试卷、题目与作答会一并删除，且不可恢复。',
        okText: '删除', danger: true,
      });
      if (!ok) return;
      await UI.busy(el, '删除中…', async () => {
        await api('/api/practices/' + enc(id), { method: 'DELETE' });
        UI.toast('练习已删除', 'success');
        location.hash = '#/practice';
      });
      return;
    }
    if (action === 'regen-summary') {
      const ok = await UI.confirm({
        title: '重新生成本卷总结',
        body: '会覆盖当前总结，成绩与批改结果不受影响。',
        okText: '重新生成',
      });
      if (!ok) return;
      await UI.busy(el, '重出中…', async () => {
        const p = await api('/api/papers/' + enc(id) + '/summary', { method: 'POST' });
        sessionState = p;
        renderSessionKeepScroll();
        UI.toast('总结已重新生成', 'success');
      });
      return;
    }
    if (action === 'goto-paper') {
      syncDraft();
      const papers = (sessionState && sessionState.papers) || [];
      const i = Number(el.dataset.idx);
      if (!Number.isInteger(i) || i < 0 || i >= papers.length) return;
      paperIdx = i;
      questionIdx = 0;
      renderSession();
      return;
    }
    if (action === 'generate-paper') {
      // 建议按钮会带 data-mode；手动点「开始/继续出题」则用当前选择器的值
      const mode = el.dataset.mode || paperMode;
      // 考试模式：题型题量在弹窗里定（每种可为 0，合计至少 1），其余模式直接出
      if (mode === 'exam') {
        openExamMixDialog(id, mode);
        return;
      }
      await UI.busy(el, '出题中…', async () => {
        const p = await api('/api/practices/' + enc(id) + '/papers', {
          method: 'POST', body: { mode }
        });
        sessionState = p;
        pickDefaultPaper();
        renderSession();
        schedulePoll();
        UI.toast('已开始出题，进度会自动更新', 'info');
      });
      return;
    }
    if (action === 'final-exam') {
      await UI.busy(el, '出卷中…', async () => {
        const p = await api('/api/practices/' + enc(id) + '/final-exam', { method: 'POST' });
        sessionState = p;
        pickDefaultPaper();
        renderSession();
        schedulePoll();
        loadFinalExam(id);
        UI.toast('期末考已开始，答题时不能看参考答案', 'info');
      });
      return;
    }
    if (action === 'resume-paper') {
      await UI.busy(el, '续跑中…', async () => {
        const p = await api('/api/papers/' + enc(id) + '/resume', { method: 'POST' });
        sessionState = p;
        renderSession();
        schedulePoll();
      });
      return;
    }
    if (action === 'gen-profile-summary') {
      // 覆盖已有总结前先确认；首次生成没有可丢的内容，不打扰
      if (summaryState && summaryState.summary) {
        const ok = await UI.confirm({
          title: '重新生成求职总结',
          body: '会覆盖现有的求职总结，重新汇总全部学习数据生成。',
          okText: '重新生成',
        });
        if (!ok) return;
      }
      // 服务端抢占任务后立即返回；之后靠轮询读状态，刷新页面也能接着显示「生成中」
      el.disabled = true;
      try {
        summaryState = await api('/api/me/summary', { method: 'POST' });
      } catch (err) {
        // 409 = 另一个标签页/刷新前已经在生成，直接同步状态而不是报错
        if (!/正在生成/.test(err.message)) UI.toast(err.message, 'error', '无法生成总结');
      }
      refreshSummary(renderSeq);
      return;
    }
    if (action === 'view-profile-summary') { openSummaryDialog(); return; }
    if (action === 'open-password-dialog') { openPasswordDialog(); return; }
    if (action === 'open-llm-dialog') { openLlmDialog(); return; }
    if (action === 'clear-api-key') {
      const ok = await UI.confirm({
        title: '清除 API Key',
        body: '清除后这套配置没有独立 Key，回落服务端默认。保存后生效。',
        okText: '清除', danger: true,
      });
      if (!ok) return;
      const f = $('#llm-config-form');
      if (f) {
        f.dataset.clearKey = '1';
        f.elements.apiKey.value = '';
        renderApiKeyStatus({ hasApiKey: false });
      }
      return;
    }
    if (action === 'llm-config-new') { openLlmConfigForm(null); return; }
    if (action === 'llm-config-edit') { openLlmConfigForm(el.dataset.id); return; }
    if (action === 'llm-config-activate') {
      await UI.busy(el, '切换中…', async () => {
        llmConfigsState = await api('/api/me/llm-configs/' + enc(el.dataset.id) + '/activate', { method: 'POST' });
        renderLlmConfigList();
        loadLlmSummary(renderSeq);
      });
      return;
    }
    if (action === 'llm-config-delete') {
      const ok = await UI.confirm({
        title: '删除配置',
        body: '删除后不可恢复，共享给其他人的记录也会一并清除。',
        okText: '删除', danger: true,
      });
      if (!ok) return;
      await UI.busy(el, '删除中…', async () => {
        await api('/api/me/llm-configs/' + enc(el.dataset.id), { method: 'DELETE' });
        await refreshLlmConfigs();
        UI.toast('已删除', 'success');
      });
      return;
    }
    if (action === 'llm-config-share') { openLlmShareDialog(el.dataset.id); return; }
    if (action === 'llm-user-search') {
      const input = $('#share-search');
      const box = $('#share-results');
      if (!box) return;
      const q = (input && input.value || '').trim();
      if (!q) { box.innerHTML = '<div class="muted">输入用户名或昵称搜索</div>'; return; }
      box.innerHTML = '<div class="muted">搜索中…</div>';
      try {
        const found = await api('/api/me/user-search?q=' + enc(q));
        box.innerHTML = found.length ? found.map((u) => `
          <div class="llm-config-item">
            <div class="llm-config-main">
              <b>${esc(u.nickname || u.username)}</b> <span class="muted">@${esc(u.username)}</span>
            </div>
            <div class="llm-config-btns">
              <button type="button" class="btn btn-ghost btn-sm" data-action="llm-share-do" data-uid="${esc(u.userId)}">共享</button>
            </div>
          </div>`).join('') : '<div class="muted">没找到匹配的用户</div>';
      } catch (err) {
        box.innerHTML = '<div class="muted">搜索失败：' + esc(err.message) + '</div>';
      }
      return;
    }
    if (action === 'llm-share-do') {
      await UI.busy(el, '共享中…', async () => {
        await api('/api/me/llm-configs/' + enc(llmShareConfigId) + '/shares', {
          method: 'POST', body: { userId: el.dataset.uid },
        });
        await refreshShareList();
        UI.toast('已共享', 'success');
      });
      return;
    }
    if (action === 'llm-share-revoke') {
      await UI.busy(el, '取消中…', async () => {
        await api('/api/me/llm-configs/' + enc(llmShareConfigId) + '/shares/' + enc(el.dataset.uid),
          { method: 'DELETE' });
        await refreshShareList();
        UI.toast('已取消共享', 'success');
      });
      return;
    }
    if (action === 'test-mic') {
      const box = $('#mic-diag');
      if (!box || !window.Speech) return;
      box.style.display = '';
      box.className = 'hint';
      box.textContent = '检测中…';
      const d = await window.Speech.diagnose();
      // 先试真的拿一次麦克风——权限弹窗只有在真请求时才会出现
      let grab = { ok: true };
      try {
        const s = await window.Speech.start();
        await window.Speech.stop();
      } catch (err) {
        grab = { ok: false, name: err && err.name, msg: window.Speech.explainError(err) };
      }
      const rows = [
        ['访问地址', d.host + '（' + d.protocol + '）'],
        ['安全上下文', d.secureContext ? '是（可用麦克风）' : '否 — 浏览器禁止使用麦克风，需要用 localhost 或 HTTPS 打开'],
        ['麦克风 API', d.hasMediaDevices ? '支持' : '不支持'],
        ['浏览器权限', d.permission === 'granted' ? '已允许' : d.permission === 'denied' ? '已拒绝 — 请在地址栏锁形图标里改为允许' : d.permission],
        ['实际获取麦克风', grab.ok ? '成功' : '失败（' + grab.name + '）'],
      ];
      box.className = 'hint ' + (grab.ok && d.secureContext ? 'ok' : 'warn');
      box.innerHTML = rows.map((r) => `<div><b>${esc(r[0])}</b>：${esc(r[1])}</div>`).join('')
        + (grab.ok ? '' : `<div style="margin-top:8px">${esc(grab.msg)}</div>`);
      return;
    }

    if (action === 'mic') {
      const qid = el.dataset.qid;
      if (speechState === 'recording') {
        // 停止 → 转写 → 追加进作答框
        speechState = 'transcribing';
        stopMicTimer();
        renderQuestion();
        try {
          const rec = await window.Speech.stop();
          if (!rec || !rec.wav || !rec.wav.length) throw new Error('没有录到声音');
          // 把当前题目的考察点一并传过去，用来纠正 equals/volatile 这类术语
          const cur = currentQuestion();
          const hotwords = (cur && cur.knowledgePoints) || [];
          const out = await window.Speech.transcribe(rec.wav, hotwords);
          const text = (out && out.text ? out.text : '').trim();
          if (!text) {
            UI.toast('没听清，请靠近麦克风再说一次', 'warning');
          } else {
            const ta = $('#answer-input');
            const prev = ta && ta.value ? ta.value.trim() : '';
            const merged = prev ? prev + '\n' + text : text;
            if (ta) ta.value = merged;
            drafts[qid] = merged;
            syncCodeEditorFromTextarea();
            UI.toast('已转写 ' + Math.round(rec.seconds) + ' 秒音频', 'success');
            // 后台纠正术语：先让用户看到字，术语准不准是下一步的事。
            // 只在用户还没动过这段文字时才原地替换，避免覆盖他手打的内容。
            polishInBackground(qid, merged, hotwords);
          }
        } catch (err) {
          UI.toast(err && err.message ? err.message : String(err), 'error', '语音转写失败');
        }
        speechState = 'idle';
        speechQid = null;
        renderQuestion();
        return;
      }
      if (speechState === 'transcribing') return;
      try {
        await window.Speech.start();
        speechState = 'recording';
        speechQid = qid;
        renderQuestion();
        startMicTimer();
      } catch (err) {
        speechState = 'idle';
        const msg = window.Speech.explainError ? window.Speech.explainError(err)
          : (err && err.message ? err.message : String(err));
        UI.toast(msg, 'error', '无法开始录音');
        // 把浏览器环境一并打出来，省得猜是权限还是协议问题
        if (window.Speech.diagnose) {
          window.Speech.diagnose().then((d) => {
            console.log('[myreview] 麦克风诊断', d, '原始错误', err && err.name, err && err.message);
          }).catch(() => {});
        }
      }
      return;
    }

    if (action === 'toggle-code-mode') {
      const q = activeQuestion();
      if (!q) return;
      syncDraft();
      codeMode[q.id] = !codeMode[q.id];
      if (sessionView() === 'drill') renderSessionKeepScroll(); else renderQuestion();
      return;
    }
    if (action === 'insert-table') {
      const ta = $('#answer-input');
      if (!ta) return UI.toast('客观题不用插入表格', 'warning');
      const tpl = '| 列 1 | 列 2 | 列 3 |\n| --- | --- | --- |\n|  |  |  |\n|  |  |  |';
      // 光标位置：Monaco 打开时以编辑器选区为准（textarea 的选区是陈旧的）
      let start = ta.selectionStart != null ? ta.selectionStart : ta.value.length;
      let end = ta.selectionEnd != null ? ta.selectionEnd : start;
      if (codeEditor) {
        try {
          const model = codeEditor.getModel();
          const sel = codeEditor.getSelection();
          start = model.getOffsetAt(sel.getStartPosition());
          end = model.getOffsetAt(sel.getEndPosition());
        } catch (e) { /* 编辑器已卸载：退回 textarea 选区 */ }
      }
      const before = ta.value.slice(0, start);
      const after = ta.value.slice(end);
      // 表格独立成块：上下补齐空行，不粘住已有文字
      const lead = before && !before.endsWith('\n') ? '\n\n' : (before.endsWith('\n\n') || !before ? '' : '\n');
      const tail = after && !after.startsWith('\n') ? '\n\n' : (after.startsWith('\n\n') || !after ? '' : '\n');
      ta.value = before + lead + tpl + tail + after;
      syncDraft();
      syncCodeEditorFromTextarea();   // 代码模式下把表格推给 Monaco
      // 光标落到第一个表头占位（'| ' 之后的「列 1」），直接打字即可替换
      const cellStart = before.length + lead.length + 2;
      if (codeEditor) {
        try {
          const pos = codeEditor.getModel().getPositionAt(cellStart);
          codeEditor.focus();
          codeEditor.setPosition(pos);
          codeEditor.revealPosition(pos);
        } catch (e) { /* 编辑器已卸载 */ }
      } else {
        ta.focus();
        ta.setSelectionRange(cellStart, cellStart + 3);
      }
      return;
    }
    if (action === 'drill-start') {
      await UI.busy(el, 'AI 出题中…', async () => {
        const d = await api('/api/practices/' + enc(id) + '/drill/start', { method: 'POST' });
        // 响应属于哪个练习就标哪个（成对写入）：出题要几秒，中途切走再回来也不会串到别的练习
        drillState = d;
        drillLoadedFor = id;
        drillIdx = null;
        renderSession();
        if (id === (sessionState && sessionState.id)) UI.toast('开始刷题', 'success');
      });
      return;
    }
    if (action === 'drill-submit') {
      const q = activeQuestion();   // 翻页后补答旧题：提交正在看的这道，不是最新题
      if (!q) return;
      const pid = sessionState && sessionState.id;   // 请求属于哪个练习：响应按它配对
      const text = collectAnswerValue(q).trim();
      // 看过参考答案的题注定 0 分：允许空着完成，不用凑字数
      if (!text && !q.revealedAt) return UI.toast('请先写下你的回答', 'warning');
      await UI.busy(el, 'AI 批改中…', async () => {
        await api('/api/questions/' + enc(q.id) + '/attempts', {
          method: 'POST', body: JSON.stringify({ answer: text }),
        });
        const d = await api('/api/questions/' + enc(q.id) + '/drill-grade', { method: 'POST' });
        drillState = d;
        drillLoadedFor = pid;   // 成对写入：中途切了练习也不会把 A 的状态盖到 B 头上
        renderSession();
        if (pid === (sessionState && sessionState.id)) celebrateDrill(drillState);
      });
      return;
    }
    if (action === 'drill-next') {
      const pid = sessionState && sessionState.id;   // 请求属于哪个练习：响应按它配对
      await UI.busy(el, 'AI 出题中…', async () => {
        const d = await api('/api/practices/' + enc(pid) + '/drill/next', { method: 'POST' });
        drillState = d;
        drillLoadedFor = pid;   // 成对写入：慢出题期间切走也不会串练习
        drillIdx = null;   // 新题落在最新位，跳过去
        renderSession();
        if (pid === (sessionState && sessionState.id)) celebrateDrill(drillState);
      });
      return;
    }
    if (action === 'drill-view') {
      const idx = Number(el.dataset.idx);
      if (Number.isInteger(idx) && idx >= 0) {
        drillIdx = idx;
        renderSessionKeepScroll();
      }
      return;
    }
    if (action === 'toggle-drill-stats') {
      toggleCollapsed('drill-stats', false);
      renderSessionKeepScroll();
      return;
    }
    if (action === 'drill-window') {
      const n = Number(el.dataset.n);
      if (Number.isInteger(n) && n > 0) drillWindow = n;
      renderSessionKeepScroll();
      return;
    }
    if (action === 'drill-chart') {
      const m = el.dataset.mode;
      if (m === 'score' || m === 'avg') drillChartMode = m;
      renderSessionKeepScroll();
      return;
    }
    if (action === 'save-answer') {
      const qid = el.dataset.qid;
      const cur = currentQuestion();
      const text = collectAnswerValue(cur).trim();
      // 看过参考答案的题注定 0 分：允许空着保存，不用凑字数
      if (!text && !(cur && cur.revealedAt)) return UI.toast('请先写下你的回答', 'warning');
      await UI.busy(el, '保存中…', async () => {
        const q = await api('/api/questions/' + enc(qid) + '/attempts', {
          method: 'POST', body: JSON.stringify({ answer: text }),
        });
        replaceQuestion(q);
        delete drafts[qid];
        UI.toast('作答已保存', 'success');
        // 保存后直接进入下一题，不显示参考答案
        const qs = (currentPaper() && currentPaper().questions) || [];
        if (questionIdx < qs.length - 1) {
          questionIdx += 1;
          renderQuestion();
          scrollToQuestionIfOffscreen();
        } else {
          renderQuestion();
          UI.toast('这是最后一题，可以交卷了', 'info');
        }
      });
      return;
    }
    if (action === 'reveal') {
      const qid = el.dataset.qid;
      syncDraft();
      const ok = await UI.confirm({
        title: '查看参考答案',
        body: '查看参考答案后，<b>本题将按规则计 0 分</b>。确定要查看吗？',
        html: true,
        okText: '仍要查看',
        cancelText: '再想想',
        danger: true,
      });
      if (!ok) return;
      await UI.busy(el, '处理中…', async () => {
        const q = await api('/api/questions/' + enc(qid) + '/reveal', { method: 'POST' });
        replaceQuestion(q);
        renderSessionKeepScroll();
        UI.toast('本题已计 0 分，参考答案已展开', 'warning');
        // 答案在作答区下方，滚过去免得用户以为没显示
        const panel = $('#session-body .ref-card');
        if (panel && panel.scrollIntoView) {
          panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
      });
      return;
    }

    /* —— 快记笔记 / 错题本 —— */
    if (action === 'retry-mistakes') {
      await UI.busy(el, '出题中…', async () => {
        const p = await api('/api/practices/' + enc(id) + '/mistakes/retry', { method: 'POST' });
        sessionState = p;
        pickDefaultPaper();
        renderSession();
        schedulePoll();
        UI.toast('错题重做卷已开始出题', 'info');
      });
      return;
    }
    if (action === 'note-jump-latest') {
      const ta = $('#note-pad');
      if (ta) { ta.focus(); ta.selectionStart = ta.selectionEnd = ta.value.length; ta.scrollTop = ta.scrollHeight; }
      return;
    }
    if (action === 'quick-note') {
      syncDraft();   // 防止重渲丢掉作答草稿
      const text = await UI.prompt({
        title: '记一笔',
        hint: '会追加到右侧记事本末尾',
        placeholder: '记下你的理解、误区、想补充的点…',
      });
      if (text == null || !text.trim()) return;
      await UI.busy(el, '保存中…', async () => {
        const r = await api('/api/practices/' + enc(sessionState.id) + '/notes', {
          method: 'POST', body: { text: text.trim() },
        });
        sessionState.notesText = r.text;
        sessionState.notesUpdatedAt = r.updatedAt;
        renderSessionKeepScroll();
        UI.toast('已追加到记事本', 'success');
      });
      return;
    }
    if (action === 'review-tab') {
      reviewTab = el.dataset.tab || 'knowledge';
      renderSessionKeepScroll();
      return;
    }
    if (action === 'open-mistake-drawer') {
      mistakeDrawerId = el.dataset.mid;
      renderSessionKeepScroll();
      return;
    }
    if (action === 'close-mistake-drawer') {
      mistakeDrawerId = null;
      renderSessionKeepScroll();
      return;
    }
    if (action === 'page-go') {
      const n = Number(el.dataset.page);
      if (!Number.isInteger(n) || n < 1) return;
      const target = el.dataset.target;
      if (target === 'stage') {
        stagePage = n;
        renderStageList();
      } else if (target === 'practice') {
        practicePage = n;
        renderPracticeList();
      } else if (target === 'detail-practice') {
        detailPracticePage = n;
        const box = $('#detail-practice-list');
        if (box) box.innerHTML = practiceCardsHtml(detailPractices, detailPracticePage, 'detail-practice');
      }
      return;
    }
    if (action === 'mistake-page') {
      const n = Number(el.dataset.page);
      if (Number.isInteger(n) && n >= 1) {
        mistakePage = n;
        renderSessionKeepScroll();
      }
      return;
    }
    if (action === 'followup-send') {
      const qid = el.dataset.qid;
      const ta = document.getElementById('followup-input');
      const text = ta ? ta.value.trim() : '';
      if (!text) return UI.toast('先写点内容再问', 'warning');
      await UI.busy(el, '思考中…', async () => {
        const msgs = await api('/api/questions/' + enc(qid) + '/followups', {
          method: 'POST', body: { content: text },
        });
        followUpsCache[qid] = msgs;
        if (ta) ta.value = '';
        renderFollowUps(qid);
      });
      return;
    }
    if (action === 'clear-followups') {
      const qid = el.dataset.qid;
      const ok = await UI.confirm({ title: '清空追问', body: '清空这道题的聊天记录？不影响题目与作答。', danger: true, okText: '清空' });
      if (!ok) return;
      await UI.busy(el, '清空中…', async () => {
        await api('/api/questions/' + enc(qid) + '/followups', { method: 'DELETE' });
        followUpsCache[qid] = [];
        renderFollowUps(qid);
        UI.toast('已清空', 'success');
      });
      return;
    }
    if (action === 'study-generate') {
      await UI.busy(el, '准备中…', async () => {
        studyDoc = await api('/api/practices/' + enc(el.dataset.id) + '/review-doc', { method: 'POST' });
        renderSessionKeepScroll();
        scheduleStudyPoll(el.dataset.id);
        UI.toast('正在生成复习提纲', 'info');
      });
      return;
    }
    if (action === 'study-regenerate') {
      const ok = await UI.confirm({
        title: '重新生成复习提纲',
        body: '会覆盖当前内容，已读进度按点名保留。',
        okText: '重新生成',
      });
      if (!ok) return;
      await UI.busy(el, '准备中…', async () => {
        studyDoc = await api('/api/practices/' + enc(el.dataset.id) + '/review-doc/regenerate', { method: 'POST' });
        renderSessionKeepScroll();
        scheduleStudyPoll(el.dataset.id);
        UI.toast('正在重新生成复习提纲', 'info');
      });
      return;
    }
    if (action === 'study-jump') {
      const target = document.getElementById(el.dataset.target);
      if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    if (action === 'study-only-high') {
      studyOnlyHigh = !studyOnlyHigh;
      const box = document.getElementById('study-content');
      if (box) box.classList.toggle('only-high', studyOnlyHigh);
      const toc = document.querySelector('.study-toc');
      if (toc) toc.classList.toggle('only-high', studyOnlyHigh);
      el.classList.toggle('active', studyOnlyHigh);
      // 过滤后可见卡片变了，当前分类/知识点要重算
      studyTocActiveId = '';
      studyTocActiveSub = '';
      updateStudyTocActive();
      return;
    }
    if (action === 'study-toggle-all') {
      const details = $$('.study-more');
      const open = details.some((d) => !d.open);
      details.forEach((d) => { d.open = open; });
      el.textContent = open ? '全部收起' : '全部展开';
      return;
    }
    if (action === 'study-exam') {
      location.hash = '#/session/' + el.dataset.id;
      UI.toast('选好组卷模式，点「开始出题」即可开考', 'info');
      return;
    }
    if (action === 'add-mistake') {
      const qid = el.dataset.qid;
      await UI.busy(el, '收录中…', async () => {
        const m = await api('/api/practices/' + enc(sessionState.id) + '/mistakes', {
          method: 'POST', body: { questionId: qid },
        });
        sessionState.mistakes = [m, ...(sessionState.mistakes || [])];
        renderSessionKeepScroll();
        UI.toast('已加入错题本', 'success');
      });
      return;
    }
    if (action === 'resolve-mistake') {
      const mid = el.dataset.mid;
      await UI.busy(el, '处理中…', async () => {
        const m = await api('/api/practices/' + enc(sessionState.id) + '/mistakes/' + enc(mid), {
          method: 'PATCH', body: { status: 'resolved' },
        });
        sessionState.mistakes = (sessionState.mistakes || []).map((x) => (x.id === mid ? m : x));
        renderSessionKeepScroll();
        UI.toast('已标记为已订正', 'success');
      });
      return;
    }
    if (action === 'reopen-mistake') {
      const mid = el.dataset.mid;
      await UI.busy(el, '处理中…', async () => {
        const m = await api('/api/practices/' + enc(sessionState.id) + '/mistakes/' + enc(mid), {
          method: 'PATCH', body: { status: 'open' },
        });
        sessionState.mistakes = (sessionState.mistakes || []).map((x) => (x.id === mid ? m : x));
        renderSessionKeepScroll();
        UI.toast('已重新打开', 'success');
      });
      return;
    }
    if (action === 'delete-mistake') {
      const mid = el.dataset.mid;
      const ok = await UI.confirm({ title: '移出错题本', body: '确定把这道题移出错题本吗？', danger: true, okText: '移出' });
      if (!ok) return;
      await UI.busy(el, '处理中…', async () => {
        await api('/api/practices/' + enc(sessionState.id) + '/mistakes/' + enc(mid), { method: 'DELETE' });
        sessionState.mistakes = (sessionState.mistakes || []).filter((x) => x.id !== mid);
        renderSessionKeepScroll();
        UI.toast('已移出错题本', 'success');
      });
      return;
    }
    if (action === 'goto-mistake-question') {
      const qid = el.dataset.qid;
      if (!qid) return;
      // 错题本在复盘页，题目在答题页/刷题页——必须先切 hash，
      // 光 renderSession() 是没用的（sessionView() 还是 review，question-card 不会渲染）
      const inPaper = locateQuestion(qid);   // 找到会顺手定位 paperIdx/questionIdx
      const target = inPaper
        ? '#/session/' + currentSessionId()
        : '#/session/' + currentSessionId() + '/drill';   // 考卷里没有的就是刷题题
      if (location.hash !== target) {
        jumpToQuestionId = qid;
        location.hash = target; // → hashchange → render → 定位并滚动
        return;
      }
      // 已在目标页：hash 不变就不会触发 loadSession，直接定位
      if (inPaper) {
        jumpToQuestionId = null;
        renderSession();
      } else {
        const idx = ((drillState && drillState.questions) || []).findIndex((x) => x.id === qid);
        if (idx >= 0) {
          jumpToQuestionId = null;
          drillIdx = idx;
          renderSession();
        } else if (!drillLoading) {
          jumpToQuestionId = null;
          UI.toast('没找到这道题', 'warning');
        }
        // 还在加载：保留 jumpToQuestionId，drillBody 加载完成后定位
      }
      const card = $('#question-card');
      if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }

    if (action === 'submit-paper') {
      syncDraft();
      const paper = currentPaper();
      const qs = (paper && paper.questions) || [];
      const answered = qs.filter(isAnswered).length;
      const revealed = qs.filter((q) => q.revealedAt).length;
      if (!answered) return UI.toast('本卷还没有作答，先答几题再交卷', 'warning');
      const ok = await UI.confirm({
        title: '交卷',
        body: `将批改 <b>${answered}</b> 道已作答的题${revealed ? `（其中 <b>${revealed}</b> 道已看参考答案，计 0 分）` : ''}。`
          + '未作答的题不得分。交卷后本卷不可再修改作答。',
        html: true,
        okText: '确认交卷',
      });
      if (!ok) return;
      await UI.busy(el, '交卷中…', async () => {
        const p = await api('/api/papers/' + enc(id) + '/submit', { method: 'POST' });
        sessionState = p;
        renderSession();
        schedulePoll();
        UI.toast('已交卷，正在批改', 'info');
      });
      return;
    }
  });

  /** 题卡不在视野里才滚过去；在视野里就别动，避免切题被弹回顶部 */
  /* 录音计时：只改秒数文本，不整块重绘，免得按钮闪 */
  function startMicTimer() {
    stopMicTimer();
    micTimer = setInterval(() => {
      const el = $('.mic-elapsed');
      if (!el || speechState !== 'recording') return stopMicTimer();
      const s = Math.floor((window.Speech ? window.Speech.elapsedMs() : 0) / 1000);
      el.textContent = Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
    }, 500);
  }
  function stopMicTimer() {
    if (micTimer) { clearInterval(micTimer); micTimer = null; }
  }

  /** 切题/离开前把正在录的音停掉，别让麦克风一直开着 */
  async function stopRecordingIfAny() {
    if (speechState !== 'recording') return;
    stopMicTimer();
    speechState = 'idle';
    speechQid = null;
    try { await window.Speech.stop(); } catch (e) { /* 忽略 */ }
  }

  /**
   * 后台纠正语音里的技术术语（equals → ecos 这类声学误识）。
   * 原文已经先填进去了；纠正结果只在用户没动过那段文字时才替换，免得覆盖他手打的内容。
   */
  function polishInBackground(qid, rawText, hotwords) {
    if (!window.Speech || !window.Speech.polish) return;
    window.Speech.polish(rawText, (sessionState && sessionState.topic) || '', hotwords)
      .then((out) => {
        const fixed = (out && out.text ? out.text : '').trim();
        if (!fixed || fixed === rawText) return;
        const ta = $('#answer-input');
        if (!ta || ta.dataset.qid !== qid) return;
        // 用户已经改动过就别覆盖
        if (ta.value.trim() !== rawText.trim()) return;
        ta.value = fixed;
        drafts[qid] = fixed;
        syncCodeEditorFromTextarea();
        UI.toast('术语已纠正', 'info');
      })
      .catch(() => { /* 润色失败不影响使用，原文还在 */ });
  }

  function scrollToQuestionIfOffscreen() {
    const card = $('#question-card');
    if (!card || !card.getBoundingClientRect) return;
    const r = card.getBoundingClientRect();
    if (r.top < 56 || r.top > window.innerHeight * 0.65) {
      card.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }

  /* ============================================================
   *  启动
   * ============================================================ */
  function renderNav() {
    const nav = $('#nav');
    if (!nav) return;
    nav.innerHTML = NAV_ITEMS.map((n) => {
      if (n.section) return `<div class="nav-section">${esc(n.section)}</div>`;
      return `
        <a href="${n.href}" class="nav-item" data-route="${n.route}">
          <span class="nav-icon">${I.get(n.icon, 17)}</span><span>${n.label}</span>
        </a>`;
    }).join('');

    const user = $('#user-panel');
    if (!user) return;
    if (!sessionUser) { user.innerHTML = ''; return; }
    const nickname = sessionUser.nickname || sessionUser.username || '我';
    const avatar = sessionUser.avatarExt
      ? `<img class="user-avatar-img" data-avatar="${esc(sessionUser.id)}" alt="" src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">`
      : `<div class="user-avatar">${esc(nickname.charAt(0))}</div>`;
    user.innerHTML = `
      <div class="user-card" data-action="open-profile" role="button" tabindex="0" title="个人中心">
        ${avatar}
        <div class="user-meta">
          <div class="user-name">${esc(nickname)}</div>
          <div class="user-sub">@${esc(sessionUser.username || '')}</div>
        </div>
        <button class="user-menu-btn" data-action="logout" title="退出登录">${I.get('logout', 16)}</button>
      </div>`;
    hydrateAvatars();
  }

  document.addEventListener('click', async (e) => {
    if (e.target.closest('[data-action="logout"]')) {
      const ok = await UI.confirm({
        title: '退出登录', body: '退出后需要重新输入用户名和密码。', okText: '退出', danger: true,
      });
      if (!ok) return;
      try { await api('/api/auth/logout', { method: 'POST' }); } catch (err) { /* token 可能已过期 */ }
      setToken('');
      sessionUser = null;
      renderNav();
      render();
      UI.toast('已退出登录', 'success');
      return;
    }
    if (e.target.closest('[data-action="open-profile"]')) {
      location.hash = '#/profile';
    }
  });

  // 追问输入框：Enter 发送，Shift+Enter 换行（只在追问框生效，不干扰作答框）
  document.addEventListener('keydown', (e) => {
    if (e.target && e.target.id === 'followup-input' && e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      const btn = document.querySelector('[data-action="followup-send"]');
      if (btn) btn.click();
    }
  });

  window.addEventListener('hashchange', render);
  window.addEventListener('beforeunload', clearPoll);
  // 字号先落地再渲染，免得先按默认大小闪一下
  initFontSize();

  /** 启动：有 token 就校验会话；无效/没有则落到登录页 */
  (async function boot() {
    if (getToken()) {
      try {
        sessionUser = await api('/api/auth/me');
      } catch (err) {
        sessionUser = null;
      }
    }
    renderNav();
    render();
  })();
})();
