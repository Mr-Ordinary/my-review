/* ============================================================
   MyReview 阶段首页（stages.html）
   ============================================================ */
(function () {
  'use strict';

  const C = window.MRCommon;
  const { createApp } = Vue;

  createApp({
    data() {
      return {
        user: null,
        avatarUrl: '',
        navItems: C.NAV_ITEMS,
        stages: [],
        loading: true,
        error: '',
        page: 1,
        pageSize: 12,          // 原 STAGE_PAGE_SIZE
        createOpen: false,
        createName: '',
        creating: false,
        deleteTarget: null,    // 待删除的阶段对象
        deleting: false,
        logoutOpen: false,
        loggingOut: false,
      };
    },

    computed: {
      pages() {
        return Math.max(1, Math.ceil(this.stages.length / this.pageSize));
      },
      /** 当前页切片；页码越界时拉回合法范围（原 paginate 语义） */
      pageItems() {
        const p = Math.min(Math.max(1, this.page), this.pages);
        return this.stages.slice((p - 1) * this.pageSize, p * this.pageSize);
      },
      countText() {
        return this.stages.length ? `共 ${this.stages.length} 个` : '';
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

      /**
       * init 钩子：页面要展示的数据都在这里加载。
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
        await this.loadStages();
        // 列表渲染后重测吸顶页头（异步数据会改变文档高度）
        this.$nextTick(() => this._remeasure && this._remeasure());
      },

      async loadStages() {
        this.loading = true;
        this.error = '';
        try {
          this.stages = await C.api('/api/stages');
        } catch (err) {
          this.error = err && err.message ? err.message : String(err);
        } finally {
          this.loading = false;
        }
      },

      goPage(n) {
        this.page = Math.min(Math.max(1, n), this.pages);
      },

      /* ---- 打开阶段详情（独立页） ---- */
      openStage(s) {
        location.href = 'stage.html?id=' + encodeURIComponent(s.id);
      },

      goProfile() {
        location.href = 'profile.html';
      },

      /* ---- 新建阶段（弹窗遮罩不关闭，只能 X / 取消关闭） ---- */
      openCreate() {
        this.createName = '';
        this.createOpen = true;
        this.$nextTick(() => {
          const input = this.$refs.createInput;
          if (input) input.focus();
        });
      },
      closeCreate() {
        if (this.creating) return;
        this.createOpen = false;
      },
      async submitCreate() {
        const name = this.createName.trim();
        if (!name) return C.toast('请填写阶段名称', 'warning');
        if (this.creating) return;
        this.creating = true;
        try {
          const stage = await C.api('/api/stages', { method: 'POST', body: { name } });
          C.toast('阶段已创建', 'success');
          this.createOpen = false;
          // 反应式入列；跳到能看见新卡的那一页
          this.stages.push(stage);
          this.goPage(this.pages);
        } catch (err) {
          C.toast(err && err.message ? err.message : String(err), 'error', '操作失败');
        } finally {
          this.creating = false;
        }
      },

      /* ---- 删除阶段 ---- */
      askDelete(s) {
        this.deleteTarget = s;
      },
      closeDelete() {
        if (this.deleting) return;
        this.deleteTarget = null;
      },
      async confirmDelete() {
        const s = this.deleteTarget;
        if (!s || this.deleting) return;
        this.deleting = true;
        try {
          await C.api('/api/stages/' + encodeURIComponent(s.id) + '/delete', { method: 'POST' });
          const i = this.stages.findIndex((x) => String(x.id) === String(s.id));
          if (i >= 0) this.stages.splice(i, 1);
          this.deleteTarget = null;
          C.toast('阶段已删除', 'success');
          // 删掉末页最后一项后页码收敛
          if (this.page > this.pages) this.page = this.pages;
        } catch (err) {
          C.toast(err && err.message ? err.message : String(err), 'error', '操作失败');
        } finally {
          this.deleting = false;
        }
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
      this._remeasure = C.initStickyHead('.page-head');
      this.init();
    },
  }).mount('#app');
})();
