# 模块化 Agent 架构改造设计

## 背景

Study-Agent 已有一轮 package-by-feature 清理成果位于 `refactor/codebase-structure-cleanup` 分支 / `.worktrees/codebase-structure-cleanup` 工作树；当前 `main` 仍可见顶级 `entity`、`mapper`、`dto` 等遗留包。因此，本设计的实施基线应明确为：先合入或等价应用前一轮结构清理，再执行本次“大模块重划分”。即使以前一轮清理后的代码为基线，顶级包仍偏多，`agent`、`knowledge`、`modelrouting`、`tool`、`im`、`mcp`、`security`、`config` 等职责边界仍不够稳定。继续开发更复杂的 Agent 能力时，容易出现业务编排、RAG、模型调用、外部工具适配互相穿透的问题。

本设计参考 `/Users/bytedance/bits/lark-hive-ai/lark-hive-ai/` 的架构治理方式，但不照搬多服务拆分。可借鉴点包括：明确模块 owner、public contract 与 internal implementation 分离、外部系统适配不进入领域模型、架构文档和规则与代码同步演进。

## 目标

- 保持单体 Spring Boot 应用，降低本地启动和联调复杂度。
- 将顶级包收敛为少数稳定大模块，适合后续开发“能动手干活”的 Agent。
- 为未来接入 Mastra 等 Agent Runtime 框架预留边界，但第一阶段不引入依赖、不创建空实现。
- 通过文档和 ArchUnit 规则约束跨模块依赖，避免重新变成松散包集合。

## 非目标

- 不拆分为 gateway/core-api/agent-runtime 多服务。
- 不在第一阶段引入 Mastra，不重构现有 MCP 能力为新框架，也不引入新的部署形态。
- 不重写业务逻辑，不改变 API 行为，不做数据库模型重构。
- 不创建没有真实调用方的空抽象、空目录或 placeholder implementation。

## 目标模块划分

目标根包仍为 `io.github.imzmq.interview`，第一阶段收敛为以下大模块：

| 模块 | 职责 |
| --- | --- |
| `interfaces` | HTTP Controller、IM/Webhook 入口、传输 DTO。只负责入站适配和响应转换，不放业务规则。 |
| `interview` | 面试主流程、会话、题目、反馈、学习闭环入口。 |
| `conversation` | Chat、Prompt、对话上下文、记忆、stream 输出协议。 |
| `agent` | Agent 任务生命周期、计划/步骤、runtime 抽象、执行编排、失败恢复、审计事件。 |
| `tools` | Agent 可调用动作的统一 contract、权限策略、安全校验、执行结果模型。 |
| `knowledge` | RAG、知识库 catalog、索引、检索、Graph、知识资料 ingestion、面向知识的 media 抽取。 |
| `model` | LLM/VLM provider、模型路由、健康探测、模型执行策略。 |
| `integration` | 外部系统 adapter/client：IM、MCP、Search、第三方 API、供应商协议封装。 |
| `platform` | Identity、Security、Config、Observability、Async/HTTP 等基础设施。 |
| `shared` | 极少量无业务语义的通用类型；禁止承载业务流程。 |

## 模块内部约定

较大的模块采用 public surface + internal implementation：

```text
<module>
├── <ModuleFacade>.java          # 推荐跨模块入口
├── api                          # 公开 command/query/result/DTO，可被其他模块依赖
├── internal
│   ├── application              # use case 编排
│   ├── domain                   # 领域模型、值对象、纯策略
│   ├── infrastructure           # persistence、client、adapter、framework glue
│   └── support                  # 模块内部辅助类
└── README.md                    # 模块职责、边界、常用入口
```

小模块可暂时不强制补齐所有目录。目录必须由真实代码驱动，禁止为了“看起来完整”创建空骨架。

## 旧包迁移映射原则

| 当前区域 | 目标区域 |
| --- | --- |
| `interview`, `feedback`, `learning` | `interview`，其中学习画像可作为 `interview.internal.learning` 或后续独立子域评估。 |
| `chat`, `stream` | `conversation`。 |
| `agent` | `agent`，保留为任务与 runtime 编排中心。 |
| `skill` 中“可执行能力契约” | `tools`；若只是外部协议适配则进入 `integration`。 |
| `tool`, `tools` | `tools` 或 `integration`，按“动作抽象”与“外部适配”拆分。 |
| `knowledge`, `rag`, `graph` | `knowledge`。 |
| `ingestion` | 知识资料导入进入 `knowledge.internal.ingestion`。 |
| `media` | 知识抽取相关进入 `knowledge.internal.media`；通用视觉/图像动作后续进入 `tools` 或 `model`。 |
| `modelrouting`, `modelruntime` | `model`。 |
| `im`, `mcp`, `search` | `integration.im`、`integration.mcp`、`integration.search`。 |
| `identity`, `security`, `observability`, `config` | `platform`。 |
| `common`, `core` | 逐项审计；无业务语义的进入 `shared`，其余移动到 owner 模块。 |

## 跨模块依赖规则

- 其他模块优先依赖目标模块的 `Facade` 或 `<module>.api`。
- 禁止跨模块引用 `<module>.internal..`。
- `interfaces` 可以依赖 facade/api，但不能直接依赖 mapper、repository 或外部 vendor client。
- `agent` 可以编排 `knowledge`、`tools`、`model`，但不直接持有供应商协议细节。
- `knowledge` 不反向依赖 `agent`，避免 RAG 成为系统中心。
- `integration` 只做协议和供应商适配，不拥有核心业务规则。
- `platform` 提供基础能力，不包含业务 use case。
- `shared` 必须保持很小；新增内容需要证明不属于任何业务模块。

## Agent Runtime 与 Mastra 边界

第一阶段不引入 Mastra。架构上仅明确：Agent Runtime 是 `agent` 模块内部的实现细节。

未来若接入 Mastra，推荐放置在：

```text
agent.internal.infrastructure.runtime.mastra
```

上层模块只依赖 `agent` 暴露的任务/执行 contract，不直接依赖 Mastra 类型。项目自己的 `AgentTask`、`Plan`、`Step`、`ToolCall`、`ExecutionState` 等模型应保持独立，Mastra adapter 负责类型转换。

## 第一阶段实施范围

0. 确认实施基线：若在 `main` 上实施，先合入或等价应用 `refactor/codebase-structure-cleanup` 的遗留 `entity`/`mapper`/`dto` 清理；若在该清理分支上继续，则先同步最新 `main` 并确认验证结果。
1. 更新架构文档和包约定，记录目标模块、迁移映射和依赖规则。
2. 补充或调整 ArchUnit 规则：禁止跨模块访问 `internal`，禁止 `interfaces` 直接依赖 persistence mapper/repository。
3. 分批迁移最影响开发理解的顶级包，建议优先顺序：`model`、`integration`、`platform`、`knowledge`、`tools/agent`；实际顺序以依赖扫描结果为准。
4. 每批迁移只移动 ownership 和 import，不改变业务行为。
5. 每批运行最小验证：`mvn -q compile`、相关 ArchUnit 测试；最后运行 `mvn -q verify -DskipTests`。

## 验证策略

- 架构规则用 ArchUnit 固化，避免迁移后回退。
- 编译验证用于发现 import 和 Spring bean 扫描问题。
- 当前环境下完整 `mvn test` 可能受 Mockito inline Byte Buddy self-attach 影响失败；实施时应区分环境问题和迁移引入的问题，并记录实际输出。

## 风险与缓解

- **一次性移动过多导致难以定位问题**：按模块小批次迁移，每批单独编译。
- **`shared` 变成新垃圾桶**：文档和 ArchUnit 限制 shared 只能放无业务语义类型。
- **Agent、Knowledge、Tools 边界混淆**：以问题归属判断，Agent 管“怎么执行任务”，Knowledge 管“查到什么资料”，Tools 管“能安全执行什么动作”。
- **未来框架绑架领域模型**：Mastra 等 runtime 只作为 adapter，不进入 public API/domain model。

## 待用户确认后的下一步

用户确认本设计后，进入实施计划编写阶段。计划应先选择实施基线，再拆成可验证的小任务，并明确每个任务的迁移包、改动文件、验证命令和回滚点。
