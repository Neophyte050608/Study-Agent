# Durable 动态计划快照设计

状态：已确认，等待书面复核。

## 1. 背景

当前持久化执行框架已具备 task/node/attempt 三层状态、租约、epoch fencing、checkpoint、进程恢复和基于 revision 的局部重试。但是，`definition_json` 只保存节点 ID、依赖关系和依赖成功策略。worker 与重试路径仍通过 `DurableWorkflow.nodes(owner, inputJson)` 重新生成执行图。

该模型适用于固定图，不适用于动态规划图。规划器、配置或 capability 注册发生变化后，恢复和重试可能得到不同的图，既无法证明执行的是提交时计划，也可能因 definition 对账失败而中断。

本阶段把提交时最终采用的计划保存为不可变执行快照。后续执行、恢复和重试都从该快照编译原图，不再次规划。

## 2. 目标与非目标

### 2.1 目标

- durable 任务只在提交阶段规划一次。
- 提交阶段完成规划、校验、摘要、可执行性编译和快照生成。
- 正常规则图与规划失败后的固定回退图使用同一种 capability 快照格式。
- worker、进程重启恢复、租约恢复和局部重试严格执行原快照。
- `definition_json` 成为 durable 执行定义的唯一真相源。
- 复用现有 task/node/attempt、租约、epoch fencing、checkpoint 和 revision retry。
- 保留 workflow v1 以恢复历史任务；所有新提交使用 workflow v2。

### 2.2 非目标

本阶段不实现：

- 运行中重新规划或动态改图；
- first-success-wins 竞速；
- Tool、MCP、Shell 或代码沙箱；
- 有副作用的写 capability；
- 第二套 durable runtime；
- 固定 durable 与动态 durable 的运行模式开关；
- 新的前端功能或 HTTP contract；
- 真实模型调用或新的 API Key 配置。

## 3. 方案选择

采用现有 `definition_json` 保存完整执行快照。

不新增计划表或额外快照列。独立表会与 `definition_json` 形成两份真相源，并增加事务一致性、清理和迁移成本。只保存 digest 并在恢复时重新规划同样不可接受，因为它无法阻止图漂移。

当前 `t_agent_execution_task.definition_json` 为 `JSON NOT NULL`，能够承载本阶段快照。本阶段不修改 V5，不增加 Flyway migration。

## 4. 总体架构

```text
DurableLearningPlanService.submit
        │
        ▼
LEARNING_PLAN_PREVIEW workflow v2 prepare
        │
        ├── 解析可信 owner/topic/request
        ├── 规则规划一次
        ├── 校验 capability、参数、拓扑和预算
        ├── 规划失败时生成固定 fallback candidate
        ├── 计算最终 plan digest
        ├── 编译一次，验证当前可执行
        └── 生成不可变 definition snapshot
                    │
                    ▼
        task + node rows 原子持久化
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
 Worker 恢复                 局部重试
        │                       │
        ├── 严格解析 definition_json
        ├── 校验 schema、digest 和 workflow identity
        ├── 精确解析 capability ID + version
        ├── 使用可信 owner/input 组装上下文
        └── 编译并执行原图，不调用 planner
```

planner 只允许出现在新任务的 prepare 阶段。worker、重启恢复、lease 恢复和 retry 路径都不得调用 planner 或 shadow planner。

## 5. Workflow 版本策略

`LEARNING_PLAN_PREVIEW` 同时注册两个 workflow 版本：

| 版本 | 职责 |
| --- | --- |
| v1 | 恢复已经存在的固定图任务 |
| v2 | 接受所有新任务并使用完整 capability 快照 |

规则如下：

1. 保留当前 v1 行为，不能原地改写为 v2，否则历史任务无法恢复。
2. `DurableLearningPlanService.submit` 只提交 v2。
3. 查询、重试和删除同时接受本场景的 v1、v2 任务。
4. 不提供选择 workflow 版本的用户配置。
5. v1 是持久数据兼容协议，不是与 v2 并列的产品运行模式。

## 6. Definition snapshot

### 6.1 Schema

v2 的 `definition_json` 采用严格、版本化结构：

```json
{
  "schemaVersion": "2",
  "workflowType": "LEARNING_PLAN_PREVIEW",
  "workflowVersion": 2,
  "scenarioId": "learning-plan-preview",
  "planSource": "RULES",
  "fallbackCode": null,
  "planDigest": "sha256...",
  "nodes": [
    {
      "id": "evidence",
      "capabilityId": "knowledge.evidence.retrieve",
      "capabilityVersion": 1,
      "dependsOn": [],
      "arguments": {
        "limit": 4
      },
      "requireSuccess": true
    },
    {
      "id": "compose",
      "capabilityId": "learning.plan.compose",
      "capabilityVersion": 1,
      "dependsOn": ["evidence"],
      "arguments": {},
      "requireSuccess": false
    }
  ]
}
```

### 6.2 字段约束

- `schemaVersion`：快照协议版本。未知版本必须拒绝。
- `workflowType`、`workflowVersion`：必须与 task 记录一致。
- `scenarioId`：必须是 `learning-plan-preview`。
- `planSource`：只允许 `RULES` 或 `FIXED_FALLBACK`。
- `fallbackCode`：`RULES` 时必须为 `null`；`FIXED_FALLBACK` 时必须是受控稳定错误码。
- `planDigest`：SHA-256 摘要，覆盖 schema/scenario、节点 ID、capability ID/version、依赖和规范化参数。
- `requireSuccess`：保存最终依赖成功策略；恢复时必须与 capability descriptor 对账。
- `nodes`：节点、依赖和参数按确定性规则排序，保证相同计划得到相同定义。

严格解析必须拒绝未知字段、重复字段、字段缺失、非法枚举、超限值和尾随内容。

### 6.3 不进入快照的数据

快照不得保存：

- owner、topic 或 request；
- prompt、画像、知识证据或模型响应；
- Java 类名、Bean 名、实现类或 adapter 名；
- 原始异常消息或堆栈。

owner 来自 task 的可信所有权字段；topic/request 来自严格校验后的 `input_json`。快照只能决定执行结构，不能覆盖身份或业务输入。

## 7. 正常计划与固定回退

v2 只有一种恢复协议。正常规则计划和固定回退都必须转换为 `ValidatedDynamicTaskPlan`，再通过同一套 digest、compile 和 snapshot 流程。

固定回退仍表达：

```text
profile + evidence -> compose
```

对应稳定 capability：

```text
learning.profile.lookup@1
knowledge.evidence.retrieve@1
learning.plan.compose@1
```

规划、校验、digest 或首次编译在业务节点开始前失败时，系统使用固定 fallback candidate。快照保存 `planSource=FIXED_FALLBACK` 和受控 `fallbackCode`，不保存异常原文。

如果固定回退自身无法校验或编译，提交失败。系统不能接受一个已知无法恢复的 durable 任务。

现有 `LearningPlanFixedNodeFactory` 只服务 v1 历史任务恢复。v2 不通过该工厂维持第二套执行定义。

## 8. 提交语义

新任务提交执行以下步骤：

1. Learning 校验 owner、幂等键、topic 和 request。
2. Agent registry 定位 workflow v2。
3. v2 严格解析 `input_json`。
4. 规则 planner 生成 candidate。
5. Agent 校验 capability、参数、拓扑、effect 和预算。
6. 规则 candidate 失败时，改用固定 fallback candidate。
7. 对最终 validated plan 计算 digest。
8. 编译一次，确认 capability ID/version 和参数当前可用。
9. 生成规范化 definition snapshot。
10. Agent 对节点 ID、DAG、definition 大小及快照/编译结果一致性进行最终校验。
11. 现有短事务原子写入 task 和对应 node rows。

任何业务节点或模型调用都不能在数据库事务中运行。

### 8.1 幂等行为

现有唯一键继续作为提交幂等边界：

```text
(owner_user_id, workflow_type, idempotency_key)
```

- 相同 input 和 workflow version：返回已存在任务，不覆盖它的 definition snapshot。
- input 或 workflow version 不同：返回 `IDEMPOTENCY_CONFLICT`。
- 并发首次提交可能在入库前各自完成 prepare，但只有唯一约束胜出的 task/snapshot 会持久化；失败方不得修改胜出快照。
- 一旦任务持久化，查询、恢复和重试都不得再次规划。

本阶段不为“并发请求只计算一次 prepare”引入 reservation 状态或独立幂等表。

## 9. Worker 恢复

worker 领取 v2 任务后：

1. 根据 task type/version 定位 v2 workflow。
2. 严格解析 `definition_json`。
3. 校验 snapshot schema、workflow identity、scenario、source 和 fallback code。
4. 从 snapshot nodes 恢复 `ValidatedDynamicTaskPlan`。
5. 重新计算 digest 并精确比较。
6. 按 capability ID/version 精确查找 capability。
7. 校验参数和 `requireSuccess` 与当前 descriptor 一致。
8. 使用 task owner 与严格解析后的 `input_json` 创建执行上下文。
9. 将快照计划编译为 `DagNode`。
10. 校验编译节点集合与数据库 node rows 完全一致。
11. 进入现有 checkpoint DAG executor。

恢复阶段允许验证和编译快照，但不允许规划、切换图、更新 definition 或运行 shadow。快照不可执行时，任务按稳定内部错误失败，不自动退回固定图。

## 10. Capability 版本兼容

capability version 是 durable 持久化兼容契约：

1. 参数格式、执行语义或依赖成功策略出现不兼容变化时，必须升级 capability version。
2. 老版本 durable 任务仍可能恢复期间，必须保留对应 capability 实现。
3. 快照版本不存在时，不得自动选择最新版本。
4. 不允许静默执行 capability version 升级。
5. capability 缺失、参数无法解码或 descriptor 不一致时，恢复失败且不重新规划。

内部可使用受控稳定错误码区分 definition 非法、definition 对账失败和 capability 不可用。Learning 公共 API 继续执行现有安全映射，不暴露快照原文、实现类或异常消息。

## 11. 局部重试

v2 后，`DurableLearningPlanService` 不再用固定的 `profile/evidence/compose` 集合校验重试选择。

重试流程：

1. 按 owner 查询任务，保持任务存在性不泄漏。
2. 确认任务属于 `LEARNING_PLAN_PREVIEW` v1 或 v2。
3. 使用该任务实际 node rows 校验用户选择。
4. Agent 从原 `definition_json` 恢复依赖图。
5. 使用现有 `DurableRetrySelection` 计算失败根和后继闭包。
6. 保留 revision 乐观并发、每节点 attempt budget 和成功 seed 复用。
7. 重试只重置受影响节点，不修改 input 或 definition。

v1 从固定定义恢复依赖；v2 从 capability snapshot 恢复依赖。两者继续共用同一个 durable retry runtime。

## 12. 结果投影

规则计划可以选择：

```text
profile -> compose
```

```text
evidence -> compose
```

或：

```text
profile + evidence -> compose
```

因此：

- v1 presenter 继续严格要求固定三节点。
- v2 presenter 以任务实际节点集合为准。
- `compose` 必须存在且是唯一最终输出。
- 规划器主动省略 profile/evidence 不构成降级。
- 计划包含的材料节点执行失败或返回空材料时，才产生对应 degradation。
- HTTP 字段与前端 contract 保持不变；`nodes` 只包含实际参与执行的节点。

## 13. 接口与职责

### 13.1 Durable workflow 生命周期

`DurableWorkflow` 从可重复调用的 `nodes(owner, inputJson)` 调整为两个明确阶段：

```text
prepare(owner, inputJson)
    -> definition snapshot + compiled nodes

restore(owner, inputJson, definitionJson)
    -> compiled nodes
```

- `prepare` 只用于首次提交。
- `restore` 必须完全由持久快照决定结构。
- output encode/decode 和 outcome 仍由 workflow owner 提供。
- Agent 在 workflow 结果外层执行 definition 大小、DAG 和 node-row 一致性校验。

具体 Java 类型在实施计划中按最小公开契约确定；不创建空 port、adapter、facade 或只转发的包装层。

### 13.2 Agent ownership

Agent 负责：

- durable lifecycle 协议；
- definition JSON 安全边界；
- task/node/attempt 持久化；
- snapshot 与 node rows 对账；
- lease、fencing、checkpoint 和 retry；
- capability registry、动态计划校验、digest 和编译。

### 13.3 Learning ownership

Learning 负责：

- Learning Plan Preview v1/v2 workflow；
- topic/request 输入协议；
- 场景规则 planner；
- 固定 fallback candidate；
- Learning 场景策略；
- output codec 和最终结果投影。

跨模块依赖继续只通过 Agent 的 application contract，不允许 Learning 访问 Agent infrastructure 或持久化类型。

## 14. 数据库与迁移

当前 `t_agent_execution_task.definition_json` 为 `JSON NOT NULL`。本阶段：

- 不新增表；
- 不新增 `plan_json` 或 `plan_digest` 列；
- 不修改已应用的 V5；
- 不新增 Flyway migration；
- digest 仅保存在 definition snapshot；
- 不为暂不存在的 digest 查询需求建立索引。

## 15. 安全与失败策略

- owner 只能来自 task 所有权字段，快照无法指定或覆盖 owner。
- topic/request 只来自受控 `input_json`，不在 definition 中复制。
- planner 与 fallback 错误只持久化受控稳定码。
- 严格 parser 拒绝重复字段、未知字段、非法版本和超限定义。
- worker/retry 不因 snapshot 错误改图或重规划。
- owner-scoped 查询先于 workflow/node 校验，避免跨 owner 和其他 workflow 信息泄漏。
- 自动化测试不访问真实模型、外部 API 或个人基础设施，不需要 API Key。

## 16. 测试策略与验收标准

### 16.1 快照协议

- 正常规则图保存完整 capability snapshot。
- fallback 使用相同 snapshot 格式。
- 未知字段、重复字段、错误 schema 和尾随内容被拒绝。
- workflow、scenario、source 或 fallback code 不匹配时被拒绝。
- digest 不一致时被拒绝。
- capability version 缺失时被拒绝。
- `requireSuccess` 与 descriptor 漂移时被拒绝。
- definition 节点与 node rows 不一致时被拒绝。

### 16.2 生命周期

- worker 恢复不调用 planner。
- 进程重启和 lease 恢复执行原图。
- 局部重试不调用 planner，依赖闭包来自原 definition。
- 动态二节点和三节点图均可执行。
- v1 历史 definition 仍能恢复。
- 新提交只使用 v2。
- 幂等重放不覆盖已持久化 snapshot。

### 16.3 业务投影与安全

- 被计划省略的材料节点不产生降级。
- 被计划包含但失败或为空的材料节点产生对应降级。
- owner、topic 和 request 不能由快照覆盖。
- definition 不包含业务输入、prompt 或原始异常消息。
- 跨 owner 和错误 workflow 不泄漏。
- Learning 只依赖 Agent application contract。

### 16.4 验证

实施按 TDD 推进。完成后至少运行：

```bash
mvn -q -Dtest=<snapshot-and-durable-focused-tests> test
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q test
mvn -q verify -DskipTests
git diff --check
```

只有本轮实际运行且退出码为 0 的命令才能在交付总结中声明通过。
