# Tool Execution Port Design

## 背景

项目已经有 `skill/core`、`skill/runtime` 和 `skill/policy`，可执行技能具备超时、重试、熔断、fallback 和 telemetry 能力；同时 `agent/application/AgentSkillService` 负责读取 `SKILL.md` 并生成提示词约束。两者职责不同：前者偏运行时执行，后者偏提示词注入。随着后续 Agent 需要“真的动手干活”，直接让 Agent 依赖具体 Skill、MCP 客户端或未来 Mastra Runtime 会让边界继续变乱。

第二阶段先建立轻量的 Tool Execution Port。它不引入 Temporal、Mastra 或复杂 MCP Registry，只把“工具调用”抽象成稳定入口，为后续权限、审计、外部工具和 Agent Runtime 框架预留位置。

## 目标

- Agent 侧只依赖统一的 `ToolExecutionPort`，不直接绑定具体 Skill 实现。
- 复用现有 `SkillOrchestrator`、`SkillExecutor`、`SkillExecutionBudget` 的执行能力。
- 为每次工具调用保留 `traceId`、`operator`、`source`、`riskLevel`、`dryRun` 等上下文字段。
- 保持低风险：不改现有业务行为，不引入新数据库表，不接入外部框架。

## 非目标

- 不在本阶段引入 Mastra、Temporal、Ray、MLflow 或其他重型框架。
- 不重命名或整体迁移现有 `skill` 包。
- 不实现完整 RBAC、审批流或持久化审计表。
- 不让 Controller 直接调用具体 Tool、Skill 或未来 Agent Runtime。

## 包结构

新增轻量模块：

```text
src/main/java/io/github/imzmq/interview/tool/core
src/main/java/io/github/imzmq/interview/tool/runtime
src/main/java/io/github/imzmq/interview/tool/policy
```

职责划分：

- `tool/core`：稳定模型和端口接口。
- `tool/runtime`：适配现有 Skill 运行时。
- `tool/policy`：放置轻量风险级别、dry-run 策略等枚举或值对象。

## 核心模型

- `ToolDefinition`：工具元信息，包括 `id`、`name`、`description`、`capabilities`。
- `ToolExecutionRequest`：调用请求，包括 `traceId`、`operator`、`toolId`、`input`、`source`、`riskLevel`、`dryRun`、`metadata`。
- `ToolExecutionResult`：调用结果，包括 `toolId`、`status`、`output`、`message`、`attempts`、`fallbackUsed`、`latencyMs`。
- `ToolExecutionStatus`：先映射现有 `SkillExecutionStatus`，避免重复语义。
- `ToolExecutionPort`：统一执行入口，提供 `execute(request)` 和可选的 `definition(toolId)` 查询。

## Runtime 适配

新增 `SkillBackedToolExecutionAdapter` 实现 `ToolExecutionPort`：

1. 从 `ToolExecutionRequest` 读取 `toolId`、`traceId`、`operator` 和 `input`。
2. 使用 `SkillOrchestrator.newBudget()` 创建预算。
3. 构造 `SkillExecutionContext` 并调用 `SkillOrchestrator.execute(toolId, context)`。
4. 将 `SkillExecutionResult` 转为 `ToolExecutionResult`。
5. `toolId` 不存在时返回明确的 skipped/failed 结果，不抛出无上下文异常。

## 数据流

```text
Agent/Application Service
  -> ToolExecutionPort
  -> SkillBackedToolExecutionAdapter
  -> SkillOrchestrator
  -> SkillExecutor
  -> ExecutableSkill
```

后续接入 Mastra 或 MCP 时，只新增新的 adapter 或 registry，不要求业务层改成依赖框架 SDK。

## 错误处理

- 输入为空或 `toolId` 为空：返回失败结果，消息为 `tool_id_required`。
- 未注册工具：返回 skipped 结果，消息为 `tool_not_registered`。
- Skill 执行异常：继续由 `SkillExecutor` 处理重试、超时、fallback 和熔断；Tool 层只做结果映射。
- `dryRun=true`：本阶段只透传到 request/context metadata，不改变执行行为；后续接权限策略时再收敛语义。

## 测试策略

新增单元测试覆盖：

- 成功 Skill 调用能映射为成功 Tool 结果。
- 未注册 `toolId` 返回稳定结果。
- `traceId`、`operator`、`input` 能传入 `SkillExecutionContext`。
- `fallbackUsed`、`attempts`、`message` 能从 Skill 结果映射到 Tool 结果。

如现有完整测试仍受 Mockito inline / ByteBuddy self-attach 环境问题影响，本阶段至少运行新增测试和可运行的相关模块测试，并在提交说明中标注环境限制。

## 后续演进

- 第三阶段可在 `tool/policy` 下加入权限策略和审计事件。
- MCP 工具可通过独立 `McpToolExecutionAdapter` 接入同一个端口。
- Mastra 或其他 Agent Runtime 只通过 `ToolExecutionPort` 触达 Spring Boot 管理的工具能力。
