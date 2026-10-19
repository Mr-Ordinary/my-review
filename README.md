# MyReview · 个人面试练习工作台

单机自用的面试练习 Web 应用：练习（背八股/场景题/刷力扣）、押题（简历押题）、模拟面试、系统设置。
业务规则与提示词移植自 dsh-interview（MIT），规格见工作区文档《dsh-interview-提示词与逻辑全集.md》。

## 现在就能用（纯前端，无需构建）

直接双击打开：

```
src/main/resources/static/index.html
```

- 纯 HTML/CSS/JS，无构建、无依赖、离线可用
- 配置和练习数据都保存在浏览器 localStorage（系统设置页可导出/清空）
- 当前 AI 回复是本地演示数据，界面全流程可点

## 目录结构

```
src/main/resources/static/
├── index.html      入口：左侧菜单（练习/押题/模拟面试/系统设置）
├── css/app.css     统一样式
└── js/
    ├── store.js    本地存储层（设置 + 练习数据）
    ├── api.js      AI 调用层（当前为演示数据，TODO 处接入后端）
    └── app.js      路由 + 四个页面 + 练习进行中视图

data/
├── settings.json   系统设置（LLM 配置，本地 JSON）
└── store/          业务数据（一表一文件，JSON 数组，行字段 = 表列）
    ├── stages.json     阶段表
    ├── practices.json  会话表（一次练习 = 一个会话）
    ├── questions.json  题目表
    └── attempts.json   作答表（追加保存，一答一评）
```

## 数据存储

- 业务数据存本地 JSON 文件（`data/store/*.json`），无数据库
- 存储层是接口抽象（`com.review.vt.repo`），当前实现为 JSON 文件（`repo.json.Json*Repository`），后续换数据库只需新增 `repo.jpa.Jpa*Repository` 实现同名接口，服务层零改动
- `data/myreview-db*` 是旧 H2 残留文件，可安全删除

## 练习逻辑

- 每一次练习 = 一个新会话，一个阶段可有若干会话
- 出题时 AI 参考**该阶段全部会话**的历史题目、评分、点评、会话总结和阶段总结，随着练习量增加不断深入、升级难度
- 阶段总结是动态的：可随时重新生成，生成后仍可继续在该阶段练习
