# 受控动态任务计划第一期设计

状态：设计已确认，等待用户审阅书面规范；尚未进入实施计划与代码开发。

## 1. 背景与现状

Study-Agent 已具备固定 DAG 与单业务 durable execution，不再处于“没有 DAG”的阶段。当前代码包括：

- `DagExecutionOperations`、`DagNode`、`DagNodeResult` 与 `DagStructure`；
- `FixedDagExecutor` 的拓扑校验、有界并行、依赖失败处理与结果归集；
- `DurableExecutionService`、worker、租约、heartbeat、epoch fencing、局部重试与成功节点复用；
- Learning Plan Preview 的同步任务入口、durable HTTP 入口与前端闭环。

现有学习计划图固定为：

```text
profile ──┐
          ├── compose
evidence ─┘
```

下一阶段的真实缺口不是再建一套 DAG 执行器，而是允许规划器在受控能力目录内选择节点和依赖。系统必须先验证计划，再复用现有执行内核。

本期采用以下原则：

> 动态的是计划，稳定的是能力目录、安全策略和执行内核。

## 2. 目标

本期为 request-scoped 学习计划预览增加受控动态规划能力：

1. 根据规范化请求选择画像、知识证据或二者；
2. 通过白名单、参数、依赖、复杂度和预算校验拦截非法计划；
3. 将合法计划确定性编译为现有 `DagNode`；
4. 继续使用 `FixedDagExecutor` 执行，不复制调度逻辑；
5. 规划失败时在任何业务节点运行前回退；
6. 先运行确定性 Planner，再以影子模式验证 LLM Planner；
7. 保持现有 durable 工作流和数据库 schema 不变。

本期希望验证两个假设：

- 部分请求可以安全省略不需要的画像或检索节点；
- 节点省略带来的延迟与资源收益，能够覆盖规划和治理成本。

## 3. 范围

### 3.1 包含

- 通用候选计划和已验证计划契约；
- 受控 capability descriptor 和不可变 registry；
- 严格的 LLM 计划 JSON 解析；
- DAG 结构、参数、依赖、场景和预算校验；
- 动态计划到现有 DAG 契约的编译；
- 学习计划预览这一个业务切片；
- 确定性规则 Planner；
- 固定计划回退；
- LLM Planner 影子运行；
- 规划与回退观测事件；
- 架构测试和负向 fixture。

### 3.2 不包含

- Shell、HTTP、MCP 或任意 Tool 节点；
- 数据库写入、记忆写入、画像更新和笔记保存；
- Docker 代码执行；
- first-success-wins 竞速组；
- 运行中 replan 或动态改图；
- 通用 Agent Runtime；
- Temporal、Mastra 等新框架；
- Neo4j 记忆增强；
- 第二套 DAG 执行器；
- Planner 输出 Java 类名、Bean 名、方法名或任意工具名；
- 动态计划持久化和数据库迁移；
- LLM 计划直接承接真实执行流量。

## 4. 业务入口与启用边界

本期动态规划只作用于 request-scoped 学习计划预览，即现有同步 `LearningPlanPreviewService` 及其任务入口。现有 `DurableLearningPlanService`、durable HTTP API 和前端 durable 流程继续使用固定、版本化工作流。

这样做可以先验证动态选图价值，不同时修改 Planner、持久化协议、恢复语义和前端行为。

本期不新增外部 API。Learning Controller 不能直接接触 Planner、registry、候选计划、计划解析器或 Agent runtime。

## 5. 总体架构

```text
规范化学习请求
       │
       ▼
DynamicTaskPlanner
       │ CandidateDynamicTaskPlan
       ▼
DynamicTaskPlanValidator
       │ ValidatedDynamicTaskPlan
       ▼
DynamicTaskPlanCompiler
       │ List<DagNode>
       ▼
FixedDagExecutor
       │
       ├── profile lookup
       ├── evidence retrieval
       └── plan composition
```

规划路径与固定路径必须复用相同的学习业务节点实现：

```text
固定计划构造器 ─┐
                ├── capability handlers ──> FixedDagExecutor
动态计划编译器 ─┘
```

不得让固定计划和动态计划形成两套画像读取、知识检索或计划生成实现。

## 6. 责任划分

### 6.1 Planner

Planner 输入规范化后的有限业务视图，输出候选计划。它只决定：

- 选择哪些已注册能力；
- 节点的逻辑 ID；
- 节点之间的依赖；
- descriptor 明确允许的有限参数。

Planner 不知道 Spring Bean、Java 实现、数据库结构、SDK、运行时租约或执行器内部状态。

### 6.2 Validator

Validator 是确定性的 application/domain policy，负责：

- 计划结构和 DAG 合法性；
- capability 白名单；
- 参数 schema；
- 依赖输入兼容性；
- 只读副作用等级；
- 节点数、深度、入度、fan-out 和成本预算；
- Learning 场景的最终输出约束。

Validator 不调用模型、数据库或外部服务。

### 6.3 Compiler

Compiler 只接受已经验证的计划与可信执行上下文，并确定性生成现有 `DagNode`。它不调用模型、不查询数据库、不解释自然语言、不补节点，也不修改依赖。

### 6.4 Capability registry

Registry 在应用启动时由真实 capability 实现构建为不可变映射。重复 capability ID 或版本冲突导致启动失败，不能在运行时覆盖。

Registry 不能成为按字符串获取 Spring Bean 的 Service Locator。禁止：

```java
applicationContext.getBean(capabilityId)
Class.forName(className)
Method.invoke(...)
```

### 6.5 现有执行器

`FixedDagExecutor` 继续独占以下职责：

- 图执行和拓扑调度；
- 有界并行；
- 依赖结果传递；
- 节点失败和跳过；
- 运行期限；
- 结果归集。

动态规划模块不得复制这些能力。

## 7. 包与模块边界

实现应沿用现有包结构，不预先建立空的“大而全”动态规划模块。

建议归属：

```text
agent.application.contract.execution
└── 真正被 Learning 消费的技术中立计划/执行契约

agent.application.contract.extension
└── Agent 需要业务域实现的最小 capability 扩展契约

agent.application.execution
└── 通用计划校验、registry 和 DAG 编译实现

agent.infrastructure.planning
└── 第五步才出现的 LLM Planner adapter

learning.application.planning
├── Learning 场景规则
├── 确定性 Planner
├── 三个只读 capability 实现
├── 固定计划构造
└── 回退与结果呈现
```

实施时只有出现真实类型时才创建最小子包。不得为了匹配设计图创建空 port、空 adapter、空 facade 或空目录。

依赖规则：

- Learning 只依赖 Agent application 公开契约，不依赖 Agent runtime 实现；
- Agent 不反向依赖 Learning；
- Learning-specific capability 通过真实的 consumer-owned extension SPI 接入 Agent；
- Knowledge 查询继续通过现有 Learning-owned extension `LearningPlanEvidenceReader`；
- Controller 只调用 Learning application 公开契约；
- application contract 不暴露 domain、Spring Web、Spring AI、Jackson、MyBatis 或供应商 SDK 类型。

## 8. 计划生命周期

计划经过三个明确阶段：

```text
Planner 输出
   │
   ▼
CandidateDynamicTaskPlan（不可信）
   │ 验证与规范化
   ▼
ValidatedDynamicTaskPlan（不可变）
   │ 确定性编译
   ▼
List<DagNode>（现有执行契约）
```

LLM Planner 的文本响应在 Candidate 之前还需经过严格 JSON 解析。确定性 Planner 可以直接创建 Candidate，但不能绕过后续统一校验。

Candidate 不能直接传给 `FixedDagExecutor`。Compiler 也不能接收未经验证的 Candidate。

## 9. 候选计划契约

第一版逻辑 JSON：

```json
{
  "schemaVersion": "1",
  "nodes": [
    {
      "id": "profile",
      "capability": "learning.profile.lookup",
      "dependsOn": [],
      "arguments": {}
    },
    {
      "id": "evidence",
      "capability": "knowledge.evidence.retrieve",
      "dependsOn": [],
      "arguments": {
        "limit": 5
      }
    },
    {
      "id": "compose",
      "capability": "learning.plan.compose",
      "dependsOn": ["profile", "evidence"],
      "arguments": {}
    }
  ]
}
```

第一期只接受：

| 字段 | 含义 |
| --- | --- |
| `schemaVersion` | 契约版本；只接受字符串 `"1"` |
| `nodes[].id` | 当前计划内唯一的逻辑节点 ID |
| `nodes[].capability` | 预注册的稳定 capability ID |
| `nodes[].dependsOn` | 显式依赖的逻辑节点 ID |
| `nodes[].arguments` | capability 白名单允许的有限参数 |

未知字段直接拒绝，不做兼容性猜测。

候选计划不得包含：

- 类名、方法名、Bean 名；
- Prompt、SQL、Shell 或代码片段；
- URL、文件路径或回调；
- 任意工具名称；
- userId、owner、tenantId、session owner 或凭据；
- 线程数、超时、重试、token 上限、lease 等运行参数；
- 模型生成的执行实现。

身份和原始请求来自可信 `ExecutionContext`，Planner 不能覆盖。

## 10. 节点标识

第一期节点 ID 满足：

```text
^[a-z][a-z0-9-]{0,31}$
```

并遵守：

- 同一计划内唯一；
- 大小写不自动转换；
- 不允许空白、控制字符和路径分隔符；
- 不允许用能力实现类名充当节点 ID；
- 只用于本计划依赖引用，不作为数据库实体身份。

## 11. Capability 模型

### 11.1 Descriptor

`DynamicNodeCapabilityDescriptor` 描述公开约束：

```text
capabilityId
capabilityVersion
effectType
argumentContract
dependencyContract
estimatedCost
```

- `capabilityId`：稳定业务名称；
- `capabilityVersion`：输入输出契约版本；
- `effectType`：第一期必须为 `READ_ONLY`；
- `argumentContract`：字段、类型、默认值和取值范围；
- `dependencyContract`：允许的上游能力及结果类型；
- `estimatedCost`：服务端定义的相对成本，Planner 无权修改。

Planner 只能看到 descriptor 的安全投影，不得看到 handler 类型、Bean 名、Repository、SDK 或内部异常。

### 11.2 Handler

Handler 接收：

```text
类型化参数
+ 可信执行上下文
+ 类型化上游结果
```

并调用现有 application 能力产生类型化节点结果。

Handler 不得：

- 直接访问其他业务域 Mapper；
- 从参数读取用户身份；
- 反射调用任意方法；
- 创建线程池；
- 自行 retry；
- 修改 registry；
- 持久化执行状态。

### 11.3 第一批能力

#### `learning.profile.lookup`

- 读取当前用户学习画像；
- `READ_ONLY`；
- 无参数；
- 无依赖；
- 身份来自可信上下文。

#### `knowledge.evidence.retrieve`

- 根据规范化后的原始主题检索证据；
- `READ_ONLY`；
- 无依赖；
- 仅允许可选参数 `limit`；
- `limit` 范围为 `1..5`，默认值由系统设置；
- Planner 不能替换检索 query。

#### `learning.plan.compose`

- 根据原始请求和可用上下文生成预览；
- 第一期开启 `READ_ONLY`，不保存结果；
- 无参数；
- 可依赖画像、证据或二者；
- 产生唯一最终输出。

## 12. 严格 JSON 解析

仅 LLM Planner 文本输出经过本层。要求：

- 根节点必须是单个 JSON 对象；
- 拒绝 Markdown code fence；
- 拒绝 JSON 前后的说明文本；
- 拒绝重复 key；
- 拒绝未知字段；
- 拒绝错误数据类型；
- 只接受支持的 schema 版本；
- UTF-8 响应最多 32 KiB；
- 单节点 arguments 的规范化 JSON 最多 4 KiB。

解析失败时不猜测、不截取、不自动修复模型输出，也不要求模型二次重写。本次影子结果标记为拒绝，实际规则计划不受影响。

## 13. 通用结构校验

所有 Candidate，包括确定性 Planner 生成的计划，都必须通过：

- 节点列表非空；
- 节点 ID 合法且唯一；
- capability 存在；
- 依赖节点存在；
- 不允许自依赖和重复依赖；
- 不允许环；
- 所有节点都能到达唯一业务终点；
- 不允许悬空或无结果贡献节点；
- 节点数量、深度、入度和 fan-out 不超限。

默认通用限制：

| 项目 | 上限 |
| --- | ---: |
| 单计划节点数 | 8 |
| DAG 最大深度 | 4 |
| 单节点依赖数 | 4 |
| 单节点 fan-out | 4 |
| 原始计划 JSON | 32 KiB |
| 单节点 arguments | 4 KiB |
| 单次返回的校验错误 | 20 |

Learning 场景另有更严格的三节点限制。

## 14. Capability 与参数校验

每个节点必须满足：

- 参数字段处于白名单；
- 参数类型精确；
- 必填字段存在；
- 数值与字符串在范围内；
- 未知参数被拒绝；
- 上游 capability 和输出类型兼容；
- capability 属于当前场景允许集合；
- `effectType` 为 `READ_ONLY`；
- 估算成本不超过服务端预算。

验证完成后，arguments 转为内部类型化参数。Handler 不读取原始 JSON、`JsonNode` 或未经验证的 `Map<String, Object>`。

## 15. Learning 场景规则

Learning Plan Preview 追加以下约束：

1. 必须且只能存在一个 `learning.plan.compose`；
2. compose 是唯一终点；
3. profile 最多一个；
4. evidence 最多一个；
5. 所有节点必须直接或间接服务 compose；
6. 整个计划最多三个节点；
7. profile 和 evidence 不能互相依赖；
8. compose 的依赖必须与实际选择的上下文来源一致；
9. 至少选择 profile 或 evidence，不能单独 compose。

合法计划只有：

```text
profile ──┐
          ├── compose
evidence ─┘
```

```text
profile ── compose
```

```text
evidence ── compose
```

第一期拒绝单独 compose，因为它没有利用 Study-Agent 的画像或知识能力，也无法证明动态选图价值。

## 16. 预算所有权

Planner 可以选择能力，不能设置资源上限。服务端根据配置和 descriptor 计算总成本：

```text
plan cost = sum(selected capability estimatedCost)
```

Planner 不得设置：

- 并行度和线程池；
- 节点超时和整体 deadline；
- retry 次数；
- token 上限；
- durable lease；
- 数据库事务边界。

超预算计划直接拒绝。第一期不要求 Planner 删节点重试，也不触发 replan。

## 17. 稳定错误码

规划链路使用稳定错误码，不向用户或普通日志暴露原始模型文本：

```text
UNSUPPORTED_SCHEMA_VERSION
PLAN_EMPTY
PLAN_TOO_LARGE
INVALID_NODE_ID
DUPLICATE_NODE_ID
UNKNOWN_CAPABILITY
INVALID_ARGUMENTS
UNKNOWN_DEPENDENCY
CYCLIC_DEPENDENCY
MAX_DEPTH_EXCEEDED
MAX_DEPENDENCIES_EXCEEDED
MAX_FAN_OUT_EXCEEDED
DISALLOWED_EFFECT
INCOMPATIBLE_DEPENDENCY
MISSING_FINAL_OUTPUT
SCENARIO_POLICY_VIOLATION
PLAN_BUDGET_EXCEEDED
PLAN_COMPILE_FAILED
PLANNER_TIMEOUT
PLANNER_UNAVAILABLE
```

内部可以收集最多 20 个校验问题用于测试和受控诊断；对外只返回现有学习计划结果或稳定的通用失败，不透传 Planner 响应、异常消息和内部类型。

## 18. Planner 分阶段策略

### 18.1 确定性规则 Planner

第一阶段使用代码内确定性规则。它是正式的保底实现，不是只能用于测试的 mock。

输入只包括：

```text
topic
learningRequest
requestCharacteristics
allowedCapabilities
planningPolicyVersion
```

不包括：

- JWT、API Key 或认证对象；
- 数据库实体；
- 完整学习画像；
- 检索证据正文；
- 历史聊天全文；
- Repository、Mapper 或 SDK；
- durable lease、attempt 或 worker 信息。

保守选图策略：

| 明确请求特征 | 计划 |
| --- | --- |
| 结合薄弱项、历史表现或个性化 | `profile -> compose` |
| 参考资料、题目、知识库或证据 | `evidence -> compose` |
| 同时需要个性化与证据 | `profile + evidence -> compose` |
| 无法可靠分类 | 完整三节点计划 |

同一规范化输入和 policy 版本必须产生相同 Candidate。只有明确不需要某来源时才省略节点。

### 18.2 LLM Planner 影子运行

第五步增加 LLM adapter。实际请求继续执行规则计划；LLM 只生成、解析、校验和比较影子计划：

```text
请求
 ├── 规则 Candidate -> 校验 -> 编译 -> 实际执行
 └── LLM 文本 -> 严格解析 -> 校验 -> 比较，不执行
```

影子 Planner：

- 不改变用户结果；
- 不执行任何业务节点；
- 不写业务数据；
- 不创建 durable task；
- 超时后放弃，不阻塞实际规则计划；
- 不在普通日志保存 Prompt 或原始响应。

本期不提供 LLM `ACTIVE` 模式。进入真实执行必须基于影子数据另行设计和批准。

## 19. 编译

Compiler 接收：

```text
ValidatedDynamicTaskPlan
TrustedExecutionContext
```

处理步骤：

1. 按节点 ID 产生确定性顺序；
2. 从不可变 registry 取得匹配 capability 版本；
3. 将类型化参数绑定到 capability handler；
4. 建立依赖边；
5. 生成 `List<DagNode>`；
6. 交给现有 `DagExecutionOperations`。

Compiler 不允许：

- 调用 LLM；
- 查询数据库；
- 执行业务能力；
- 解释自然语言；
- 自动补充节点；
- 修改已经验证的依赖；
- 根据 Bean 名查找执行器；
- 对非法计划做尽力编译。

编译失败必须发生在业务节点运行前，并进入固定计划回退。

## 20. 类型化结果与降级语义

节点使用明确结果类型，例如：

```text
ProfileLookupResult
EvidenceRetrievalResult
PlanCompositionResult
```

compose 的输入明确区分：

- 计划没有选择某来源；
- 计划选择了，但节点失败；
- 节点成功，但没有数据；
- 节点成功并有有效数据。

不能使用一个 `null` 表示以上四种状态。最终 Learning 响应继续使用受控 degradation code，且只为实际选择的节点生成对应降级原因。例如，计划未选择 profile 时不能错误报告 `PROFILE_UNAVAILABLE`。

## 21. 回退与执行失败

### 21.1 执行开始前

以下失败允许安全回退：

- Planner 异常或超时；
- LLM JSON 解析失败；
- 计划校验失败；
- capability 解析或编译失败；
- 动态规划关闭。

规则 Planner 路径的回退链：

```text
规则 Planner
  -> 校验/编译失败
  -> 现有固定三节点计划
```

影子 LLM 路径失败只产生受控观测，不改变规则计划执行。

### 21.2 执行开始后

画像、检索、compose 或节点 deadline 失败后，不重新执行固定图。系统沿用当前 DAG 的失败、跳过、降级和结果聚合语义。

原因是执行中换图会重复外部调用、扭曲 attempt 统计，并破坏未来副作用与 durable 恢复语义。

本期核心规则：

> 只允许执行前回退；执行开始后不换图、不 replan。

## 22. 固定计划定位

固定三节点计划继续承担：

1. `FIXED` 模式的稳定路径；
2. 规则 Planner 或编译器失败时的最终保底；
3. 动态计划效果的基线。

固定计划与动态计划必须调用相同 capability 实现。固定计划失败后不再循环进入 Planner 或再次回退。

## 23. 配置

配置位于 Learning Plan Preview 场景下：

```yaml
app:
  learning:
    plan-preview:
      dynamic-planning:
        mode: FIXED
        planner-timeout: 2s
        max-plan-bytes: 32768
        limits:
          max-nodes: 8
          max-depth: 4
          max-dependencies-per-node: 4
          max-fan-out: 4
```

模式：

| 模式 | 实际执行 | LLM Planner |
| --- | --- | --- |
| `FIXED` | 固定三节点计划 | 不调用 |
| `RULES` | 已验证的规则动态计划 | 不调用 |
| `SHADOW` | 已验证的规则动态计划 | 仅生成、校验和比较，不执行 |

约束：

- 默认 `FIXED`；
- 本期没有 `ACTIVE`；
- 客户端不能通过请求或 Header 切换模式；
- 非法模式或边界配置导致启动失败；
- `local-lite` 显式保持 `FIXED`；
- durable 工作流忽略该 request-scoped 实验配置，继续固定图；
- 并行度、执行 deadline 和节点 retry 继续由现有执行配置控制。

如果实施时 Spring Boot 的配置绑定约定需要 duration 使用毫秒字段，则保持同一语义并在 `application.yml` 和配置类中统一命名，不同时保留两个别名。

## 24. API Key 与外部依赖

前四个实施步骤不需要 API Key。第五步自动化测试使用 fake model，也不要求真实 Key。

真实影子联调时：

- 复用现有 Spring AI provider 配置；
- 不创建动态规划专用密钥格式；
- Key 只写入未跟踪文件：

```text
/Users/bytedance/bits/StudyAgent/Study-Agent/application-private.yml
```

- 未配置 Key 时，`FIXED` 和 `RULES` 正常运行；
- `SHADOW` 缺少模型能力时安全跳过影子调用，不阻断规则计划；
- 不增加新的必需基础设施。

## 25. 可观测性

复用现有 `AiObservationPublisher` 或 `OperationalObservationPublisher`，由业务代码发布技术中立事件，不直接依赖 Langfuse 或 OpenTelemetry SDK。

事件：

```text
dynamic_plan.requested
dynamic_plan.generated
dynamic_plan.rejected
dynamic_plan.compiled
dynamic_plan.fallback
dynamic_plan.shadow_compared
```

允许属性：

- 场景 ID；
- Planner 类型、版本和 planning policy 版本；
- schema 版本；
- 节点数和 DAG 深度；
- capability ID 集合；
- 规范化 plan digest；
- 稳定错误码；
- Planner、校验和编译耗时；
- 回退目标；
- 规则与影子计划是否一致；
- 节点增加或省略数量。

禁止属性：

- 原始用户请求和完整 topic；
- Prompt 和模型原始响应；
- arguments 原文；
- 用户画像和证据正文；
- JWT、API Key 和认证凭据；
- 可能包含模型内容的 exception message。

影子比较基于规范化计划结构，不保存两份原始 JSON。

## 26. 影子阶段指标与晋级条件

影子阶段观察：

- Planner 调用成功率；
- 严格 JSON 解析成功率；
- 计划校验通过率；
- 与规则计划一致率；
- 节点省略率；
- capability 选择分布；
- 回退或拒绝原因；
- Planner 延迟与 token 使用。

进入 LLM 真实执行不是本期内容。后续设计至少需要证明：

1. 解析和校验通过率稳定；
2. 没有越权或副作用能力；
3. 动态计划能稳定减少不必要节点；
4. 离线质量不低于规则计划；
5. 延迟与 token 成本可接受；
6. 任意规划失败都可无感回退；
7. 计划摘要和拒绝原因可观测。

## 27. 安全边界

- 所有 Planner 输出均不可信；
- 确定性 Candidate 也必须通过统一校验；
- capability 映射只能来自不可变 registry；
- 用户身份只来自可信执行上下文；
- 第一批 capability 全部只读；
- Planner 超时不能污染业务执行；
- LLM 影子调用不得占用 DAG 节点执行额度；
- 影子调用应使用独立且有界的执行资源，过载时直接跳过；
- 执行开始后禁止换图；
- 规划失败不能导致现有学习计划不可用。

## 28. ArchUnit 约束

在现有 `ArchitectureRulesTest` 增加规则和负向 fixture。

### 28.1 Learning API 隔离

`learning.api` 不能依赖：

- Planner 与 parser 实现；
- capability registry；
- 动态 compiler；
- Agent runtime；
- Jackson 计划解析器。

### 28.2 动态规划核心技术中立

计划契约、validator 和 compiler 不能依赖：

- Spring Web；
- MyBatis；
- Spring AI；
- Milvus、Neo4j 或供应商 SDK；
- LangChain4j；
- Learning、Knowledge 等产品域实现包。

### 28.3 LLM 依赖隔离

Spring AI 模型调用只能位于 planning infrastructure adapter，不能进入计划模型、validator、descriptor、application contract 或 Learning API。

### 28.4 Capability 禁止 persistence 和 Tool

Learning capability 实现不能直接依赖：

- Mapper；
- `infrastructure.persistence`；
- Tool Executor；
- MCP；
- Agent runtime 实现。

### 28.5 公开契约不得泄漏内部类型

公开 application contract 不暴露 domain、handler、registry、runtime `DagNode` 以外的新内部实现、Spring AI、Jackson `JsonNode` 或 infrastructure adapter。

每条新规则至少配一个能触发违规的测试 fixture，证明规则不是永远通过。

## 29. 测试矩阵

### 29.1 JSON 解析

- 合法对象；
- 非对象根节点；
- Markdown code fence；
- 前后附加文本；
- 重复 key；
- 未知字段；
- 超过 32 KiB；
- 不支持的 schema；
- 错误字段类型。

### 29.2 通用校验

- 重复和非法节点 ID；
- 未知 capability；
- 未知、自身和重复依赖；
- 环；
- 超节点、深度、入度和 fan-out；
- 悬空节点；
- 非只读能力；
- 超预算；
- 参数错误与默认值规范化。

### 29.3 Learning 场景

- 三节点完整计划；
- profile + compose；
- evidence + compose；
- 单独 compose 被拒绝；
- 多个 compose 被拒绝；
- compose 不是终点；
- 重复 profile 或 evidence；
- 无关节点；
- 不兼容依赖。

### 29.4 编译与执行

使用 fake capability 验证：

- 合法计划编译为现有 DAG；
- profile 与 evidence 并行；
- compose 等待已选择依赖；
- 未选择节点不执行；
- 类型化结果正确传递；
- 节点失败沿用现有语义；
- 执行开始后不切换固定图；
- 编译失败发生在任何业务节点前。

### 29.5 Planner 与回退

- 相同规范化输入生成相同计划；
- 不确定请求选择完整图；
- Planner 异常和超时回退；
- 校验和编译失败回退；
- 固定计划失败后不循环回退；
- 单次请求不会重复执行动态图和固定图。

### 29.6 影子模式

- 影子计划不改变实际结果；
- 影子超时不阻塞规则计划；
- 非法影子计划不进入执行器；
- 未配置模型时跳过；
- 观测事件不含敏感原文；
- `FIXED` 完全不调用 Planner；
- `RULES` 完全不调用模型；
- 影子执行资源过载时安全跳过。

### 29.7 架构与回归

每步运行最窄测试。最终至少运行：

```bash
mvn -q -Dtest=ArchitectureRulesTest test
mvn test
```

本期不修改前端，前端构建不作为本功能的必需验收项。

## 30. 实施切片

每一步独立测试和提交。详细文件与命令将在用户批准本规范后由 implementation plan 确定。

### D1：计划契约与纯校验器

- Candidate 与 Validated 模型；
- descriptor；
- 通用 DAG 校验；
- Learning 场景规则；
- 单元测试。

LLM 严格 JSON parser 可在 D1 先定义并测试，也可在 D5 与真实 adapter 一起引入；implementation plan 应选择一种，不得维护两套 parser。推荐 D1 完成 parser，以便从第一天冻结输入契约。

建议提交：

```text
feat(agent): define controlled dynamic plan validation
```

### D2：Registry 与现有 DAG 编译

- 不可变 registry；
- 重复注册失败；
- 类型化参数；
- compiler；
- fake capability；
- `FixedDagExecutor` 集成测试。

建议提交：

```text
feat(agent): compile validated plans into dag execution
```

### D3：Learning 规则 Planner 与业务切片

- 三个只读 capability；
- Learning 场景编排；
- 确定性选图；
- 固定计划回退；
- `FIXED` 与 `RULES` 模式。

建议提交：

```text
feat(learning): add rule-based dynamic plan preview
```

### D4：安全约束与可观测性

- ArchUnit 规则与负向 fixture；
- 规划、拒绝、编译和回退事件；
- 脱敏测试；
- 配置校验；
- 包结构、配置和开发文档更新。

建议提交：

```text
test(architecture): enforce dynamic planning boundaries
```

### D5：LLM Planner 影子运行

- infrastructure adapter；
- 严格结构化输出；
- timeout；
- `SHADOW` 模式；
- 规则计划与影子计划比较；
- 未配置模型时安全跳过。

建议提交：

```text
feat(agent): observe llm-generated plans in shadow mode
```

## 31. 完成标准

本期同时满足以下条件才算完成：

1. 计划只能选择预注册的只读 capability；
2. 所有 Candidate 经过统一严格校验；
3. 固定与动态计划复用业务能力和 `FixedDagExecutor`；
4. 规划故障不降低现有功能可用性；
5. 执行开始后不换图、不 replan；
6. durable 主链路和数据库 schema 不变；
7. 默认模式为 `FIXED`；
8. 未配置模型 Key 时项目正常启动；
9. 影子 Planner 不影响用户结果；
10. ArchUnit 能通过 fixture 捕获越界实现；
11. 不增加 Saber 专用代码、配置、collection 或数据格式；
12. 不增加空抽象和第二套执行器；
13. 至少通过测试证明某类请求能省略不必要节点；
14. 实际 LLM 联调前明确 Key 位置，但不提交凭据。

## 32. 回滚

本期无数据库迁移和持久动态计划。将配置切回：

```yaml
app:
  learning:
    plan-preview:
      dynamic-planning:
        mode: FIXED
```

即可停止规则和影子 Planner：

- 不改变现有 API；
- 不影响 durable task；
- 不清理数据库；
- 不依赖外部迁移；
- 动态规划代码可以保留用于离线测试。

## 33. Durable 演进约束

本期不实现 durable 动态计划，但提前固定以下原则，避免以后返工。

首次受理 durable 动态任务时，系统应保存经过验证和规范化的计划，而不是 Planner 原始响应：

```text
planSchemaVersion
planningPolicyVersion
plannerType
plannerVersion
canonicalValidatedPlan
planDigest
capabilityId + capabilityVersion
createdAt
```

不保存 Prompt、原始 LLM 响应、Bean 名、handler 类名、API Key、未脱敏请求或 Java 序列化对象。

计划一旦受理必须冻结。恢复与局部重试读取原计划，不能再次调用 Planner：

```text
首次受理
  -> 规划
  -> 校验和规范化
  -> 冻结计划及 capability 版本
  -> 创建 durable nodes

恢复或局部重试
  -> 读取冻结计划
  -> 校验 capability 版本
  -> 复用成功节点
  -> 重算允许的失败节点及后继
```

如果冻结的 capability 版本不可用，任务进入稳定的不可恢复状态，不能静默切换新版本或重新规划。

未来 plan digest 应由规范化 schema、节点、依赖、capability ID/版本和参数计算，不能包含时间戳、随机 ID、凭据、handler 类名或无序 Map 遍历结果。

## 34. 后续方向

完成并验证本期后，再根据数据决定是否设计：

1. LLM Planner 小流量真实执行；
2. durable 动态计划冻结与恢复；
3. capability 兼容和迁移政策；
4. first-success-wins 竞速组；
5. replan；
6. 带副作用工具节点及幂等协议。

这些能力不因本规范存在而自动进入实施范围，均需独立设计和批准。
