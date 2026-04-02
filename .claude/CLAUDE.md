# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 构建与运行

### 前置依赖
- Java 21, Maven 3
- Docker Compose（启动 MySQL/Redis/Milvus/Neo4j/RocketMQ）
- Node.js（前端开发）

### 基础设施启动
```bash
docker compose up -d          # 启动所有中间件（MySQL:3307, Redis:6379, Milvus:19530, Neo4j:7687）
```

### 后端
```bash
./mvnw spring-boot:run                          # 启动后端（端口 9596）
./mvnw compile                                   # 编译
./mvnw test                                      # 运行全部测试
./mvnw test -Dtest=RAGServiceTest                # 运行单个测试类
./mvnw test -Dtest=RAGServiceTest#testMethod     # 运行单个测试方法
```

### 前端
```bash
cd frontend && npm install
npm run dev              # Vite 开发服务器（端口 5173）
npm run build:spring     # 构建并输出到 src/main/resources/static/spa/
```

### 配置
- `application.yml`：主配置
- `application-private.yml`：密钥配置（不提交 Git），包含 API Key、数据库密码等
- 数据库 schema：`sql/schema.sql`，初始数据：`sql/data.sql`

---

## 架构概览

**AI 面试复习助手**——基于 Spring Boot 3.3 + Spring AI 1.0，集成多层 Agent、混合 RAG、IM 渠道的智能面试辅导系统。

### 多层 Agent 架构（`agent/` 包）
请求进入 `InterviewOrchestratorAgent`，按层路由：
1. **Decision Layer** — 意图识别与路由
2. **Knowledge Layer** — RAG 检索（向量 + 词法 + 图谱混合）
3. **Evaluation Layer** — 答案评估与追问
4. **Growth Layer** — 学习画像更新

`TaskRouterAgent` 根据意图分发到 `InterviewOrchestratorAgent` 或 `CodingPracticeAgent`。

Agent 间通信通过 `agent/a2a/` 事件总线（支持 InMemory / RocketMQ 切换）。

### RAG 管线（`service/` + `rag/` 包）
- **混合检索**：Milvus 向量 + MySQL 全文 + Neo4j 图谱，RRF 融合排序
- **父子索引**：文档分层切块（parent-child），检索子块后回溯父块补全上下文
- **知识摄入**：本地目录/浏览器上传 → Markdown 解析 → 结构化切块 → 多通道索引
- **可观测性**：RAG Trace（`t_rag_trace` / `t_rag_trace_node`），Retrieval Evaluation（Recall@5、MRR）

### IM 渠道集成（`im/` 包）
统一消息模型 `UnifiedMessage`，支持飞书、QQ WebSocket 长连接，ReAct 意图推理。

### 状态机（`core/statemachine/`）
基于阿里 COLA StateMachine 管理面试会话生命周期。

### 模型路由（`modelrouting/`）
多模型候选 + 优先级 + 三态熔断器，首包超时自动降级。LLM 默认走 DeepSeek，Embedding 走智谱。

### 持久层
- ORM：MyBatis-Plus 3.5（`mapper/` 包）
- 数据库：MySQL 8（端口 3307），逻辑删除 + 自动填充时间戳

### MCP 支持（`mcp/` 包）
FastMCP Java 实现，可暴露 Neo4j/Milvus 等工具给外部 Agent 调用。

---

## 关键技术约定
- Embedding 维度固定 2048（智谱 embedding-3），Milvus collection 名 `interview_notes`
- 分块策略 `STRUCTURE_RECURSIVE_WITH_METADATA`（目标 550 / 上限 800 / 重叠 80）
- 意图树存储在 `t_intent_node`，三级结构：domain → category → topic
- 链路追踪使用 `TransmittableThreadLocal`（alibaba TTL）跨线程传递 traceId
- Spring AI BOM 版本 1.0.0-SNAPSHOT，仓库地址 repo.spring.io/snapshot

---

# Claude Code 核心规范
## 工作模式：Superpowers + AI 协作

### 角色分工
**Claude（我）——架构师 / 项目经理**：
- 需求分析、架构设计、任务拆分
- 使用 Superpowers 进行规划、审查、调试
- 代码审核、最终验收、Git 提交管理
- **绝对不亲自编写代码**，所有编码任务必须委派给 Codex 或 Gemini

**Codex——后端开发**：
- 服务端代码、API、数据库、Migration
- 单元测试、集成测试
- 通过 `/ask codex "..."` 调用

**Gemini——前端开发**：
- 前端组件、页面、样式、交互逻辑
- 代码审查、安全审计
- 通过 `/ask gemini "..."` 调用

---

### 降级机制
当某个 AI 提供者不可用时，按以下规则降级：
```
Codex 不可用 → Gemini 接管后端任务
Gemini 不可用 → Codex 接管前端任务
两者都不可用 → 暂停编码，等待恢复（Claude 不代写代码）
```
降级时在任务描述中注明"降级接管"，便于后续追溯。

---

### 协作方式
**使用 Superpowers skills 进行**：
- 规划：`superpowers:writing-plans`
- 执行：`superpowers:executing-plans`
- 审查：`superpowers:requesting-code-review`
- 调试：`superpowers:systematic-debugging`
- 完成：`superpowers:finishing-a-development-branch`

**调用 AI 提供者执行代码任务**：
```bash
# 指派 Codex 实现后端
/ask codex "实现 XXX 后端功能，涉及文件：..."

# 指派 Gemini 实现前端
/ask gemini "实现 XXX 前端功能，涉及文件：..."

# 查看执行结果
/pend codex
/pend gemini
```

---

## Linus 三问（决策前必问）
1.  **这是现实问题还是想象问题？** → 拒绝过度设计
2.  **有没有更简单的做法？** → 始终寻找最简方案
3.  **会破坏什么？** → 向后兼容是铁律

---

## Git 规范
- 提交前必须通过代码审查
- 提交信息：`<类型>: <描述>`（中文）
- 类型：`feat` / `fix` / `docs` / `refactor` / `chore`
- **禁止**：force push、修改已 push 历史

---
