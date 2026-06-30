# 刷题上下文装配设计

## 背景

当前知识问答和面试评估已接入 `agent.application.context` 上下文装配框架，但刷题链路仍在 `CodingPracticeService` 和 `CodingPracticeAgent` 中手工拼接 `profileSnapshot`、题型、难度和排除主题等信息。这样会导致上下文来源分散，后续引入 Agent Runtime、观测系统或更复杂练习策略时难以统一治理。

本阶段只处理刷题出题上下文，先覆盖单题生成和批量选择题生成，不改答案评估、下一题生成和交互状态机。

## 目标

- 为 `AgentContextMode.CODING_PRACTICE` 增加默认 schema。
- 让刷题单题生成和批量选择题生成通过 `AgentContextAssembler` 统一装配上下文。
- 保留现有 prompt 变量，避免破坏已有模板、测试和前端行为。
- 将题型、难度、排除主题、题目数量和学习画像等信息沉淀为可测试的 context source。

## 非目标

- 不重写 `CodingPracticeAgent` 的会话状态机。
- 不改答案评估 `evaluateCodingAnswer` 和下一题生成 `generateNextCodingQuestion`。
- 不引入 Langfuse、Mastra 或新的外部框架。
- 不调整现有 prompt 模板文件或前端交互协议。

## 设计方案

新增 `CodingPracticeContextAttributes` 作为刷题上下文查询参数约定，包含：

- `userId`
- `topic`
- `difficulty`
- `questionType`
- `count`
- `excludedTopics`
- `profileSnapshot`

扩展 `AgentContextSchema`，增加 `codingPractice()`：

1. `CONSTRAINTS`：输出格式、题目数量、排除主题等硬约束。
2. `PROFILE`：学习画像，优先使用调用方传入的 `profileSnapshot`，后续再考虑统一由 source 读取用户画像。
3. `TASK_PLAN`：刷题任务意图，包括主题、难度、题型、数量。

新增刷题上下文来源：

- `CodingConstraintsContextSource`：从排除主题、数量和题型生成约束说明。
- `CodingProfileContextSource`：封装已有 `profileSnapshot`，避免模板直接依赖散落变量。
- `CodingTaskPlanContextSource`：封装主题、难度、题型、数量等任务计划。

`CodingPracticeService.generateCodingQuestion` 和 `generateBatchQuiz` 注入 `AgentContextAssembler`，构建 `AgentContextQuery` 后生成 `AgentRuntimeContext`，并把渲染后的上下文放入 prompt 参数，例如 `agentContext`。原有变量 `profileSnapshot`、`topic`、`difficulty`、`questionType`、`count`、`skillBlock` 保持不变。

## 数据流

1. 调用方进入 `CodingPracticeService`。
2. 服务标准化 topic、difficulty、questionType、count 和 excludedTopics。
3. 服务构建 `AgentContextQuery.create(CODING_PRACTICE, topic, attributes)`。
4. `AgentContextAssembler` 按 `codingPractice()` schema 调用相关 source。
5. 服务把 `AgentRuntimeContext.render()` 结果写入 prompt 参数。
6. PromptManager 继续使用现有模板渲染；如果模板暂未引用 `agentContext`，行为保持兼容。

## 测试策略

- 为三个新 context source 添加单元测试，覆盖空值、排除主题、画像和任务计划装配。
- 为 `AgentContextSchema.defaults()` 添加 `CODING_PRACTICE` 覆盖。
- 扩展 `CodingPracticeServiceTest`，断言单题生成和批量选择题 prompt 参数包含 `agentContext`，且旧变量仍然存在。
- 运行 `ArchitectureRulesTest`，确保新增类仍位于 `agent.application.context`，刷题业务逻辑仍位于 `knowledge.application.coding`。

## 风险与兼容性

- Prompt 模板如果未引用 `agentContext`，本阶段不会改变 LLM 输入效果；这是刻意选择的兼容策略。
- 上下文内容会增加 prompt 参数体积，但 schema 字符预算较小，风险可控。
- 后续如果要让刷题评估也统一上下文，应单独设计第二阶段，避免本次范围膨胀。
