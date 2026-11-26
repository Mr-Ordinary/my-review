/* ============================================================
   MyReview 练习首页（practice.html）
   阶段 chips 筛选 + 练习卡片列表 + 新建练习弹窗（嵌套新建阶段）。
   逻辑与工作台 app.js 练习页逐条对齐；页面数据在 init() 钩子里加载。
   ============================================================ */
(function () {
  'use strict';

  const C = window.MRCommon;
  const { createApp } = Vue;
  const LEVEL_LABELS = { junior: '初级', mid: '中级', senior: '高级' };

  createApp({
    data() {
      return {
        user: null,
        avatarUrl: '',
        navItems: C.NAV_ITEMS,

        // 阶段 chips + 练习列表
        stages: [],
        stagesLoaded: false,
        stageId: '',
        practices: [],
        listLoading: true,
        listError: '',
        page: 1,
        pageSize: 9,            // 原 PRACTICE_PAGE_SIZE

        // 新建练习弹窗
        createOpen: false,
        creating: false,
        form: {
          stageId: '', scopeMode: 'topic', level: 'mid', intensity: 'standard',
          paperSize: 10, topic: '', title: '', scopeText: '',
        },

        // 嵌套「新建阶段」弹窗
        stageDialogOpen: false,
        stageCreating: false,
        stageName: '',

        chipEls: [],
        logoutOpen: false,
        loggingOut: false,
      };
    },

    computed: {
      userNick() {
        const u = this.user || {};
        return u.nickname || u.username || '我';
      },
      activeStages() {
        return this.stages.filter((s) => s.status === 'active');
      },
      currentStageName() {
        const s = this.stages.find((x) => String(x.id) === String(this.stageId));
        return s ? s.name : '';
      },
      pages() {
        return Math.max(1, Math.ceil(this.practices.length / this.pageSize));
      },
      pageItems() {
        const p = Math.min(Math.max(1, this.page), this.pages);
        return this.practices.slice((p - 1) * this.pageSize, p * this.pageSize);
      },
    },

    methods: {
      icon(name, size) { return window.Icons.get(name, size); },
      fmtTime: (ts) => C.fmtTime(ts),
      fmtNum: (v) => C.num(v),
      fmtScore(v) { return v === null || v === undefined ? '—' : v; },
      levelLabel(level) { return LEVEL_LABELS[level] || '中级'; },
      scopeLabel(p) {
        const mode = p.scopeMode === 'range' ? '范围模式' : '主题模式';
        const intensity = ({ quick: '速通', standard: '标准', deep: '深挖' })[p.intensity] || '标准';
        return `${mode} · ${intensity}`;
      },
      setChipRef(el) {
        if (el) this.chipEls.push(el);
      },

      /** init 钩子：鉴权 + 阶段列表 + 练习列表（?new=1 自动弹新建练习，承接阶段详情「新建练习」） */
      async init() {
        try {
          this.user = await C.api('/api/auth/me');
        } catch (err) {
          C.setToken('');
          location.replace('login.html');
          return;
        }
        if (this.user.avatarExt) {
          this.avatarUrl = await C.loadAvatar(this.user.id) || '';
        }
        await this.loadStages();
        const qs = new URLSearchParams(location.search);
        if (qs.get('stage')) this.form.stageId = qs.get('stage');
        if (qs.get('new') === '1') {
          this.openCreate();
          // 地址栏去掉参数，刷新不会重复弹
          history.replaceState(null, '', 'practice.html');
        }
      },

      /** 阶段 chips + 新建练习弹窗的阶段下拉（与工作台 loadPracticeStages 同逻辑） */
      async loadStages() {
        this.chipEls = [];
        let stages;
        try {
          stages = await C.api('/api/stages');
        } catch (err) {
          this.stagesLoaded = true;
          this.listLoading = false;
          this.listError = err.message;
          return;
        }
        this.stages = stages || [];
        this.stagesLoaded = true;

        const all = this.stages;
        if (!all.length) {
          this.listLoading = false;
          this.listError = '';
          this.practices = [];
          return;
        }
        const actives = all.filter((s) => s.status === 'active');
        // 页面顶部的阶段筛选：展示所有阶段（含已完成）的练习
        if (!all.some((s) => String(s.id) === String(this.stageId))) {
          this.stageId = (actives[0] || all[0]).id;
        }
        // 新建练习弹窗：只列进行中的阶段；页面筛选器选的是已完成阶段时退回到第一个进行中的阶段
        this.form.stageId = actives.some((s) => String(s.id) === String(this.stageId))
          ? this.stageId : (actives[0] ? actives[0].id : '');
        this.$nextTick(() => this.scrollActiveChipIntoView(false));
        await this.loadPractices();
      },

      /** 把当前选中的 chip 平滑滚入可见区域（横向滚动条带） */
      scrollActiveChipIntoView(smooth) {
        const active = this.chipEls.find((el) => el.classList && el.classList.contains('active'));
        if (active) active.scrollIntoView({ behavior: smooth ? 'smooth' : 'auto', inline: 'center', block: 'nearest' });
      },

      selectStage(s) {
        this.stageId = s.id;
        this.form.stageId = s.id;
        this.loadPractices();
      },

      /** 阶段下拉切换：同步新建表单归属并重拉练习列表 */
      onStageChange() {
        this.form.stageId = this.stageId;
        this.loadPractices();
      },

      /** 练习列表：按选中阶段拉详情（与工作台 loadPracticeList 同逻辑） */
      async loadPractices() {
        if (!this.stageId) return;
        this.listLoading = true;
        this.listError = '';
        try {
          const stage = await C.api('/api/stages/' + encodeURIComponent(this.stageId));
          this.practices = stage.practices || [];
          this.page = 1;
        } catch (err) {
          this.listError = err.message;
          this.practices = [];
        } finally {
          this.listLoading = false;
        }
      },

      goPage(n) {
        this.page = Math.min(Math.max(1, n), this.pages);
      },

      /** 打开练习会话页（会话仍在工作台 index.html） */
      openPractice(p) {
        location.href = 'answer.html?id=' + encodeURIComponent(p.id);
      },

      goProfile() { location.href = 'profile.html'; },

      reloadStages() { this.loadStages(); },

      openCreate() {
        this.createOpen = true;
        if (!this.stagesLoaded) this.loadStages();
      },
      closeCreate() {
        if (this.creating) return;
        this.createOpen = false;
      },

      /** 新建练习（与工作台 practice-create 提交同校验、同请求、同跳转） */
      async submitCreate() {
        const f = this.form;
        if (!f.stageId) return C.toast('请先选择所属阶段', 'warning');
        const body = { scopeMode: f.scopeMode, level: f.level, intensity: f.intensity, paperSize: f.paperSize };
        if (f.scopeMode === 'range') {
          if (!f.title.trim()) return C.toast('请填写标题', 'warning');
          if (!f.scopeText.trim()) return C.toast('范围模式需填写范围描述', 'warning');
          body.topic = f.title.trim();
          body.scopeText = f.scopeText.trim();
        } else {
          if (!f.topic.trim()) return C.toast('请填写知识点主题', 'warning');
          body.topic = f.topic.trim();
        }
        if (this.creating) return;
        this.creating = true;
        try {
          const p = await C.api('/api/stages/' + encodeURIComponent(f.stageId) + '/practices',
            { method: 'POST', body });
          this.stageId = f.stageId;   // 回到练习页时筛选器停在新练习所属阶段
          C.toast('练习已创建', 'success');
          this.createOpen = false;
          location.href = 'answer.html?id=' + encodeURIComponent(p.id);
        } catch (err) {
          C.toast(err.message, 'error', '创建失败');
        } finally {
          this.creating = false;
        }
      },

      /** 嵌套「新建阶段」（与工作台 stage-create 提交同校验、同跳转） */
      async submitStage() {
        const name = this.stageName.trim();
        if (!name) return C.toast('请填写阶段名称', 'warning');
        if (this.stageCreating) return;
        this.stageCreating = true;
        try {
          const stage = await C.api('/api/stages', { method: 'POST', body: { name } });
          C.toast('阶段已创建', 'success');
          this.stageDialogOpen = false;
          this.createOpen = false;
          location.href = 'stage.html?id=' + encodeURIComponent(stage.id);
        } catch (err) {
          C.toast(err.message, 'error', '创建失败');
        } finally {
          this.stageCreating = false;
        }
      },

      askLogout() { this.logoutOpen = true; },
      closeLogout() {
        if (this.loggingOut) return;
        this.logoutOpen = false;
      },
      async confirmLogout() {
        if (this.loggingOut) return;
        this.loggingOut = true;
        await C.logout();
      },
    },

    mounted() {
      C.initFontSize();
      // 吸顶页头：滚过页头后收成 mini 条；init 渲染完重测一次，避免用到过期位置
      this._remeasure = C.initStickyHead('.page-head');
      Promise.resolve(this.init()).finally(() => this._remeasure && this._remeasure());
    },
  }).mount('#app');
})();
