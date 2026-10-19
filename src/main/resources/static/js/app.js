/* ===== MyReview 前端主逻辑 =====
 * hash 路由（阶段 / 练习 / 押题 / 模拟面试 / 系统设置 + 两个详情视图），
 * 数据全部通过 fetch('/api/...') 与 Java Spring Boot 后端交互（同源托管，无 CORS）。
 * 后端出错统一返回 {"error":"消息"} + 4xx/5xx（AI 失败为 502）。
 */
(function () {
  'use strict';

  /* ================================================================
   *  工具函数
   * ================================================================ */
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  const esc = (s) => String(s === null || s === undefined ? '' : s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
  const enc = encodeURIComponent;
  const num = (v) => (v === null || v === undefined ? '-' : v);
  const fmtTime = (ts) => {
    if (!ts) return '-';
    const d = new Date(ts);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };
  const I = window.Icons || { get: () => '' };
  const busyIcon = () => I.get('refresh', 15).replace('<svg ', '<svg class="spin" ');

  const MODE_LABELS = { bagu: '背八股', scenario: '场景题', leetcode: '刷力扣' };
  const NAV_ITEMS = [
    { route: 'stages', href: '#/stages', icon: 'folder', label: '阶段' },
    { route: 'practice', href: '#/practice', icon: 'book', label: '练习' },
    { route: 'drill', href: '#/drill', icon: 'target', label: '押题' },
    { route: 'mock', href: '#/mock', icon: 'mic', label: '模拟面试' },
    { route: 'settings', href: '#/settings', icon: 'gear', label: '系统设置' },
  ];

  /* ================================================================
   *  页面级状态
   * ================================================================ */
  let renderSeq = 0;        // 渲染代号：异步返回时若页面已切换则丢弃结果
  let practiceStageId = ''; // 练习页当前选中的阶段
  let sessionState = null;  // 练习进行中视图的练习详情（含 questions）
  const drafts = {};        // 作答草稿，按题目 id 保存

  /* ================================================================
   *  REST API
   * ================================================================ */
  async function api(path, options = {}) {
    let res;
    try {
      res = await fetch(path, Object.assign({
        headers: { 'Content-Type': 'application/json' },
      }, options));
    } catch (err) {
      throw new Error('网络请求失败，无法连接后端服务');
    }
    let data = null;
    const text = await res.text();
    if (text) {
      try { data = JSON.parse(text); } catch (e) { data = null; }
    }
    if (!res.ok) {
      throw new Error((data && data.error) ? data.error : `请求失败（HTTP ${res.status}）`);
    }
    return data;
  }

  /* 统一「按钮转圈 + 禁用 + 出错 alert 并恢复」包装 */
  async function withBusy(btn, busyText, fn) {
    const original = btn.innerHTML;
    const wasDisabled = btn.disabled;
    btn.disabled = true;
    btn.innerHTML = busyIcon() + ' ' + esc(busyText);
    try {
      await fn();
    } catch (err) {
      alert(err && err.message ? err.message : String(err));
    } finally {
      if (document.contains(btn)) {
        btn.disabled = wasDisabled;
        btn.innerHTML = original;
      }
    }
  }

  /* ================================================================
   *  路由
   * ================================================================ */
  function render() {
    renderSeq += 1;
    const seq = renderSeq;

    const hash = location.hash || '#/stages';
    const parts = hash.replace(/^#\/?/, '').split('/');
    const head = parts[0] || 'stages';
    const arg = parts[1] ? decodeURIComponent(parts[1]) : '';

    let route = head;
    if (head === 'stages' && arg) route = 'stage-detail';
    if (head === 'session' && arg) route = 'session';

    /* 侧边栏高亮（详情视图归属一级菜单） */
    let navRoute = head;
    if (route === 'stage-detail') navRoute = 'stages';
    if (route === 'session') navRoute = 'practice';
    $$('.nav-item').forEach((el) => el.classList.toggle('active', el.dataset.route === navRoute));

    const view = $('#view');
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
      view.innerHTML = drillView();
    } else if (route === 'mock') {
      view.innerHTML = mockView();
    } else if (route === 'settings') {
      view.innerHTML = settingsView();
      loadSettings(seq);
    } else {
      view.innerHTML = stagesView();
      loadStages(seq);
    }
    window.scrollTo(0, 0);
  }

  /* ================================================================
   *  公共片段
   * ================================================================ */
  function listBlock(title, arr, iconName) {
    if (!arr || !arr.length) return '';
    return `
      <div class="result-block">
        <h4>${I.get(iconName || 'check', 14)} ${esc(title)}</h4>
        <div class="rb-body"><ul>${arr.map((x) => `<li>${esc(x)}</li>`).join('')}</ul></div>
      </div>`;
  }

  function statItem(label, value, iconName) {
    return `
      <div class="stat-item">
        <div class="s-label">${I.get(iconName || 'chart', 14)} ${esc(label)}</div>
        <div class="s-value">${value === null || value === undefined ? '-' : esc(value)}</div>
      </div>`;
  }

  function statusBadge(status) {
    return status === 'completed'
      ? '<span class="badge badge-green">已完成</span>'
      : '<span class="badge badge-blue">进行中</span>';
  }

  /* 阶段列表项 */
  function stageListHtml(stages) {
    if (!stages || !stages.length) return '<div class="empty">还没有阶段，先在上方创建一个吧。</div>';
    return `<div class="list">${stages.map((s) => {
      const st = s.stats || {};
      return `
        <div class="list-item" data-action="open-stage" data-id="${esc(s.id)}">
          ${I.get('folder', 18)}
          <div class="li-main">
            <div class="li-title">${esc(s.name)}</div>
            <div class="li-sub">${esc(s.goal || '未设置目标')} · 更新于 ${fmtTime(s.updatedAt)}</div>
          </div>
          <div class="li-stats">
            <span>练习 <b>${num(st.practiceCount)}</b></span>
            <span>题目 <b>${num(st.questionCount)}</b></span>
            <span>作答 <b>${num(st.attemptCount)}</b></span>
            <span>均分 <b>${num(st.averageScore)}</b></span>
          </div>
          ${statusBadge(s.status)}
          <button class="btn btn-ghost btn-mini" data-action="delete-stage" data-id="${esc(s.id)}">
            ${I.get('trash', 14)} 删除
          </button>
        </div>`;
    }).join('')}</div>`;
  }

  /* 练习列表项（阶段详情 / 练习页共用） */
  function practiceListHtml(list) {
    if (!list || !list.length) return '<div class="empty">暂无练习记录。</div>';
    return `<div class="list">${list.map((p) => {
      const st = p.stats || {};
      return `
        <div class="list-item" data-action="open-practice" data-id="${esc(p.id)}">
          ${I.get('book', 18)}
          <div class="li-main">
            <div class="li-title">${esc(p.topic)}</div>
            <div class="li-sub">${esc(MODE_LABELS[p.mode] || p.mode || '')} · 更新于 ${fmtTime(p.updatedAt)}</div>
          </div>
          <div class="li-stats">
            <span>题目 <b>${num(st.questionCount)}</b></span>
            <span>作答 <b>${num(st.attemptCount)}</b></span>
            <span>均分 <b>${num(st.averageScore)}</b></span>
          </div>
          ${statusBadge(p.status)}
        </div>`;
    }).join('')}</div>`;
  }

  /* ================================================================
   *  页面一：阶段 #/stages
   * ================================================================ */
  function stagesView() {
    return `
      <div class="page-head">
        <h1>阶段</h1>
        <p>阶段是学习工作区：练习 / 押题 / 模拟面试都归属某个阶段，阶段可以生成总结报告。</p>
      </div>

      <div class="notice notice-info">
        <span class="notice-icon">${I.get('info', 16)}</span>
        <div>
          <strong>什么是阶段？</strong>
          阶段 = 学习工作区。练习、押题、模拟面试都归属于某个阶段；阶段完成后可调用 AI 生成总结报告，
          给出总体评价、各维度平均分、重点优势、重点不足与下一步建议。
        </div>
      </div>

      <div class="card">
        <h2>新建阶段 <span class="sub">带 * 的为必填</span></h2>
        <form data-form="stage-create">
          <div class="form-grid">
            <div class="field">
              <label>阶段名称<span class="req">*</span></label>
              <input type="text" name="name" placeholder="例：Java 后端秋招冲刺" />
            </div>
            <div class="field">
              <label>目标 <span class="tip">可选</span></label>
              <input type="text" name="goal" placeholder="例：四周内完成 MySQL / Redis / 并发三块八股" />
            </div>
          </div>
          <div class="btn-row">
            <button type="submit" class="btn btn-primary">${I.get('plus', 15)} 创建阶段</button>
          </div>
        </form>
      </div>

      <div class="card">
        <h2>阶段列表</h2>
        <div id="stage-list"><div class="empty">加载中…</div></div>
      </div>
    `;
  }

  async function loadStages(seq) {
    const box = $('#stage-list');
    if (!box) return;
    try {
      const stages = await api('/api/stages');
      if (seq !== renderSeq) return;
      box.innerHTML = stageListHtml(stages);
    } catch (err) {
      if (seq !== renderSeq) return;
      alert(err.message);
      box.innerHTML = `<div class="empty">加载失败：${esc(err.message)}</div>`;
    }
  }

  /* ================================================================
   *  页面二：阶段详情 #/stages/{id}
   * ================================================================ */
  function stageDetailView() {
    return `
      <div id="stage-detail-body">
        <div class="session-head">
          <a class="back-link" href="#/stages">${I.get('back', 15)} 返回阶段列表</a>
          <h1>阶段详情</h1>
        </div>
        <div class="empty">加载中…</div>
      </div>
    `;
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
      alert(err.message);
      box.innerHTML = `
        <div class="empty">
          加载失败：${esc(err.message)}<br/><br/>
          <a class="back-link" href="#/stages">${I.get('back', 15)} 返回阶段列表</a>
        </div>`;
    }
  }

  function stageDetailHtml(s) {
    const st = s.stats || {};
    return `
      <div class="session-head">
        <a class="back-link" href="#/stages">${I.get('back', 15)} 返回阶段列表</a>
        <h1>${esc(s.name)}</h1>
        ${statusBadge(s.status)}
        <div class="btn-row" style="margin-left:auto;margin-top:0">
          <button class="btn btn-primary" data-action="gen-stage-summary" data-id="${esc(s.id)}">
            ${I.get('star', 15)} 生成阶段总结
          </button>
        </div>
      </div>
      ${s.goal ? `<div class="goal-line">${I.get('target', 14)} 目标：${esc(s.goal)}</div>` : ''}

      <div class="card">
        <h2>数据概览</h2>
        <div class="stat-grid">
          ${statItem('练习数', num(st.practiceCount), 'book')}
          ${statItem('题目数', num(st.questionCount), 'list')}
          ${statItem('作答数', num(st.attemptCount), 'send')}
          ${statItem('已评分数', num(st.evaluatedCount), 'check')}
          ${statItem('平均分', num(st.averageScore), 'chart')}
        </div>
      </div>

      ${stageSummaryHtml(s.summary)}

      <div class="card">
        <h2>阶段内练习 <span class="sub">共 ${(s.practices || []).length} 个</span></h2>
        ${practiceListHtml(s.practices || [])}
        <div class="btn-row">
          <a class="btn btn-primary" href="#/practice">${I.get('plus', 15)} 新建练习</a>
        </div>
      </div>
    `;
  }

  function stageSummaryHtml(sm) {
    if (!sm) return '';
    const dims = Object.entries(sm.dimensionScores || {});
    return `
      <div class="panel-result" style="border-color:#bfdbfe;background:#f4f8ff">
        <h3>${I.get('star', 16)} 阶段总结 <span class="muted">生成于 ${fmtTime(sm.generatedAt)}</span></h3>
        <div class="result-block">
          <h4>${I.get('chat', 14)} 总体评价</h4>
          <div class="rb-body">${esc(sm.overall)}</div>
        </div>
        ${dims.length ? `
          <div class="result-block">
            <h4>${I.get('chart', 14)} 各维度平均分</h4>
            <div class="rb-body">
              ${dims.map(([k, v]) => `
                <div class="bar-row">
                  <span class="bar-name">${esc(k)}</span>
                  <div class="bar-track">
                    <div class="bar-fill" style="width:${Math.max(0, Math.min(100, (Number(v) || 0) * 10))}%"></div>
                  </div>
                  <span class="bar-val">${esc(v)}</span>
                </div>`).join('')}
            </div>
          </div>` : ''}
        ${listBlock('重点优势', sm.strengths, 'check')}
        ${listBlock('重点不足', sm.keyWeaknesses, 'close')}
        ${listBlock('下一步建议', sm.nextSteps, 'target')}
      </div>`;
  }

  /* ================================================================
   *  页面三：练习 #/practice（当前只做背八股）
   * ================================================================ */
  function practiceView() {
    return `
      <div class="page-head">
        <h1>练习</h1>
        <p>当前支持「背八股」：围绕知识点主题逐题出题、作答、点评，看答案可直接背诵。练习归属于某个阶段。</p>
      </div>

      <div class="mode-tabs">
        <div class="mode-tab active">
          <div class="mt-icon">${I.get('book', 22)}</div>
          <div class="mt-title">背八股</div>
          <div class="mt-desc">围绕主题逐个知识点问答，看答案可直接背诵</div>
        </div>
        <div class="mode-tab disabled" title="接入中">
          <div class="mt-icon">${I.get('chat', 22)}</div>
          <div class="mt-title">场景题 <span class="badge badge-gray">接入中</span></div>
          <div class="mt-desc">工程场景诊断 / 设计 / 决策题，逐步加约束</div>
        </div>
        <div class="mode-tab disabled" title="接入中">
          <div class="mt-icon">${I.get('chart', 22)}</div>
          <div class="mt-title">刷力扣 <span class="badge badge-gray">接入中</span></div>
          <div class="mt-desc">固定热题 100 抽题，指定语言作答</div>
        </div>
      </div>

      <div class="card">
        <h2>新建练习 <span class="sub">当前支持背八股，带 * 的为必填</span></h2>
        <form data-form="practice-create">
          <div class="form-grid">
            <div class="field">
              <label>所属阶段<span class="req">*</span></label>
              <div class="inline-row">
                <select name="stageId" id="stage-select"></select>
                <button type="button" class="btn btn-ghost btn-mini" data-action="reload-stages">
                  ${I.get('refresh', 14)} 刷新
                </button>
                <a class="link" href="#/stages">${I.get('plus', 14)} 去新建阶段</a>
              </div>
              <span class="tip" id="stage-select-tip">加载中…</span>
            </div>
            <div class="field">
              <label>知识点主题<span class="req">*</span></label>
              <input type="text" name="topic" placeholder="例：Java 并发编程 / MySQL 索引 / Redis 缓存" />
              <span class="tip">出题只围绕这个主题展开</span>
            </div>
          </div>
          <div class="btn-row">
            <button type="submit" class="btn btn-primary" id="practice-submit">
              ${I.get('play', 15)} 开始练习
            </button>
          </div>
        </form>
      </div>

      <div class="card">
        <h2>练习列表 <span class="sub" id="practice-list-sub"></span></h2>
        <div id="practice-list"><div class="empty">请先选择所属阶段。</div></div>
      </div>
    `;
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
      alert(err.message);
      if (tip) tip.textContent = '阶段列表加载失败：' + err.message;
      return;
    }
    if (seq !== renderSeq) return;

    const actives = (stages || []).filter((s) => s.status === 'active');
    if (!actives.length) {
      sel.innerHTML = '<option value="">暂无进行中的阶段</option>';
      sel.disabled = true;
      if (submit) submit.disabled = true;
      if (tip) tip.textContent = '还没有进行中的阶段，请先创建阶段。';
      const box = $('#practice-list');
      if (box) box.innerHTML = '<div class="empty">请先创建阶段后再开始练习。</div>';
      const sub = $('#practice-list-sub');
      if (sub) sub.textContent = '';
      return;
    }

    sel.disabled = false;
    if (submit) submit.disabled = false;
    if (!actives.some((s) => s.id === practiceStageId)) practiceStageId = actives[0].id;
    sel.innerHTML = actives.map((s) => (
      `<option value="${esc(s.id)}" ${s.id === practiceStageId ? 'selected' : ''}>${esc(s.name)}</option>`
    )).join('');
    sel.value = practiceStageId;
    if (tip) tip.textContent = '练习将归属到所选阶段（只列出进行中的阶段）';
    loadPracticeList(seq);
  }

  async function loadPracticeList(seq) {
    const box = $('#practice-list');
    if (!box || !practiceStageId) return;
    box.innerHTML = '<div class="empty">加载中…</div>';
    try {
      const stage = await api('/api/stages/' + enc(practiceStageId));
      if (seq !== renderSeq) return;
      box.innerHTML = practiceListHtml(stage.practices || []);
      const sub = $('#practice-list-sub');
      if (sub) sub.textContent = '阶段：' + (stage.name || '');
    } catch (err) {
      if (seq !== renderSeq) return;
      alert(err.message);
      box.innerHTML = `<div class="empty">加载失败：${esc(err.message)}</div>`;
    }
  }

  /* ================================================================
   *  页面四：练习进行中 #/session/{practiceId}（背八股）
   * ================================================================ */
  function sessionShell() {
    return `
      <div class="session-head">
        <a class="back-link" href="#/practice">${I.get('back', 15)} 返回练习</a>
        <h1 id="session-title">练习进行中</h1>
        <span id="session-badge"></span>
        <div class="btn-row" style="margin-left:auto;margin-top:0" id="session-actions"></div>
      </div>
      <div id="session-body"><div class="empty">加载中…</div></div>
    `;
  }

  async function loadSession(id, seq) {
    sessionState = null;
    try {
      const p = await api('/api/practices/' + enc(id));
      if (seq !== renderSeq) return;
      sessionState = p;
      renderSession();
    } catch (err) {
      if (seq !== renderSeq) return;
      alert(err.message);
      const box = $('#session-body');
      if (box) {
        box.innerHTML = `
          <div class="empty">
            练习不存在或加载失败：${esc(err.message)}<br/><br/>
            <a class="back-link" href="#/practice">${I.get('back', 15)} 返回练习</a>
          </div>`;
      }
    }
  }

  function currentQuestion() {
    const qs = (sessionState && sessionState.questions) || [];
    return qs.length ? qs[qs.length - 1] : null;
  }

  function findQuestion(qid) {
    return ((sessionState && sessionState.questions) || []).find((q) => q.id === qid) || null;
  }

  function replaceQuestion(q) {
    if (!sessionState || !q) return;
    const list = sessionState.questions || [];
    const idx = list.findIndex((x) => x.id === q.id);
    if (idx >= 0) list[idx] = q; else list.push(q);
  }

  function renderSession() {
    const p = sessionState;
    if (!p || !$('#session-body')) return;
    const done = p.status === 'completed';

    $('#session-title').textContent = '背八股 · ' + (p.topic || '');
    $('#session-badge').innerHTML = statusBadge(p.status);
    $('#session-actions').innerHTML = done ? '' : `
      <button class="btn btn-danger" data-action="end-practice" data-id="${esc(p.id)}">
        ${I.get('flag', 15)} 结束练习
      </button>`;
    $('#session-body').innerHTML = sessionBodyHtml(p);

    /* 恢复作答草稿 */
    const q = currentQuestion();
    const ta = $('#answer-input');
    if (ta && q && drafts[q.id]) ta.value = drafts[q.id];
  }

  function sessionBodyHtml(p) {
    const done = p.status === 'completed';
    const q = currentQuestion();

    return `
      ${practiceSummaryHtml(p.summary)}

      ${q ? `
        <div class="question-card" id="question-card">
          <div class="q-seq">${I.get('book', 14)} 第 ${q.sequence} 题 · ${fmtTime(q.createdAt)}</div>
          <div class="q-text">${esc(q.prompt)}</div>
        </div>

        ${done ? '' : `
          <div class="card answer-area">
            <h2>我的作答 <span class="sub">回答原样保存，多次作答都会保留</span></h2>
            <textarea id="answer-input" data-qid="${esc(q.id)}"
              placeholder="写下你的回答；也可以直接点「看答案」看讲解。"></textarea>
            <div class="btn-row">
              <button class="btn btn-primary" data-action="submit-answer" data-qid="${esc(q.id)}">
                ${I.get('send', 15)} 提交作答
              </button>
              <button class="btn btn-ghost" data-action="reveal" data-qid="${esc(q.id)}">
                ${I.get('eye', 15)} 看答案
              </button>
              <button class="btn btn-ghost" data-action="next-question" data-id="${esc(p.id)}">
                ${I.get('play', 15)} 下一题
              </button>
            </div>
          </div>`}

        ${reviewPanel(q)}
        ${revealPanel(q)}
        ${attemptsPanel(q)}
      ` : `
        <div class="card">
          <div class="empty">
            本次练习还没有题目。<br/><br/>
            ${done ? '练习已结束。' : `
              <button class="btn btn-primary" data-action="next-question" data-id="${esc(p.id)}">
                ${I.get('play', 15)} 出第一题
              </button>`}
          </div>
        </div>
      `}
    `;
  }

  function practiceSummaryHtml(sm) {
    if (!sm) return '';
    return `
      <div class="panel-result" style="border-color:#bbf7d0;background:#f3fbf5">
        <h3>${I.get('star', 16)} 练习总结 <span class="muted">生成于 ${fmtTime(sm.createdAt)}</span></h3>
        <div class="result-block">
          <h4>${I.get('chat', 14)} 总体评价</h4>
          <div class="rb-body">${esc(sm.overall)}</div>
        </div>
        ${listBlock('表现亮点', sm.strengths, 'check')}
        ${listBlock('改进建议', sm.improvements, 'target')}
      </div>`;
  }

  function revealPanel(q) {
    if (!q.explanation) return '';
    return `
      <div class="panel-result">
        <h3>${I.get('eye', 16)} 讲解（直接看答案，未生成作答与评分）</h3>
        <div class="result-block">
          <h4>${I.get('book', 14)} 详细讲解</h4>
          <div class="rb-body">${esc(q.explanation.detail)}</div>
        </div>
        <div class="result-block">
          <h4>${I.get('star', 14)} 可直接背诵</h4>
          <div class="memo-box">${esc(q.explanation.memorizationPoints)}</div>
        </div>
      </div>`;
  }

  function reviewPanel(q) {
    const attempts = (q.attempts || []).filter((a) => a.evaluation);
    const last = attempts[attempts.length - 1];
    if (!last) return '';
    const ev = last.evaluation;
    const cls = ev.score >= 8 ? 'score-high' : ev.score >= 6 ? 'score-mid' : 'score-low';
    return `
      <div class="panel-result">
        <h3>${I.get('chat', 16)} 点评与讲解 <span class="muted">（针对第 ${last.sequence} 次作答）</span></h3>
        <div class="score-row">
          <div class="score-big ${cls}">${esc(ev.score)}</div>
          <div class="dims">
            ${Object.entries(ev.dimensions || {}).map(([k, v]) => (
              `<span class="dim-chip">${esc(k)} <b>${esc(v)}</b></span>`
            )).join('')}
          </div>
        </div>
        <div class="result-block">
          <h4>${I.get('chat', 14)} 点评</h4>
          <div class="rb-body">${esc(ev.feedback)}</div>
        </div>
        ${ev.detail ? `
          <div class="result-block">
            <h4>${I.get('book', 14)} 详细讲解</h4>
            <div class="rb-body">${esc(ev.detail)}</div>
          </div>` : ''}
        ${ev.memorizationPoints ? `
          <div class="result-block">
            <h4>${I.get('star', 14)} 可直接背诵</h4>
            <div class="memo-box">${esc(ev.memorizationPoints)}</div>
          </div>` : ''}
      </div>`;
  }

  function attemptsPanel(q) {
    const attempts = q.attempts || [];
    if (!attempts.length) return '';
    return `
      <div class="panel-result">
        <h3>${I.get('list', 16)} 作答记录 <span class="muted">共 ${attempts.length} 次，历史回答不会被覆盖</span></h3>
        ${attempts.map((a) => `
          <div class="result-block">
            <h4>${I.get('clock', 14)} 第 ${a.sequence} 次 · ${fmtTime(a.submittedAt)} ·
              ${a.evaluation ? `评分 ${esc(a.evaluation.score)}` : '未评分'}</h4>
            <div class="rb-body">${esc(a.answer)}</div>
          </div>`).join('')}
      </div>`;
  }

  function scrollToQuestion() {
    const card = $('#question-card');
    if (card && card.scrollIntoView) {
      card.scrollIntoView({ behavior: 'smooth', block: 'start' });
    } else {
      window.scrollTo(0, 0);
    }
  }

  /* ================================================================
   *  页面五：押题 #/drill（接入中）
   * ================================================================ */
  function drillView() {
    return `
      <div class="page-head">
        <h1>押题</h1>
        <p>简历押题：根据简历、目标岗位和可选 JD，练习最可能被问到的问题，作答后给可执行改进。</p>
      </div>

      <div class="notice notice-warning">
        <span class="notice-icon">${I.get('info', 16)}</span>
        <div>
          <strong>押题功能接入中，敬请期待</strong>
          配置表单已就绪，后端接入完成后即可开始押题练习。当前提交按钮不可用。
        </div>
      </div>

      <div class="card">
        <h2>新建押题练习 <span class="sub">带 * 的为必填，不设默认值</span></h2>
        <form data-form="drill-create">
          <div class="form-grid">
            <div class="field full">
              <label>简历内容<span class="req">*</span></label>
              <textarea name="resume" class="tall" placeholder="粘贴你的简历文本：项目经历、技术栈、职责、成果指标……"></textarea>
            </div>
            <div class="field">
              <label>目标岗位<span class="req">*</span></label>
              <input type="text" name="targetRole" placeholder="例：Java 后端开发工程师" />
            </div>
            <div class="field">
              <label>押题范围<span class="req">*</span></label>
              <input type="text" name="focus" placeholder="例：项目深挖 + 技术选型 + 难点排查" />
            </div>
            <div class="field full">
              <label>提供 JD（职位描述）</label>
              <div class="switch-row">
                <div class="switch" data-action="toggle-jd"></div>
                <span class="muted">开启后按 JD 重点押题；关闭则只依据简历和目标岗位</span>
              </div>
            </div>
            <div class="field full jd-field" style="display:none">
              <label>JD 内容</label>
              <textarea name="jobDescription" placeholder="粘贴招聘 JD：岗位职责、技术栈、能力要求……"></textarea>
            </div>
            <div class="field full">
              <label>难度<span class="req">*</span></label>
              <div class="seg" data-seg="difficulty">
                <button type="button" data-value="junior">初级</button>
                <button type="button" data-value="intermediate" class="active">中级</button>
                <button type="button" data-value="senior">高级</button>
              </div>
            </div>
          </div>
          <div class="btn-row">
            <button type="submit" class="btn btn-primary" disabled>押题功能接入中，敬请期待</button>
          </div>
        </form>
      </div>
    `;
  }

  /* ================================================================
   *  页面六：模拟面试 #/mock（接入中）
   * ================================================================ */
  function mockView() {
    return `
      <div class="page-head">
        <h1>模拟面试</h1>
        <p>贴近真实面试：面试官按简历 / JD 逐轮提问追问，全程不评分不讲解，结束后只归档记录。</p>
      </div>

      <div class="notice notice-warning">
        <span class="notice-icon">${I.get('info', 16)}</span>
        <div>
          <strong>模拟面试功能接入中，敬请期待</strong>
          配置表单已就绪，后端接入完成后即可开始模拟面试。当前提交按钮不可用。
        </div>
      </div>

      <div class="card">
        <h2>新建模拟面试 <span class="sub">带 * 的为必填，不设默认值</span></h2>
        <form data-form="mock-create">
          <div class="form-grid">
            <div class="field full">
              <label>简历内容<span class="req">*</span></label>
              <textarea name="resume" class="tall" placeholder="粘贴你的简历文本：项目经历、技术栈、职责、成果指标……"></textarea>
            </div>
            <div class="field">
              <label>目标岗位<span class="req">*</span></label>
              <input type="text" name="targetRole" placeholder="例：Java 后端开发工程师" />
            </div>
            <div class="field">
              <label>面试官风格<span class="req">*</span></label>
              <input type="text" name="interviewerStyle" placeholder="例：专业严谨 / 温和友好 / 压力追问" />
            </div>
            <div class="field full">
              <label>提供 JD（职位描述）</label>
              <div class="switch-row">
                <div class="switch" data-action="toggle-jd"></div>
                <span class="muted">开启后按 JD 验证岗位匹配度；关闭则只依据简历面试</span>
              </div>
            </div>
            <div class="field full jd-field" style="display:none">
              <label>JD 内容</label>
              <textarea name="jobDescription" placeholder="粘贴招聘 JD：岗位职责、技术栈、能力要求……"></textarea>
            </div>
            <div class="field">
              <label>手撕代码环节</label>
              <div class="switch-row">
                <div class="switch" data-action="toggle-coding"></div>
                <span class="muted">开启表示整场安排一次手撕（从热题 100 抽取）</span>
              </div>
            </div>
            <div class="field">
              <label>难度<span class="req">*</span></label>
              <div class="seg" data-seg="difficulty">
                <button type="button" data-value="junior">初级</button>
                <button type="button" data-value="intermediate" class="active">中级</button>
                <button type="button" data-value="senior">高级</button>
              </div>
            </div>
          </div>
          <div class="btn-row">
            <button type="submit" class="btn btn-primary" disabled>模拟面试功能接入中，敬请期待</button>
          </div>
        </form>
      </div>
    `;
  }

  /* ================================================================
   *  页面七：系统设置 #/settings
   * ================================================================ */
  function settingsView() {
    return `
      <div class="page-head">
        <h1>系统设置</h1>
        <p>配置保存在服务端 data/settings.json，练习数据保存在服务端数据库。</p>
      </div>

      <div class="card">
        <h2>模型接入</h2>
        <form data-form="settings-save" id="settings-form">
          <div class="form-grid">
            <div class="field full">
              <label>API Base URL</label>
              <input type="text" name="apiBase" placeholder="https://api.deepseek.com" />
            </div>
            <div class="field full">
              <label>API Key</label>
              <input type="password" name="apiKey" placeholder="sk-..." />
              <span class="tip">密钥保存在服务端配置文件，由后端调用大模型时使用</span>
            </div>
            <div class="field">
              <label>模型</label>
              <input type="text" name="model" placeholder="deepseek-chat" />
            </div>
            <div class="field">
              <label>温度（temperature）</label>
              <input type="text" name="temperature" placeholder="1.3" />
            </div>
          </div>
          <div class="save-bar">
            <button type="submit" class="btn btn-primary">${I.get('check', 15)} 保存设置</button>
            <span id="save-ok" class="save-ok" style="display:none">${I.get('check', 14)} 已保存</span>
          </div>
        </form>
      </div>

      <div class="card">
        <h2>数据说明</h2>
        <p class="muted mt0">
          配置保存在服务端 data/settings.json，练习数据（阶段、练习、题目、作答与点评）保存在服务端数据库。
          换电脑 / 清浏览器缓存不影响数据。
        </p>
      </div>

      <div class="card">
        <h2>关于</h2>
        <p class="muted mt0">
          MyReview 个人面试练习工作台 · 数据通过 REST API 与后端交互，
          AI 出题 / 点评 / 讲解 / 总结均由后端调用大模型生成。
        </p>
      </div>
    `;
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
    } catch (err) {
      if (seq !== renderSeq) return;
      alert(err.message);
    }
  }

  /* ================================================================
   *  事件：表单提交
   * ================================================================ */
  document.addEventListener('submit', async (e) => {
    const form = e.target.closest('form[data-form]');
    if (!form) return;
    e.preventDefault();
    const kind = form.dataset.form;
    const btn = form.querySelector('button[type="submit"]');
    const fd = new FormData(form);
    const g = (k) => String(fd.get(k) === null || fd.get(k) === undefined ? '' : fd.get(k)).trim();

    /* —— 新建阶段 —— */
    if (kind === 'stage-create') {
      const name = g('name');
      if (!name) return alert('请填写阶段名称');
      const goal = g('goal');
      const body = { name };
      if (goal) body.goal = goal;
      await withBusy(btn, '创建中…', async () => {
        const stage = await api('/api/stages', { method: 'POST', body: JSON.stringify(body) });
        location.hash = '#/stages/' + stage.id;
      });
      return;
    }

    /* —— 新建练习（背八股） —— */
    if (kind === 'practice-create') {
      const stageId = g('stageId');
      const topic = g('topic');
      if (!stageId) return alert('请先选择所属阶段（没有阶段请先创建）');
      if (!topic) return alert('请填写知识点主题');
      await withBusy(btn, '创建中…', async () => {
        const p = await api('/api/stages/' + enc(stageId) + '/practices', {
          method: 'POST', body: JSON.stringify({ topic }),
        });
        location.hash = '#/session/' + p.id;
      });
      return;
    }

    /* —— 押题 / 模拟面试：接入中，不提交任何数据 —— */
    if (kind === 'drill-create' || kind === 'mock-create') return;

    /* —— 系统设置 —— */
    if (kind === 'settings-save') {
      const rawTemp = g('temperature');
      const temperature = Number(rawTemp);
      if (!rawTemp || !Number.isFinite(temperature)) return alert('请填写有效的温度数值，例如 1.3');
      await withBusy(btn, '保存中…', async () => {
        const saved = await api('/api/settings', {
          method: 'PUT',
          body: JSON.stringify({
            apiBase: g('apiBase'), apiKey: g('apiKey'),
            model: g('model'), temperature,
          }),
        });
        if (saved) {
          form.elements.apiBase.value = saved.apiBase ?? '';
          form.elements.apiKey.value = saved.apiKey ?? '';
          form.elements.model.value = saved.model ?? '';
          form.elements.temperature.value = saved.temperature ?? '';
        }
        const ok = $('#save-ok');
        if (ok) {
          ok.style.display = 'inline';
          setTimeout(() => { ok.style.display = 'none'; }, 2500);
        }
      });
    }
  });

  /* ================================================================
   *  事件：输入（作答草稿）
   * ================================================================ */
  document.addEventListener('input', (e) => {
    if (e.target.id === 'answer-input') drafts[e.target.dataset.qid] = e.target.value;
  });

  /* ================================================================
   *  事件：选择变化（练习页阶段下拉）
   * ================================================================ */
  document.addEventListener('change', (e) => {
    if (e.target.id === 'stage-select') {
      practiceStageId = e.target.value;
      loadPracticeList(renderSeq);
    }
  });

  /* ================================================================
   *  事件：点击（统一委托）
   * ================================================================ */
  document.addEventListener('click', async (e) => {
    /* —— 开关与分段控件 —— */
    const sw = e.target.closest('.switch[data-action="toggle-jd"], .switch[data-action="toggle-coding"]');
    if (sw) {
      sw.classList.toggle('on');
      if (sw.dataset.action === 'toggle-jd') {
        const jdField = sw.closest('form') && sw.closest('form').querySelector('.jd-field');
        if (jdField) jdField.style.display = sw.classList.contains('on') ? '' : 'none';
      }
      return;
    }
    const segBtn = e.target.closest('.seg[data-seg] button');
    if (segBtn) {
      $$('.seg[data-seg] button', segBtn.closest('.seg')).forEach((b) => b.classList.remove('active'));
      segBtn.classList.add('active');
      return;
    }

    const el = e.target.closest('[data-action]');
    if (!el) return;
    const action = el.dataset.action;
    const id = el.dataset.id;

    /* —— 阶段列表 —— */
    if (action === 'open-stage') {
      location.hash = '#/stages/' + id;
      return;
    }
    if (action === 'delete-stage') {
      if (!confirm('确定删除该阶段？阶段下的练习将一并删除，且不可恢复。')) return;
      await withBusy(el, '删除中…', async () => {
        await api('/api/stages/' + enc(id), { method: 'DELETE' });
        render();
      });
      return;
    }
    if (action === 'gen-stage-summary') {
      await withBusy(el, '总结生成中…', async () => {
        await api('/api/stages/' + enc(id) + '/summary', { method: 'POST' });
        await loadStageDetail(id, renderSeq);
      });
      return;
    }

    /* —— 练习页 —— */
    if (action === 'reload-stages') {
      loadPracticeStages(renderSeq);
      return;
    }
    if (action === 'open-practice') {
      location.hash = '#/session/' + id;
      return;
    }

    /* —— 练习进行中 —— */
    if (action === 'next-question') {
      await withBusy(el, '出题中…', async () => {
        const q = await api('/api/practices/' + enc(id) + '/questions', { method: 'POST' });
        if (sessionState && sessionState.questions) sessionState.questions.push(q);
        renderSession();
        scrollToQuestion();
      });
      return;
    }
    if (action === 'submit-answer') {
      const qid = el.dataset.qid;
      const ta = $('#answer-input');
      const text = (ta && ta.value ? ta.value : '').trim();
      if (!text) return alert('请先写下你的回答');
      await withBusy(el, '点评生成中…', async () => {
        const q = await api('/api/questions/' + enc(qid) + '/attempts', {
          method: 'POST', body: JSON.stringify({ answer: text }),
        });
        replaceQuestion(q);
        delete drafts[qid];
        renderSession();
      });
      return;
    }
    if (action === 'reveal') {
      const qid = el.dataset.qid;
      const q = findQuestion(qid);
      if (!q) return;
      if (q.explanation) return; // 讲解已在页面上，无需重复生成
      await withBusy(el, '讲解生成中…', async () => {
        const q2 = await api('/api/questions/' + enc(qid) + '/reveal', { method: 'POST' });
        replaceQuestion(q2);
        renderSession();
      });
      return;
    }
    if (action === 'end-practice') {
      if (!confirm('结束本次练习？结束后将调用 AI 生成练习总结，可能需要等待一会儿。')) return;
      await withBusy(el, '总结生成中…', async () => {
        const p = await api('/api/practices/' + enc(id) + '/complete', { method: 'POST' });
        sessionState = Object.assign({}, sessionState || {}, p);
        renderSession();
      });
      return;
    }
  });

  /* ================================================================
   *  启动
   * ================================================================ */
  function renderNav() {
    const nav = $('#nav');
    if (!nav) return;
    nav.innerHTML = NAV_ITEMS.map((n) => `
      <a href="${n.href}" class="nav-item" data-route="${n.route}">
        <span class="nav-icon">${I.get(n.icon, 18)}</span><span>${n.label}</span>
      </a>`).join('');
  }

  window.addEventListener('hashchange', render);
  renderNav();
  render();
})();
