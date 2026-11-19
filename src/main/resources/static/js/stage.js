/* ============================================================
   MyReview 阶段详情（stage.html）
   从工作台 app.js 拆出的独立页：KPI/趋势、阶段总结、练习卡片。
   ============================================================ */
(function () {
  'use strict';

  const C = window.MRCommon;
  const { createApp } = Vue;

  const LEVEL_LABELS = { junior: '初级', mid: '中级', senior: '高级' };
  const INTENSITY_LABELS = { quick: '速通', standard: '标准', deep: '深挖' };

  createApp({
    data() {
      return {
        user: null,
        avatarUrl: '',
        navItems: C.NAV_ITEMS,
        stageId: '',
        stage: null,
        loading: true,
        error: '',
        page: 1,
        pageSize: 9,             // 练习卡片一页 9 张（原 PRACTICE_PAGE_SIZE）
        reviewIntensity: 'standard',
        confirmOpen: false,      // 生成/重出阶段总结的确认弹窗
        working: false,          // 总结生成 / 全复习发起进行中
        logoutOpen: false,
        loggingOut: false,
      };
    },

    computed: {
      hasSummary() { return !!(this.stage && this.stage.summary && this.stage.summary.overall); },
      practices() { return (this.stage && this.stage.practices) || []; },
      pages() {
        return Math.max(1, Math.ceil(this.practices.length / this.pageSize));
      },
      /** 当前页切片；页码越界时拉回合法范围 */
      pageItems() {
        const p = Math.min(Math.max(1, this.page), this.pages);
        return this.practices.slice((p - 1) * this.pageSize, p * this.pageSize);
      },
      userNick() {
        const u = this.user || {};
        return u.nickname || u.username || '我';
      },
    },

    methods: {
      /* ---- 模板用的格式化 ---- */
      icon(name, size) { return window.Icons.get(name, size); },
      fmtTime: (ts) => C.fmtTime(ts),
      fmtNum: (v) => C.num(v),
      fmtScore(v) { return v === null || v === undefined ? '—' : v; },
      levelLabel(level) { return LEVEL_LABELS[level] || '中级'; },
      scopeLabel(p) {
        const mode = p.scopeMode === 'range' ? '范围模式' : '主题模式';
        const intensity = INTENSITY_LABELS[p.intensity] || '标准';
        return mode + ' · ' + intensity;
      },

      /**
       * init 钩子：鉴权 + 加载阶段详情。
       * 鉴权失败/401 统一回登录页。
       */
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
        await this.loadStage();
        // 列表渲染后重测吸顶页头（异步数据会改变文档高度）
        this.$nextTick(() => this._remeasure && this._remeasure());
      },

      async loadStage() {
        this.loading = true;
        this.error = '';
        try {
          this.stage = await C.api('/api/stages/' + encodeURIComponent(this.stageId));
          this.page = 1;
        } catch (err) {
          this.error = err && err.message ? err.message : String(err);
        } finally {
          this.loading = false;
          this.$nextTick(() => this._remeasure && this._remeasure());
        }
      },

      goPage(n) {
        this.page = Math.min(Math.max(1, n), this.pages);
      },

      /* ---- 练习卡片 ---- */
      openPractice(p) {
        location.href = 'answer.html?id=' + encodeURIComponent(p.id);
      },
      newPractice() {
        location.href = 'practice.html?new=1&stage=' + encodeURIComponent(this.stageId);
      },

      /* ---- 生成 / 重出阶段总结（确认后覆盖现总结） ---- */
      askGenSummary() {
        if (this.working) return;
        this.confirmOpen = true;
      },
      closeGenSummary() {
        if (this.working) return;
        this.confirmOpen = false;
      },
      async confirmGenSummary() {
        if (this.working) return;
        this.working = true;
        try {
          await C.api('/api/stages/' + encodeURIComponent(this.stageId) + '/summary', { method: 'POST' });
          C.toast('阶段总结已生成', 'success');
          this.confirmOpen = false;
          await this.loadStage();
        } catch (err) {
          C.toast(err && err.message ? err.message : String(err), 'error', '生成失败');
        } finally {
          this.working = false;
        }
      },

      /* ---- 全复习练习：需先有阶段总结（范围取 reviewScope） ---- */
      async startReview() {
        if (!this.hasSummary || this.working) return;
        this.working = true;
        try {
          const p = await C.api('/api/stages/' + encodeURIComponent(this.stageId) + '/review', {
            method: 'POST', body: { intensity: this.reviewIntensity },
          });
          C.toast('全复习练习已创建', 'success');
          location.href = 'answer.html?id=' + encodeURIComponent(p.id);
        } catch (err) {
          C.toast(err && err.message ? err.message : String(err), 'error', '操作失败');
          this.working = false;
        }
      },

      goProfile() {
        location.href = 'profile.html';
      },

      /* ---- 退出登录 ---- */
      askLogout() {
        this.logoutOpen = true;
      },
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
      this._remeasure = C.initStickyHead('.session-head');
      this.stageId = new URLSearchParams(location.search).get('id') || '';
      if (!this.stageId) {
        location.replace('stages.html');
        return;
      }
      this.init();
    },
  }).mount('#app');
})();
