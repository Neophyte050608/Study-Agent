# 面试评估上下文装配接入设计

## 背景

当前上下文装配模块已在 Knowledge QA 链路试点接入，提供了 `AgentContextAssembler`、slot/source 模型和字符预算裁剪。面试链路仍由多个服务手写拼接上下文：`InterviewOrchestratorAgent` 生成策略提示，`EvaluationAgent` 透传画像和知识包，`InterviewAnswerEvaluationService` 再截断 profile、RAG context、image context 和 evidence 后塞入 prompt 变量。

下一阶段目标是把面试回答评估链路纳入统一上下文装配，但不一次性改造首题生成、最终报告或刷题链路，避免影响面过大。

## 目标

- 为 `AgentContextMode.INTERVIEW` 增加明确 schema。
- 将面试评估所需的画像、策略、阶段、知识上下文统一装配为结构化 runtime context。
- 保持现有 prompt 模板变量兼容，降低模板回归风险。
- 让后续首题生成、刷题训练、工具任务可复用同一模式。

## 非目标

- 不改造 `startSession()` 首题生成。
- 不修改 prompt 模板表结构或模板语义。
- 不让 assembler 主动调用 RAG、LLM、数据库或外部服务。
- 不删除现有 `planDecision()`、`KnowledgeLayerAgent.gatherKnowledge()` 或证据校验逻辑。
- 不把面试业务对象迁入 `agent.application.context`；context 模块只通过 attributes 消费快照。

## 目标链路

本次只覆盖回答评估：

```text
InterviewOrchestratorAgent.submitAnswer
  -> planDecision
  -> knowledgeLayerAgent.gatherKnowledge
  -> evaluationAgent.evaluateAnswerWithKnowledge
  -> RAGService.evaluateWithKnowledge
  -> InterviewAnswerEvaluationService.evaluateWithKnowledge
  -> AgentContextAssembler.assemble(INTERVIEW)
  -> PromptManager.renderSplit("interviewer", "evaluation", vars)
```

`InterviewOrchestratorAgent` 仍负责会话状态推进和策略计算；`InterviewAnswerEvaluationService` 负责把已经计算好的输入快照交给 assembler。

## 新增模型

### Attributes

新增 `InterviewContextAttributes`，集中定义面试评估 attributes key 和类型读取方法。

建议支持：

```text
PROFILE_SNAPSHOT
STRATEGY_HINT
TOPIC
QUESTION
USER_ANSWER
DIFFICULTY_LEVEL
FOLLOW_UP_STATE
TOPIC_MASTERY
KNOWLEDGE_PACKET
CURRENT_STAGE
NEXT_STAGE
ANSWERED_COUNT
TOTAL_QUESTIONS
```

第一批接入时，`CURRENT_STAGE/NEXT_STAGE/ANSWERED_COUNT/TOTAL_QUESTIONS` 可以先为空；后续如果要把 `planDecision()` 也迁到 source，再从 `InterviewOrchestratorAgent` 传入。

### Schema

扩展 `AgentContextSchema.defaults()`：

```text
INTERVIEW:
  CONSTRAINTS       评估安全边界
  PROFILE           学习画像/薄弱点
  TASK_PLAN         面试阶段、策略、掌握度
  KNOWLEDGE         RAG 文本、图片说明、证据摘要
```

暂不使用 `SESSION_HISTORY`，因为面试会话历史已经体现在 `InterviewSession.history`、题目和用户回答中；后续如需多轮摘要再单独加 source。

## 新增 Source

### InterviewProfileContextSource

职责：从 attributes 中读取 `PROFILE_SNAPSHOT`，填充 `PROFILE` slot。

规则：

- 空值或 `暂无历史学习画像。` 不输出。
- 不调用 `LearningProfileAgent`，避免重复查库；画像快照仍由上游生成。
- 输出 metadata：`source=interview-profile`。

### InterviewStrategyContextSource

职责：填充 `TASK_PLAN` slot。

输入：

- `STRATEGY_HINT`
- `DIFFICULTY_LEVEL`
- `FOLLOW_UP_STATE`
- `TOPIC_MASTERY`
- 可选阶段和轮次信息

输出示例：

```text
当前难度：ADVANCED，追问状态：PROBE，主题掌握度：62.0。
评估策略：保持中等强度评估，兼顾理论与实践。
```

### InterviewKnowledgeContextSource

职责：从 `RAGService.KnowledgePacket` 提取评估知识上下文，填充 `KNOWLEDGE` slot。

规则：

- 使用 `packet.context()`、`packet.imageContext()`、`packet.retrievalEvidence()`。
- 不复用 `KnowledgeContextSource`，避免 Knowledge QA 的“追问/回跳/图片内联回答”语义污染面试评估。
- evidence 不替代 `retrievalEvidence` 模板变量，仍保留原证据目录用于引用校验。

### InterviewConstraintsContextSource

可以先复用 `BasicConstraintsContextSource` 的通用约束；如果后续发现评估链路需要更强约束，再新增面试专属 source。第一阶段不重复造类。

## Prompt 变量兼容策略

`InterviewAnswerEvaluationService.generateEvaluationResult()` 目前传入：

```text
profileSnapshot
strategyHint
context
imageContext
retrievalEvidence
```

改造后仍保留这些 key：

- `profileSnapshot`：继续传安全截断后的原画像，保持模板兼容。
- `strategyHint`：继续传原策略，保持模板兼容。
- `context`：传 `AgentRuntimeContext.render()`，包含结构化 slot 内容。
- `imageContext`：继续传原图片上下文。
- `retrievalEvidence`：继续传原证据目录，确保证据引用校验不变。

这样即使模板仍读取旧变量，也不会失效；新装配结果则通过 `context` 提供更完整结构。

## 数据流

```text
InterviewAnswerEvaluationService.evaluateWithKnowledge
  1. 读取并截断 packet/profile/strategy
  2. 构造 AgentContextQuery(INTERVIEW, question, attributes)
  3. 调用 AgentContextAssembler.assemble
  4. 用 runtimeContext.render() 替换 prompt context
  5. 保留 imageContext/retrievalEvidence/profileSnapshot/strategyHint 旧变量
  6. 调用 PromptManager.renderSplit
```

## 测试策略

新增单元测试：

- `InterviewProfileContextSourceTest`
  - 空画像不输出。
  - 有画像时填充 `PROFILE`。
- `InterviewStrategyContextSourceTest`
  - 输出难度、追问状态、掌握度和策略。
- `InterviewKnowledgeContextSourceTest`
  - packet 为空时不输出。
  - packet 有 context/image/evidence 时输出面试评估知识上下文。
- `AgentContextAssemblerTest`
  - 增加 `INTERVIEW` schema 顺序断言。

接入后运行：

```bash
mvn -q -Dtest=AgentRuntimeContextTest,AgentContextAssemblerTest,InterviewProfileContextSourceTest,InterviewStrategyContextSourceTest,InterviewKnowledgeContextSourceTest test
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q -DskipTests compile
```

## 分阶段实施

### 阶段 1：模型和 source

新增 `InterviewContextAttributes`、三个 source，并扩展 schema。只加测试，不接生产链路。

### 阶段 2：接入评估服务

在 `InterviewAnswerEvaluationService` 中注入 `AgentContextAssembler`，用 assembler 输出替换 `contextMap.context`。保留旧变量。

### 阶段 3：观测与文档

将 context 渲染长度、slot trace 长度写入安全日志，不输出完整内容。更新 `PACKAGE_CONVENTIONS.md` 如已有规则不足。

## 风险与约束

- `context` 内容变长可能影响 token；必须依赖 slot budget 和现有截断双重保护。
- 不能把 `retrievalEvidence` 混入普通知识上下文后删除原变量，否则证据引用校验可能失效。
- 如果模板同时读取 `context` 和 `profileSnapshot/strategyHint`，可能出现少量重复；第一阶段接受该重复，以兼容性优先。
- 不要迁移 `planDecision()`，它还负责面试阶段策略，后续应单独设计。
