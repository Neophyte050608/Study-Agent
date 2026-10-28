# 动态规划执行链收敛设计

状态：已确认，进入实施。

## 1. 背景

request-scoped Learning Plan Preview 当前用 `FIXED`、`RULES`、`SHADOW` 三种模式同时表达“正式执行策略”和“是否运行影子比较”。这让固定图既是主模式又是失败回退，`SHADOW` 也同时承担主路径选择和旁路评估开关。配置中的 `max-plan-bytes` 没有接入实际 LLM JSON parser，属于无效配置。生产服务还为测试保留多组不完整构造入口。

本次不扩展能力，而是删除这些过渡设计，收敛到一个正式执行模型。

## 2. 最终执行模型

同步、request-scoped Learning Plan Preview 只有一条正式路径：

```text
RuleBasedLearningPlanPlanner
  -> validate
  -> digest
  -> compile
  -> existing bounded DAG executor
```

规划、校验、摘要或编译在业务节点执行前失败时，返回 `LearningPlanFixedNodeFactory` 生成的固定三节点图：

```text
profile + evidence -> compose
```

节点开始执行后不重新规划、不换图，也不触发第二次固定图执行。durable `LearningPlanWorkflow` 继续直接使用固定图，不接入 request-scoped 动态计划。

## 3. 影子比较

LLM shadow 只比较计划，不执行候选图，也不影响正式规则图。Learning 场景通过单一配置开关控制是否提交比较：

```yaml
app.learning.plan-preview.dynamic-planning.shadow-enabled: false
```

对应环境变量：

```text
APP_LEARNING_PLAN_DYNAMIC_SHADOW_ENABLED
```

Agent 继续拥有 shadow runner 的 `timeout`、`workers` 和 `queue-capacity`。Learning 只拥有场景是否启用的策略。shadow 提交失败、超时或过载均不得改变正式结果。

## 4. 删除内容

- 删除 `Mode`、`FIXED`、`RULES`、`SHADOW` 模式配置。
- 删除 `APP_LEARNING_PLAN_DYNAMIC_MODE`。
- 删除未实际生效的 `max-plan-bytes` 和 `APP_LEARNING_PLAN_DYNAMIC_MAX_PLAN_BYTES`。
- 删除生产服务的测试便利构造器和可选 `ObjectProvider` 装配。
- 不提供旧配置别名，不新增 facade、port、adapter 或纯转发包装层。

固定图工厂本身保留，因为它同时承担规划前失败回退和 durable workflow 定义，不是过渡实现。

## 5. 构造和边界

`LearningPlanDynamicPlanningService` 保留一个公开构造器，直接依赖已有真实契约：

- `DynamicPlanningOperations`
- `DynamicPlanObservationOperations`
- `DynamicPlanningShadowOperations`

`LearningPlanScenarioPolicy` 是无状态 Learning 内部策略，由服务持有一个固定实例，不为测试暴露替换入口。跨模块调用继续只通过 Agent 的 `application.contract.execution.dynamic` 公开契约。

## 6. 配置和安全

规划预算及图限制继续由 Learning 配置管理。LLM parser 的输入大小限制仍由 Agent 的严格 parser 内部约束；本次不增加第二个配置真相源。

自动化测试不访问真实模型或外部 API，不需要 API Key。观测事件继续只包含受控结构字段和稳定错误码，不包含 owner、request、prompt、候选原文或原始异常。

## 7. 验收标准

1. request-scoped preview 默认执行规则计划。
2. 规划阶段失败会在执行前回退固定图。
3. `shadow-enabled=false` 不提交影子任务；设为 `true` 时只提交一次非执行比较。
4. shadow 异常不影响规则图。
5. durable workflow 始终使用固定三节点图。
6. 仓库运行配置和现行文档不再出现旧三模式或无效 `max-plan-bytes`。
7. 相关单元、Spring wiring、local-lite 配置和 ArchUnit 测试通过。
