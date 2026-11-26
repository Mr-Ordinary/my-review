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
    { route: 'stages', href: 'stages.html', icon: 'folder', label: '阶段' },
    { route: 'practice', href: 'practice.html', icon: 'book', label: '练习' },
    { section: '进阶' },
    { route: 'drill', href: 'drill.html', icon: 'target', label: '押题' },
    { route: 'mock', href: 'mock.html', icon: 'mic', label: '模拟面试' },
    { section: '系统' },
    { route: 'profile', href: 'profile.html', icon: 'user', label: '个人中心' },
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
  let sessionState = null;
  let paperIdx = -1;
  let questionIdx = 0;
  /** 下一张卷的组卷模式（点「开始出题/继续出题」时随请求提交） */
  let paperMode = 'regular';
  /** 错题本分页（一页 5 条——几百条时不能全糊在一屏里） */
  let mistakePage = 1;
  const MISTAKE_PAGE_SIZE = 5;
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
  let sseCtrl = null;
  let ssePracticeId = null;
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
    if (res.status === 401) {
      // token 无效/过期：清掉本地 token，回独立登录页（登录/注册接口自己处理错误）
      setToken('');
      sessionUser = null;
      location.replace('login.html');
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
  function closeTaskStream() {
    if (sseCtrl) { sseCtrl.abort(); sseCtrl = null; }
    ssePracticeId = null;
  }

  /**
   * 任务事件流（SSE）：出题/批改由后端推送，替代轮询刷接口。
   * 刷新页面 = 重新订阅；连接断开 1.5s 自动重连；空闲时零请求零查询。
   */
  function subscribeTaskStream(id) {
    if (ssePracticeId === id && sseCtrl) return;
    closeTaskStream();
    ssePracticeId = id;
    const ctrl = new AbortController();
    sseCtrl = ctrl;
    (async () => {
      try {
        const res = await fetch('/api/practices/' + enc(id) + '/events', {
          headers: { Authorization: 'Bearer ' + getToken() },
          signal: ctrl.signal,
        });
        if (res.status === 401) { closeTaskStream(); return; }
        if (!res.ok || !res.body) throw new Error('事件流连接失败');
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buf = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buf += decoder.decode(value, { stream: true });
          let i;
          while ((i = buf.indexOf('\n\n')) >= 0) {
            const chunk = buf.slice(0, i);
            buf = buf.slice(i + 2);
            onTaskEvent(chunk);
          }
        }
      } catch (e) {
        if (ctrl.signal.aborted) return;   // 主动关闭，不再重连
      }
      setTimeout(() => {
        if (sseCtrl === ctrl) { sseCtrl = null; subscribeTaskStream(id); }
      }, 1500);
    })();
  }

  /** 收到推送：拉一次练习详情并原位刷新（事件稀疏，整刷一次无压力） */
  async function onTaskEvent(chunk) {
    const line = chunk.split('\n').find((l) => l.startsWith('data:'));
    if (!line || !sessionState || String(sessionState.id) !== String(ssePracticeId)) return;
    let ev = null;
    try { ev = JSON.parse(line.slice(5).trim()); } catch (e) { return; }
    if (ev.type === 'progress') {
      // 过程进度事件已停推（每批一推效果不好还连带写库）；留着分支只为兼容旧事件，直接忽略
      return;
    }
    // done（全任务唯一事件）：先整刷卷 meta，再重拉当前卷大纲 + 当前题（点查缓存，轻）；message（如「全部出完」）提示
    try {
      if (ev.message) UI.toast(ev.message, 'success');
      // ① brief 整刷各卷 meta：批改完翻「已交卷」出成绩、出题完转「答题中」都靠这一步
      const brief = await api('/api/practices/' + enc(sessionState.id) + '/brief');
      refreshPaperMeta(brief);
      // ② 题目存根（answered/revealed 标记）整卷重拉，当前题按题补内容
      const paper = currentPaper();
      if (paper) {
        paper._outlined = false;
        await hydratePaper(paper);
        const q = activeQuestion();
        if (q && q.prompt === null) await hydrateCurrentQuestion();
      }
      const now = currentPaper();
      if (now && now.status === 'graded') loadKnowledge(sessionState.id);
      renderSessionKeepScroll();
    } catch (e) { /* 拉取失败等下一个事件兜底 */ }
  }
  /** 卷的关键状态指纹：没变化就完全不动 DOM，避免每 2.5 秒闪一次 */
  function paperFingerprint(p) {
    if (!p) return '';
    const pr = p.progress || {};
    return [p.status, p.score, pr.planned, pr.done, (pr.failed || []).length,
            pr.error || '', (p.questions || []).length].join('|');
  }

  /*（出题/批改进度已由 SSE 任务事件流取代：见 subscribeTaskStream） */


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
    // mini（fixed）时页头 rect.top 已脱离文档流，直接量会把阈值写成 scrollY、再也解不开——
    // 改量占位块（它就躺在页头原来的文档位置上）
    const ref = (h && h.classList.contains('mini') && stickySpacer) ? stickySpacer : h;
    stickyHeadTop = ref ? ref.getBoundingClientRect().top + window.scrollY : Infinity;
    updateStickyHead();
  }

  function updateStickyHead() {
    const h = stickyHeadEl();
    if (!h) return;
    // 判定带迟滞（进入 1px / 解除 40px）：贴着阈值反复进出会让标题吸顶来回切，
    // 每次切换都伴随高度读写，是滚动抖动的来源之一。
    const isMini = h.classList.contains('mini');
    const exitAt = Math.max(0, stickyHeadTop - 40);
    const mini = isMini ? window.scrollY > exitAt : window.scrollY >= stickyHeadTop - 1;
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
  window.addEventListener('resize', () => {
    measureStickyHead();
    measureQuestionSticky();
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
        </form>`,
    });
  }

  /*（阶段列表/详情已拆到独立页 stages.html、stage.html，Vue 渲染） */

  /* ============================================================
   *  页面：练习进行中
   * ============================================================ */
  /** 只要掌握度统计（答题页 KPI）：不拉清单/记忆点，遵守答题页轻加载纪律 */
  async function loadKnowledgeStats(practiceId) {
    try {
      const st = await api('/api/practices/' + enc(practiceId) + '/knowledge/stats');
      knowledgeData = Object.assign({}, knowledgeData, { stats: st });
      renderSessionKeepScroll();
    } catch (e) { /* 旁路数据，拿不到不影响答题 */ }
  }

  async function loadKnowledge(practiceId) {
    try {
      const k = await api('/api/practices/' + enc(practiceId) + '/knowledge');
      knowledgeData = k;
      if (true) renderSessionKeepScroll();
    } catch (e) { /* 拿不到就不显示，不影响答题 */ }
  }

  /** 期末考解锁状态 + 历次成绩单 */
  async function loadFinalExam(practiceId) {
    try {
      const gate = await api('/api/practices/' + enc(practiceId) + '/final-exam/gate');
      let exams = [];
      try { exams = await api('/api/practices/' + enc(practiceId) + '/final-exams'); } catch (e) { /* 还没考过 */ }
      finalExamData = { gate, exams: exams || [] };
      if (true) renderSessionKeepScroll();
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
        <a class="back-link" href="practice.html">${I.get('back', 15)} 返回练习</a>
        <h1 id="session-title">练习</h1>
        <span id="session-badge"></span>
        <div class="seg session-view-seg" style="margin-left:16px">
          <a href="answer.html?id=${esc(currentSessionId())}" class="${!isReview && !isStudy && !isDrill ? 'active' : ''}">答题</a>
          <a href="study.html?id=${esc(currentSessionId())}" class="${isStudy ? 'active' : ''}">复习</a>
          <a href="review.html?id=${esc(currentSessionId())}" class="${isReview ? 'active' : ''}">复盘</a>
          <a href="quiz.html?id=${esc(currentSessionId())}" class="${isDrill ? 'active' : ''}">刷题</a>
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
  /** 当前会话页的练习 id（?id= 参数） */
  function currentSessionId() {
    return new URLSearchParams(location.search).get('id') || '';
  }

  /** 当前视图（body[data-view]）：answer / study / review / quiz（引擎内部刷题仍叫 drill） */
  function sessionView() {
    const v = document.body.dataset.view;
    return v === 'quiz' ? 'drill' : (v || 'answer');
  }

  /** brief → sessionState（沿用旧渲染器的形状；题目按题懒加载） */
  function briefToState(brief) {
    return {
      id: brief.id, stageId: brief.stageId, mode: brief.mode, topic: brief.topic,
      scopeMode: brief.scopeMode, scopeText: brief.scopeText, level: brief.level,
      intensity: brief.intensity, review: brief.review, status: brief.status,
      createdAt: brief.createdAt, updatedAt: brief.updatedAt, completedAt: brief.completedAt,
      memory: brief.memory, digest: brief.digest,
      notesText: brief.notesText, notesUpdatedAt: brief.notesUpdatedAt,
      mistakes: [],
      papers: (brief.papers || []).map((x) => ({
        id: x.id, sequence: x.sequence, kind: x.kind, status: x.status,
        compositionMode: x.compositionMode, maxScore: x.maxScore,
        score: x.score, summary: x.summary, progress: x.progress,
        createdAt: x.createdAt, submittedAt: x.submittedAt,
        questions: [], _outlined: false,
      })),
    };
  }

  /** brief 整刷各卷 meta（状态/得分/总结/进度）：任务完成后翻面用；题目与草稿不动 */
  function refreshPaperMeta(brief) {
    for (const x of (brief.papers || [])) {
      const paper = ((sessionState && sessionState.papers) || []).find((p) => String(p.id) === String(x.id));
      if (!paper) continue;
      paper.status = x.status;
      paper.score = x.score;
      paper.summary = x.summary;
      paper.progress = x.progress;
      paper.maxScore = x.maxScore;
      paper.submittedAt = x.submittedAt;
    }
  }

  /** 拉卷大纲（paper:{id} 缓存）：题存根 → 伪题对象（内容按题懒加载） */
  async function hydratePaper(paper) {
    if (!paper || paper._outlined) return;
    const outline = await api('/api/papers/' + enc(paper.id));
    paper.questions = (outline.questions || []).map((s) => ({
      id: s.id, paperId: s.paperId, sequence: s.sequence, qtype: s.qtype, tier: s.tier,
      questionType: s.questionType, points: s.points, prompt: null,
      revealedAt: s.revealed ? 1 : null, answered: !!s.answered, attempts: [],
    }));
    paper._outlined = true;
  }

  /** 只加载当前题（question:{id} 缓存）；有回答再取回答（attempt:{id}） */
  async function hydrateCurrentQuestion() {
    const paper = currentPaper();
    const q = paper && (paper.questions || [])[questionIdx];
    if (!q || q.prompt !== null) return;   // 已有内容（整卷全量返回）就不拉
    const det = await api('/api/questions/' + enc(q.id));
    const attempts = [];
    if (det.latestAttemptId) {
      try { attempts.push(await api('/api/attempts/' + enc(det.latestAttemptId))); } catch (e) { /* 作答没了当没答 */ }
    }
    paper.questions[questionIdx] = {
      id: det.id, paperId: det.paperId, sequence: det.sequence,
      prompt: det.prompt, referenceAnswer: det.referenceAnswer,
      assignedPoints: det.assignedPoints, angle: det.angle,
      knowledgePoints: det.knowledgePoints, demands: det.demands,
      tier: det.tier, questionType: det.questionType, points: det.points,
      qtype: det.qtype, optionsJson: det.optionsJson, answerJson: det.answerJson,
      createdAt: det.createdAt, revealedAt: det.revealedAt,
      answered: !!det.latestAttemptId, attempts,
    };
  }

  /** 复盘页错题列表（直查 DB） */
  async function loadMistakes(id) {
    try {
      const list = await api('/api/practices/' + enc(id) + '/mistakes');
      if (sessionState && String(sessionState.id) === String(id)) {
        sessionState.mistakes = list || [];
        renderSessionKeepScroll();
      }
    } catch (e) { /* 拿不到就不显示 */ }
  }

  /**
   * 会话 init（用户指定流程）：
   * ① 练习详情（practice:{id} 缓存）→ ② 全部卷（下拉）+ 默认最新卷并加载（paper:{id} 缓存）
   * → ③ 当前卷题目，默认第 1 题只载这题（question:{id} 缓存）→ ④ 有回答载回答（attempt:{id}）
   * → ⑤ 追问（渲染时直查 DB）。
   */
  async function loadSession(id, seq) {
    sessionState = null;
    mistakePage = 1;
    // 语音识别状态：就绪才显示麦克风按钮，否则给明确原因
    if (window.Speech) {
      window.Speech.status().then((s) => {
        speechReady = !!(s && s.ready);
        speechReason = (s && s.reason) || '';
        renderQuestion();
      }).catch(() => {});
    }
    try {
      // 复习页：只加载复习提纲（review_doc:{practiceId} 点查缓存）——
      // 不拉练习/卷/知识点/记忆点，也不订 SSE（提纲进度走 3s 轮询），
      // 冷加载就这一条查询（用户指定）
      if (sessionView() === 'study') {
        sessionState = { id: id, topic: '复习提纲', review: false, status: 'active',
          papers: [], mistakes: [], notesText: null, notesUpdatedAt: null };
        await loadStudyDoc(id);
        renderSession();
        return;
      }
      // 刷题页：题目走刷题自己的状态流（drillState 懒加载），不走「只加载最新卷」链
      if (sessionView() === 'drill') {
        sessionState = { id: id, topic: '刷题', review: false, status: 'active',
          papers: [], mistakes: [], notesText: null, notesUpdatedAt: null };
        renderSession();
        subscribeTaskStream(id);
        return;
      }
      const brief = await api('/api/practices/' + enc(id) + '/brief');
      if (seq !== renderSeq) return;
      sessionState = briefToState(brief);
      // 默认最新卷（latest_paper:{practiceId} 缓存解析；没有就取最后一张，一张都没有=还没出卷）
      const latestId = brief.latestPaperId
        || (brief.papers.length ? brief.papers[brief.papers.length - 1].id : null);
      paperIdx = -1;
      if (latestId && sessionState.papers.length) {
        const fi = sessionState.papers.findIndex((x) => String(x.id) === String(latestId));
        paperIdx = fi >= 0 ? fi : sessionState.papers.length - 1;
      }
      if (paperIdx >= 0) await hydratePaper(sessionState.papers[paperIdx]);
      questionIdx = 0;
      // 错题跳题：?q= 参数承接（跨页跳转过来的）
      const jumpQid = jumpToQuestionId || new URLSearchParams(location.search).get('q');
      jumpToQuestionId = null;
      const located = jumpQid ? await locateQuestion(jumpQid) : false;
      if (jumpQid && !located) {
        if (sessionView() === 'drill') {
          jumpToQuestionId = jumpQid;   // 刷题题：留给 drillBody 加载完成后定位（别转圈）
        } else {
          // 考卷里没有的就是刷题题 → 转刷题页继续定位
          location.replace('quiz.html?id=' + enc(id) + '&q=' + enc(jumpQid));
          return;
        }
      }
      await hydrateCurrentQuestion();
      renderSession();
      if (located) {
        const card = $('#question-card');
        if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
      subscribeTaskStream(id);
      // 分区侧载（旁路数据，拿不到不影响答题）：复盘要知识点/期末考/错题，复习要提纲。
      // 答题页 init 仍按五步走（练习/卷/题/回答/追问）；知识点统计走旁路异步补 KPI，不进 init 链
      if (sessionView() === 'answer' || sessionView() === 'quiz') {
        loadKnowledgeStats(id);
      }
      if (sessionView() === 'review') {
        loadKnowledge(id);
        loadFinalExam(id);
        loadMistakes(id);
      }
    } catch (err) {
      if (seq !== renderSeq) return;
      const box = $('#session-body');
      if (box) {
        box.innerHTML = `
          <div class="session-head">
            <a class="back-link" href="practice.html">${I.get('back', 15)} 返回练习</a>
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

  /** 按题 id 定位纸与题号（按需拉各卷大纲）。找到则更新 paperIdx/questionIdx 并返回 true */
  async function locateQuestion(qid) {
    const papers = (sessionState && sessionState.papers) || [];
    for (let pi = 0; pi < papers.length; pi++) {
      await hydratePaper(papers[pi]);
      const qi = (papers[pi].questions || []).findIndex((q) => String(q.id) === String(qid));
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

  /** 保存作答后：把新 attempt 追加进本地题目（按对象去重，考卷与刷题通用） */
  function recordAttempt(qid, attempt) {
    const done = new Set();
    const patch = (q) => {
      if (!q || q.id !== qid || done.has(q)) return;
      done.add(q);
      q.attempts = [...(q.attempts || []), attempt];
    };
    if (drillState) {
      patch(drillState.question);
      (drillState.questions || []).forEach(patch);
    }
    for (const paper of (sessionState && sessionState.papers) || []) {
      (paper.questions || []).forEach(patch);
    }
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
  const isAnswered = (q) => !!(q && (q.answered || latestAttempt(q)));

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
    measureStickyHead();
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
    measureStickyHead();
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
               <a class="link" href="study.html?id=${esc(p.id)}">第一次考？先看复习提纲</a>`)}
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

  /**
   * 答题视图顶部 KPI 网格：覆盖 / 掌握 / 待巩固 / 本卷进度 四张大数字卡。
   * 卡面只留数字与短标签，拆分数据走底栏两格。
   */
  function progressStrip(p) {
    const st = (knowledgeData && knowledgeData.stats) || null;
    const paper = currentPaper();
    const qs = (paper && paper.questions) || [];
    const answered = qs.filter((q) => q.answered).length;
    const seen = st ? st.covered : 0;
    // 待巩固建议 chip：有弱项 → 补弱卷；全覆盖 → 收尾
    const weakChip = !st ? '' : (st.weak > 0
      ? `<span class="delta flat">→ 补弱卷</span>`
      : `<span class="delta up">→ 收尾</span>`);
    return `
      <div class="kpi-grid">
        <div class="kpi-card">
          <div class="kpi-head">
            <div class="kpi-ic i-info">${I.get('chart', 14)}</div>
            <div><div class="kpi-t">覆盖</div><div class="kpi-sub">本练习知识点</div></div>
          </div>
          <div class="kpi-val-row"><b class="kpi-val">${st ? st.covered + '/' + st.total : '—'}</b></div>
          <div class="kpi-note">未见 <b>${st ? (st.total - seen) : '—'}</b> 个点</div>
          <div class="kpi-foot"><span>已见 <b>${st ? seen : '—'}</b></span><span>进度 <b>${st && st.total ? Math.round(seen * 100 / st.total) : 0}%</b></span></div>
        </div>
        <div class="kpi-card">
          <div class="kpi-head">
            <div class="kpi-ic i-ok">${I.get('check', 14)}</div>
            <div><div class="kpi-t">掌握</div><div class="kpi-sub">最近 2 次变种均达标</div></div>
          </div>
          <div class="kpi-val-row"><b class="kpi-val">${st ? st.mastered : '—'}</b></div>
          <div class="kpi-note">掌握率 <b>${st && seen ? Math.round(st.mastered * 100 / seen) : 0}%</b></div>
          <div class="kpi-foot"><span>已覆盖 <b>${st ? seen : '—'}</b></span><span>总点 <b>${st ? st.total : '—'}</b></span></div>
        </div>
        <div class="kpi-card">
          <div class="kpi-head">
            <div class="kpi-ic i-warn">${I.get('target', 14)}</div>
            <div><div class="kpi-t">待巩固</div><div class="kpi-sub">薄弱点，优先出卷</div></div>
          </div>
          <div class="kpi-val-row"><b class="kpi-val">${st ? st.weak : '—'}</b>${weakChip}</div>
          <div class="kpi-note">下一步出 <b>${st && st.weak > 0 ? '补弱卷' : '复习卷'}</b></div>
          <div class="kpi-foot"><span>已掌握 <b>${st ? st.mastered : '—'}</b></span><span>待覆盖 <b>${st ? (st.total - seen) : '—'}</b></span></div>
        </div>
        <div class="kpi-card">
          <div class="kpi-head">
            <div class="kpi-ic i-accent">${I.get('play', 14)}</div>
            <div><div class="kpi-t">${paper ? '第 ' + paper.sequence + ' 张卷' : '本卷'}</div><div class="kpi-sub">${paper ? (MODE_LABELS[paper.compositionMode] || '常规') + ' · ' + qs.length + ' 题' : '尚未出题'}</div></div>
          </div>
          <div class="kpi-val-row"><b class="kpi-val">${paper ? answered + '/' + qs.length : '—'}</b>${paper && paper.score != null ? `<span class="delta up">得分 ${esc(String(paper.score))}</span>` : ''}</div>
          <div class="kpi-note">${paper ? '已答 <b>' + answered + '</b> 题' : ''}</div>
          <div class="kpi-foot"><span>满分 <b>100</b></span><span>状态 <b>${paper ? (PAPER_STATUS[paper.status] || paper.status) : '—'}</b></span></div>
        </div>
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
        <span class="ps-pos">第 ${idx >= 0 ? idx + 1 : '—'}/${papers.length} 张</span>
        <button class="btn btn-ghost btn-sm ps-arrow" data-action="goto-paper" data-idx="${idx + 1}"
          ${next ? 'title="下一张"' : 'disabled title="已经是最后一张"'}>
          <span class="flip-x">${I.get('back', 12)}</span>
        </button>
        <select id="paper-select" title="切换卷">
          ${papers.map((x, i) => `<option value="${i}" ${i === idx ? 'selected' : ''}>第 ${i + 1} 张 · ${PAPER_STATUS[x.status] || x.status}${x.score != null ? ` · ${x.score} 分` : ''}</option>`).join('')}
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
      `已掌握 ${st.mastered}/${st.total} · 平均掌握度 ${st.avgMastery}`, `
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
    const angleTip = angles.length ? '已用过的问法（出题会避开）：\n' + angles.join('\n') : '还没出过题';
    const mems = (knowledgeData && knowledgeData.memoryPoints && knowledgeData.memoryPoints[kp.name]) || [];
    return `
      <div class="kp-row${mems.length ? ' has-mem' : ''}">
        <div class="kp-name" title="${esc(kp.name)}">${esc(kp.name)}</div>
        <div class="kp-bar"><div class="kp-fill ${cls}" style="width:${Math.max(2, Math.round(kp.mastery))}%"></div></div>
        <div class="kp-meta">
          <span class="kp-pct num" title="掌握度 0-100：历次作答的近期加权平均（最近的表现权重更高）">${Math.round(kp.mastery)}</span>
          <span class="kp-seen" title="${esc(angleTip)}">考过 ${kp.timesSeen} 次${angles.length ? ' · ' + angles.length + ' 种问法' : ''}</span>
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
              满分 ${num(paper.maxScore)}${paper.score != null ? ` · 得分 <b>${esc(paper.score)}</b>` : ''}
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
          ? `<div class="hint">${pr.running ? '任务进行中' : '已停止 · 可续跑'}</div>`
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

  /** 批改后的选项回顾：客观题把选项列出来，标出你的选择与正确答案——不看选项没法排错 */
  function optionsReviewHtml(q, a) {
    if (q.qtype !== 'single' && q.qtype !== 'multi') return '';
    const opts = parseOptions(q.optionsJson);
    if (!opts.length) return '';
    const correct = new Set();
    try {
      const ans = JSON.parse(q.answerJson || '{}');
      (ans.correct || []).forEach((k) => correct.add(String(k).toUpperCase().trim()));
    } catch (e) { /* 坏数据就不标对错，只列选项 */ }
    const picked = new Set(String((a && a.answer) || '').toUpperCase()
      .split(/[,，、;\s]+/).filter(Boolean));
    return `
      <div class="exam-opts review">
        ${opts.map((o) => {
          const k = String(o.key).toUpperCase().trim();
          const ok = correct.has(k), mine = picked.has(k);
          return `
            <div class="exam-opt review-opt${ok ? ' ok' : ''}${mine && !ok ? ' bad' : ''}">
              <span class="opt-key">${esc(o.key)}</span>
              <span class="opt-text">${esc(o.text)}</span>
              ${ok && mine ? '<span class="opt-tag ok">你的选择 · 正确</span>'
                : mine ? '<span class="opt-tag bad">你的选择</span>'
                : ok ? '<span class="opt-tag ok">正确答案</span>' : ''}
            </div>`;
        }).join('')}
      </div>`;
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
          <p class="muted">按薄弱知识点逐题出题，答完立刻批改；掌握越稳，题越深。</p>
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
        ${scoreTrendHtml(recent, scores.length - recent.length)}
        <div class="drill-stats-body" ${open ? '' : 'hidden'}>
          <div class="drill-win">
            <span class="drill-win-label">统计窗口</span>
            ${[10, 20, 30, 50].map((n) => `
              <button class="drill-win-btn${n === win ? ' active' : ''}"
                data-action="drill-window" data-n="${n}">近 ${n} 题</button>`).join('')}
          </div>
          ${drillAchievementsHtml(d)}
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
        UI.toast(`已刷 ${t} 题，里程碑达成`, 'success', '里程碑');
      }
    });
    [3, 5, 10].forEach((t) => {
      if (crossed(d.streak || 0, prev.streak, t)) celebrateStreak(t);
    });
    if (crossed(d.todayCount || 0, prev.todayCount, 10)) {
      UI.toast('今日目标达成', 'success', '里程碑');
    }
    drillPrev = { count: d.count || 0, streak: d.streak || 0, todayCount: d.todayCount || 0 };
  }

  /** 连对庆祝：中央安静小签一闪，不打断刷题 */
  function celebrateStreak(n) {
    const host = document.createElement('div');
    host.className = 'streak-burst';
    host.innerHTML = `<span class="streak-burst-text">连对 ${n}</span>`;
    document.body.appendChild(host);
    setTimeout(() => host.remove(), 1700);
  }

  /** 得分趋势：折线 + 掌握线；画线动画、逐点入场、悬停出明细。
   *  可切「平均分」：每题后的累计平均（总分 ÷ 题数）——看进步路线，单题失手不至断崖。 */
  function scoreTrendHtml(scores, offset) {
    if (!scores || !scores.length) return '';
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
        fill="${v >= 6 ? 'var(--ok)' : 'var(--bad)'}">
        <title>第 ${offset + i + 1} 题 · ${isAvg ? `平均 ${v}（本题得 ${scores[i]} 分）` : `${scores[i]} / 10`}</title>
      </circle>`).join('');
    return `
      <div class="drill-trend">
        <div class="drill-trend-sub">
          <span>${isAvg ? '平均分趋势' : '得分趋势 · 虚线为掌握线'}</span>
          <span class="drill-chart-seg">
            <button class="drill-win-btn${isAvg ? '' : ' active'}" data-action="drill-chart" data-mode="score">得分</button>
            <button class="drill-win-btn${isAvg ? ' active' : ''}" data-action="drill-chart" data-mode="avg">平均分</button>
          </span>
        </div>
        <svg class="score-chart" viewBox="0 0 ${W} ${H}">
          <line x1="${padX}" y1="${y(6)}" x2="${W - padX}" y2="${y(6)}"
            stroke="var(--line-2)" stroke-dasharray="5 5" stroke-width="1.5"/>
          <polyline class="chart-line" pathLength="1" points="${pts}" fill="none" stroke="var(--info)" stroke-width="2"
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
        ${optionsReviewHtml(q, a)}
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
        <div class="rb-body">${rich(q.referenceAnswer || '（本题没有参考答案）')}</div>
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
          <div class="score-big ${scoreCls((paper.score || 0) / 100)}">${paper.score == null ? '—' : esc(paper.score)}</div>
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
                <span>得分 <b>${p.score == null ? '—' : esc(p.score) + ' / ' + num(p.maxScore)}</b></span>
              </div>
            </div>`;
        }).join('')}</div>
      </div>`;
  }

  const scoreCls = (ratio) => (ratio >= .8 ? 'score-high' : ratio >= .6 ? 'score-mid' : 'score-low');

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
        subscribeTaskStream(sessionState.id);
        UI.toast('已开始出题，进度会自动更新', 'info');
      });
      return;
    }

    /* —— 登录 / 注册已拆到独立 login.html（js/login.js），这里只处理工作台内表单 —— */

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
      const r = await api('/api/practices/' + enc(sessionState.id) + '/notes/save', {
        method: 'POST', body: { text },
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
    if (e.target.id === 'paper-select') {
      syncDraft();
      paperIdx = Number(e.target.value);
      questionIdx = 0;
      // ① 切到的卷可能还没拉过：先补卷大纲
      await hydratePaper(currentPaper());
      // ② 再拉当前题的题面，否则题目区是空的
      await hydrateCurrentQuestion();
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
      return;
    }

    const el = e.target.closest('[data-action]');
    if (!el) return;
    const action = el.dataset.action;
    const id = el.dataset.id;

    if (action === 'gen-summary') {
      // 会话复盘页也有这个按钮（阶段详情页的在 stage.html 自己处理）
      const ok = await UI.confirm({
        title: '生成阶段总结',
        body: '会覆盖当前的阶段总结，全复习的范围以新总结为准。',
        okText: '生成',
      });
      if (!ok) return;
      await UI.busy(el, '生成中…', async () => {
        await api('/api/stages/' + enc(id) + '/summary', { method: 'POST' });
        UI.toast('阶段总结已生成', 'success');
      });
      return;
    }

    /* —— 练习进行中 —— */
    if (action === 'goto-question') {
      syncDraft();
      await stopRecordingIfAny();
      questionIdx = Number(el.dataset.idx) || 0;
      await hydrateCurrentQuestion();   // 只拉这一题（question:{id} 缓存）
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
        await api('/api/practices/' + enc(id) + '/delete', { method: 'POST' });
        UI.toast('练习已删除', 'success');
        location.href = 'practice.html';
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
      // ① 切到的卷可能还没拉过：先补卷大纲（已拉过是空操作）
      await hydratePaper(currentPaper());
      // ② 再拉当前题的题面，否则题目区是空的
      await hydrateCurrentQuestion();
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
        subscribeTaskStream(sessionState.id);
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
        subscribeTaskStream(sessionState.id);
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
        subscribeTaskStream(sessionState.id);
      });
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
        const saved = await api('/api/questions/' + enc(qid) + '/attempts', {
          method: 'POST', body: JSON.stringify({ answer: text }),
        });
        recordAttempt(qid, saved);
        delete drafts[qid];
        UI.toast('作答已保存', 'success');
        // 保存后直接进入下一题，不显示参考答案
        const qs = (currentPaper() && currentPaper().questions) || [];
        if (questionIdx < qs.length - 1) {
          questionIdx += 1;
          // ① 新题在大纲里只是题号存根（prompt 为 null），先按题拉一次题面
          await hydrateCurrentQuestion();
          // ② 题面到手再渲染，否则题面区是空的
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
        subscribeTaskStream(sessionState.id);
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
        hint: '',
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
        await api('/api/questions/' + enc(qid) + '/followups/clear', { method: 'POST' });
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
      location.href = 'answer.html?id=' + el.dataset.id;
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
        const m = await api('/api/practices/' + enc(sessionState.id) + '/mistakes/' + enc(mid) + '/status', {
          method: 'POST', body: { status: 'resolved' },
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
        const m = await api('/api/practices/' + enc(sessionState.id) + '/mistakes/' + enc(mid) + '/status', {
          method: 'POST', body: { status: 'open' },
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
        await api('/api/practices/' + enc(sessionState.id) + '/mistakes/' + enc(mid) + '/delete', { method: 'POST' });
        sessionState.mistakes = (sessionState.mistakes || []).filter((x) => x.id !== mid);
        renderSessionKeepScroll();
        UI.toast('已移出错题本', 'success');
      });
      return;
    }
    if (action === 'goto-mistake-question') {
      const qid = el.dataset.qid;
      if (!qid) return;
      // 错题本在复盘页，题目在答题页/刷题页——跨页跳转（?q= 承接定位）。
      // 找到就去答题页（顺手定位 paperIdx/questionIdx），找不到就是刷题题去刷题页
      const inPaper = await locateQuestion(qid);
      if (inPaper) {
        location.href = 'answer.html?id=' + currentSessionId() + '&q=' + encodeURIComponent(qid);
      } else {
        location.href = 'quiz.html?id=' + currentSessionId() + '&q=' + encodeURIComponent(qid);
      }
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
        subscribeTaskStream(sessionState.id);
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
    // 当前路由类（如 route-practice）决定侧栏高亮——详情页（答题/刷题/复盘）都算「练习」
    const routeMatch = (document.body.className || '').match(/route-(\w+)/);
    const activeRoute = routeMatch ? routeMatch[1] : '';
    nav.innerHTML = NAV_ITEMS.map((n) => {
      if (n.section) return `<div class="nav-section">${esc(n.section)}</div>`;
      return `
        <a href="${n.href}" class="nav-item${n.route === activeRoute ? ' active' : ''}" data-route="${n.route}">
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
      location.replace('login.html');
      return;
    }
    if (e.target.closest('[data-action="open-profile"]')) {
      location.href = 'profile.html';
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


  window.addEventListener('beforeunload', closeTaskStream);
  // 字号先落地再渲染，免得先按默认大小闪一下
  initFontSize();

  /** 启动（拆页后）：鉴权 → 按 body[data-view] 渲染会话壳并 init。?id= 练习，?q= 错题跳题 */
  (async function boot() {
    if (getToken()) {
      try {
        sessionUser = await api('/api/auth/me');
      } catch (err) {
        sessionUser = null;
      }
    }
    if (!sessionUser) {
      location.replace('login.html');
      return;
    }
    renderNav();
    const view = $('#view');
    if (view) view.innerHTML = sessionShell(sessionView());
    measureStickyHead();   // 壳一挂上就量页头位置，mini 判定不等数据
    renderSeq += 1;
    await loadSession(currentSessionId(), renderSeq);
  })();
})();
