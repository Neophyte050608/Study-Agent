# Study-Agent AI Coding Guide

本文件是本仓库中 AI coding agent 的最高优先级项目级开发说明。`README.md` 负责项目概览，`ARCHITECTURE.md` 和 `PACKAGE_CONVENTIONS.md` 负责架构与包结构细则；真正开始改代码前，先读本文件。

## 项目心智模型

Study-Agent 是一个面向技术面试复盘、知识检索、刷题训练、学习画像和 RAG 评测的本地开发项目。

主要区域：

- `src/main/java/io/github/imzmq/interview`：Spring Boot 后端。
- `src/test/java/io/github/imzmq/interview`：后端自动化测试。
- `frontend/`：Vue 3 + Vite 前端。
- `sql/`：数据库建表和初始化脚本。
- `docker-compose.yml`：MySQL、Redis、Milvus、Neo4j 等本地依赖编排。
- `scripts/dev-start.sh`、`scripts/dev-stop.sh`：本地依赖、后端和前端的一键启停脚本。
- `clip-service/`、`eval-service/`：独立辅助服务。
- `docs/`、`ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md`：开发规范、架构说明和设计计划。

## 指令优先级

按以下顺序执行：

1. 当前用户请求中的显式约束。
2. 本文件。
3. `ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md`、`docs/development/*.md`。
4. `README.md` 和历史设计文档。

如规则冲突，优先遵循更靠近当前任务、更新更近期且更具体的规则，并在总结中说明取舍。

## 默认工作流

1. 修改前运行 `git status --short --branch`，确认不要覆盖用户未提交工作。
2. 识别本次影响范围：后端、前端、配置、数据库、文档或启动脚本。
3. 修改生产代码前阅读 `ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md` 和对应 `docs/development/*-guidelines.md`。
4. 行为变更默认先补测试，再实现最小改动。
5. 改完先跑最窄相关检查，再按影响范围跑更宽检查。
6. 如果改了启动方式、配置项、目录约定或架构边界，同步更新文档。

详细流程见 `docs/development/workflow.md`，验证命令见 `docs/development/verification.md`，一键启动见 `docs/development/local-startup.md`。

## 后端模块代码放置规范

后端采用 domain-first / package-by-feature。新增代码先判断“属于哪个业务能力”，再判断层次；不要先按 Controller、Service、Mapper、DTO 这种技术类型找位置。

### 基础分层

每个业务域优先使用以下结构，按真实需要创建，不预建空目录：

```text
io.github.imzmq.interview.<domain>.api              # Controller、HTTP request/response DTO
io.github.imzmq.interview.<domain>.application      # 用例编排、应用服务、应用内部 DTO
io.github.imzmq.interview.<domain>.domain           # 领域对象、值对象、纯业务规则
io.github.imzmq.interview.<domain>.infrastructure   # 持久化、外部系统 adapter/client
```

硬性规则：

- 不要新增业务代码到旧式顶层 `service`、`entity`、`mapper`、`dto` 包。
- Controller 不要直接调用 MyBatis Mapper 或外部 SDK。
- MyBatis DO 和 Mapper 放在 `<domain>.infrastructure.persistence`。
- Controller-facing DTO 放在 `<domain>.api`。
- application 内部 DTO 放在 `<domain>.application.dto`。
- domain 层不能依赖 Spring Web、Controller DTO、MyBatis DO、外部 SDK 或 infrastructure adapter。
- 不要为了“架构完整”创建空 port、空 adapter、空 facade、空目录。

### 当前模块 ownership

新增代码优先放入已有模块，除非确实出现新的稳定业务域：

- `agent`：Agent 编排、任务路由、运行时状态、子任务、技能调度入口。
- `chat`：对话、Prompt/template、聊天记忆、会话上下文、自动总结。
- `knowledge`：RAG、检索、切分、索引、知识库、知识文档、检索/生成评测。
- `modelrouting`：模型候选、模型路由、动态模型工厂、健康探测、熔断状态。
- `observability`：AI/RAG trace、脱敏、外部观测 adapter、启动/运行诊断。
- `im`：飞书/QQ 接入、消息解析、平台 runtime adapter、webhook/WS 入口。
- `ingestion`：知识导入、同步配置、导入任务、索引构建触发。
- `learning`：学习画像、能力曲线、遗忘/复习策略、学习轨迹。
- `mcp`：MCP capability gateway、MCP adapter、MCP 审计和降级。
- `tool`：可执行工具抽象、工具策略、工具运行时；不得承载具体业务状态机。
- `skill`：技能加载、技能执行策略、内置技能、技能运行时。
- `routing` / `intent`：意图树、意图路由、槽位/refine 规则。
- `search`：自动补全、热度、排序、搜索辅助能力。
- `media`：图片/多模态元数据、视觉向量、图文关联检索。
- `feedback`：反馈、指标快照、满意度等反馈闭环。
- `menu`：菜单、工作区配置和前端导航相关配置。
- `security`：认证授权、安全策略、限流等横切安全能力。
- `bootstrap`：启动诊断、启动模式、启动期只读装配信息；不要放业务流程。
- `config`：Spring Bean 装配与配置属性；不要放 use case 逻辑。
- `common`：通用 API 响应、异常、跨模块 stream 支撑、极少量无业务归属的基础设施；不要变成杂物区。

### Knowledge / RAG 细分

不要继续把新职责塞进 `knowledge.application.RAGService`；它只保留兼容入口和主流程门面。新增逻辑按职责放置：

- 检索编排、融合、query rewrite、RAG adapter：`knowledge.application.retrieval`
- 本地图谱链路、候选召回、笔记解析、图扩展：`knowledge.application.localgraph`
- 多轮上下文、topic state、动态上下文策略：`knowledge.application.context`
- 流式问答场景、stream packet/helper：`knowledge.application.chatstream`
- 刷题生成、评估、quiz fallback：`knowledge.application.coding`
- 离线评测、答案评分、证据校验：`knowledge.application.evaluation`
- RAG trace、事件总线、trace service：`knowledge.application.observability`
- 知识库、文档 catalog 查询和管理：`knowledge.application.catalog`
- 索引构建、chunk、tokenizer、lexical/parent-child index：`knowledge.application.indexing`

### Agent / Tool / Runtime 边界

为后续可能引入 Mastra 或独立 Agent Runtime 预留边界：

- `agent` 负责 AI 任务编排和运行时流程，不直接拥有 durable business state。
- 会创建、更新、完成、失败或审计持久业务状态的 Agent 行为，必须回到对应 `<domain>.application` 和 `<domain>.infrastructure.persistence`。
- `tool` / `skill` 只暴露可执行能力和策略，不在工具内部偷偷写业务状态机。
- 外部系统调用必须封装成 typed adapter/client；不要在 agent、tool、skill 中散写 ad hoc HTTP、SQL 或 SDK 调用。
- Controller 只调用 application service，不直接调用具体 Agent runtime、tool executor 或外部 SDK。
- 若未来引入独立 Agent Runtime，本项目内的 application service 仍是业务状态和权限校验入口。

后端细则见 `docs/development/backend-guidelines.md`；长期包结构规则见 `PACKAGE_CONVENTIONS.md`。

## 前端代码放置规则

前端采用 Vue 3 + Vite。

- `frontend/src/views`：页面级组件。
- `frontend/src/views/<domain>`：页面私有组件。
- `frontend/src/api`：HTTP 请求封装，只处理请求路径、参数和响应。
- `frontend/src/services`：前端业务组合逻辑。
- `frontend/src/composables`：可复用组合式状态逻辑。
- `frontend/src/router`：路由注册。

页面组件不要直接堆积复杂 API 编排、数据转换和业务判断；可下沉到 `services`、`composables` 或局部组件。

前端细则见 `docs/development/frontend-guidelines.md`。

## 本地启动

本地开发优先使用一键脚本：

```bash
bash scripts/dev-start.sh
bash scripts/dev-stop.sh
bash scripts/dev-stop.sh --with-docker
```

脚本会启动 Docker 依赖、后端 `local-lite` profile 和前端 Vite。详细说明见 `docs/development/local-startup.md`。

## 测试与验证

常用命令：

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn test
mvn -q verify -DskipTests

cd frontend && npm run build
```

修改架构边界时优先运行：

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

禁止声称检查通过，除非本轮会话实际运行过并确认退出码为 0。

## 文档维护

以下变更必须同步文档：

- 启动命令、依赖服务、环境变量变化。
- 后端包结构、模块职责、架构边界变化。
- 新增数据库表、外部服务、模型提供商或重要配置项。
- 前端页面结构、路由、API contract 变化。
- AI agent 开发规则或验证命令变化。
- 成熟框架、Agent Runtime、观测系统或长任务框架引入路线变化，见 `docs/architecture/framework-adoption-roadmap.md`。
- AI/RAG/Agent 可观测性事件、trace 字段或外部观测 adapter 变化，需同步 `docs/development/observability-guidelines.md`。

## Commit 约定

使用：

```text
type: subject
type(scope): subject
```

允许的 type：`feat`、`fix`、`refactor`、`test`、`docs`、`chore`。

示例：

```text
docs: 补充 AI coding 开发规范
refactor(knowledge): 拆分检索编排服务
```

不要添加 AI 生成 footer 或无意义的 `update/misc/stuff` 前缀。

## 禁止事项

- 不要提交 `.env`、密钥、token、真实账号凭证或本地私有配置。
- 不要提交 `target/`、`node_modules/`、日志、临时文件或本地构建产物。
- 不要为了“看起来架构完整”创建空抽象、空 port、空 adapter。
- 不要绕过现有包结构把新逻辑放到旧目录。
- 不要在未验证的情况下宣称“已完成”“测试通过”。
