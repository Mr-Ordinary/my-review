// 实测 LLM 代理是否支持 Anthropic prompt caching（cache_control）。
// 只看 usage 里的缓存字段，不打印正文、不打印 API key。
// 用法：node test\cache-probe.mjs
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const settings = JSON.parse(fs.readFileSync(
  path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'data', 'settings.json'), 'utf8'));

const openai = String(settings.apiFormat).toLowerCase() === 'openai';
const url = settings.apiBase.replace(/\/$/, '') + (openai ? '/v1/chat/completions' : '/v1/messages');

// 用一段足够长的系统提示词（缓存通常要求前缀 >1024 token），内容不重要
const system = `你是一个技术面试教练。以下是必须遵守的规则清单，每一条都很重要，请完整记住并严格执行。
${Array.from({ length: 60 }, (_, i) => `规则${i + 1}：出题必须贴合真实工程场景，答案要能直接口述，不要空话套话，必须给出结论、原因与取舍。`).join('\n')}
以上规则全部生效。`;

function build() {
  const body = { model: settings.model, max_tokens: 16, temperature: 0 };
  if (openai) {
    body.messages = [
      { role: 'system', content: [{ type: 'text', text: system, cache_control: { type: 'ephemeral' } }] },
      { role: 'user', content: '只回复两个字：好的' },
    ];
  } else {
    body.system = [{ type: 'text', text: system, cache_control: { type: 'ephemeral' } }];
    body.messages = [{ role: 'user', content: '只回复两个字：好的' }];
    body.thinking = { type: 'disabled' };
  }
  return body;
}

async function once(label) {
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': settings.apiKey,
      'anthropic-version': '2023-06-01',
      Authorization: `Bearer ${settings.apiKey}`,
    },
    body: JSON.stringify(build()),
  });
  const text = await res.text();
  if (!res.ok) {
    console.log(`${label}  HTTP ${res.status}`);
    console.log('  响应片段:', text.slice(0, 300));
    return null;
  }
  let json;
  try { json = JSON.parse(text); } catch { console.log(label, '响应不是 JSON'); return null; }
  const u = json.usage || {};
  console.log(`${label}  HTTP ${res.status}`);
  console.log(`  input_tokens              = ${u.input_tokens ?? u.prompt_tokens}`);
  console.log(`  cache_creation_input_tokens = ${u.cache_creation_input_tokens ?? '(无此字段)'}`);
  console.log(`  cache_read_input_tokens      = ${u.cache_read_input_tokens ?? '(无此字段)'}`);
  return u;
}

console.log('端点:', url, '| 模型:', settings.model, '| 协议:', settings.apiFormat);
console.log('系统提示词约', system.length, '字\n');

const a = await once('第 1 次调用（应该写缓存）');
const b = await once('第 2 次调用（应该读缓存）');

console.log('\n--- 结论 ---');
if (!a || !b) {
  console.log('调用失败，无法判断');
} else if (b.cache_read_input_tokens > 0) {
  console.log('✅ 代理支持 prompt caching，第 2 次命中缓存');
  const saved = b.cache_read_input_tokens;
  console.log(`   本次读缓存 ${saved} token，按 1/10 价算约省 ${(saved * 0.9).toFixed(0)} token 的费用`);
} else if (a.cache_creation_input_tokens > 0 && b.cache_creation_input_tokens > 0) {
  console.log('⚠️  接受 cache_control 并报告了 cache_creation，但第 2 次没读到缓存。');
  console.log('   可能是 TTL/前缀不一致，或代理不做跨请求缓存。');
} else if (a.cache_creation_input_tokens === undefined) {
  console.log('❌ 代理忽略了 cache_control（usage 里没有缓存字段）。');
  console.log('   → 接下来测 OpenAI 协议的自动前缀缓存。');
}

/* ---------- 再测 OpenAI 协议（自动前缀缓存，通常不需要显式标记） ---------- */
console.log('\n===== OpenAI 协议 =====');
const oaiUrl = settings.apiBase.replace(/\/$/, '') + '/v1/chat/completions';
console.log('端点:', oaiUrl);

async function onceOpenAi(label) {
  const res = await fetch(oaiUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${settings.apiKey}`,
    },
    body: JSON.stringify({
      model: settings.model,
      max_tokens: 16,
      temperature: 0,
      messages: [
        { role: 'system', content: system },
        { role: 'user', content: '只回复两个字：好的' },
      ],
    }),
  });
  const text = await res.text();
  if (!res.ok) {
    console.log(`${label}  HTTP ${res.status}`);
    console.log('  响应片段:', text.slice(0, 200));
    return null;
  }
  let json;
  try { json = JSON.parse(text); } catch { console.log(label, '响应不是 JSON'); return null; }
  const u = json.usage || {};
  const details = u.prompt_tokens_details || {};
  console.log(`${label}  HTTP ${res.status}`);
  console.log(`  prompt_tokens        = ${u.prompt_tokens}`);
  console.log(`  cached_tokens        = ${details.cached_tokens ?? '(无此字段)'}`);
  return u;
}

const oa = await onceOpenAi('第 1 次调用');
const ob = await onceOpenAi('第 2 次调用（看 cached_tokens）');
console.log('');
if (!oa || !ob) {
  console.log('❌ OpenAI 协议这条路径不通（或不报告缓存字段）。');
} else if (ob.usage?.prompt_tokens_details?.cached_tokens > 0) {
  console.log('✅ OpenAI 协议有自动前缀缓存，第 2 次命中');
  console.log(`   命中 ${ob.usage.prompt_tokens_details.cached_tokens} token`);
  console.log('   → 把 apiFormat 切成 openai 即可，代码不用改，前缀稳定就自动命中');
} else {
  console.log('⚠️  OpenAI 协议能用，但没报告 cached_tokens。');
  console.log('   可能是代理不透传该字段，或未启用前缀缓存。');
}
