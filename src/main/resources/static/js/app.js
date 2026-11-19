/* ============================================================
   MyReview 旧地址重定向壳（index.html）
   会话已拆成 4 个独立页（answer/study/review/quiz.html），
   这里只负责把旧 hash 书签搬到新页，不再承载任何业务渲染。
   ============================================================ */
(function () {
  'use strict';

  const TOKEN_KEY = 'mr.token';

  /** 旧 hash → 新页 URL；返回 null 表示未知路由（落回阶段首页） */
  function targetOf(hash) {
    const parts = (hash || '').replace(/^#\/?/, '').split('/');
    const head = parts[0] || 'stages';
    const arg = parts[1] ? decodeURIComponent(parts[1]) : '';
    const sub = parts[2] ? decodeURIComponent(parts[2]) : '';

    if (head === 'session' && arg) {
      if (sub === 'study') return 'study.html?id=' + encodeURIComponent(arg);
      if (sub === 'review') return 'review.html?id=' + encodeURIComponent(arg);
      if (sub === 'drill') return 'quiz.html?id=' + encodeURIComponent(arg);
      return 'answer.html?id=' + encodeURIComponent(arg);
    }
    if (head === 'stages' && arg) return 'stage.html?id=' + encodeURIComponent(arg);
    if (head === 'practice') return 'practice.html';
    if (head === 'drill') return 'drill.html';   // 押题占位页（与刷题 quiz.html 不同）
    if (head === 'mock') return 'mock.html';
    if (head === 'settings' || head === 'profile') return 'profile.html';
    return 'stages.html';   // 阶段列表（含旧 #/stages 书签）与未知路由
  }

  (function boot() {
    const token = localStorage.getItem(TOKEN_KEY);
    if (!token) {
      location.replace('login.html');
      return;
    }
    location.replace(targetOf(location.hash));
  })();
})();
