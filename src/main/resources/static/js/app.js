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
      const el = document.createElement('div');
      el.className = 'toast toast-' + (type || 'info');
      el.innerHTML = `
        <span class="toast-icon">${I.get(icons[type] || 'info', 16)}</span>
        <div class="toast-body">
          ${title ? `<div class="toast-title">${esc(title)}</div>` : ''}
          <div class="toast-msg">${esc(msg)}</div>
        </div>`;
      ensureHost().appendChild(el);
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

    return { toast, confirm, busy, prompt };
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
    { route: 'settings', href: '#/settings', icon: 'gear', label: '设置' },
  ];

  const PAPER_STATUS = { generating: '出题中', answering: '答题中', grading: '批改中', graded: '已交卷' };
  const TIER_ORDER = ['基础', '中档', '大题'];
  const TIER_CLS = { '基础': 'tier-base', '中档': 'tier-mid', '大题': 'tier-major' };
  /** 组卷模式：控制这卷出哪些难度档、出几道题。与知识点强化链路无关 */
  const MODE_LABELS = {
    regular: '常规', base: '基础', improve: '提升',
    deep: '深入', baseImprove: '基础强化', midImprove: '中档提升'
  };
  const MODE_TIPS = {
    regular: '混排 · 按题量',
    base: '25 题',
    improve: '14 题',
    deep: '5 题',
    baseImprove: '20 题',
    midImprove: '10 题'
  };

  let renderSeq = 0;
  let practiceStageId = '';
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
  let pollTimer = null;
  let knowledgeData = null;   // 知识点清单 + 掌握度
  let finalExamData = null;   // 期末考解锁状态 + 历次成绩单
  const drafts = {};
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
  async function api(path, options = {}) {
    const opts = Object.assign({ headers: { 'Content-Type': 'application/json' } }, options);
    // body 允许传对象——这里统一 JSON.stringify，防止有人直接丢对象给 fetch
    // （fetch 会把对象转成 "[object Object]"，Jackson 看到开头 [ 就报 START_ARRAY）
    if (opts.body != null && typeof opts.body !== 'string' && !(opts.body instanceof FormData)) {
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

    if (route === 'stage-detail') {
      view.innerHTML = stageDetailView();
      loadStageDetail(arg, seq);
    } else if (route === 'practice') {
      view.innerHTML = practiceView();
      loadPracticeStages(seq);
    } else if (route === 'session') {
      view.innerHTML = sessionShell(sub === 'review' ? 'review' : 'answer');
      loadSession(arg, seq);
    } else if (route === 'drill') {
      view.innerHTML = placeholderView('押题', '根据简历与目标岗位，练习最可能被问到的问题。');
    } else if (route === 'mock') {
      view.innerHTML = placeholderView('模拟面试', '贴近真实面试的多轮追问，全程不评分，结束后归档记录。');
    } else if (route === 'settings') {
      view.innerHTML = settingsView();
      loadSettings(seq);
    } else {
      view.innerHTML = stagesView();
      loadStages(seq);
    }
    window.scrollTo({ top: 0, behavior: 'instant' in window ? 'instant' : 'auto' });
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
        : `<button class="btn btn-quiet btn-sm" data-action="add-mistake" data-qid="${esc(qid)}">
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
        <button class="btn btn-ghost btn-sm flip-x" data-action="mistake-page" data-page="${mistakePage + 1}" ${mistakePage >= pages ? 'disabled' : ''}>
          ${I.get('back', 13)}
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
                <td class="ellipsis">${esc(info.prompt || '（题目已不存在）')}</td>
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
            <div class="drawer-text">${rich(info.prompt || '（题目已不存在）')}</div>
          </div>
          ${a ? `
          <div class="drawer-section">
            <div class="drawer-label">我的作答</div>
            <div class="drawer-text muted">${esc(a.answer || '（未作答）')}</div>
          </div>
          ${ev && ev.feedback ? `
          <div class="drawer-section">
            <div class="drawer-label">点评${ev.score !== null ? ` · ${esc(ev.score)}/10` : ''}</div>
            <div class="drawer-text">${rich(ev.feedback)}</div>
          </div>` : ''}` : ''}
        </div>
        <div class="drawer-actions">
          ${info.paperSeq ? `<button class="btn btn-ghost btn-sm" data-action="goto-mistake-question" data-qid="${esc(m.questionId)}">看原题</button>` : ''}
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
                  title="记事本 · 最后修改 ${esc(when)}&#10;随手记、随手改，停下 0.8 秒自动保存"
                  placeholder="随手记一笔…&#10;&#10;这里就是个记事本：随时改，自动保存。">${esc(text)}</textarea>
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

  /* ============================================================
   *  页面：阶段
   * ============================================================ */
  function stagesView() {
    return `
      ${pageHead('Workspace', '阶段')}
      <div class="stagger">
        <div class="card">
          <h2>新建阶段</h2>
          <form data-form="stage-create">
            <div class="form-grid">
              <div class="field">
                <label>阶段名称 <span class="req">*</span></label>
                <input type="text" name="name" placeholder="例：Java 后端秋招冲刺" />
              </div>
            </div>
            <div class="btn-row">
              <button type="submit" class="btn btn-primary">${I.get('plus', 15)} 创建阶段</button>
            </div>
          </form>
        </div>
        <div class="card">
          <h2>全部阶段 <span class="sub" id="stage-count"></span></h2>
          <div id="stage-list">${emptyState('加载中…')}</div>
        </div>
      </div>`;
  }

  async function loadStages(seq) {
    const box = $('#stage-list');
    if (!box) return;
    try {
      const stages = await api('/api/stages');
      if (seq !== renderSeq) return;
      const sub = $('#stage-count');
      if (sub) sub.textContent = stages.length ? `共 ${stages.length} 个` : '';
      box.innerHTML = stages.length ? stageListHtml(stages) : emptyState('还没有阶段', '',
        `<div class="btn-row" style="justify-content:center"><button class="btn btn-primary" onclick="location.hash='#/stages'">${I.get('plus', 15)} 新建阶段</button></div>`);
    } catch (err) {
      if (seq !== renderSeq) return;
      box.innerHTML = emptyState('加载失败', err.message);
    }
  }

  function stageListHtml(stages) {
    return `<div class="list stagger">${stages.map((s) => {
      const st = s.stats || {};
      return `
        <div class="list-item" data-action="open-stage" data-id="${esc(s.id)}">
          <div class="li-icon">${I.get('folder', 17)}</div>
          <div class="li-main">
            <div class="li-title">${esc(s.name)} ${statusBadge(s.status)}</div>
            <div class="li-sub">更新于 ${fmtTime(s.updatedAt)}${s.summary ? ' · 已生成阶段总结' : ''}</div>
          </div>
          <div class="li-stats">
            <span>练习 <b>${num(st.practiceCount)}</b></span>
            <span>卷 <b>${num(st.paperCount)}</b></span>
            <span>题 <b>${num(st.questionCount)}</b></span>
            <span>均分 <b>${st.averageScore === null ? '—' : esc(st.averageScore)}</b></span>
          </div>
          <button class="btn btn-quiet btn-sm" data-action="delete-stage" data-id="${esc(s.id)}">
            ${I.get('trash', 14)}
          </button>
        </div>`;
    }).join('')}</div>`;
  }

  /* ============================================================
   *  页面：阶段详情
   * ============================================================ */
  function stageDetailView() {
    return `<div id="stage-detail-body">${emptyState('加载中…')}</div>`;
  }

  async function loadStageDetail(id, seq) {
    const box = $('#stage-detail-body');
    if (!box) return;
    try {
      const stage = await api('/api/stages/' + enc(id));
      if (seq !== renderSeq) return;
      box.innerHTML = stageDetailHtml(stage);
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
              ${sparkline(st.recentScores || [])}
            </div>
          </div>
        </div>

        ${stageSummaryHtml(s.summary, hasSummary)}

        <div class="card">
          <h2>练习 <span class="sub">${(s.practices || []).length}</span></h2>
          ${practiceTableHtml(s.practices || [])}
          <div class="btn-row">
            <a class="btn btn-primary" href="#/practice">${I.get('plus', 15)} 新建练习</a>
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
      ${pageHead('Practice', '练习')}
      <div class="stagger">
        <div class="card">
          <h2>新建练习</h2>
          <form data-form="practice-create">
            <div class="form-grid">
              <div class="field">
                <label>所属阶段 <span class="req">*</span></label>
                <div class="inline-row">
                  <select name="stageId" id="stage-select"></select>
                  <button type="button" class="btn btn-ghost btn-sm" data-action="reload-stages">
                    ${I.get('refresh', 14)} 刷新
                  </button>
                  <a class="link" href="#/stages">${I.get('plus', 13)} 新建阶段</a>
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
            <div class="btn-row">
              <button type="submit" class="btn btn-primary" id="practice-submit">
                ${I.get('play', 15)} 创建练习
              </button>
            </div>
          </form>
        </div>
        <div class="card">
          <h2>练习列表 <span class="sub" id="practice-list-sub"></span></h2>
          <div id="practice-list">${emptyState('请选择所属阶段')}</div>
        </div>
      </div>`;
  }

  async function loadPracticeStages(seq) {
    const sel = $('#stage-select');
    if (!sel) return;
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

    const actives = (stages || []).filter((s) => s.status === 'active');
    if (!actives.length) {
      sel.innerHTML = '<option value="">暂无阶段</option>';
      sel.disabled = true;
      if (submit) submit.disabled = true;
      if (tip) tip.textContent = '请先创建阶段。';
      const box = $('#practice-list');
      if (box) box.innerHTML = emptyState('还没有阶段');
      return;
    }
    sel.disabled = false;
    if (submit) submit.disabled = false;
    if (!actives.some((s) => s.id === practiceStageId)) practiceStageId = actives[0].id;
    sel.innerHTML = actives.map((s) => (
      `<option value="${esc(s.id)}" ${s.id === practiceStageId ? 'selected' : ''}>${esc(s.name)}</option>`
    )).join('');
    sel.value = practiceStageId;
    if (tip) tip.textContent = '练习归属到所选阶段';
    loadPracticeList(seq);
  }

  async function loadPracticeList(seq) {
    const box = $('#practice-list');
    if (!box || !practiceStageId) return;
    box.innerHTML = emptyState('加载中…');
    try {
      const stage = await api('/api/stages/' + enc(practiceStageId));
      if (seq !== renderSeq) return;
      box.innerHTML = practiceTableHtml(stage.practices || []);
      const sub = $('#practice-list-sub');
      if (sub) sub.textContent = stage.name || '';
    } catch (err) {
      if (seq !== renderSeq) return;
      box.innerHTML = emptyState('加载失败', err.message);
    }
  }

  /** 练习列表：紧凑表格（阶段详情用），点行进练习 */
  function practiceTableHtml(list) {
    if (!list || !list.length) return emptyState('还没有练习');
    return `
      <div class="tbl-wrap">
        <table class="tbl">
          <thead>
            <tr><th>练习</th><th>范围</th><th class="w-4 num">卷</th><th class="w-4 num">均分</th><th class="w-4 num">最近</th><th class="w-5 num">错题</th><th class="w-time">更新</th></tr>
          </thead>
          <tbody>
            ${list.map((p) => {
              const st = p.stats || {};
              return `
                <tr data-action="open-practice" data-id="${esc(p.id)}" style="cursor:pointer">
                  <td>${esc(p.topic)}${p.review ? ' <span class="badge badge-orange">全复习</span>' : ''}</td>
                  <td class="muted">${scopeLabel(p)}</td>
                  <td class="num">${num(st.paperCount)}</td>
                  <td class="num">${st.averageScore === null ? '—' : esc(st.averageScore)}</td>
                  <td class="num">${st.lastScore === null ? '—' : esc(st.lastScore)}</td>
                  <td class="num">${p.openMistakeCount ? `<b style="color:var(--danger)">${num(p.openMistakeCount)}</b>` : '—'}</td>
                  <td class="muted num w-time">${fmtTime(p.updatedAt)}</td>
                </tr>`;
            }).join('')}
          </tbody>
        </table>
      </div>`;
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
    return `
      <div class="session-head">
        <a class="back-link" href="#/practice">${I.get('back', 15)} 返回练习</a>
        <h1 id="session-title">练习</h1>
        <span id="session-badge"></span>
        <div class="seg session-view-seg" style="margin-left:16px">
          <a href="#/session/${esc(currentSessionId())}" class="${isReview ? '' : 'active'}">答题</a>
          <a href="#/session/${esc(currentSessionId())}/review" class="${isReview ? 'active' : ''}">复盘</a>
        </div>
        <div class="page-actions" style="margin-left:auto" id="session-actions"></div>
      </div>
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

  /** 当前是「答题」还是「复盘」——视图状态存在 URL 里，轮询重绘天然保持 */
  function sessionView() {
    const parts = (location.hash || '').replace(/^#\/?/, '').split('/');
    return parts[2] === 'review' ? 'review' : 'answer';
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
      if (jumpQid) locateQuestion(jumpQid);
      renderSession();
      if (jumpQid) {
        const card = $('#question-card');
        if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
      schedulePoll();
      // 知识点掌握度与期末考是旁路数据，拿不到不影响答题
      loadKnowledge(id);
      loadFinalExam(id);
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
    if (!sessionState || !q) return;
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
    const acts = $('#session-actions');
    if (acts) {
      acts.innerHTML = `
        <button class="btn btn-quiet btn-sm" data-action="delete-practice" data-id="${esc(p.id)}">
          ${I.get('trash', 14)} 删除练习
        </button>`;
    }
    $('#session-body').innerHTML = sessionView() === 'review' ? reviewBody(p) : answerBody(p);
    const notesBox = $('#session-notes');
    if (notesBox) notesBox.innerHTML = noteRail(p);
    restoreAnswer();
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
      body.innerHTML = paper.status === 'answering' ? answeringBody(paper)
        : paper.status === 'graded' ? gradedPaperBody(paper)
        : paper.status === 'generating' ? generatingBody(paper)
        : gradingBody(paper);
    }
    const nav = $('#qnav-box');
    if (nav && qs.length) nav.innerHTML = qnavHtml(qs);
    restoreAnswer();
  }

  function restoreAnswer() {
    const q = currentQuestion();
    const ta = $('#answer-input');
    if (ta && q) {
      ta.value = drafts[q.id] !== undefined ? drafts[q.id] : (latestAttempt(q) ? latestAttempt(q).answer : '');
    }
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
        ${paperSwitcher(p)}
        ${paper ? paperSection(paper, p) : `
          <div class="card">
            ${emptyState('尚未出题', '',
              `${modePicker()}
              <div class="btn-row" style="justify-content:center">
                <button class="btn btn-primary btn-lg" data-action="generate-paper" data-id="${esc(p.id)}">
                  ${I.get('play', 16)} 开始出题
                </button>
              </div>`)}
          </div>`}
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
    // 今日进度：覆盖 → 掌握 → 待巩固；并给出下一步建议（只在没有进行中的卷时）
    const open = paper && paper.status !== 'graded';
    const canSuggest = st && st.total > 0 && !open;
    return `
      <div class="progress-strip">
        <span>覆盖 <b>${st ? st.covered : '—'}/${st ? st.total : '—'}</b></span>
        <span>掌握 <b>${st ? st.mastered : '—'}</b></span>
        <span>待巩固 <b>${st ? st.weak : '—'}</b></span>
        ${paper ? `<span>第 <b>${paper.sequence}</b> 张卷${paper.score != null ? ` · 得分 <b>${paper.score}</b>` : ''}</span>` : ''}
        ${canSuggest ? suggestNext(st, p) : ''}
        <a class="spacer link" href="#/session/${esc(p.id)}/review">看复盘 <span class="flip-x">${I.get('back', 13)}</span></a>
      </div>`;
  }

  /** 下一步建议：覆盖 → 补弱 → 收尾（全部掌握）。单日节奏下不让用户自己猜该出什么卷 */
  function suggestNext(st, p) {
    if (st.stage === 'mastered') {
      return `<a class="btn btn-ghost btn-sm" href="#/session/${esc(p.id)}/review">全部掌握 · 去复盘</a>`;
    }
    const label = st.stage === 'coverage'
      ? `继续覆盖 · 还剩 ${st.unseen} 个点`
      : `补弱卷 · ${st.weak} 个待巩固`;
    return `<button class="btn btn-primary btn-sm" data-action="generate-paper"
      data-id="${esc(p.id)}" data-mode="regular">${label}</button>`;
  }

  /** 紧凑卷切换器：答题时切卷用，完整历史在复盘视图 */
  function paperSwitcher(p) {
    const papers = p.papers || [];
    if (papers.length < 2) return '';
    const paper = currentPaper();
    const idx = paper ? papers.findIndex((x) => x.id === paper.id) : -1;
    const prev = idx > 0 ? papers[idx - 1] : null;
    const next = idx >= 0 && idx < papers.length - 1 ? papers[idx + 1] : null;
    return `
      <div class="paper-switch">
        <button class="btn btn-ghost btn-sm" data-action="goto-paper" data-idx="${idx - 1}" ${prev ? '' : 'disabled'}>
          ${I.get('back', 13)}
        </button>
        <span class="ps-pos">第 <b>${paper ? paper.sequence : '—'}</b> / ${papers.length} 张</span>
        <button class="btn btn-ghost btn-sm flip-x" data-action="goto-paper" data-idx="${idx + 1}" ${next ? '' : 'disabled'}>
          ${I.get('back', 13)}
        </button>
        <select id="paper-select">
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
    const ready = !!g.unlocked;

    const action = `
      <div class="btn-row" style="justify-content:center; margin:10px 0 4px">
        <button class="btn ${ready ? 'btn-primary' : 'btn-ghost'} btn-lg"
                data-action="final-exam" data-id="${esc(practice.id)}" ${ready ? '' : 'disabled'}>
          ${I.get('award', 15)} 申请期末考
        </button>
      </div>
      <div class="empty" style="padding:6px 0 12px">${esc(g.hint || '')}</div>`;

    const list = exams.length ? exams.map(finalExamCard).join('')
      : '<div class="empty" style="padding:12px 0">还没考过期末考。</div>';

    return collapsiblePanel('final-exam', 'panel-neutral', 'award', '期末综合考',
      ready ? `可申请 · 共 ${exams.length} 次` : `未解锁 · 共 ${exams.length} 次`, `
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
              满分 ${num(paper.maxScore)}${paper.score !== null ? ` · 得分 <b>${esc(paper.score)}</b>` : ` · 已答 ${answered}`}
              ${paper.compositionMode && paper.compositionMode !== 'regular' ? ` · <b>${MODE_LABELS[paper.compositionMode] || paper.compositionMode}</b>` : ''}
            </span>
          </h2>
          ${paperActions(paper, p, answered)}
        </div>
        ${isGraded ? modePicker() : ''}
        ${tierBreakdown(qs)}
        ${isGraded ? paperSummaryHtml(paper) : ''}
        <div id="qnav-box">${qs.length ? qnavHtml(qs) : ''}</div>
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
          <button class="btn btn-success" data-action="submit-paper" data-id="${esc(paper.id)}">
            ${I.get('send', 15)} 交卷${answered ? `（已答 ${answered}）` : ''}
          </button>
        </div>`;
    }
    return `
      <div class="page-actions">
        <button class="btn btn-primary" data-action="generate-paper" data-id="${esc(p.id)}">
          ${I.get('play', 15)} 继续出题
        </button>
      </div>`;
  }

  /** 组卷模式选择器：决定下一张卷出哪些难度档、出几道题 */
  function modePicker() {
    return `
      <div class="field" style="align-items:center;text-align:center">
        <label>组卷模式</label>
        <div class="seg" data-seg="paperMode">
          ${Object.keys(MODE_LABELS).map((k) => `<button type="button" data-value="${k}" class="${k === paperMode ? 'active' : ''}">${MODE_LABELS[k]}</button>`).join('')}
        </div>
        <span class="tip" id="paper-mode-tip">${MODE_TIPS[paperMode] || ''}</span>
      </div>`;
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
        <h2>作答 <span class="sub">${a ? `已保存 ${a.sequence} 次` : ''}</span></h2>
        <textarea id="answer-input" data-qid="${esc(q.id)}"
          placeholder="写下你的回答…"></textarea>
        <div class="btn-row">
          ${micButtonHtml(q)}
          <button class="btn btn-primary" data-action="save-answer" data-qid="${esc(q.id)}">
            ${I.get('check', 15)} 保存并下一题
          </button>
          <button class="btn btn-ghost" data-action="goto-question" data-idx="${Math.max(0, questionIdx - 1)}">
            ${I.get('back', 15)} 上一题
          </button>
          <button class="btn btn-ghost" data-action="goto-question" data-idx="${Math.min(qs.length - 1, questionIdx + 1)}">
            下一题 ${I.get('play', 15)}
          </button>
          <span style="flex:1"></span>
          ${captureBtns(q.id)}
          <button class="btn btn-quiet" data-action="reveal" data-qid="${esc(q.id)}" ${revealed ? 'disabled' : ''}>
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

      <div class="card">
        <h2>${I.get('send', 15)} 我的作答</h2>
        ${a
          ? `<div class="answer-box">${esc(a.answer)}</div>`
          : `<div class="empty" style="padding:22px 0">未作答</div>`}
      </div>

      ${ev ? `
        <div class="card review-card">
          <h2>${I.get('star', 15)} AI 点评
            ${ev.score !== null && ev.score !== undefined
              ? `<span class="sub">表现 ${esc(ev.score)} / 10</span>` : ''}
            <button class="btn btn-quiet btn-sm" style="margin-left:auto"
              data-action="quick-note" data-qid="${esc(q.id)}" data-scene="grading">
              ${I.get('pencil', 13)} 记笔记
            </button>
          </h2>
          <div class="review-box">${richFeedback(ev.feedback)}</div>
          ${Object.keys(ev.dimensions || {}).length ? `
            <div class="dims" style="margin-top:10px">
              ${Object.entries(ev.dimensions || {}).map(([k, v]) => (
                `<span class="dim-chip">${esc(k)} <b>${esc(v)}</b></span>`
              )).join('')}
            </div>` : ''}
        </div>` : ''}

      ${referenceAnswerPanel(q)}

      <div class="btn-row">
        ${captureBtns(q.id)}
        <span style="flex:1"></span>
        <button class="btn btn-ghost" data-action="goto-question" data-idx="${Math.max(0, questionIdx - 1)}">
          ${I.get('back', 15)} 上一题
        </button>
        <button class="btn btn-ghost" data-action="goto-question" data-idx="${Math.min(qs.length - 1, questionIdx + 1)}">
          下一题 ${I.get('play', 15)}
        </button>
      </div>`;
  }

  function referenceAnswerPanel(q) {
    return `
      <div class="panel ref-card">
        <h3>${I.get('eye', 15)} 参考答案</h3>
        <div class="rb-body" style="background:var(--surface);border-color:#abefc6;line-height:1.8">${rich(q.referenceAnswer || '（本题没有参考答案）')}</div>
      </div>`;
  }

  function paperSummaryHtml(paper) {
    const sm = paper.summary;
    const failed = !!(sm && sm.failed);
    return `
      <div class="panel panel-success">
        <h3>${I.get('star', 15)} 本卷成绩 <span class="muted">${sm ? '批改于 ' + fmtTime(sm.gradedAt) : ''}</span></h3>
        <div class="score-row">
          <div class="score-big ${scoreCls((paper.score || 0) / 100)}">${esc(paper.score === null ? '—' : paper.score)}</div>
          <div>
            <div class="s-label" style="font-size:var(--fs-sm);color:var(--text-3);font-weight:650;letter-spacing:.05em">得分 / 满分</div>
            <div class="num" style="font-size:var(--fs-lg);font-weight:650">${num(paper.maxScore)}</div>
          </div>
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
   *  页面：占位（押题 / 模拟面试）
   * ============================================================ */
  function placeholderView(title, desc) {
    return `
      ${pageHead('Coming soon', title, desc)}
      <div class="stagger">
        <div class="card">
          ${emptyState('规划中')}
        </div>
      </div>`;
  }

  /* ============================================================
   *  页面：设置
   * ============================================================ */
  function settingsView() {
    return `
      ${pageHead('Settings', '设置')}
      <div class="stagger">
        <div class="card">
          <h2>模型</h2>
          <form data-form="settings-save" id="settings-form">
            <div class="form-grid">
              <div class="field full">
                <label>接口协议</label>
                <select name="apiFormat">
                  <option value="anthropic">Anthropic Messages（Claude / mimo）</option>
                  <option value="openai">OpenAI Chat Completions（DeepSeek / 通义 / Ollama）</option>
                </select>
              </div>
              <div class="field full">
                <label>API Base URL</label>
                <input type="text" name="apiBase" placeholder="https://api.anthropic.com" />
              </div>
              <div class="field full">
                <label>API Key</label>
                <input type="password" name="apiKey" placeholder="sk-..." />
              </div>
              <div class="field">
                <label>模型</label>
                <input type="text" name="model" placeholder="claude-sonnet-5" />
              </div>
              <div class="field">
                <label>温度</label>
                <input type="text" name="temperature" placeholder="1.0" />
              </div>
            </div>
            <div class="save-bar">
              <button type="submit" class="btn btn-primary">${I.get('check', 15)} 保存</button>
              <span id="save-ok" class="save-ok" style="display:none">${I.get('check', 14)} 已保存</span>
            </div>
          </form>
        </div>

        <div class="card">
          <h2>语音识别</h2>
          <div class="speech-row">
            <div class="speech-model">
              <div class="speech-model-name">Paraformer 中文</div>
              <div id="speech-status" class="hint">读取中…</div>
            </div>
            <button type="button" class="btn btn-ghost btn-sm" data-action="test-mic">
              ${I.get('mic', 14)} 检测麦克风
            </button>
          </div>
          <div id="mic-diag" class="hint" style="display:none"></div>
        </div>

        <div class="card">
          <h2>显示</h2>
          <div class="field">
            <label>字号</label>
            <div class="seg" data-seg="fontSize">
              <button type="button" data-value="small">小</button>
              <button type="button" data-value="standard" class="active">标准</button>
              <button type="button" data-value="large">大</button>
            </div>
          </div>
        </div>

        <div class="card">
          <h2>数据</h2>
          <div class="stat-grid">
            ${statItem('每阶段练习数', '50', 'book')}
            ${statItem('每练习卷数', '20', 'list')}
          </div>
        </div>
        </div>
      </div>`;
  }

  async function loadSettings(seq) {
    const form = $('#settings-form');
    if (!form) return;
    try {
      const s = await api('/api/settings');
      if (seq !== renderSeq) return;
      form.elements.apiBase.value = s.apiBase ?? '';
      form.elements.apiKey.value = s.apiKey ?? '';
      form.elements.model.value = s.model ?? '';
      form.elements.temperature.value = s.temperature ?? '';
      form.elements.apiFormat.value = s.apiFormat ?? 'anthropic';
    } catch (err) {
      if (seq !== renderSeq) return;
      UI.toast(err.message, 'error', '加载设置失败');
    }
    loadSpeechStatus(seq);
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
        : `${I.get('info', 14)} ${esc(st.reason || '未安装语音模型')}`;
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
    const btn = form.querySelector('button[type="submit"]');
    const fd = new FormData(form);
    const g = (k) => String(fd.get(k) === null || fd.get(k) === undefined ? '' : fd.get(k)).trim();

    if (kind === 'stage-create') {
      const name = g('name');
      if (!name) return UI.toast('请填写阶段名称', 'warning');
      await UI.busy(btn, '创建中…', async () => {
        const stage = await api('/api/stages', { method: 'POST', body: JSON.stringify({ name }) });
        UI.toast('阶段已创建', 'success');
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
        UI.toast('练习已创建', 'success');
        location.hash = '#/session/' + p.id;
      });
      return;
    }

    if (kind === 'settings-save') {
      const rawTemp = g('temperature');
      const temperature = Number(rawTemp);
      if (!rawTemp || !Number.isFinite(temperature)) return UI.toast('请填写有效的温度数值', 'warning');
      await UI.busy(btn, '保存中…', async () => {
        const saved = await api('/api/settings', {
          method: 'PUT',
          body: JSON.stringify({
            apiFormat: g('apiFormat'), apiBase: g('apiBase'),
            apiKey: g('apiKey'), model: g('model'), temperature,
          }),
        });
        if (saved) {
          form.elements.apiBase.value = saved.apiBase ?? '';
          form.elements.apiKey.value = saved.apiKey ?? '';
          form.elements.model.value = saved.model ?? '';
          form.elements.temperature.value = saved.temperature ?? '';
          form.elements.apiFormat.value = saved.apiFormat ?? 'anthropic';
        }
        loadSpeechStatus(renderSeq);
        const ok = $('#save-ok');
        if (ok) {
          ok.style.display = 'inline';
          setTimeout(() => { ok.style.display = 'none'; }, 2400);
        }
        UI.toast('设置已保存', 'success');
      });
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

  document.addEventListener('change', (e) => {
    if (e.target.id === 'stage-select') {
      practiceStageId = e.target.value;
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
      return;
    }

    const el = e.target.closest('[data-action]');
    if (!el) return;
    const action = el.dataset.action;
    const id = el.dataset.id;

    /* —— 阶段 —— */
    if (action === 'open-stage') { location.hash = '#/stages/' + id; return; }
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

    if (action === 'save-answer') {
      const qid = el.dataset.qid;
      const ta = $('#answer-input');
      const text = (ta && ta.value ? ta.value : '').trim();
      if (!text) return UI.toast('请先写下你的回答', 'warning');
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
        const panel = $('#paper-body .ref-card');
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
    if (action === 'mistake-page') {
      const n = Number(el.dataset.page);
      if (Number.isInteger(n) && n >= 1) {
        mistakePage = n;
        renderSessionKeepScroll();
      }
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
      // 错题本在复盘页，题目区在答题页——必须先切 hash，
      // 光 renderSession() 是没用的（sessionView() 还是 review，question-card 不会渲染）
      jumpToQuestionId = qid;
      const target = '#/session/' + currentSessionId();
      if (location.hash === target) {
        // 已在答题页：hash 不变就不会触发 loadSession，直接定位
        jumpToQuestionId = null;
        if (locateQuestion(qid)) {
          renderSession();
          const card = $('#question-card');
          if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
        } else {
          UI.toast('没找到这道题', 'warning');
        }
      } else {
        location.hash = target; // → hashchange → render → loadSession → 定位并滚动
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
    if (user) {
      user.innerHTML = `
        <div class="user-card">
          <div class="user-avatar">游</div>
          <div class="user-meta">
            <div class="user-name">访客模式</div>
            <div class="user-sub">登录后同步学习数据</div>
          </div>
          <button class="user-menu-btn" data-action="user-menu" title="账号">${I.get('gear', 15)}</button>
        </div>`;
    }
  }

  document.addEventListener('click', (e) => {
    if (e.target.closest('[data-action="user-menu"]')) {
      UI.toast('用户体系规划中，届时支持注册登录与数据归属', 'info', '敬请期待');
    }
  });

  window.addEventListener('hashchange', render);
  window.addEventListener('beforeunload', clearPoll);
  // 字号先落地再渲染，免得先按默认大小闪一下
  initFontSize();
  renderNav();
  render();
})();
