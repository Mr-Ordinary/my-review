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

        const close = (val) => {
          backdrop.classList.add('leaving');
          setTimeout(() => backdrop.remove(), 220);
          resolve(val);
        };
        backdrop.addEventListener('click', (e) => {
          const act = e.target.closest('[data-act]');
          if (act) return close(act.dataset.act === 'ok');
          if (e.target === backdrop) close(false);
        });
        const onKey = (e) => {
          if (e.key === 'Escape') { document.removeEventListener('keydown', onKey); close(false); }
          if (e.key === 'Enter') { document.removeEventListener('keydown', onKey); close(true); }
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

    return { toast, confirm, busy };
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

  let renderSeq = 0;
  let practiceStageId = '';
  let sessionState = null;
  let paperIdx = -1;
  let questionIdx = 0;
  let pollTimer = null;
  const drafts = {};
  /** 可折叠面板的收起状态（跨渲染、跨刷新保留） */
  const collapsed = (() => {
    try { return JSON.parse(sessionStorage.getItem('mr.collapsed') || '{}'); } catch (e) { return {}; }
  })();

  function isCollapsed(key) { return !!collapsed[key]; }
  function toggleCollapsed(key) {
    collapsed[key] = !collapsed[key];
    try { sessionStorage.setItem('mr.collapsed', JSON.stringify(collapsed)); } catch (e) { /* 忽略 */ }
  }

  /* ============================================================
   *  REST
   * ============================================================ */
  async function api(path, options = {}) {
    let res;
    try {
      res = await fetch(path, Object.assign({ headers: { 'Content-Type': 'application/json' } }, options));
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
      view.innerHTML = sessionShell();
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
    const kps = q.knowledgePoints || [];
    if (!kps.length) return '';
    return `<div class="q-kp">${I.get('target', 12)} ${kps.map(esc).join(' · ')}</div>`;
  }

  function scopeLabel(p) {
    return p.scopeMode === 'range' ? '范围模式' : '主题模式';
  }

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

  function emptyState(title, desc, actionHtml) {
    return `
      <div class="empty">
        <div class="empty-icon">${I.get('list', 22)}</div>
        <div class="empty-title">${esc(title)}</div>
        ${desc ? `<div class="empty-desc">${esc(desc)}</div>` : ''}
        ${actionHtml || ''}
      </div>`;
  }

  /* ============================================================
   *  页面：阶段
   * ============================================================ */
  function stagesView() {
    return `
      ${pageHead('Workspace', '阶段', '阶段是分组单位，用来区分不同的学习空间。练习挂在其下，方便归类与统计。')}
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
      box.innerHTML = stages.length ? stageListHtml(stages) : emptyState('还没有阶段', '创建一个阶段来开始练习。',
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
          <button class="btn btn-primary" data-action="start-review" data-id="${esc(s.id)}"
            ${hasSummary ? '' : 'disabled title="需先生成阶段总结"'}>
            ${I.get('play', 15)} 全复习练习
          </button>
        </div>
      </div>

      <div class="stagger">
        <div class="card">
          <h2>数据概览 <span class="sub">百分制，每卷满分 100</span></h2>
          <div class="stat-grid">
            ${statItem('练习', num(st.practiceCount), 'book')}
            ${statItem('卷', num(st.paperCount), 'list')}
            ${statItem('题目', num(st.questionCount), 'list')}
            ${statItem('已评分', num(st.evaluatedCount), 'check')}
            ${statItem('平均分', st.averageScore === null ? '—' : st.averageScore, 'chart')}
            ${statItem('最近得分', st.lastScore === null ? '—' : st.lastScore, 'star')}
          </div>
        </div>

        ${stageSummaryHtml(s.summary, hasSummary)}

        <div class="card">
          <h2>阶段内练习 <span class="sub">${(s.practices || []).length} 个</span></h2>
          ${practiceListHtml(s.practices || [])}
          <div class="btn-row">
            <a class="btn btn-primary" href="#/practice">${I.get('plus', 15)} 新建练习</a>
          </div>
        </div>
      </div>`;
  }

  function stageSummaryHtml(sm, has) {
    if (!has) {
      return `
        <div class="card">
          <h2>阶段总结</h2>
          ${emptyState('尚未生成', '阶段总结是 AI 给出的复盘反馈，也是全复习出题的范围依据。做完练习交卷后会自动生成。')}
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
      ${pageHead('Practice', '练习', '一次出一整卷（满分 100，前易后难），答完交卷由 AI 批改、总结并沉淀记忆。')}
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
                <label>出题模式 <span class="req">*</span></label>
                <div class="seg" data-seg="scopeMode">
                  <button type="button" data-value="topic" class="active">主题模式</button>
                  <button type="button" data-value="range">范围模式</button>
                </div>
                <span class="tip">主题模式填关键词；范围模式描述整份资料的边界</span>
              </div>
              <div class="field full" id="scope-topic-field">
                <label>知识点主题 <span class="req">*</span></label>
                <input type="text" name="topic" placeholder="例：集合、泛型、反射" />
              </div>
              <div class="field full" id="scope-range-field" style="display:none">
                <label>标题 <span class="req">*</span></label>
                <input type="text" name="title" placeholder="例：javaguide 基础篇" />
              </div>
              <div class="field full" id="scope-range-text" style="display:none">
                <label>范围描述 <span class="req">*</span></label>
                <textarea name="scopeText" class="tall"
                  placeholder="例：范围是 javaguide 的基础篇上中下，具体内容参照 https://javaguide.cn/java/basics/"></textarea>
                <span class="tip">范围内的链接会被抓取正文作为出题依据。读不到的站点请直接把正文粘进来。</span>
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
      if (box) box.innerHTML = emptyState('暂无阶段', '创建阶段后即可开始练习。');
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
      box.innerHTML = practiceListHtml(stage.practices || []);
      const sub = $('#practice-list-sub');
      if (sub) sub.textContent = stage.name || '';
    } catch (err) {
      if (seq !== renderSeq) return;
      box.innerHTML = emptyState('加载失败', err.message);
    }
  }

  function practiceListHtml(list) {
    if (!list || !list.length) return emptyState('暂无练习', '新建一个练习开始出题。');
    return `<div class="list stagger">${list.map((p) => {
      const st = p.stats || {};
      return `
        <div class="list-item" data-action="open-practice" data-id="${esc(p.id)}">
          <div class="li-icon">${I.get('book', 17)}</div>
          <div class="li-main">
            <div class="li-title">
              ${esc(p.topic)}
              ${p.review ? '<span class="badge badge-orange">全复习</span>' : ''}
            </div>
            <div class="li-sub">${scopeLabel(p)} · 更新于 ${fmtTime(p.updatedAt)}</div>
          </div>
          <div class="li-stats">
            <span>卷 <b>${num(st.paperCount)}</b></span>
            <span>题 <b>${num(st.questionCount)}</b></span>
            <span>均分 <b>${st.averageScore === null ? '—' : esc(st.averageScore)}</b></span>
            <span>最近 <b>${st.lastScore === null ? '—' : esc(st.lastScore)}</b></span>
          </div>
        </div>`;
    }).join('')}</div>`;
  }

  /* ============================================================
   *  页面：练习进行中
   * ============================================================ */
  function sessionShell() {
    return `
      <div class="session-head">
        <a class="back-link" href="#/practice">${I.get('back', 15)} 返回练习</a>
        <h1 id="session-title">练习</h1>
        <span id="session-badge"></span>
        <div class="page-actions" style="margin-left:auto" id="session-actions"></div>
      </div>
      <div id="session-body">${emptyState('加载中…')}</div>`;
  }

  async function loadSession(id, seq) {
    sessionState = null;
    try {
      const p = await api('/api/practices/' + enc(id));
      if (seq !== renderSeq) return;
      sessionState = p;
      pickDefaultPaper();
      renderSession();
      schedulePoll();
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
    $('#session-body').innerHTML = sessionBodyHtml(p);
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

  function sessionBodyHtml(p) {
    const papers = p.papers || [];
    const paper = currentPaper();
    return `
      <div class="stagger">
        ${memoryPanel(p.memory)}
        ${digestPanel(p.digest)}
        ${paper ? paperSection(paper, p) : `
          <div class="card">
            ${emptyState('尚未出题', '点击下方按钮，AI 会一次出一整卷（15-30 题，满分 100，含参考答案）。出题在后台进行，可以先去别的页面。',
              `<div class="btn-row" style="justify-content:center">
                <button class="btn btn-primary btn-lg" data-action="generate-paper" data-id="${esc(p.id)}">
                  ${I.get('play', 16)} 开始出题
                </button>
              </div>`)}
          </div>`}
        ${historySection(papers, paper)}
      </div>`;
  }

  /** 可折叠面板：收起时只留标题行 */
  function collapsiblePanel(key, cls, icon, title, meta, contentHtml) {
    const open = !isCollapsed(key);
    return `
      <div class="panel ${cls}${open ? '' : ' collapsed'}">
        <h3>
          ${I.get(icon, 15)} ${esc(title)}
          ${meta ? `<span class="muted">${meta}</span>` : ''}
          <button class="panel-toggle${open ? '' : ' collapsed'}" data-action="toggle-panel" data-key="${esc(key)}">
            ${open ? '收起' : '展开'}
          </button>
        </h3>
        <div class="panel-content">${contentHtml}</div>
      </div>`;
  }

  function digestPanel(dig) {
    if (!dig) return '';
    return collapsiblePanel('digest', 'panel-neutral', 'list', '会话摘要',
      `更新于 ${fmtTime(dig.updatedAt)}`, `
        <div class="result-block">
          <h4>总览</h4>
          <div class="rb-body">${esc(dig.overview)}</div>
        </div>
        ${dig.level ? `
          <div class="result-block">
            <h4>水平定位</h4>
            <div class="rb-body">${esc(dig.level)}</div>
          </div>` : ''}
        <div class="cols-2">
          <div>${listBlock('已覆盖大块', dig.coveredAreas, 'check')}</div>
          <div>${listBlock('尚未覆盖', dig.uncoveredAreas, 'close')}</div>
        </div>`);
  }

  function memoryPanel(mem) {
    if (!mem) return '';
    return collapsiblePanel('memory', 'panel-accent', 'star', '长期记忆',
      `跨卷累积 · 更新于 ${fmtTime(mem.updatedAt)}`, `
        <div class="cols-2">
          <div>
            ${listBlock('已掌握', mem.mastered, 'check')}
            ${listBlock('理解偏差', mem.misconceptions, 'info')}
          </div>
          <div>
            ${listBlock('薄弱点', mem.weakPoints, 'close')}
            ${listBlock('下一步', mem.nextFocus, 'target')}
          </div>
        </div>
        ${listBlock('已覆盖知识点', mem.coverage, 'list')}`);
  }

  function paperSection(paper, p) {
    const qs = paper.questions || [];
    const answered = qs.filter(isAnswered).length;
    return `
      <div class="card">
        <div class="paper-head">
          <h2>${I.get('list', 15)} 第 ${paper.sequence} 张卷
            <span class="sub">
              ${PAPER_STATUS[paper.status] || paper.status} · ${qs.length} 题 ·
              满分 ${num(paper.maxScore)}${paper.score !== null ? ` · 得分 <b>${esc(paper.score)}</b>` : ` · 已答 ${answered}`}
            </span>
          </h2>
          ${paperActions(paper, p, answered)}
        </div>
        ${tierBreakdown(qs)}
        <div id="qnav-box">${qs.length ? qnavHtml(qs) : ''}</div>
        <div id="paper-body">
          ${paper.status === 'generating' ? generatingBody(paper) : ''}
          ${paper.status === 'grading' ? gradingBody(paper) : ''}
          ${paper.status === 'answering' ? answeringBody(paper) : ''}
          ${paper.status === 'graded' ? gradedPaperBody(paper) : ''}
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
  function answeringBody(paper) {
    const q = currentQuestion();
    if (!q) return emptyState('正在出题', '题目生成后即可开始作答。');
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
        <div class="q-text">${esc(q.prompt)}</div>
        ${kpLine(q)}
      </div>

      <div class="card answer-area">
        <h2>作答 <span class="sub">${a ? `已保存 ${a.sequence} 次` : '保存后自动进入下一题'}</span></h2>
        <textarea id="answer-input" data-qid="${esc(q.id)}"
          placeholder="按面试口述的方式写下回答：先给结论，再说原因与取舍。"></textarea>
        <div class="btn-row">
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
          <button class="btn btn-quiet" data-action="reveal" data-qid="${esc(q.id)}" ${revealed ? 'disabled' : ''}>
            ${I.get('eye', 15)} ${revealed ? '已看答案' : '看参考答案'}
          </button>
        </div>
      </div>

      ${revealed ? referenceAnswerPanel(q) : ''}`;
  }

  /* ---------- 已交卷：同样一题一屏，别把所有题的答案糊在一起 ---------- */
  function gradedPaperBody(paper) {
    const q = currentQuestion();
    return `
      ${paperSummaryHtml(paper)}
      ${q ? gradedQuestionBlock(paper, q) : ''}`;
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
        <div class="q-text">${esc(q.prompt)}</div>
        ${kpLine(q)}
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
          </h2>
          <div class="review-box">${esc(ev.feedback)}</div>
          ${Object.keys(ev.dimensions || {}).length ? `
            <div class="dims" style="margin-top:10px">
              ${Object.entries(ev.dimensions || {}).map(([k, v]) => (
                `<span class="dim-chip">${esc(k)} <b>${esc(v)}</b></span>`
              )).join('')}
            </div>` : ''}
        </div>` : ''}

      ${referenceAnswerPanel(q)}

      <div class="btn-row">
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
        <div class="rb-body" style="background:var(--surface);border-color:#abefc6;white-space:pre-wrap">${esc(q.referenceAnswer || '（本题没有参考答案）')}</div>
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
            <div class="s-label" style="font-size:11px;color:var(--text-3);font-weight:650;letter-spacing:.05em">得分 / 满分</div>
            <div class="num" style="font-size:15px;font-weight:650">${num(paper.maxScore)}</div>
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
          ${emptyState('功能规划中', '该模块的交互流程已定稿，待后端接入后开放。')}
        </div>
      </div>`;
  }

  /* ============================================================
   *  页面：设置
   * ============================================================ */
  function settingsView() {
    return `
      ${pageHead('Settings', '设置', '模型接入与本地配置。配置保存在服务端，换设备不受影响。')}
      <div class="stagger">
        <div class="card">
          <h2>模型接入</h2>
          <form data-form="settings-save" id="settings-form">
            <div class="form-grid">
              <div class="field full">
                <label>接口协议</label>
                <select name="apiFormat">
                  <option value="anthropic">Anthropic Messages（Claude / mimo 等）</option>
                  <option value="openai">OpenAI Chat Completions（DeepSeek / 通义 / Ollama 等）</option>
                </select>
                <span class="tip">协议不同则请求地址与认证头不同，选对即可，无需改代码</span>
              </div>
              <div class="field full">
                <label>API Base URL</label>
                <input type="text" name="apiBase" placeholder="https://api.anthropic.com" />
              </div>
              <div class="field full">
                <label>API Key</label>
                <input type="password" name="apiKey" placeholder="sk-..." />
                <span class="tip">密钥仅保存在服务端配置文件，浏览器不保存</span>
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
              <button type="submit" class="btn btn-primary">${I.get('check', 15)} 保存设置</button>
              <span id="save-ok" class="save-ok" style="display:none">${I.get('check', 14)} 已保存</span>
            </div>
          </form>
        </div>

        <div class="cols-2">
          <div class="card">
            <h2>数据存储</h2>
            <p class="muted mt0" style="line-height:1.8">
              阶段、练习、卷、题目、作答与点评保存在服务端 JSON 文件（一表一文件）。
              存储层是接口抽象，后续可无损切换到数据库。
            </p>
          </div>
          <div class="card">
            <h2>容量上限</h2>
            <div class="stat-grid">
              ${statItem('每阶段练习数', '50', 'book')}
              ${statItem('每练习卷数', '20', 'list')}
            </div>
            <div class="hint">上限用于防止数据无限膨胀，日常使用不会触发。</div>
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
      if (!stageId) return UI.toast('请先选择所属阶段', 'warning');
      const body = { scopeMode };
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
        const ok = $('#save-ok');
        if (ok) {
          ok.style.display = 'inline';
          setTimeout(() => { ok.style.display = 'none'; }, 2400);
        }
        UI.toast('设置已保存', 'success');
      });
    }
  });

  document.addEventListener('input', (e) => {
    if (e.target.id === 'answer-input') drafts[e.target.dataset.qid] = e.target.value;
  });

  document.addEventListener('change', (e) => {
    if (e.target.id === 'stage-select') {
      practiceStageId = e.target.value;
      loadPracticeList(renderSeq);
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
      await UI.busy(el, '准备中…', async () => {
        const p = await api('/api/stages/' + enc(id) + '/review', { method: 'POST' });
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
      questionIdx = Number(el.dataset.idx) || 0;
      // 只重绘题目区，保持滚动位置——否则切一题就被弹回顶部
      renderQuestion();
      return;
    }
    if (action === 'toggle-panel') {
      syncDraft();
      toggleCollapsed(el.dataset.key);
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
      paperIdx = Number(el.dataset.idx);
      questionIdx = 0;
      renderSession();
      return;
    }
    if (action === 'generate-paper') {
      await UI.busy(el, '出题中…', async () => {
        const p = await api('/api/practices/' + enc(id) + '/papers', { method: 'POST' });
        sessionState = p;
        pickDefaultPaper();
        renderSession();
        schedulePoll();
        UI.toast('已开始出题，进度会自动更新', 'info');
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
  renderNav();
  render();
})();
