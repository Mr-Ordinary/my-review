/* ============================================================
   MyReview 个人中心（profile.html）
   昵称 / 头像（裁剪上传）/ 字号 / 麦克风检测 / 改密 / 模型配置（多套+共享）/ 求职总结。
   逻辑与工作台 app.js 个人中心逐条对齐；页面数据在 init() 钩子里加载。
   ============================================================ */
(function () {
  'use strict';

  const C = window.MRCommon;
  const { createApp } = Vue;
  const esc = C.esc;

  createApp({
    data() {
      return {
        user: null,
        avatarUrl: '',
        navItems: C.NAV_ITEMS,
        nickname: '',
        savingNickname: false,
        fontSize: 'standard',
        themeMode: 'system',
        speechStatusHtml: '',
        micDiag: { show: false, cls: '', html: '' },

        // 改密
        pwdOpen: false,
        pwdBusy: false,
        pwd: { oldPassword: '', newPassword: '', newPassword2: '' },

        // 模型配置
        llmOpen: false,
        llmConfigs: [],
        llmSummary: { cls: '', text: '读取中…' },
        llmFormOpen: false,
        llmFormBusy: false,
        llmForm: { configId: '', name: '', apiFormat: 'anthropic', apiBase: '', apiKey: '', model: '', temperature: '' },
        llmFormEdit: null,          // 正在编辑的配置（判断 hasApiKey）
        llmClearKey: false,

        // 共享
        shareOpen: false,
        shareConfigId: null,
        shareQuery: '',
        shareResults: null,         // null=未搜索
        shareSearching: false,
        shareList: null,

        // 求职总结
        summary: { status: null, error: null, startedAt: null, summary: null, summaryAt: null },
        summaryOpen: false,
        summaryPollTimer: null,

        // 头像裁剪
        crop: { show: false, z: 1, dragging: false, sx: 0, sy: 0, stx: 0, sty: 0, tx: 0, ty: 0, base: 1, dw: 280, dh: 280 },
        cropImg: null,              // Image 对象
        cropUrl: null,

        logoutOpen: false,
        loggingOut: false,
      };
    },

    computed: {
      userNick() {
        const u = this.user || {};
        return u.nickname || u.username || '我';
      },
      llmFormHasKey() {
        return !!(this.llmFormEdit && this.llmFormEdit.hasApiKey);
      },
      llmKeyPlaceholder() {
        return this.llmFormHasKey ? `${this.llmFormEdit.masked || 'sk-****'}（留空不改）` : 'sk-...';
      },
      llmKeyStatusHtml() {
        const s = this.llmFormEdit;
        return s && s.hasApiKey
          ? `${this.icon('check', 13)} 已配置 <code>${esc(s.masked || 'sk-****')}</code>，留空保存则保持不变`
          : `${this.icon('info', 13)} 未配置 Key：出题、批改、总结等 AI 功能将无法使用。`;
      },
      /** 求职总结详情 HTML（与工作台 profileSummaryHtml 同构） */
      summaryHtml() {
        const s = this.summary.summary;
        if (!s) return '';
        const p = s.profile || {};
        const job = s.jobAdvice || {};
        const items = (arr, fn) => (arr && arr.length ? arr.map(fn).join('') : '');
        const levelLabel = { junior: '初级', mid: '中级', senior: '高级' };
        return `
          <div class="muted">生成于 ${C.fmtTime(this.summary.summaryAt || Date.now())}</div>
          <div class="panel panel-accent" style="margin-top:8px">
            <div class="sum-title">${this.icon('star', 15)} ${esc(p.title || '')}</div>
            <p>${esc(p.overview || '')}</p>
          </div>
          <div class="sum-grid">
            <div class="sum-block"><h4>${this.icon('check', 13)} 强项</h4>
              ${items(s.strengths, (x) => `
                <div class="sum-item"><b>${esc(x.point)}</b><div class="muted">${esc(x.evidence)}</div></div>`)}
            </div>
            <div class="sum-block"><h4>${this.icon('info', 13)} 短板</h4>
              ${items(s.weaknesses, (x) => `
                <div class="sum-item"><b>${esc(x.point)}</b><div class="muted">${esc(x.advice)}</div></div>`)}
            </div>
          </div>
          <div class="sum-block"><h4>${this.icon('target', 13)} 求职建议</h4>
            <div class="sum-item">
              <span class="badge badge-blue">${esc(levelLabel[job.targetLevel] || job.targetLevel || '未定级')}</span>
              ${items(job.positions, (x) => `<span class="badge badge-gray">${esc(x)}</span>`)}
              <div class="muted">${items(job.reasons, (r) => `<div>· ${esc(r)}</div>`)}</div>
            </div>
          </div>
          <div class="sum-block"><h4>${this.icon('mic', 13)} 面试重点</h4>
            ${items(s.interviewFocus, (x) => `<div class="sum-item">${esc(x)}</div>`)}
          </div>
          <div class="sum-block"><h4>${this.icon('flag', 13)} 行动计划</h4>
            ${items(s.actionPlan, (x) => `
              <div class="sum-item"><b>${esc(x.step)}</b><div class="muted">${esc(x.why)}</div></div>`)}
          </div>`;
      },
    },

    methods: {
      icon(name, size) { return window.Icons.get(name, size); },
      fmtTime: (ts) => C.fmtTime(ts),

      /** init 钩子：鉴权 + 个人中心各区块数据 */
      async init() {
        try {
          this.user = await C.api('/api/auth/me');
        } catch (err) {
          C.setToken('');
          location.replace('login.html');
          return;
        }
        this.nickname = this.user.nickname || this.user.username || '';
        if (this.user.avatarExt) {
          this.avatarUrl = await C.loadAvatar(this.user.id) || '';
        }
        this.loadSpeechStatus();
        this.loadLlmSummary();
        this.refreshSummary();
      },

      /* ================= 昵称 ================= */

      async saveNickname() {
        const nickname = this.nickname.trim();
        if (!nickname) return C.toast('昵称不能为空', 'warning');
        if (this.savingNickname) return;
        this.savingNickname = true;
        try {
          const u = await C.api('/api/me/profile', { method: 'POST', body: { nickname } });
          this.user = u;
          C.toast('昵称已保存', 'success');
        } catch (err) {
          C.toast(err.message, 'error', '保存失败');
        } finally {
          this.savingNickname = false;
        }
      },

      /* ================= 字号 / 主题 / 麦克风 ================= */

      /** 主题：null=跟随系统，'light'/'dark'=手动指定（存 localStorage，全站 html[data-theme]） */
      applyTheme(mode) {
        this.themeMode = mode || 'system';
        C.setTheme(mode);
        C.toast(mode ? '主题已切换' : '已跟随系统', 'success');
      },


      applyFontSize(size) {
        this.fontSize = size;
        document.documentElement.dataset.fs = size;
        try { localStorage.setItem('mr.fontSize', size); } catch (e) { /* 忽略 */ }
        C.toast('字号已切换', 'success');
      },

      async loadSpeechStatus() {
        if (!window.Speech) { this.speechStatusHtml = '无法获取语音识别状态'; return; }
        try {
          const st = await window.Speech.status();
          this.speechStatusHtml = st.ready
            ? `${this.icon('check', 14)} 已就绪`
            : `${this.icon('info', 14)} 语音识别暂不可用`;
        } catch (e) {
          this.speechStatusHtml = '无法获取语音识别状态';
        }
      },

      /** 检测麦克风：诊断 + 真拿一次权限（与工作台 test-mic 同逻辑） */
      async testMic() {
        if (!window.Speech) return;
        this.micDiag = { show: true, cls: 'hint', html: '检测中…' };
        const d = await window.Speech.diagnose();
        let grab = { ok: true };
        try {
          await window.Speech.start();
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
        this.micDiag = {
          show: true,
          cls: 'hint ' + (grab.ok && d.secureContext ? 'ok' : 'warn'),
          html: rows.map((r) => `<div><b>${esc(r[0])}</b>：${esc(r[1])}</div>`).join('')
            + (grab.ok ? '' : `<div style="margin-top:8px">${esc(grab.msg)}</div>`),
        };
      },

      /* ================= 改密 ================= */

      openPassword() {
        this.pwd = { oldPassword: '', newPassword: '', newPassword2: '' };
        this.pwdOpen = true;
      },
      closePassword() {
        if (this.pwdBusy) return;
        this.pwdOpen = false;
      },
      async submitPassword() {
        const { oldPassword, newPassword, newPassword2 } = this.pwd;
        if (!oldPassword || !newPassword) return C.toast('请填写原密码和新密码', 'warning');
        if (newPassword !== newPassword2) return C.toast('两次输入的新密码不一致', 'warning');
        if (this.pwdBusy) return;
        this.pwdBusy = true;
        try {
          // 改密会撤销全部旧 token，返回新 token，前端替换本地保存
          const r = await C.api('/api/auth/password', { method: 'POST', body: { oldPassword, newPassword } });
          C.setToken(r.token);
          if (r.user) this.user = r.user;
          this.pwdOpen = false;
          C.toast('密码已修改', 'success');
        } catch (err) {
          C.toast(err.message, 'error', '修改失败');
        } finally {
          this.pwdBusy = false;
        }
      },

      /* ================= 模型配置 ================= */

      async openLlm() {
        this.llmOpen = true;
        await this.refreshLlmConfigs();
      },
      async refreshLlmConfigs() {
        try {
          this.llmConfigs = await C.api('/api/me/llm-configs');
          await this.loadLlmSummary();
        } catch (err) {
          C.toast(err.message, 'error', '读取配置失败');
        }
      },
      async loadLlmSummary() {
        try {
          const list = this.llmConfigs.length ? this.llmConfigs : await C.api('/api/me/llm-configs');
          this.llmConfigs = list;
          const active = list.find((c) => c.active);
          if (!active) {
            this.llmSummary = {
              cls: 'hint warn',
              text: list.length
                ? '未启用任何 API 配置，AI 功能（出题、批改、总结）不可用。点「配置」选择启用。'
                : '还没有 API 配置，AI 功能（出题、批改、总结）不可用。点「配置」添加。',
            };
            return;
          }
          const from = active.sharedBy ? `（由 ${active.sharedBy.nickname || active.sharedBy.username} 共享）` : '';
          this.llmSummary = {
            cls: 'hint ok',
            text: `启用：${active.name}${from} · ${active.model || '默认模型'}${active.hasApiKey ? ' · Key ' + (active.masked || '已配置') : ''}`,
          };
        } catch (err) {
          this.llmSummary = { cls: 'hint warn', text: '读取失败：' + err.message };
        }
      },

      openLlmForm(c) {
        this.llmFormEdit = c;
        this.llmClearKey = false;
        this.llmForm = c
          ? {
              configId: c.id, name: c.name || '', apiFormat: c.apiFormat || 'anthropic',
              apiBase: c.apiBase || '', apiKey: '', model: c.model || '',
              temperature: (c.temperature !== null && c.temperature !== undefined) ? String(c.temperature) : '',
            }
          : { configId: '', name: '', apiFormat: 'anthropic', apiBase: '', apiKey: '', model: '', temperature: '' };
        this.llmFormOpen = true;
      },
      clearApiKey() {
        // 与工作台一致：确认后清标记，保存时 clearKey 生效
        this.confirm({
          title: '清除 API Key',
          body: '清除后这套配置没有 Key，AI 功能将无法使用。保存后生效。',
          okText: '清除', danger: true,
        }).then((ok) => {
          if (!ok) return;
          this.llmClearKey = true;
          this.llmForm.apiKey = '';
        });
      },
      async submitLlmForm() {
        const rawTemp = this.llmForm.temperature;
        const temperature = rawTemp === '' ? null : Number(rawTemp);
        if (rawTemp !== '' && !Number.isFinite(temperature)) {
          return C.toast('请填写有效的温度数值', 'warning');
        }
        if (this.llmFormBusy) return;
        this.llmFormBusy = true;
        try {
          // 点过「清除」又填了新 Key 时以新 Key 为准
          const clearKey = this.llmClearKey && !this.llmForm.apiKey;
          const body = {
            name: this.llmForm.name, apiFormat: this.llmForm.apiFormat, apiBase: this.llmForm.apiBase,
            apiKey: this.llmForm.apiKey, model: this.llmForm.model, temperature, clearKey,
          };
          if (this.llmForm.configId) {
            await C.api('/api/me/llm-configs/' + encodeURIComponent(this.llmForm.configId) + '/update',
              { method: 'POST', body });
          } else {
            await C.api('/api/me/llm-configs', { method: 'POST', body });
          }
          // 只关编辑弹窗，管理弹窗留着并刷新列表
          this.llmFormOpen = false;
          await this.refreshLlmConfigs();
          C.toast('配置已保存', 'success');
        } catch (err) {
          C.toast(err.message, 'error', '保存失败');
        } finally {
          this.llmFormBusy = false;
        }
      },
      activateLlm(c) {
        C.busy(document.createElement('button'), '切换中…', async () => {
          this.llmConfigs = await C.api('/api/me/llm-configs/' + encodeURIComponent(c.id) + '/activate', { method: 'POST' });
          await this.loadLlmSummary();
        });
      },
      deleteLlm(c) {
        this.confirm({
          title: '删除配置',
          body: '删除后不可恢复，共享给其他人的记录也会一并清除。',
          okText: '删除', danger: true,
        }).then(async (ok) => {
          if (!ok) return;
          await C.busy(document.createElement('button'), '删除中…', async () => {
            await C.api('/api/me/llm-configs/' + encodeURIComponent(c.id) + '/delete', { method: 'POST' });
            await this.refreshLlmConfigs();
            C.toast('已删除', 'success');
          });
        });
      },

      /* ================= 共享 ================= */

      openShare(c) {
        this.shareConfigId = c.id;
        this.shareQuery = '';
        this.shareResults = null;
        this.shareList = null;
        this.shareOpen = true;
        this.refreshShareList();
      },
      async refreshShareList() {
        try {
          this.shareList = await C.api('/api/me/llm-configs/' + encodeURIComponent(this.shareConfigId) + '/shares');
        } catch (err) {
          this.shareList = [];
          C.toast(err.message, 'error', '读取共享列表失败');
        }
      },
      async searchUsers() {
        const q = this.shareQuery.trim();
        if (!q) { this.shareResults = null; return; }
        this.shareSearching = true;
        try {
          this.shareResults = await C.api('/api/me/user-search?q=' + encodeURIComponent(q));
        } catch (err) {
          this.shareResults = [];
          C.toast(err.message, 'error', '搜索失败');
        } finally {
          this.shareSearching = false;
        }
      },
      async doShare(u) {
        await C.busy(document.createElement('button'), '共享中…', async () => {
          await C.api('/api/me/llm-configs/' + encodeURIComponent(this.shareConfigId) + '/shares',
            { method: 'POST', body: { userId: u.userId } });
          C.toast('已共享', 'success');
          this.shareResults = null;
          this.shareQuery = '';
          await this.refreshShareList();
        });
      },
      async revokeShare(s) {
        await C.busy(document.createElement('button'), '取消中…', async () => {
          await C.api('/api/me/llm-configs/' + encodeURIComponent(this.shareConfigId)
            + '/shares/' + encodeURIComponent(s.userId) + '/revoke', { method: 'POST' });
          C.toast('已取消共享', 'success');
          await this.refreshShareList();
        });
      },

      /* ================= 求职总结 ================= */

      /** 拉一次总结状态；生成中则 3 秒后再拉，直到不再是 generating（与工作台同节奏） */
      async refreshSummary() {
        if (this.summaryPollTimer) { clearTimeout(this.summaryPollTimer); this.summaryPollTimer = null; }
        try {
          const r = await C.api('/api/me/summary');
          this.summary = r;
          if (r.status === 'generating') {
            this.summaryPollTimer = setTimeout(() => this.refreshSummary(), 3000);
          }
        } catch (err) {
          this.summary = { status: 'failed', error: '读取失败：' + err.message, summary: null };
        }
      },
      genSummary() {
        // 覆盖已有总结前先确认；首次生成没有可丢的内容，不打扰
        const go = async () => {
          try {
            this.summary = await C.api('/api/me/summary', { method: 'POST' });
          } catch (err) {
            // 409 = 另一个标签页/刷新前已经在生成，直接同步状态而不是报错
            if (!/正在生成/.test(err.message)) C.toast(err.message, 'error', '无法生成总结');
          }
          this.refreshSummary();
        };
        if (this.summary.summary) {
          this.confirm({
            title: '重新生成求职总结',
            body: '会覆盖现有的求职总结，重新汇总全部学习数据生成。',
            okText: '重新生成',
          }).then((ok) => { if (ok) go(); });
        } else {
          go();
        }
      },

      /* ================= 头像：选图 → 裁剪 → 上传 ================= */

      onAvatarPick(e) {
        const file = e.target.files && e.target.files[0];
        e.target.value = '';   // 允许再次选择同一张图
        if (!file) return;
        this.openCrop(file);
      },
      openCrop(file) {
        const url = URL.createObjectURL(file);
        const img = new Image();
        img.onload = () => {
          this.cropImg = img;
          this.cropUrl = url;
          this.crop = { show: true, z: 1, dragging: false, sx: 0, sy: 0, stx: 0, sty: 0, tx: 0, ty: 0, base: 1, dw: 280, dh: 280 };
          this.$nextTick(() => this.cropLayout());
        };
        img.onerror = () => { URL.revokeObjectURL(url); C.toast('图片读取失败', 'error'); };
        img.src = url;
      },
      cropLayout() {
        const V = 280;
        const img = this.cropImg;
        const el = this.$refs.cropImg;
        if (!img || !el) return;
        const base = Math.max(V / img.naturalWidth, V / img.naturalHeight);
        this.crop.base = base;
        const s = base * this.crop.z;
        this.crop.dw = img.naturalWidth * s;
        this.crop.dh = img.naturalHeight * s;
        this.cropClamp();
        el.style.width = this.crop.dw + 'px';
        el.style.height = this.crop.dh + 'px';
        el.style.left = this.crop.tx + 'px';
        el.style.top = this.crop.ty + 'px';
      },
      cropClamp() {
        const V = 280;
        this.crop.tx = Math.min(0, Math.max(V - this.crop.dw, this.crop.tx));
        this.crop.ty = Math.min(0, Math.max(V - this.crop.dh, this.crop.ty));
      },
      cropDown(e) {
        this.crop.dragging = true;
        this.crop.sx = e.clientX; this.crop.sy = e.clientY;
        this.crop.stx = this.crop.tx; this.crop.sty = this.crop.ty;
        e.currentTarget.setPointerCapture(e.pointerId);
        e.currentTarget.classList.add('dragging');
      },
      cropMove(e) {
        if (!this.crop.dragging) return;
        this.crop.tx = this.crop.stx + (e.clientX - this.crop.sx);
        this.crop.ty = this.crop.sty + (e.clientY - this.crop.sy);
        this.cropClamp();
        const el = this.$refs.cropImg;
        el.style.left = this.crop.tx + 'px';
        el.style.top = this.crop.ty + 'px';
      },
      cropUp(e) {
        this.crop.dragging = false;
        if (e && e.currentTarget) e.currentTarget.classList.remove('dragging');
      },
      cropZoomInput(e) {
        this.crop.z = Number(e.target.value) / 100;
        this.cropLayout();
      },
      /** 滚轮缩放：以指针为中心（与工作台同公式） */
      cropWheel(e) {
        const V = 280;
        const vp = this.$refs.cropVp;
        const rect = vp.getBoundingClientRect();
        const cx = e.clientX - rect.left;
        const cy = e.clientY - rect.top;
        const oldS = this.crop.base * this.crop.z;
        const nz = Math.min(3, Math.max(1, this.crop.z + (e.deltaY < 0 ? 0.1 : -0.1)));
        if (nz === this.crop.z) return;
        this.crop.z = nz;
        const newS = this.crop.base * this.crop.z;
        this.crop.tx = cx - (cx - this.crop.tx) * (newS / oldS);
        this.crop.ty = cy - (cy - this.crop.ty) * (newS / oldS);
        this.cropLayout();
      },
      closeCrop() {
        if (this.cropUrl) URL.revokeObjectURL(this.cropUrl);
        this.cropUrl = null;
        this.cropImg = null;
        this.crop.show = false;
      },
      confirmCrop() {
        const V = 280;
        const img = this.cropImg;
        if (!img) return;
        // 按预览框的取景范围裁到 512×512（cover 模式）
        const canvas = document.createElement('canvas');
        canvas.width = 512; canvas.height = 512;
        const ctx = canvas.getContext('2d');
        // PNG 透明区域转 JPEG 会变黑，先铺白底
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, 512, 512);
        const s = this.crop.base * this.crop.z;
        ctx.drawImage(img, -this.crop.tx / s, -this.crop.ty / s, V / s, V / s, 0, 0, 512, 512);
        canvas.toBlob(async (blob) => {
          if (!blob) return;
          const fd = new FormData();
          fd.append('file', blob, 'avatar.jpg');
          try {
            const u = await C.api('/api/me/avatar', { method: 'POST', body: fd });
            this.user = u;
            this.avatarUrl = URL.createObjectURL(blob);   // 本地即时换新
            this.closeCrop();
            C.toast('头像已更新', 'success');
          } catch (err) {
            C.toast(err.message, 'error', '头像上传失败');
          }
        }, 'image/jpeg', 0.9);
      },

      /* ================= 退出 / 确认框 ================= */

      /** 简易确认框（同工作台 UI.confirm 语义：遮罩点击=取消） */
      confirm(opts) {
        return new Promise((resolve) => {
          const backdrop = document.createElement('div');
          backdrop.className = 'dialog-backdrop';
          backdrop.innerHTML = `
            <div class="dialog" role="dialog" aria-modal="true">
              <div class="dialog-head">
                <div class="dialog-title">${window.Icons.get(opts.danger ? 'info' : 'check', 17)} ${esc(opts.title || '请确认')}</div>
              </div>
              <div class="dialog-body">${esc(opts.body || '')}</div>
              <div class="dialog-foot">
                <button class="btn btn-ghost" data-act="cancel">${esc(opts.cancelText || '取消')}</button>
                <button class="btn ${opts.danger ? 'btn-danger' : 'btn-primary'}" data-act="ok">${esc(opts.okText || '确定')}</button>
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
            if (act) return close(act.dataset.act === 'ok');
            if (!e.target.closest('.dialog')) close(false);
          });
        });
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
      this.fontSize = document.documentElement.dataset.fs || 'standard';
      try { this.themeMode = localStorage.getItem('mr.theme') || 'system'; } catch (e) { this.themeMode = 'system'; }
      // 吸顶页头：滚过页头后收成 mini 条；init 渲染完重测一次，避免用到过期位置
      this._remeasure = C.initStickyHead('.page-head');
      Promise.resolve(this.init()).finally(() => this._remeasure && this._remeasure());
    },

    beforeUnmount() {
      if (this.summaryPollTimer) clearTimeout(this.summaryPollTimer);
    },
  }).mount('#app');
})();
