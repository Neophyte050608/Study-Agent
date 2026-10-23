# Study-Agent 模块边界与包归属设计

## 1. 背景

Study-Agent 已完成从顶层 `service/entity/mapper/dto` 向 domain-first、package-by-feature 的第一轮迁移，但目前的顶层包仍存在三类结构性问题：

1. **包名与实际职责不一致**。通用聊天编排位于 `interview`，刷题逻辑位于 `knowledge`，面试评价与学习计划 Agent 位于通用 `agent`，trace 与指标又散落在 `knowledge`。
2. **跨模块依赖穿透 application 边界**。部分服务直接访问其他模块的 Mapper、DO 或内部 helper，导致持久化模型成为事实上的跨模块接口。
3. **ArchUnit 只覆盖少数基础规则**。当前规则能阻止 Controller 直接访问 Mapper，并阻止重新引入旧式顶层包，但尚未完整约束模块 ownership、跨模块持久化访问、反向依赖与依赖环。

继续只做物理搬包，会保留上述耦合；立即拆 Maven 多模块，又会把职责治理与构建系统改造绑在一起。因此，本设计选择在现有 Spring Boot 模块化单体内重新明确 ownership，以 application 公开契约隔离模块，并逐步用 ArchUnit 固化边界。

## 2. 目标

- 保持单体 Spring Boot 和单 Maven 模块，不改变当前部署形态。
- 按业务能力确定顶层模块，不按 Controller、Service、Mapper 等技术类型横向分包。
- 让每个重要类都有唯一 owner；类名、包名与实际业务职责一致。
- 跨模块调用只依赖 owner 提供的 `application.contract`，不访问对方 Mapper、DO、infrastructure 或内部 helper。
- 把通用执行机制与面试、刷题、学习、知识问答等业务实现分开。
- 用可增量启用的 ArchUnit 规则阻止新增越界，并随迁移逐项清除历史例外。
- 让每批迁移独立可编译、可测试、可回退，不进行一次性全仓搬迁。

## 3. 非目标

- 本阶段不拆 Maven 多模块，不拆微服务。
- 不重写业务流程，不改变数据库表结构、HTTP API 或前端 SSE 协议。
- 不在本次包治理中实现 DAG、竞速、replan 或新的 Agent Runtime。
- 不引入新的通用 `ai`、`platform`、`shared` 等大而泛的顶层模块。
- 不为了形式完整创建空 facade、空 port、空 adapter 或空目录。
- 不要求每个模块机械拥有完整的 `api/application/domain/infrastructure` 四层；只有真实职责需要时才创建对应包。
- 不在搬包过程中顺便删除行为不明确的代码。删除候选必须单独证明无调用方、无框架扫描入口且验证通过。

## 4. 方案比较与决策

### 方案 A：只做物理搬包

根据当前类名和目录调整 package，更新 import，不改变模块交互方式。

- 优点：改动小，短期编译风险低。
- 缺点：跨模块 Mapper、职责错位和反向依赖仍然存在；新包只是旧耦合的新名字。
- 结论：不采用。

### 方案 B：职责归属 + application 公开契约 + ArchUnit

先定义模块 owner 和公开能力，再用公开契约替换越界访问，最后移动物理包并收紧 ArchUnit。

- 优点：真正修复边界；可小步迁移；不增加部署和构建复杂度。
- 缺点：迁移初期会存在兼容 facade 和少量 ArchUnit 例外，需要持续收敛。
- 结论：**采用。**

### 方案 C：同步拆 Maven 多模块

每个业务模块建立独立 Maven module，通过编译器隔离依赖。

- 优点：边界最强，错误依赖可在编译期暴露。
- 缺点：会同时改变构建、测试、Spring 扫描、资源加载和本地开发方式；当前收益不足以覆盖成本。
- 结论：后置。只有模块边界稳定且团队确有独立构建需求时再评估。

## 5. 目标模块地图

```text
io.github.imzmq.interview
├── chat                 # 通用会话、消息、对话上下文、流式聊天编排
├── interview            # 面试生命周期、回答评价、报告
├── practice             # 刷题、代码练习、作答评价
├── learning             # 学习画像、学习事件、学习计划
├── knowledge            # 目录、索引、检索、证据、知识图谱
├── feedback             # 用户显式反馈用例
│
├── routing              # 意图理解、澄清、路由决策
├── agent                # 通用任务执行机制、生命周期与上下文装配机制
├── skill                # Skill 契约、注册与调用机制
├── tool                 # Tool 契约、注册与执行机制
├── mcp                  # MCP 协议接入
│
├── modelrouting         # 模型选择、调用、故障转移、结构化输出
├── observability        # trace、指标、运行观测与查询
├── ingestion            # 文档摄取管线
├── media                # 图片等媒体能力
├── search               # 跨业务搜索与排序能力
├── im                   # 即时消息渠道适配
├── identity             # 身份解析与当前用户能力
├── security             # 认证、授权与安全策略
├── menu                 # 菜单和工作区配置
│
├── common               # 无业务语义的共享技术设施
├── bootstrap            # 启动与装配
└── config               # 过渡配置区；业务配置逐步回归 owner
```

最终消除以下模糊顶层包：

- `intent` 合并到 `routing.domain`。
- `graph` 按职责进入 `knowledge.domain` 或 `knowledge.infrastructure.graph`。
- `rag` 按具体 owner 分到 `knowledge`、`interview` 或相应配置包，不保留模糊顶层。

## 6. 模块内部结构与公开面

### 6.1 推荐结构

```text
<module>/
├── api/                          # Controller 和 Controller-facing DTO
├── application/
│   ├── contract/                 # 唯一允许的跨模块业务入口
│   │   ├── command/              # 跨模块命令参数；按需创建
│   │   ├── query/                # 跨模块查询参数；按需创建
│   │   └── result/               # 稳定结果类型；按需创建
│   ├── dto/                      # 模块内部 application DTO
│   └── <use-case>/               # 用例实现与内部编排
├── domain/                       # 实体、值对象和纯业务策略
└── infrastructure/
    ├── persistence/              # MyBatis DO、Mapper 和持久化 adapter
    └── <adapter>/                # 外部系统和框架 adapter
```

`application.contract` 是跨模块稳定公开面，不等于“为每个 service 创建接口”。只有存在真实跨模块调用方时才增加契约。模块内部调用不必绕经 facade，也不创建没有调用者的抽象。

### 6.2 契约规则

- 契约由能力 owner 定义，调用方不能把自己的 DTO 强加给 owner。
- 契约使用业务语言，不暴露表名、DO、MyBatis 分页对象、供应商 SDK 类型或 Spring Web 类型。
- 命令表达写入意图，查询表达读取意图；结果类型保持稳定、最小且不可泄漏持久化细节。
- 契约实现留在 owner 的 application 层，持久化和外部调用由 owner 自己完成。
- 同一 JVM 内优先同步调用 application contract；只有现有业务确实需要异步语义时才使用消息，不为解耦而强行事件化。
- `common` 不承载业务契约。跨模块类型仍归提供能力的业务模块所有。

## 7. 模块职责与公开能力

| 模块 | 拥有的职责 | 建议公开的 application contract | 不拥有的职责 |
| --- | --- | --- | --- |
| `chat` | 会话、消息、通用对话上下文、消息级流式编排、场景扩展点 | 会话创建/查询、消息追加/读取、对话上下文查询、聊天流式用例 | 面试评分、刷题出题、知识检索实现、任务运行时 |
| `interview` | 面试开始、答题、评价、报告、简历材料、面试专属 Agent | start/answer/evaluate/report 用例 | 通用聊天表、通用 Agent runtime |
| `practice` | 题目生成、代码练习、答案评价、下一题、练习会话 | generate/submit/evaluate/next 用例 | 知识库索引、通用聊天编排 |
| `learning` | 学习画像、事件、薄弱点、学习计划 | profile/event/plan 查询与更新 | 通用 Agent runtime、面试评价 |
| `knowledge` | 知识目录、摄取后的索引、检索、证据、GraphRAG | `KnowledgeRetrievalService`、catalog/indexing/query 契约 | 聊天场景总调度、刷题生命周期、通用 trace 平台 |
| `feedback` | 用户显式点赞、点踩、原因和反馈用例 | submit/query feedback | trace 存储、RAG 指标存储 |
| `routing` | 快车道、意图树、关系判断、澄清与统一理解结果 | `UnderstandingService`，返回 `IntentRecord` | 任务执行、业务 Handler 实现 |
| `agent` | Task/Handler 契约、注册、执行生命周期、取消、A2A、通用上下文装配机制 | `TaskExecutionService`、handler/context provider 扩展契约 | 面试/刷题/学习的具体 Agent 行为 |
| `skill` | Skill 契约、注册、选择、调用机制 | Skill provider/execution contract | 各业务 Skill 的业务 ownership |
| `tool` | Tool 契约、权限校验、注册和执行机制 | Tool execution contract | MCP 协议细节、业务流程 |
| `mcp` | MCP client/server 协议适配和审计接入 | MCP gateway contract | 通用 Tool 领域规则 |
| `modelrouting` | 模型候选、健康、路由、调用、重试、结构化输出 | model execution 与 structured output contract | 聊天或业务场景判断 |
| `observability` | trace、span、运行指标、质量指标和观测查询 | 记录事件、写 trace、查询 trace/metrics | 知识检索和用户反馈业务 |
| `common` | SSE 连接、通用序列化、时钟等无业务语义设施 | 必要的技术 API | 任务状态、消息状态、业务 DTO |

## 8. 关键类与类簇迁移清单

本节决定 ownership，不要求在一个提交内完成。实施前应再次核对实际调用链；若源码行为发生变化，以“谁拥有业务不变量”作为最终判断标准。

### 8.1 整类迁移到 `chat`

| 当前类或区域 | 目标位置 | 原因 |
| --- | --- | --- |
| `interview.api.ChatController` | `chat.api` | HTTP 入口提供通用聊天能力，不是面试专属 API |
| `interview.application.WebChatService` | `chat.application` | 管理通用聊天会话与消息 |
| `interview.application.ChatStreamingService` | `chat.application` | 是通用聊天主入口和流式编排 |
| `interview.application.TaskResponsePresentationService` | `chat.application` | 将通用任务结果转换为聊天输出 |
| `knowledge.application.chatstream.ChatScenarioHandlerRegistry` | `chat.application` | 场景扩展注册表属于聊天中枢 |
| `knowledge.application.chatstream` 中通用 context/support/stream 类 | `chat.application` | 不应由知识模块拥有通用对话分发基础设施 |
| `knowledge.application.context.ConversationTopicTracker` | `chat.application.context` | 维护通用对话话题状态 |
| `knowledge.domain.DialogAct` | `chat.domain` | 表达通用对话行为 |
| `knowledge.domain.TopicState` | `chat.domain` | 表达通用话题状态 |
| `knowledge.domain.TurnAnalysis` | `chat.domain` | 表达通用 turn 分析结果 |

`ChatScenarioHandler` 的扩展契约由 `chat` 提供，但具体实现回到业务 owner：面试 Handler 属于 `interview`，刷题 Handler 属于 `practice`，知识问答 Handler 属于 `knowledge`。Spring 可以发现这些实现，`chat` 不直接 import 具体业务实现。

### 8.2 新建 `practice` 并迁移刷题能力

| 当前类或区域 | 目标位置 | 说明 |
| --- | --- | --- |
| `interview.api.CodingPracticeController` | `practice.api` | 刷题是独立业务生命周期 |
| `knowledge.application.coding.CodingPracticeService` | `practice.application` | 出题、答题、评价不属于知识检索模块 |
| `agent.runtime.CodingPracticeAgent` | `practice.application.agent` | 具体业务 Agent 由业务 owner 实现 |
| `agent.router.handler.CodingPracticeTaskHandler` | `practice.application.handler` | 实现 `agent` 提供的 Handler 契约 |
| 刷题专属 context attributes/source/schema | `practice.application.context` | `agent` 只保留通用装配机制 |
| 刷题场景 Handler | `practice.application.chat` | 实现 `chat` 提供的场景契约 |
| `CodingInterviewCoachSkill` 等刷题实现 | `practice.infrastructure.skill` | 业务实现归 owner，并实现 `skill` 契约 |

若某个 Skill 确实面向多个业务域，契约和通用实现可留在 `skill`；仅被刷题使用且包含刷题规则的实现必须回到 `practice`。

### 8.3 迁移到 `interview`

| 当前类或区域 | 目标位置 | 说明 |
| --- | --- | --- |
| `agent.runtime.EvaluationAgent` | `interview.application.agent` | 评价面试答案 |
| `agent.runtime.KnowledgeLayerAgent` | `interview.application.agent` | 为面试评价收集证据，而非通用 Knowledge Agent |
| `agent.runtime.RollingSummaryAgent` | `interview.application.agent` | 维护面试会话滚动摘要 |
| `agent.runtime.InterviewOrchestratorAgent` | `interview.application.agent` | 编排面试流程 |
| 面试专属 TaskHandler | `interview.application.handler` | 实现通用 Agent Handler 契约 |
| `knowledge.application.evaluation.InterviewAnswerEvaluationService` | `interview.application.evaluation` | 面试答案评价由 interview 拥有 |
| `rag.core.ResumeLoader` | `interview.infrastructure.document` | 简历文档是面试输入材料 |
| 面试专属 ContextSource/schema | `interview.application.context` | 业务 schema 不应内置在通用 agent 中 |
| 面试报告 Skill 实现 | `interview.infrastructure.skill` | 面试业务实现归 interview |

### 8.4 迁移到 `learning`

| 当前类或区域 | 目标位置 | 说明 |
| --- | --- | --- |
| `agent.runtime.NoteMakingAgent` | `learning.application.agent` | 当前实际职责是生成学习计划和学习内容 |
| 学习专属 TaskHandler | `learning.application.handler` | 实现 `agent` 扩展契约 |
| learning ContextSource/schema | `learning.application.context` | 业务上下文由 owner 提供 |
| Personalized planner Skill 实现 | `learning.infrastructure.skill` | 保留通用 Skill 契约，迁移具体业务规则 |

`LearningProfileAgent` 已归学习域时保持原位，只按新的 application/internal 规则整理依赖。

### 8.5 合并到 `routing`

| 当前类或区域 | 目标位置 | 说明 |
| --- | --- | --- |
| 顶层 `intent.domain` | `routing.domain` | 意图模型与路由决策属于同一能力边界 |
| 当前 `routing` 代码 | `routing` 内部分层 | 保留快车道、意图树和澄清能力 |
| 新 `IntentRecord` | `routing.application.contract.result` | 作为跨模块统一理解结果，直接归 routing 的公开 contract；routing 内部若需要更丰富模型，另用 domain 类型并在边界转换 |
| 新 `UnderstandingService` | `routing.application.contract` | 对聊天中枢提供统一理解能力 |

未来不创建顶层 `turn.understanding`。理解能力归 `routing`；未来确需规划器和执行计划时归 `agent`。

### 8.6 保留在 `agent` 的通用机制

- 通用 Agent、Task、Handler 契约。
- `TaskHandlerRegistry` 和 Handler 注册机制。
- 任务执行生命周期、取消和状态转换。
- A2A 契约与基础实现；协议适配可进入 `agent.infrastructure`。
- 通用 ContextAssembler、slot、schema provider 和 context source provider 契约。
- 未来 planner/runtime 的 ownership；未实施前不创建空目录或占位接口。

以下内容不能继续留在通用 `agent`：面试、刷题、学习、知识问答专属 Agent，业务 TaskHandler，硬编码业务 schema，以及直接访问业务 Mapper 的 runtime 实现。

### 8.7 保留与拆分 `knowledge`

保留：

- `catalog`
- `indexing`
- `retrieval`
- `localgraph`
- `KnowledgePacketBuilder`
- 检索离线评测
- GraphRAG 领域模型和 adapter
- `RAGService` 过渡 facade；新代码继续优先使用聚焦服务

拆分：

- `knowledge.application.chatstream`：通用框架迁 `chat`，知识问答 Handler 留 `knowledge`。
- `knowledge.application.coding`：迁 `practice`。
- `knowledge.application.context`：通用话题状态迁 `chat`，知识证据构造留 `knowledge`。
- `DynamicKnowledgeContextBuilder`：拆成 chat 对话信号输入与 knowledge 证据构造，不整类盲目搬迁。
- `knowledge.application.evaluation`：检索/生成评测留 `knowledge`，面试答案评价迁 `interview`。
- `knowledge.application.observability`：通用 trace 与指标迁 `observability`。

### 8.8 迁移到 `modelrouting`

当前放在 `chat.application`、但被多个 AI 模块复用的结构化模型输出能力迁到 `modelrouting.application.structured`：

- `LlmJsonParser`
- `SchemaSpec`
- `RetryCall`
- `JsonResult`

这样消除当前 `modelrouting -> chat` 的反向依赖。迁移后，chat、routing、agent 等模块通过 modelrouting 的公开 contract 使用结构化输出能力。

### 8.9 迁移到 `observability`

- `RAGObservabilityService`
- `TraceService`
- 通用 trace/span 模型和持久化
- `RagTraceStreamController`
- 当前位于 `interview.application`、实际跨域的 `ObservabilityApplicationService`
- 通用质量指标、模型调用指标和运行观测查询

`observability` 不直接读取 `knowledge` Mapper。业务模块通过观测记录契约发布 trace/metric，观测查询由 `observability` 自己的存储完成。用户显式反馈继续由 `feedback` 拥有；若反馈需要关联 trace，只保存稳定 trace ID 或调用 observability 查询契约。

### 8.10 需要拆职责或单独核实的类

| 类或区域 | 处理方式 |
| --- | --- |
| `TaskRouterAgent` | 拆分意图理解、任务选择、A2A/执行和观测职责；理解归 `routing`，执行归 `agent`，具体 Handler 归业务 owner |
| `TaskRequest` | 迁移期保留兼容；逐步用 typed command/result 替代 `TaskType + Map payload + Map context`，不在纯搬包提交中改行为 |
| `agent.application.context.AgentContextSchema` | 保留通用 schema 机制；面试、刷题、学习、知识专属 schema 由业务模块注册 |
| `StreamTaskManager` | `common.stream` 只保留 SSE 连接和传输；任务取消/生命周期归 `agent`，消息状态归 `chat`；多实例状态外置另立设计 |
| `graph.domain.TechConcept*` | GraphRAG 模型归 `knowledge`；带 Spring Data 依赖的 repository 接口/实现归 `knowledge.infrastructure.graph`，不得伪装成纯 domain |
| `rag.core.VectorStoreConfig` | 实施时核实真实使用；若为空壳且无框架作用，按死代码规则单独删除，否则迁 owner 配置包 |

## 9. 依赖规则

### 9.1 允许的依赖

```text
同一模块：
api -> application
application -> domain
infrastructure -> application contract / domain

跨模块：
caller -> owner.application.contract
business module -> agent/skill/tool 的扩展契约
business module -> observability 的记录契约
bootstrap/config -> 各模块公开装配点（仅启动装配）
```

infrastructure 可以实现所属模块定义的 port，但 domain 不能反向依赖 infrastructure。业务模块需要另一个模块的数据时，应调用 owner 的 query contract，不应共享 Mapper。

### 9.2 禁止的依赖

- 跨模块访问 Mapper、DO、repository implementation 或其他 `infrastructure` 类型。
- 跨模块访问 `application.contract` 以外的 use case 实现、内部 DTO 或 helper。
- Controller 直接调用 Mapper、外部 SDK、Agent runtime 或 Tool executor implementation。
- domain 依赖 Spring Web、MyBatis、Controller DTO、外部 SDK 或 infrastructure adapter。
- `common` 依赖任何业务模块。
- `modelrouting` 依赖 `chat`。
- `observability` 依赖 `knowledge`。
- `knowledge` 依赖具体业务 Agent 实现。
- `agent` runtime 直接 import 所有业务 Agent 或具体 Handler 实现。
- 任意顶层模块形成依赖环。

### 9.3 已确认的越界依赖及替换方向

| 当前越界 | 替换方式 |
| --- | --- |
| `interview.application.WebChatService -> chat Mapper` | 先增加 chat 会话/消息 command/query contract，再迁 WebChatService 到 chat |
| `media.ImageService -> knowledge.RagParentMapper` | 通过 knowledge 索引或文档查询 contract |
| `search.PersonalRanker -> learning.UserKnowledgeStateMapper` | 通过 learning profile/state query contract |
| `feedback.* -> knowledge trace/feedback/metrics Mapper` | trace/metrics 迁 observability；feedback 通过稳定 ID 和公开查询契约协作 |
| `agent.RollingSummaryAgent -> interview.InterviewSessionMapper` | Agent 实现迁 interview，并由 interview application 管理会话 |
| `modelrouting.VisionModelService -> chat.LlmJsonParser` | 结构化输出解析迁 modelrouting |

替换顺序必须是“先建立真实契约和测试，再切调用方，最后移动或删除旧实现”，不能先搬包后用临时 public helper 维持穿透。

## 10. ArchUnit 增量约束

### 10.1 M0：刻画现状并冻结新增违规

新增架构测试时，不要求历史违规一次清零。采用显式例外清单：

- 建立“跨模块不得访问 `infrastructure.persistence`”规则。
- 对已确认的历史边逐条 `ignoreDependency` 或使用等价的明确 allowlist。
- 例外必须写出源类、目标类和所属迁移里程碑，禁止使用整包忽略。
- 新增越界不会命中 allowlist，因此测试立即失败。
- 每修复一条历史边，同一提交删除对应例外。

这比长期冻结一份不透明快照更可维护，也能在 review 中看到技术债是否收敛。

### 10.2 分阶段启用规则

1. `modelrouting` 不得依赖 `chat`。
2. `interview` 不得访问 `chat.infrastructure`。
3. `agent` 不得依赖业务模块的 infrastructure。
4. `observability` 不得依赖 `knowledge`。
5. `common` 不得依赖业务包。
6. 各业务模块不得访问其他模块的 Mapper、DO 和 infrastructure。
7. 跨模块业务依赖只能指向目标模块的 `application.contract`。
8. Controller 只能依赖所属模块 application 和必要的无业务技术设施。
9. domain 不得依赖 api、infrastructure、Spring Web、MyBatis 或供应商 SDK。
10. 对顶层业务 slice 执行 cycle 检查；待历史环清理后启用为硬规则。

### 10.3 规则作用域

- 生产代码纳入检查，测试 fixture 单独验证规则本身。
- `bootstrap` 和纯 Spring 装配配置可引用公开实现装配点，但不能借此承载业务调用。
- generated code、框架代理和明确的序列化类型按最小范围排除。
- 不允许用扩大排除包的方式“修复”失败；新增例外必须绑定具体迁移任务。

## 11. 分阶段迁移

### M0：边界基线

- 写入本设计并同步 `ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md` 的目标规则。
- 增加现状刻画型 ArchUnit 测试与精确历史例外。
- 记录顶层模块依赖图和已知跨模块 Mapper/DO 边。
- 不移动生产类，不改变行为。

**退出条件**：架构测试能阻止新增同类违规；历史例外均有 owner 和后续里程碑。

### M1：统一 routing ownership

- 将 `intent` 合并到 `routing`。
- 把 `IntentRecord`、`UnderstandingService` 放入 routing 的 domain/contract。
- 调整 turn understanding 设计中的 package ownership，不改变影子模式和回退行为。

**退出条件**：没有新的顶层 `turn` 或 `intent`；现有路由测试通过。

### M2：修复 modelrouting 反向依赖

- 将通用结构化输出解析迁到 `modelrouting`。
- 切换 `VisionModelService`、chat、routing 等调用方。
- 启用 `modelrouting` 不得依赖 `chat` 的硬规则。

**退出条件**：反向依赖清零；相关模型调用和解析测试通过。

### M3：修正 chat ownership

- 先为会话、消息和上下文建立必要 contract。
- 消除 `interview -> chat Mapper`。
- 迁移 ChatController、WebChatService、ChatStreamingService 和通用场景注册机制。
- 将具体场景 Handler 保留在业务 owner，通过 chat 扩展契约注册。

**退出条件**：通用聊天入口归 chat；interview 不访问 chat persistence；SSE 对外协议不变。

### M4：建立 practice 模块

- 新建 `practice`，迁移 Controller、CodingPracticeService、Agent、Handler 和专属 context/schema。
- 保持数据库表、HTTP 路径和前端契约不变。
- 增加 practice 模块边界测试。

**退出条件**：刷题生命周期可从 practice 单独理解；knowledge 和 interview 不再拥有刷题业务编排。

### M5：面试专属能力回归 interview

- 迁移 EvaluationAgent、KnowledgeLayerAgent、RollingSummaryAgent、InterviewOrchestratorAgent。
- 迁移面试评价、简历加载、面试 ContextSource/schema 和报告 Skill 实现。
- 用 interview application contract 替代任何跨模块 Mapper 访问。

**退出条件**：通用 agent 不再依赖 interview persistence 或具体面试实现。

### M6：精简 agent 与注册式扩展

- agent 只保留通用机制。
- 业务 Handler、schema、context source 和 Skill 实现由 owner 注册。
- 逐步替换 `TaskRequest` 中无类型的 Map；行为变化应拆成独立提交和测试。

**退出条件**：新增业务域无需修改 agent runtime 的业务分支或硬编码 schema。

### M7：清理 knowledge、observability、graph 与 rag

- 拆分 knowledge 中的 chatstream/context/evaluation/observability 职责。
- 将通用 trace 与指标迁 observability，并清除 `observability -> knowledge`。
- 将 GraphRAG 类型归 knowledge 的 domain/infrastructure。
- 审计 `rag.core`，按 owner 迁移；删除候选单独验证。
- 修复 media、search、feedback 的跨模块 Mapper 访问。

**退出条件**：knowledge 只拥有知识目录、索引、检索、证据和图谱；模糊顶层 `graph/rag` 消失。

### M8：全面收紧边界

- 删除所有迁移 allowlist。
- 启用跨模块仅依赖 `application.contract` 的最终规则。
- 启用模块 cycle 硬规则。
- 同步最终架构文档和包放置清单。

**退出条件**：ArchUnit 无历史例外；目标模块图与源码一致。

## 12. 验证策略

每个里程碑先运行最窄相关检查，再扩大范围：

```bash
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q compile
mvn -q -DskipTests test-compile
mvn test
mvn -q verify -DskipTests
```

按影响范围补充：

- 搬 Controller 或 API：对应 MockMvc/API 测试。
- 搬 Spring bean：上下文加载或聚焦 slice 测试，确认 bean 扫描和注入未改变。
- 搬 Mapper/DO：Mapper 测试或相关集成测试，确认表名、SQL 和扫描路径未改变。
- 搬 streaming：SSE 事件顺序、取消和错误事件测试。
- 搬 routing/agent：路由、Handler 注册、上下文装配和回退测试。
- 搬前端可见契约时才运行 `cd frontend && npm run build`；纯后端包移动不制造无关前端改动。

任何命令只有在当次迁移实际运行且退出码为 0 时才能写入完成报告。环境故障与代码失败应分开记录，不能把未运行或失败描述为通过。

## 13. 回滚策略

- 每个里程碑拆成独立提交；一个提交只处理一个 owner 或一组紧密依赖的边。
- 先引入兼容 contract，再切调用方，最后移动/删除旧入口。调用切换前后允许短期双入口，但必须有清理里程碑。
- 不同时修改数据库 schema、HTTP 路径和 package ownership。
- Spring bean 名称或配置键若被外部依赖，迁移期保留显式兼容。
- 任何阶段出现行为回归时，回退该阶段提交即可恢复旧链路；不依赖跨阶段数据库迁移。
- 既有理解层 shadow mode、feature flag 和降级路径保持不变。

## 14. 风险与缓解

| 风险 | 影响 | 缓解措施 |
| --- | --- | --- |
| 一次移动过多类 | 编译和 Spring 注入问题难定位 | 严格按 M0-M8 和 owner 小批次迁移 |
| `application.contract` 膨胀 | 形成新的共享 DTO 垃圾桶 | 只为真实跨模块调用建立最小 command/query/result；定期检查调用方 |
| 为解耦创建大量空接口 | 增加无价值抽象 | 契约必须有真实调用方和实现；同模块内部不强制接口化 |
| 业务 Handler 迁出 agent 后注册失败 | 运行时找不到能力 | 保留基于 Spring collection/registry 的显式注册测试 |
| package move 改变 bean 名称 | 配置或注入失败 | 优先按类型注入；必要时迁移期固定 bean name |
| trace 与 feedback 拆分后查询变复杂 | 看板或反馈关联失效 | 用稳定 trace ID 关联，查询经 observability contract 完成 |
| ArchUnit 初期失败过多 | 团队绕过规则 | 使用精确历史 allowlist，逐条删除，不做整包排除 |
| 旧设计文档与新 ownership 冲突 | 后续开发继续按旧包放置 | 在相关文档注明本设计覆盖 package ownership，行为设计继续有效 |
| 进程内 StreamTaskManager 限制多实例 | 取消和 SSE 状态无法跨实例 | 本设计只明确 ownership；状态外置另立设计，不混入搬包 |

## 15. 与现有设计文档的关系

本设计是 package ownership 与模块依赖方向的最新依据。它覆盖旧文档中与包归属冲突的部分，但不自动推翻已确认的行为设计。

| 现有文档 | 本设计的处理 |
| --- | --- |
| `2026-06-27-modular-agent-architecture-design.md` | 其“模块边界、公开契约、runtime 不绑框架”的原则保留；其中 `interfaces/conversation/model/integration/platform/shared` 的大模块归并方案不再作为目标 ownership，以本设计模块地图为准 |
| `2026-06-27-rag-service-decomposition-design.md` | RAG 聚焦服务拆分继续有效；服务归属按本设计重新落到 knowledge/interview/observability |
| `2026-06-28-coding-practice-decomposition-design.md` | 刷题行为拆分继续有效；最终 owner 改为独立 `practice` |
| context assembly 系列设计 | 通用装配机制留 agent；业务 attributes/source/schema 由 interview/practice/learning/knowledge 注册 |
| `2026-07-01-tool-task-context-foundation-design.md` | Tool task 通用契约保留；具体业务 ContextSource 不进入 agent 核心 |
| `2026-06-29-stream-runtime-package-migration-design.md` | 传输支撑留 `common.stream`；任务状态归 agent，消息状态归 chat |
| `2026-10-23-turn-understanding-incremental-design.md` | shadow mode、开关回退、量化验收和 M0-M6 小步替换保留；`turn.understanding` 改归 `routing`，未来 planning/execution 归 `agent`，不创建顶层 `turn` |

若后续文档再次讨论包位置，应引用本设计和 `PACKAGE_CONVENTIONS.md`，不能建立第二套 ownership 真相源。实施 M0 时，应把本设计中的稳定结论同步进 `PACKAGE_CONVENTIONS.md`，后者继续作为日常放置规则的唯一细则来源。

## 16. 完成标准

本轮架构治理完成时，应同时满足：

1. 顶层模块与本设计的目标模块地图一致。
2. 通用 chat、routing、agent 与具体业务实现分离。
3. 刷题拥有独立 `practice` owner。
4. `knowledge` 不再承担通用聊天、刷题和通用观测职责。
5. 跨模块 Mapper、DO、infrastructure 和 internal helper 访问为零。
6. 跨模块业务依赖只进入 owner 的 `application.contract`。
7. ArchUnit 不依赖历史 allowlist，且能阻止依赖环和新增越界。
8. 现有数据库、HTTP API 和前端 SSE 行为保持兼容。
9. `ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md` 与实际代码一致。
10. 每个迁移里程碑都有实际执行并记录的编译、测试和架构验证结果。
