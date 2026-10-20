// 测试用：模拟 Anthropic Messages API（POST /v1/messages），支持工具调用
// 用法：node test\mock-llm.mjs [端口，默认 9099]
import http from 'node:http';

const port = Number(process.argv[2] || 9099);

function replyFor(user) {
  if (user.includes('请给出这张卷的出题计划')) {
    return JSON.stringify({
      count: 2,
      outline: [
        { angle: 'HashMap 底层结构', tier: '基础', type: '概念辨析', points: 3 },
        { angle: '线程池参数调优', tier: '大题', type: '方案设计', points: 12 },
      ],
    });
  }
  if (user.includes('请为上面每一项各出一道题')) {
    return JSON.stringify({
      questions: [
        {
          prompt: 'HashMap 底层数组的容量为什么必须是 2 的幂？',
          referenceAnswer: '为了用 (n-1)&hash 代替 hash%n 来计算下标……',
          knowledgePoints: ['HashMap', '哈希表', '位运算'],
        },
      ],
    });
  }
  if (user.includes('请逐题批改')) {
    return JSON.stringify({
      evaluations: [
        {
          sequence: 1,
          score: 7.5,
          feedback: '方向正确但缺少关键推导。',
          dimensions: { 场景分析: 7, 技术深度: 6, 表达结构: 8 },
        },
      ],
    });
  }
  if (user.includes('请输出本卷总结')) {
    return JSON.stringify({
      overall: '整体掌握较好，原理深度有待加强。',
      strengths: ['基础概念清晰'],
      improvements: ['补充底层参数与边界'],
    });
  }
  if (user.includes('请合并更新长期记忆')) {
    return JSON.stringify({
      mastered: ['HashMap 基础'],
      weakPoints: ['线程池参数'],
      misconceptions: [],
      nextFocus: ['并发容器'],
      coverage: ['HashMap', '线程池'],
    });
  }
  if (user.includes('请重写会话摘要')) {
    return JSON.stringify({
      overview: '围绕 Java 集合做了 1 张卷。',
      coveredAreas: ['HashMap'],
      uncoveredAreas: ['ConcurrentHashMap'],
      level: '中等水平，基础扎实但深度不够',
    });
  }
  if (user.includes('请给我一份阶段总结')) {
    return JSON.stringify({
      overall: '本阶段完成了系统练习。',
      strengths: ['集合框架基础扎实'],
      keyWeaknesses: ['并发容器原理说不清'],
      nextSteps: ['复习 ConcurrentHashMap'],
      reviewScope: 'Java 集合框架 + 并发容器',
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
        if (block.type === 'tool_result') return false; // 已经有工具结果了，该出终局
      }
    }
    if (m.role === 'tool') return false;
  }
  return true;
}

http.createServer((req, res) => {
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

    // 工具调用路径：先回一个 tool_use，等服务端喂 tool_result 后再出终局
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
