# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 构建与运行

### 前置依赖
- Java 21, Maven 3.9+
- Docker Compose（启动 MySQL/Redis/Milvus/Neo4j/RocketMQ）
- Node.js 18+（前端开发）

### 基础设施启动
```bash
docker compose up -d mysql redis etcd minio milvus neo4j
```

### 后端
```bash
./mvnw -q compile                                # 快速编译检查
./mvnw spring-boot:run                            # 启动后端（端口 9596）
./mvnw test                                       # 运行全部测试
./mvnw test -Dtest=RAGServiceTest                 # 运行单个测试类
./mvnw test -Dtest=RAGServiceTest#testMethod      # 运行单个测试方法
./mvnw -q verify -DskipTests                      # 运行 Checkstyle + ArchUnit 检查
```

### 前端
```bash
cd frontend && npm install
npm run dev              # Vite 开发服务器（端口 5173）
npm run build:spring     # 构建并输出到 src/main/resources/static/spa/
```

### 配置
- `src/main/resources/application.yml`：公共配置
- `application-private.yml`：密钥配置（不提交 Git），包含 API Key、数据库密码等
- 数据库 schema：`sql/schema.sql`，初始数据：`sql/data.sql`
- `config/`：额外配置文件目录

---

## 架构概览

**AI 面试复习助手** — Spring Boot 3.3.6 + Spring AI 1.0.0-SNAPSHOT，集成多层 Agent、混合 RAG、IM 渠道的智能面试辅导系统。

### 根包结构：`io.github.imzmq.interview`（领域优先分包）

项目已从 type-first（`...service/controller/mapper`）迁移到 **domain-first package-by-feature**。新代码 MUST 按业务领域放置，禁止回退到旧方式。

| 领域包 | 职责 |
|---|---|
| `agent/` | Agent 编排、TaskRouter、A2A 事件总线 |
| `routing/` | 意图树分类、意图预过滤、澄清解析 |
| `knowledge/` | RAG 检索核心（含 indexing/localgraph/retrieval/context/evaluation/observability/catalog/chatstream 子包） |
| `interview/` | 面试编排门面 |
| `learning/` | 学习画像、学习事件（含 `domain` 子包） |
| `ingestion/` | 知识摄入配置与同步 |
| `chat/` | Prompt 模板、上下文压缩、对话记忆 |
| `media/` | 图片 Embedding/索引/图文关联检索 |
| `modelrouting/` | 多模型候选、优先级、三态熔断器 |
| `modelruntime/` | 动态模型运行时选择、健康检查、VLM |
| `identity/` | 用户身份提取 |
| `observability/` | Trace 属性清洗、RAG 链路追踪 |
| `mcp/` | FastMCP Java 实现，暴露 Neo4j/Milvus 工具 |
| `search/` | 自动补全与排序 |
| `menu/` | 菜单/工作区配置 |
| `im/` | IM 渠道集成（飞书、QQ WebSocket） |
| `skill/` | Skill 技能定义 |

### 分层约束（ArchUnit 强制）
- `api → application → domain`，`application → infrastructure`（通过注入接口）
- `domain` 禁止依赖 `api`
- Controller 禁止直接调用 Mapper
- 跨领域禁止直接访问 Mapper/Repository

### RAG 管线（`knowledge/` 包）
- **混合检索**：Milvus 向量 + MySQL 全文 + Neo4j 图谱，RRF 融合排序
- **父子索引**：文档分层切块（parent-child），检索子块后回溯父块补全上下文
- **知识摄入**：本地目录/浏览器上传 → Markdown 解析 → 结构化切块 → 多通道索引
- **图文联合召回**：`media.application` 提供视觉 Embedding + 图文关联召回
- **可观测性**：RAG Trace（`t_rag_trace` / `t_rag_trace_node`），Retrieval Evaluation（Recall@K、MRR）
- **评测**：检索评测（`RetrievalEvaluationService`）+ 生成质量评测（支持 java/ragas 双引擎）

### 意图路由链（四层降级）
1. `IntentPreFilter` — 规则预过滤（`/help`、`/clear`、确定性关键词）
2. 域裁决 — 归类到 `INTERVIEW/CODING/KNOWLEDGE/PROFILE` 业务域
3. `IntentTreeRoutingService` — 域内叶子意图小模型分类（`t_intent_node` 三级树）
4. 槽位精炼 — 抽取 topic/questionType/difficulty/count 等结构化参数
- 低置信/分差过小时触发澄清（`ClarificationResolver`），不硬判
- 意图树关闭或分类失败 → 回退 ReAct 路由

### 模型路由（`modelrouting/`）
- 多提供商候选 + 优先级，支持落库 `t_model_candidate`
- 三态熔断（CLOSED/OPEN/HALF_OPEN），首包超时自动降级
- LLM 默认走 DeepSeek（OpenAI 兼容接口），Embedding 走智谱 embedding-3

### 附属服务（Python sidecar）
- `clip-service/`：图片视觉向量（CLIP embedding）
- `eval-service/`：RAG 生成质量评测（Ragas）
- 两者通过 Docker Compose 可选启动

---

## 关键技术约定
- Embedding 维度 2048（智谱 embedding-3），Milvus collection 名 `interview_notes`
- 分块策略 `STRUCTURE_RECURSIVE_WITH_METADATA`（目标 550 / 上限 800 / 重叠 80）
- 意图树三级结构：domain → category → topic，存储在 `t_intent_node`
- 链路追踪使用 `TransmittableThreadLocal`（alibaba TTL）跨线程传递 traceId
- A2A 事件总线支持 InMemory / RocketMQ 切换（默认 InMemory，配置 `app.a2a.bus.type`）
- 父子文档模型：`t_rag_parent`（章节级）+ `t_rag_child`（检索块），检索后回填父文上下文

---

# Claude Code 核心规范

## 工作模式：Superpowers + AI 协作

### 角色分工
**Claude（架构师 / 项目经理）**：
- 需求分析、架构设计、任务拆分
- 使用 Superpowers 进行规划、审查、调试
- 代码审核、最终验收、Git 提交管理
- **绝对不亲自编写代码**，所有编码任务必须委派给 Codex 或 Gemini

**Codex — 后端开发**：服务端代码、API、数据库、Migration、单元测试、集成测试。通过 `/ask codex "..."` 调用。

**Gemini — 前端开发**：前端组件、页面、样式、交互逻辑、代码审查、安全审计。通过 `/ask gemini "..."` 调用。

### 降级机制
```
Codex 不可用 → Gemini 接管后端任务
Gemini 不可用 → Codex 接管前端任务
两者都不可用 → 暂停编码，等待恢复（Claude 不代写代码）
```
降级时在任务描述中注明"降级接管"。

### 协作方式
- 规划：`superpowers:writing-plans`
- 执行：`superpowers:executing-plans`
- 审查：`superpowers:requesting-code-review`
- 调试：`superpowers:systematic-debugging`
- 完成：`superpowers:finishing-a-development-branch`

```bash
/ask codex "实现 XXX 后端功能，涉及文件：..."
/ask gemini "实现 XXX 前端功能，涉及文件：..."
/pend codex    # 查看 Codex 执行结果
/pend gemini   # 查看 Gemini 执行结果
```

---

## Linus 三问（决策前必问）
1. **这是现实问题还是想象问题？** → 拒绝过度设计
2. **有没有更简单的做法？** → 始终寻找最简方案
3. **会破坏什么？** → 向后兼容是铁律

---

## Git 规范
- 提交信息：`<类型>: <描述>`（中文），类型：`feat`/`fix`/`docs`/`refactor`/`chore`
- **禁止**：force push、修改已 push 历史、`--no-verify`
- 合并前必须通过：`mvn -q compile`、`mvn test`、`mvn -q verify -DskipTests`
