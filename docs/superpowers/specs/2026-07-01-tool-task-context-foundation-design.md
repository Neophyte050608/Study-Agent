# TOOL_TASK 上下文基座设计

## 背景

Study-Agent 已经把统一上下文装配接入 Knowledge QA、面试评估和刷题出题链路。下一步需要为“Agent 真正动手干活”预留稳定基座，让未来的工具调用、任务规划、执行观察和安全确认能够通过同一套 `AgentContextAssembler` 进入 prompt，而不是继续在业务服务里手工拼接。

本阶段只建设 `TOOL_TASK` 上下文基础能力，不实现真实工具执行器、不接入 Mastra、不改变现有业务 Agent 行为。

## 目标

- 为 `AgentContextMode.TOOL_TASK` 增加默认 schema。
- 定义任务型 Agent 的上下文属性约定。
- 提供 `CONSTRAINTS`、`TASK_PLAN`、`TOOL_STATE`、`TASK_MEMORY` 的基础 source。
- 让上层未来可通过 attributes 输入任务目标、计划步骤、工具清单、执行观察和确认策略，并得到稳定渲染结果。
- 保持 `AgentContextAssembler` 只做上下文装配，不负责工具执行、规划生成或权限判断。

## 非目标

- 不实现 tool executor、sandbox、MCP 调用或真实文件/网络操作。
- 不新增 Agent Runtime 框架，也不引入 Mastra/LangGraph/LangChain。
- 不接 Langfuse，只保证输出结构便于后续观测 adapter 读取。
- 不迁移现有 Knowledge QA、Interview、Coding 调用点。
- 不创建空 port、空 adapter 或未被测试覆盖的抽象。

## 核心模型

新增 `ToolTaskContextAttributes`，作为 `TOOL_TASK` 查询参数约定。首批字段：

- `taskGoal`：本次任务目标。
- `userRequest`：用户原始请求摘要。
- `taskPlan`：当前计划步骤列表或文本。
- `completedSteps`：已完成步骤。
- `observations`：执行观察、工具返回摘要、关键中间结果。
- `availableTools`：当前允许展示给模型的工具名称或能力说明。
- `disabledTools`：不可用或被策略禁止的工具。
- `lastToolResult`：最近一次工具调用结果摘要。
- `confirmationPolicy`：需要用户确认的动作边界。
- `safetyRules`：安全约束，例如禁止删除、禁止提交密钥、禁止越权访问。

字段仅表达上下文，不表达“是否允许执行”。真正的权限判断仍应由未来 executor/policy 层负责。

## Schema 设计

`AgentContextSchema.toolTask()` 的 slot 顺序：

1. `CONSTRAINTS`：安全规则和确认策略，优先级最高。
2. `TASK_PLAN`：任务目标、原始请求和计划步骤。
3. `TOOL_STATE`：可用工具、禁用工具和最近工具结果。
4. `TASK_MEMORY`：已完成步骤与执行观察。

字符预算建议：

- `CONSTRAINTS`：600 字。
- `TASK_PLAN`：900 字。
- `TOOL_STATE`：700 字。
- `TASK_MEMORY`：1200 字。

这些预算足够支撑第一阶段测试和短任务，后续可接入 token budget 或 Langfuse 后再细化。

## Source 设计

新增四个 source，均位于 `io.github.imzmq.interview.agent.application.context`：

- `ToolTaskConstraintsContextSource`
  - 支持 `CONSTRAINTS`。
  - 渲染 `safetyRules` 和 `confirmationPolicy`。
  - 空输入时返回空列表，不输出泛化安全口号。

- `ToolTaskPlanContextSource`
  - 支持 `TASK_PLAN`。
  - 渲染 `taskGoal`、`userRequest`、`taskPlan`。
  - `taskPlan` 支持 `List<?>` 和单个字符串。

- `ToolStateContextSource`
  - 支持 `TOOL_STATE`。
  - 渲染 `availableTools`、`disabledTools`、`lastToolResult`。
  - 不展示密钥、token、完整命令输出等敏感长文本；本阶段只接收调用方已脱敏摘要。

- `ToolTaskMemoryContextSource`
  - 支持 `TASK_MEMORY`。
  - 渲染 `completedSteps` 和 `observations`。
  - 只保存当前 query attributes 中的短期任务记忆，不做持久化。

所有 source 都必须检查 `query.mode() == TOOL_TASK`，避免污染其他业务模式。

## 数据流

未来任务型 Agent 的调用方式：

1. Runtime 或 application service 收集当前任务状态。
2. 构造 `AgentContextQuery.create(TOOL_TASK, taskGoal, attributes)`。
3. `AgentContextAssembler` 根据 `toolTask()` schema 调用四类 source。
4. `AgentRuntimeContext.render()` 输出稳定上下文片段。
5. 上层把渲染结果传给 prompt，例如变量名 `agentContext` 或 `taskContext`。
6. 工具执行、确认弹窗、权限判断仍由调用方或未来 executor 处理。

## 错误处理与安全边界

- 空字段不渲染，避免 prompt 噪音。
- 列表字段会 trim、过滤空字符串，并保持输入顺序。
- 本阶段 source 不主动读取数据库、文件系统、网络或外部工具状态。
- 不在 context source 内执行任何工具。
- 后续接入真实工具调用时，必须在 executor 层做二次确认和权限检查，不能依赖 prompt 约束作为唯一安全边界。

## 测试策略

新增单元测试：

- `ToolTaskContextAttributesTest`：验证文本、列表、整数或空值读取策略。
- `ToolTaskConstraintsContextSourceTest`：验证安全规则和确认策略渲染。
- `ToolTaskPlanContextSourceTest`：验证任务目标、原始请求和计划步骤渲染。
- `ToolStateContextSourceTest`：验证可用工具、禁用工具和最近结果渲染。
- `ToolTaskMemoryContextSourceTest`：验证完成步骤和观察结果渲染。
- 扩展 `AgentContextAssemblerTest`：验证 `TOOL_TASK` schema 已注册且 slot 顺序稳定。

验证命令：

```bash
mvn -q -Dtest=ToolTaskContextAttributesTest,ToolTaskConstraintsContextSourceTest,ToolTaskPlanContextSourceTest,ToolStateContextSourceTest,ToolTaskMemoryContextSourceTest,AgentContextAssemblerTest test
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q -DskipTests compile
```

## 后续演进

- 第二阶段可接入一个轻量 `ToolTaskAgent` 或现有 agent runtime 的试点调用点。
- 后续可把 `AgentRuntimeContext` 的 slot/source/字符数写入 Langfuse trace。
- 如果引入 Mastra，只把 Mastra 作为 runtime/orchestration 层；上下文 source 仍保持 Java 侧业务事实装配能力。
- 当工具执行稳定后，再单独设计 executor policy、用户确认协议和操作审计。
