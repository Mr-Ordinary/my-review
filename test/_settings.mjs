// 测试会把 /api/settings 指向 mock LLM。**用完必须还原**，
// 否则用户的真实 API 配置会被覆盖掉——我踩过一次，别再踩。
//
// 还原做两件事：写回 settings.json（下次启动是对的）+ PUT 给后端（正在跑的这个进程内存里也是对的，
// 因为 SettingsService 启动时读文件、之后缓存在内存）。
//
// 用法：
//   import { protectSettings } from './_settings.mjs';
//   protectSettings(base);          // 在 PUT /api/settings 之前调用
//   ...
//   await restoreSettings(base);    // 结束时调用（正常路径）
//   // 异常路径靠 process.on('exit') 写文件兜底
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SETTINGS_FILE = path.join(
  path.dirname(fileURLToPath(import.meta.url)), '..', 'data', 'settings.json');

let saved = null;

/** 在改设置之前调用：记住当前配置，并挂上异常退出兜底 */
export function protectSettings() {
  saved = JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8'));
  // 同步写文件，进程异常退出时也能兜住
  process.on('exit', () => writeBack());
  process.on('uncaughtException', (e) => { writeBack(); console.error(e); process.exit(1); });
  process.on('unhandledRejection', (e) => { writeBack(); console.error(e); process.exit(1); });
}

function writeBack() {
  if (!saved) return;
  try { fs.writeFileSync(SETTINGS_FILE, JSON.stringify(saved, null, 2)); } catch { /* 兜底尽力而为 */ }
}

/** 正常结束时调用：写文件 + 通知后端 */
export async function restoreSettings(base) {
  if (!saved) return;
  writeBack();
  try {
    await fetch(base + '/api/settings', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(saved),
    });
  } catch { /* 后端没起来就算了，文件已经是对的 */ }
  saved = null;
}
