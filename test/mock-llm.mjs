// 测试用：模拟 Anthropic Messages API（POST /v1/messages），支持工具调用
// 用法：node test\mock-llm.mjs [端口，默认 9099]
//
// 除了按提示词分派固定回复，还做了三件对多轮验收有用的事：
// 1. **按请求条数回卷**——出题/批改返回的条目数跟着提示词里的条目走，否则断言没法写。
// 2. **分数可控**——作答里带 [[score=N]] 就给 N 分，方便模拟「答得好/答得差」的用户。
// 3. **提示词可回读**（GET /last）——验证「上次的问法」真的传进了提示词，
//    否则「变种题」根本没法测，只能看着代码自欺欺人。
import http from 'node:http';

const port = Number(process.argv[2] || 9099);

/** 最近收到的 user 提示词，供测试断言「上游到底喂了什么给模型」 */
const recentPrompts = [];

/* ---------- 知识点清单：30 个点，比一张卷多，逼出「选点」这一步 ---------- */

const CATEGORIES = [
  ['基本类型', ['概念', '位宽', '默认值', '取值范围', '溢出', '类型转换']],
  ['包装类', ['概念', '装箱拆箱', '缓存池', 'equals 与 ==', '空指针边界', '与基本类型互转']],
  ['面向对象 · 封装', ['概念', '访问控制', '底层可见性', '不可变设计', '封装常见误区', '与继承的边界']],
  ['集合框架', ['整体结构', 'ArrayList 扩容', 'HashMap 哈希', 'HashMap 树化', 'LinkedHashMap 顺序', 'fail-fast']],
  ['并发基础', ['线程状态', 'synchronized 原理', 'volatile 语义', 'CAS 与原子类', '线程池参数', '拒绝策略']],
];

function scaffoldReply(scopeText) {
  // 范围里带「小清单」的练习只拆 6 个点——比一张卷的槽位少，
  // 每张卷都会覆盖全部点，才能确定性地验证「换角度变种」。
  const small = /小清单/.test(scopeText || '');
  const cats = small ? CATEGORIES.slice(0, 3).map(([n, ps]) => [n, ps.slice(0, 2)]) : CATEGORIES;
  const categories = cats.map(([name, pts]) => ({
    name,
    points: pts.map((p) => ({
      name: `${name} · ${p}`,
      quick: '是什么、怎么用、什么场景选',
      std: '主干流程、关键参数与默认值、为什么这么设计',
      deep: '边界陷阱、与同类的取舍对比（不考源码实现）',
    })),
  }));
  return JSON.stringify({ categories });
}

/** 重要度分级：按名字前缀给不同优先级，方便验证点池过滤 */
function priorityReply(text) {
  const names = text.match(/候选知识点清单：\n([\s\S]*?)\n\n/) || [];
  const list = (names[1] || '').split(/[、\n]/).map((s) => s.trim()).filter(Boolean);
  const priorities = list.map((name, i) => {
    // 确定性分级：第 1 个 high，第 2 个 medium，其余 high——让速通卷也有足够点
    const priority = i === 1 ? 'medium' : 'high';
    return {
      name,
      priority,
      evidence: i === 1 ? '搜索出现过但不密集' : '多条面经线索直接命中',
      hits: [`${name} 面试题`],
    };
  });
  return JSON.stringify({ priorities: priorities.length ? priorities : [{ name: '点', priority: 'high', evidence: '兜底', hits: [] }] });
}

/**
 * 出题角度：按条目逐个给，角度里掺入「已问过」的计数，
 * 这样测试能从角度本身看出「换没换问法」。
 */
function anglesReply(text) {
  const lines = text.match(/^\s*\d+\.\s+(.+)$/gm) || [];
  const angles = lines.map((line, i) => {
    const body = line.replace(/^\s*\d+\.\s+/, '');
    const name = body.split('|')[0].trim().replace(/^\[[^\]]+\]\s*/, '');
    const past = /已问过：([^|]*)/.exec(body);
    const pastCount = past && past[1] && !/新点/.test(past[1])
      ? (past[1].split(/[、；]/).filter(Boolean).length) : 0;
    return {
      name,
      angle: pastCount ? `第${pastCount + 1}种问法：换角度问「${name}」的边界` : `初次问法：「${name}」是什么`,
    };
  });
  return JSON.stringify({ angles: angles.length ? angles : [{ name: '点', angle: '考察边界' }] });
}

function questionsReply(text) {
  const m = text.match(/^\s*\d+\.\s+【/gm);
  const n = m ? m.length : 1;
  const questions = [];
  for (let i = 0; i < n; i++) {
    questions.push({
      prompt: `第 ${i + 1} 题：请说明这个考点的核心机制与边界。`,
      demands: ['说明核心机制', '说明适用边界'],
      referenceAnswer: '**结论**：先给核心结论。\n**原因**：再讲底层机制。\n**要点**：最后给落地取舍。',
      knowledgePoints: ['考点'],
    });
  }
  return JSON.stringify({ questions });
}

/** 作答里的 [[score=N]] 控制表现分；没有就给 6（及格线） */
function scoreOf(answer) {
  const m = /\[\[score=(\d+(?:\.\d+)?)\]\]/.exec(answer || '');
  return m ? Number(m[1]) : 6;
}

function gradeReply(text) {
  const retry = text.includes('这是补批');
  const seqs = [...text.matchAll(/题号\s+(\d+)（/g)].map((m) => Number(m[1]));
  const answers = [...text.matchAll(/我的作答：(.*)/g)].map((m) => m[1]);
  const evaluations = [];
  for (let i = 0; i < seqs.length; i++) {
    const answer = answers[i] || '';
    // [[omit]]：首轮故意漏掉这题（模拟 AI 漏题），补批时必须返回
    if (!retry && answer.includes('[[omit]]')) continue;
    const noFeedback = !retry && answer.includes('[[nofeedback]]');
    evaluations.push({
      sequence: seqs[i],
      score: scoreOf(answer),
      feedback: noFeedback ? '' : '正确：结论正确。\n不足：适用边界可以再讲透一点。',
      memoryPoints: ['记忆点一', '记忆点二'],
      dimensions: { 正确性: 7, 完整性: 6, 表述: 8 },
    });
  }
  return JSON.stringify({ evaluations });
}

/** 发散：故意混一条越界的，验收要确认它被丢掉 */
function harvestReply() {
  return JSON.stringify({
    newPoints: [
      { name: '发散 · 范围内新增', category: '发散类', inScope: true, from: '用户提到了分段锁的粒度' },
      { name: '发散 · 越界应丢弃', category: '分布式', inScope: false, from: '用户随口提了 Redis 分布式锁' },
    ],
  });
}

function replyFor(user) {
  if (user.includes('请把这个范围拆成')) {
    const scope = /练习范围：(.*)/.exec(user)?.[1] || '';
    return scaffoldReply(scope);
  }
  if (user.includes('请对照上面的搜索线索')) return priorityReply(user);
  if (user.includes('请为每一项给一个')) return anglesReply(user);
  if (user.includes('请为上面每一项各出一道题')) return questionsReply(user);
  if (user.includes('请逐题批改')) return gradeReply(user);
  if (user.includes('请从用户的口述里找出')) return harvestReply();
  if (user.includes('请输出本卷总结')) {
    return JSON.stringify({
      overall: '整体掌握较好，原理深度有待加强。',
      strengths: ['基础概念清晰'],
      improvements: ['补充底层参数与边界'],
    });
  }
  if (user.includes('请合并更新长期记忆')) {
    return JSON.stringify({
      mastered: ['基本类型'],
      weakPoints: ['线程池参数'],
      misconceptions: [],
      nextFocus: ['并发容器'],
      coverage: ['基本类型', '包装类'],
    });
  }
  if (user.includes('请重写会话摘要')) {
    return JSON.stringify({
      overview: '围绕 Java 基础做了若干张卷。',
      coveredAreas: ['基本类型'],
      uncoveredAreas: ['并发基础'],
      level: '中等水平，基础扎实但深度不够',
    });
  }
  if (user.includes('请给我一份阶段总结')) {
    return JSON.stringify({
      overall: '本阶段完成了系统练习。',
      strengths: ['基本类型扎实'],
      keyWeaknesses: ['并发原理说不清'],
      nextSteps: ['复习 volatile 语义'],
      reviewScope: 'Java 基础 + 并发基础',
    });
  }
  return JSON.stringify({ prompt: '（未知阶段）请说明这道题的考察点。' });
}

/** 请求里有 tools 且还没回过 tool_result → 模拟一次工具调用，第二轮再给最终 JSON */
function shouldReturnToolUse(payload) {
  if (!payload.tools || payload.tools.length === 0) return false;
  const messages = payload.messages || [];
  for (const m of messages) {
    if (Array.isArray(m.content)) {
      for (const block of m.content) {
        if (block.type === 'tool_result') return false;
      }
    }
    if (m.role === 'tool') return false;
  }
  return true;
}

http.createServer((req, res) => {
  // 测试断言入口：回读最近收到的提示词
  if (req.method === 'GET' && req.url.startsWith('/last')) {
    const want = decodeURIComponent((req.url.split('q=')[1] || ''));
    const hit = [...recentPrompts].reverse().find((p) => !want || p.includes(want));
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ prompt: hit || null, total: recentPrompts.length }));
    return;
  }
  if (req.method === 'GET' && req.url === '/reset') {
    recentPrompts.length = 0;
    res.writeHead(200).end('ok');
    return;
  }
  if (req.method !== 'POST' || !req.url.includes('/v1/messages')) {
    res.writeHead(404).end('not found');
    return;
  }
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => {
    const payload = JSON.parse(body || '{}');
    const user = payload.messages?.find((m) => m.role === 'user')?.content || '';
    const userText = typeof user === 'string' ? user : JSON.stringify(user);
    recentPrompts.push(userText);
    if (recentPrompts.length > 200) recentPrompts.shift();

    if (shouldReturnToolUse(payload)) {
      const toolName = payload.tools[0]?.name || 'web_search';
      const input = toolName === 'fetch_url' ? { url: 'https://example.com' } : { query: '面试高频' };
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        id: 'mock', type: 'message', role: 'assistant', model: payload.model,
        content: [{ type: 'tool_use', id: 'toolu_mock_01', name: toolName, input }],
        stop_reason: 'tool_use', stop_sequence: null,
        usage: { input_tokens: 1, output_tokens: 1 },
      }));
      return;
    }

    const content = replyFor(userText);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      id: 'mock', type: 'message', role: 'assistant', model: payload.model,
      content: [{ type: 'text', text: content }],
      stop_reason: 'end_turn', stop_sequence: null,
      usage: { input_tokens: 1, output_tokens: 1 },
    }));
  });
}).listen(port, '127.0.0.1', () => console.log('mock-llm (anthropic+tools) listening on', port));
