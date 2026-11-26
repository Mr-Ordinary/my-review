/* ============================================================
   MyReview 模拟面试页（mock.html）
   北欧极简「规划中」占位页（多轮追问面试）。页面数据在 init() 钩子里加载（鉴权 + 侧栏用户）。
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
        logoutOpen: false,
        loggingOut: false,
      };
    },

    computed: {
      userNick() {
        const u = this.user || {};
        return u.nickname || u.username || '我';
      },
    },

    methods: {
      icon(name, size) { return window.Icons.get(name, size); },

      /** init 钩子：鉴权 + 侧栏用户信息 */
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
      },

      goProfile() { location.href = 'profile.html'; },

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
      // 吸顶页头：滚过页头后收成 mini 条
      this._remeasure = C.initStickyHead('.page-head');
      Promise.resolve(this.init()).finally(() => this._remeasure && this._remeasure());
    },
  }).mount('#app');
})();
