/* ===== MyReview 图标库：手写内联 SVG 线性图标 =====
 * 规则：禁止 emoji、禁止外链图片/字体图标；全部为手写简洁路径。
 * 统一规格：viewBox="0 0 24 24"，stroke="currentColor"，fill="none"，
 * 线宽 2，圆角线帽线接，默认 18px（可用 Icons.get(name, size) 指定 16-20px）。
 */
(function () {
  'use strict';

  /* 每个图标只有 <svg> 内部的路径体 */
  const PATHS = {
    /* 书本 —— 练习 */
    book: '<path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>',

    /* 靶心 —— 押题 */
    target: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1.2"/>',

    /* 麦克风 —— 模拟面试 */
    mic: '<rect x="9" y="2" width="6" height="11" rx="3"/><path d="M19 10v1a7 7 0 0 1-14 0v-1"/><path d="M12 18v4"/><path d="M8.5 22h7"/>',

    /* 齿轮 —— 系统设置 */
    gear: '<circle cx="12" cy="12" r="3.2"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.2 5.2l2.2 2.2M16.6 16.6l2.2 2.2M18.8 5.2l-2.2 2.2M7.4 16.6l-2.2 2.2"/>',

    /* 文件夹 —— 阶段 */
    folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"/>',

    /* 时钟 —— 时间 */
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5V12l3 2"/>',

    /* 加号 —— 新建 */
    plus: '<path d="M12 5v14M5 12h14"/>',

    /* 垃圾桶 —— 删除 */
    trash: '<path d="M4 7h16"/><path d="M9.5 7V5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v2"/><path d="M6.5 7l1 12a2 2 0 0 0 2 2h5a2 2 0 0 0 2-2l1-12"/><path d="M10 11v6M14 11v6"/>',

    /* 下载 —— 导出 */
    download: '<path d="M12 3v12"/><path d="M7 11l5 5 5-5"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/>',

    /* 返回箭头 */
    back: '<path d="M19 12H5"/><path d="M11 6l-6 6 6 6"/>',

    /* 勾 —— 完成 / 保存 */
    check: '<path d="M4 12.5l5 5L20 6.5"/>',

    /* 星 —— 总结 / 可背诵 */
    star: '<path d="M12 3.2l2.7 5.6 6.1.9-4.4 4.3 1.1 6.1-5.5-2.9-5.5 2.9 1.1-6.1L3.2 9.7l6.1-.9z"/>',

    /* 对话气泡 —— 点评 */
    chat: '<path d="M20 15a2 2 0 0 1-2 2H8l-4 3.5V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v9z"/>',

    /* 列表 —— 记录 / 概览 */
    list: '<path d="M8.5 6h12M8.5 12h12M8.5 18h12"/><path d="M4 6h.01M4 12h.01M4 18h.01"/>',

    /* 播放 —— 开始 / 出题 */
    play: '<path d="M8 5.5l10 6.5-10 6.5z"/>',

    /* 旗帜 —— 结束 */
    flag: '<path d="M5.5 21V4"/><path d="M5.5 5h11l-2.2 4 2.2 4h-11"/>',

    /* 刷新 */
    refresh: '<path d="M20.5 12a8.5 8.5 0 1 1-2.5-6"/><path d="M20.5 4v5.5H15"/>',

    /* 发送 —— 提交作答 */
    send: '<path d="M20.5 3.5L11 13"/><path d="M20.5 3.5l-6 17-3.8-7.7L3 9z"/>',

    /* 眼睛 —— 看答案 */
    eye: '<path d="M2.5 12S6 6 12 6s9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6z"/><circle cx="12" cy="12" r="3"/>',

    /* 柱状图 —— 统计 */
    chart: '<path d="M3.5 20.5h17"/><path d="M7 20.5v-6M12 20.5v-11M17 20.5v-8"/>',

    /* 信息 —— 提示条 */
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5"/><path d="M12 8h.01"/>',

    /* 叉 —— 关闭 */
    close: '<path d="M6 6l12 12M18 6L6 18"/>',
  };

  function svg(body, size) {
    const s = size || 18;
    return '<svg xmlns="http://www.w3.org/2000/svg" width="' + s + '" height="' + s +
      '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + body + '</svg>';
  }

  const Icons = {
    /* Icons.get('book', 20) → 指定尺寸的图标 */
    get: function (name, size) {
      return svg(PATHS[name] || '', size);
    },
    /* Icons.has('book') → 是否存在该图标 */
    has: function (name) {
      return Object.prototype.hasOwnProperty.call(PATHS, name);
    },
  };

  /* Icons.book / Icons.target ... → 默认 18px */
  Object.keys(PATHS).forEach(function (name) {
    Icons[name] = svg(PATHS[name], 18);
  });

  window.Icons = Icons;
})();
