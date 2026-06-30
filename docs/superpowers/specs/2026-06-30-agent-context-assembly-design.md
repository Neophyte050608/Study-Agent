# Agent 上下文装配改造设计

## 背景

当前 Study-Agent 的 prompt 上下文由多个业务服务各自拼接：`ChatContextCompressor` 负责会话摘要和近期对话，`DynamicKnowledgeContextBuilder` 负责知识问答上下文，`LearningProfileAgent.snapshotForPrompt()` 负责学习画像，`KnowledgeQaAgent`、`InterviewOrchestratorAgent`、`CodingPracticeAgent` 再分别组装变量并调用 `PromptManager`。这种方式短期可用，但随着 Agent 从 RAG 问答扩展到工具执行、多步任务和长期记忆，容易出现上下文来源重复、顺序不稳定、预算不可控、调试困难的问题。

AGI-saber 的 `internal/domain/promptctx` 提供了可借鉴的 Schema-driven Runtime Context Assembly：按 mode 选择 schema，再通过 slot/source 填充上下文。Study-Agent 不直接移植 Go 代码，而是在现有 Java domain-first 结构下设计轻量版本。

## 目标

- 建立统一的 Agent 上下文装配模型，减少各业务 Agent 手写 prompt 拼接。
- 用明确的 slot 表达上下文来源和优先级，保证输出顺序稳定。
- 支持字符预算裁剪，为后续 token budget、Langfuse trace 和 Agent Runtime 做准备。
- 第一轮只设计，不改生产代码；后续优先从 Knowledge QA 试点。

## 非目标

- 不重写 `PromptManager`、模板表或现有 prompt 模板。
- 不立刻迁移面试、刷题、RAG 评测等所有链路。
- 不引入 Mastra、LangChain 等 Agent Runtime 框架。
- 不新建空 adapter 或空 source；实现时只创建被接入链路实际使用的组件。

## 推荐包结构

后续实现建议放在：

```text
io.github.imzmq.interview.agent.application.context
```

原因：该能力服务于 Agent 推理运行时，而不是普通工具类；暂不放入 `common`，避免过早泛化。若未来独立 Agent Runtime 成熟，可再整体迁移到 `agent.application.runtime.context`。

建议类型：

```text
AgentContextAssembler
AgentContextSchema
AgentContextMode
AgentContextSlot
AgentContextSlotKind
AgentContextSlotFilter
AgentContextSource
AgentContextQuery
AgentRuntimeContext
FilledContextSlot
ContextItem
```

## 核心模型

### Mode

首批支持：

- `KNOWLEDGE_QA`：知识问答。
- `INTERVIEW`：面试评估/追问，先预留，不在第一批实现中接入。
- `CODING_PRACTICE`：刷题训练，先预留。
- `TOOL_TASK`：未来工具任务，先预留。

### Slot

首批 slot：

- `PROFILE`：学习画像、弱项、熟项、趋势。
- `SESSION_HISTORY`：会话摘要、近期对话、用户聊天记忆。
- `KNOWLEDGE`：本轮知识检索结果、图片上下文、证据目录。
- `DIALOG_SIGNAL`：追问、切题、回跳、总结等对话状态提示。
- `CONSTRAINTS`：回答边界、安全要求、工具执行约束。

后续可扩展：

- `TOOL_STATE`：可用工具和近期工具调用结果。
- `TASK_PLAN`：多步任务规划状态。
- `TASK_MEMORY`：当前任务步骤观察缓存。
- `RECALL`：长期记忆或图记忆召回。

### Source

`AgentContextSource` 根据 slot 提供 `ContextItem`。每个 source 只负责读取和格式化自己的上下文，不直接调用 LLM 生成最终回答。

首批可实现 source：

- `LearningProfileContextSource`：包装 `LearningProfileAgent.snapshotForPrompt()`。
- `KnowledgeContextSource`：包装 `KnowledgeRetrievalCoordinator` 和 `DynamicKnowledgeContextBuilder` 已有能力。
- `DialogSignalContextSource`：包装 `DynamicKnowledgeContextBuilder.buildDialogSignal()`。
- `SessionHistoryContextSource`：包装 `ChatContextCompressor.buildCompressedContext()`，Knowledge QA 试点可按需接入。

## Knowledge QA 试点设计

第一阶段只改造 `KnowledgeQaAgent` 的上下文组装路径。

现状简化流程：

```text
KnowledgeQaAgent
  -> ConversationTopicTracker.analyze
  -> KnowledgeRetrievalCoordinator.retrieve
  -> DynamicKnowledgeContextBuilder.buildDynamicContext
  -> LearningProfileAgent.snapshotForPrompt
  -> PromptManager.renderSplit
```

目标流程：

```text
KnowledgeQaAgent
  -> 收集 query/sessionId/userId/contextPolicy/analysis/packet
  -> AgentContextAssembler.assemble(KNOWLEDGE_QA, query)
  -> AgentRuntimeContext.render()
  -> PromptManager.renderSplit
```

其中 `ConversationTopicTracker` 和 `KnowledgeRetrievalCoordinator` 暂不迁入 assembler 内部；`KnowledgeQaAgent` 仍负责取到 `TurnAnalysis` 与 `KnowledgeContextPacket`，assembler 只负责统一组织和渲染，降低第一阶段风险。

## 渲染与预算规则

- 每个 slot 以固定标题渲染，例如 `【用户画像】`、`【知识上下文】`。
- 空 slot 默认不渲染；required slot 为空时可记录 debug trace，但不强行输出占位文本。
- 单 slot 支持 `charBudget`、`topK`；第一阶段用字符数近似 token。
- 全局预算超限时，按优先级裁剪：`CONSTRAINTS > DIALOG_SIGNAL > PROFILE > KNOWLEDGE > SESSION_HISTORY > RECALL`。
- 渲染顺序由 schema 决定，不由 source 返回顺序决定。

## 可观测性与调试

`AgentRuntimeContext` 应保留结构化信息：

- mode
- filled slots
- skipped slots 与原因
- 每个 item 的 source、score、metadata
- 渲染后的 prompt prefix 长度

第一阶段不强制接 Langfuse，但字段命名要便于后续写入 trace。日志中不得输出完整用户隐私、API key、Bearer token 或长文本原文。

## 测试策略

实现阶段应优先补单元测试：

- `AgentRuntimeContextTest`：slot 渲染顺序、空 slot 行为。
- `AgentContextAssemblerTest`：source 聚合、单 slot 预算、全局预算裁剪。
- `KnowledgeQaContextAssemblyTest`：确保 Knowledge QA 生成的 profile、dialog signal、knowledge context 均进入 prompt。

接入 `KnowledgeQaAgent` 后再跑相关测试和编译：

```bash
mvn -q -Dtest=AgentContextAssemblerTest,AgentRuntimeContextTest,KnowledgeQaAgentTest test
mvn -q -DskipTests compile
```

如果当前没有 `KnowledgeQaAgentTest`，先增加窄单测，不用为了测试完整 HTTP 链路而启动全部外部依赖。

## 分阶段计划

### 阶段 0：设计冻结

仅提交本文档，不改生产代码。确认命名、slot 范围、首个试点链路。

### 阶段 1：基础模型与纯单测

新增 context 基础类型、schema、assembler 和渲染逻辑。使用 fake source 完成单元测试，不接业务服务。

### 阶段 2：Knowledge QA 试点接入

实现 profile、knowledge、dialog signal source，并改造 `KnowledgeQaAgent`。保留旧逻辑作为内部依赖，避免一次性重写检索和话题追踪。

### 阶段 3：扩展到面试与刷题

将 `InterviewOrchestratorAgent`、`CodingPracticeAgent` 中的画像和上下文拼接逐步改为 assembler。每次只迁移一个 Agent，并保留行为兼容测试。

### 阶段 4：面向工具任务增强

新增 `TOOL_STATE`、`TASK_PLAN`、`TASK_MEMORY`、`CONSTRAINTS` source，为后续“Agent 真动手干活”和 sandbox policy 接入做准备。

## 风险与约束

- 不要把 assembler 做成新的上帝服务；它只负责装配，不负责检索、压缩、画像计算或 LLM 调用。
- 不要在第一阶段引入复杂 token 计算器，字符预算足够支撑重构起步。
- 不要直接删除 `DynamicKnowledgeContextBuilder` 或 `ChatContextCompressor`，它们应先作为 source 的依赖被复用。
- 不要修改模板语义，先保持 `PromptManager.renderSplit()` 的变量兼容。
- 新增包结构后需同步 `PACKAGE_CONVENTIONS.md` 和必要的 ArchUnit 规则。
