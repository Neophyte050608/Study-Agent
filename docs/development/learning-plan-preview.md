# 固定 DAG 学习计划预览

## 同步入口（第一步，保持不变）

第一步仅增加显式 `TaskType.LEARNING_PLAN_PREVIEW`，不改变旧 `LEARNING_PLAN` 的笔记生成行为、不改变自动意图路由、不新增 HTTP 接口或前端页面。后端已持有 `AgentTaskOperations` 的调用方可按以下契约调用：

```java
TaskResponse response = agentTaskOperations.dispatchTask(
        TaskType.LEARNING_PLAN_PREVIEW,
        Map.of("topic", "Java 并发", "query", "准备面试，安排三天练习"),
        Map.of("userId", executionUserId));
```

`executionUserId` 必须由调用方现有可信执行身份上下文确定，不来自请求 payload。本切片只防止 payload 覆盖 context，不新建认证或授权系统；公开入口仍须自行校验身份，不应直接信任客户端自报的 context。缺少 context.userId 或明确 topic 时拒绝执行，不退到默认用户，也不额外请求模型推断主题。

输入限制：userId/topic 去首尾空白后最多 200 字符；可选 query 最多 2000 字符。

## 执行与结果

```text
profile ──┐
          ├── compose
 evidence ┘
```

- `profile` 调用既有 `LearningProfileOperations.snapshot`，读取而不修改学习画像，提供最多 2400 字符摘要。
- `evidence` 调用既有 MySQL 词法索引 search，最多 4 条去重证据，每条摘录 1200 字符，标题只保留文件名且最多 120 字符。首版不调用完整 RAG、模型查询改写、Skill/MCP、Milvus 或 Neo4j 召回。
- 两项读取同时启动；compose 等待前置节点全部终止，消费其输出，走现有无工具模型生成路径，不再检索、不写笔记、不执行代码。

`TaskResponse.data` 为 `LearningPlanPreview`：

| 字段 | 含义 |
| --- | --- |
| status | COMPLETED / DEGRADED / FAILED / TIMED_OUT / OVERLOADED / INTERRUPTED / EXECUTION_FAILED |
| plan | 生成结果；未成功生成时为空字符串 |
| personalized / evidenceAvailable | 可用画像/知识材料标记；失败结果不得作为已完成计划使用 |
| evidence | 实际读取并可供生成使用的材料，不代表已做模型引用逐句验证 |
| degradations | PROFILE_EMPTY / PROFILE_UNAVAILABLE / EVIDENCE_EMPTY / EVIDENCE_UNAVAILABLE |
| nodes | profile/evidence/compose 的 status/code 摘要；不包含原始画像和异常 |

只在 COMPLETED 或 DEGRADED 时 `TaskResponse.success=true`。有材料缺失或读取失败，但模型成功生成时，返回 DEGRADED 并明确原因；空画像与基础设施读取失败不混淆。生成异常或空白答案返回 FAILED，不伪造预览。TaskRouterAgent 保留 Handler 显式响应，旧 Handler 的普通数据返回仍兼容。

## 资源与超时边界

当前实现默认 4 个专用 worker、8 个排队槽、4 个同时调度请求；每图最多 2 个在途节点，通用图限制 1–64 节点。不是新的 YAML 配置项。非法图在节点执行前拒绝；请求上限或队列饱和返回 OVERLOADED。

预览整体等待期限 75 秒。模型候选路径沿用 `TimeoutHint.NORMAL` 的首 token/生成超时；既有默认模型同步兜底路径及数据库查询仍受各自客户端超时约束，不能将它们都描述成 75 秒硬停止。超时/中断取消待执行节点并请求运行任务中断；不响应中断的任务会继续占用实际 worker，直到结束。运行结果不会在已返回响应中被后台修改。

同步第一步不引入新的依赖、迁移或启动要求，仍需现有数据库与可用模型才能获得完整结果。数据库读取失败可受控降级；模型全部失败不产生假计划。任务路由、模型路由已有 trace/A2A 记账不被关闭，“只读”指不修改业务画像/笔记/知识，不代表没有技术日志写入。

## 验证与下一步

```bash
mvn -q -Dtest=FixedDagExecutorTest,LearningPlanPreviewServiceTest,LearningPlanPreviewAdaptersTest,LearningPlanPreviewWiringTest,TaskRouterAgentTest,ArchitectureRulesTest test
mvn -q test
mvn -q verify -DskipTests
```

测试使用 mock 与真实本地 DAG 调度器，包括 Spring 最小装配和 registry/router 链路，不验证真实模型质量、真实数据库/Milvus/Neo4j 或端到端启动。并行测试以 latch 证明重叠，不承诺实际耗时缩短倍数。

第一步只保存请求内结果，不持久化、不自动重试、不恢复进程重启、不续跑、不动态增图、不竞速。独立持久入口已在第二步增加（见下节）；同步入口仍不持久，任务状态不写入 Chat 长期事实表。

## 第二步：持久预览任务

同步 `LEARNING_PLAN_PREVIEW` 继续采用上面的入口，不依赖持久调度开关。新增独立 application 契约 `learning.application.contract.DurableLearningPlanOperations`：

```java
DurableLearningPlanView accepted = durableLearningPlanOperations.submit(
        executionUserId, "request-unique-key", "Java 并发", "安排三天练习");
DurableLearningPlanView state = durableLearningPlanOperations.query(executionUserId, accepted.taskId());
DurableLearningPlanView retried = durableLearningPlanOperations.retry(
        executionUserId, accepted.taskId(), state.revision(), Set.of("evidence"));
durableLearningPlanOperations.delete(executionUserId, accepted.taskId()); // 仅终态
```

第二步只提供 application 契约；第三步新增下节的 HTTP 入口，仍无前端。当前契约返回 Learning-owned 类型化 `DurableLearningPlanView`，不再向 Learning 调用方返回执行 JSON。调用方需先鉴别可信身份，`owner` 不等于客户端自报 userId。提交返回 QUEUED 只表示持久受理；结果必须查询。相同 owner/workflow/key 的规范化 JSON 输入重复提交返回原任务；输入或版本不同报 IDEMPOTENCY_CONFLICT。不同 owner 查询统一 EXECUTION_NOT_FOUND。结果不含原始画像、尝试输入、租约身份或异常。

启用后台调度：`--app.agent.execution.enabled=true`（默认 false）。任务状态表由 Flyway V5 创建，即使调度关闭也随正常迁移创建；不改 V1–V4，不新增基础设施。异步提交/重试未启用时报 EXECUTION_DISABLED，不退回未持久的同步计算。

| 配置 | 默认 / 限制 |
| --- | --- |
| app.agent.execution.coordinators | 2，范围 1–2 |
| app.agent.execution.poll-seconds | 1，范围 1–30 |
| app.agent.execution.lease-seconds | 30，范围 10–120 |
| app.agent.execution.heartbeat-seconds | 5，至少 1 且不超过租约的 1/3 |

节点共用同步入口的 4 个实际 worker/8 个队列槽，不额外开一套节点线程池。协调容量在领取前预留，仅协调线程实际退出才释放；取消不释放仍被阻塞调用占用的物理线程。轮询与心跳使用独立于模型 worker 的两条控制线程。数据库卡住仍受现有数据源/驱动超时约束，不承诺 heartbeat 在数据库不可用时继续成功。

MySQL 为 task/node/attempt 真相源；DB 时间控制租约和每 revision 首次领取后的 75 秒期限，接管不重新计时。每次领取 epoch 递增，每个节点开始/提交和终态提交都检查 owner/epoch/status/未过期租约。短事务落节点和 attempt，提交后才释放后继；模型调用不持行锁。恢复复用已提交成功输出，原 RUNNING attempt 标记 ABANDONED，节点最多尝试 3 次；不自动重试普通失败，也不承诺模型 exactly-once。

FAILED/TIMED_OUT/DEGRADED 允许以 expectedRevision 重试失败根与全部后继，包括旧成功 compose；成功 sibling 保留，结果清空、revision 增长，attempt_count 不清零。超时或整图终止时未执行节点记 FAILED，使调用方可以显式重试。两个并发重试只有一个成功。不支持动态图、竞速、用户取消、工具或业务写入。

输入/输出预算：任务输入 16 KiB，节点输入和输出各 64 KiB，最终结果 128 KiB。版本化 workflow 只保存 JSON，不保存 lambda/类名；JSON 对象递归规范化，拒绝空/null/尾随内容和超预算。保留策略当前是 owner-scoped 终态删除并外键级联，尚无自动 TTL。

新增验证：`DurableExecutionConfigurationTest`、`ExecutionJsonTest`、`DurableExecutionWorkerTest`、`DurableRetrySelectionTest`、`DurableCheckpointTest`、`DurableExecutionServiceTest`、`LearningPlanWorkflowTest`，以及 opt-in `DurableExecutionMySqlIntegrationTest`。真实数据库测试需要 **独立临时库**，见验证指南；stub 三节点验证调度，不代表验证真实模型质量。


## 第三步：HTTP 使用入口

后端默认端口9596，基础路径 `/api/learning/plan-previews`。需按上节启用后台调度并配置可用 MySQL/模型。open 是本地单用户模式（default-user）；**不可视作多用户隔离**。共享部署使用 `app.security.mode=jwt` 与非空 `app.security.jwt-secret`，请求携带认证服务签发的有效 bearer；本次不引入登录/令牌签发服务。

| 接口 | 成功 | 输入及约束 |
| --- | --- | --- |
| POST 基础路径 | 202 + Location | 必需 Idempotency-Key，非空且<=120字符，无控制字符；topic trim 后1–200，request可选、trim后<=2000 |
| GET /{taskId} | 200 | taskId为标准UUID；不存在或其他owner均404 |
| POST /{taskId}/retry | 202 + Location | expectedRevision必需、非负整数；nodes可选数组，最多3个且不重复，仅profile/evidence/compose；空或省略选所有失败根 |
| DELETE /{taskId} | 204 | 仅终态；运行中409，不是取消 |

返回字段：taskId/status/revision/result/errorCode/nodes。result为空或类型化学习计划（status/plan/personalized/evidenceAvailable/evidence/degradations/nodes）。外层节点含status/attempts/code，无画像摘要、attempt输入、租约身份、owner或原始JSON。最终结果损坏或不符合已提交节点状态时报500，不透传损坏数据。

本地open模式提交例子：

```bash
curl -i -X POST http://localhost:9596/api/learning/plan-previews \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: learning-preview-demo-1' \
  -d '{"topic":"Java 并发","request":"安排三天练习"}'

# 将下列 taskId 替换为受理结果的 taskId
curl http://localhost:9596/api/learning/plan-previews/{taskId}
curl -i -X POST http://localhost:9596/api/learning/plan-previews/{taskId}/retry \
  -H 'Content-Type: application/json' \
  -d '{"expectedRevision":0,"nodes":["evidence"]}'
curl -i -X DELETE http://localhost:9596/api/learning/plan-previews/{taskId}
```

同一用户同一幂等键、相同规范化输入返回同一任务（统一202，可能已经终态）；不同输入409。202只代表持久受理。重试必须使用最新revision，只能选择失败根，重算后继并复用成功无关节点，每节点跨revision仍最多3次尝试。

错误体为固定 `{code,message}`：400参数；401可信身份缺失；404任务不可见；409幂等/revision/状态/预算冲突；503执行关闭或数据库不可用；500内部错误/结果损坏。错误不返回异常消息。认证过滤链自身的401保持Spring Security响应，不保证同一JSON格式。

请求体自报owner/userId/context与X-User-Id不能替换身份；不会创建session或自行解析bearer。JWT subject必须无控制字符、无首尾空白且<=64字符，避免现有resolver清理/截断碰撞。字段限制不等于Servlet请求体硬字节上限，本步未新增全站请求限流/配额/保留策略。

```bash
mvn -q -Dtest=DurableLearningPlanServiceTest,LearningPlanTaskResponseTest,LearningPlanPreviewControllerTest,LearningPlanPreviewSecurityTest,ArchitectureRulesTest test
```

接口/安全测试使用真实MVC和SecurityFilterChain/HMAC JwtDecoder；应用层与HTTP串联，只有持久runtime/storage被mock。既有durable数据库测试另行覆盖租约/事务；本步不宣称真实模型质量或整应用端到端启动已验证。


## 第四步：最小前端使用入口

在能力画像页点击「学习计划预览」，打开 `/learning/plan-preview`。输入主题与可选学习要求，
提交成功后 URL 只携带 taskId。重新打开或刷新 `?taskId=<UUID>` 查询已有任务；HTTP 返回不包含原输入，
因此刷新后不会还原主题或要求，也不会重新提交。后台开关、MySQL、模型与第三步 HTTP 认证要求保持不变；
复用前端已有 `localStorage.auth_token`，本次不引入认证服务。

页面展示 profile/evidence/compose 的状态、累计 attempts、revision、生成计划、参考证据和降级原因。
计划和证据作为 Vue 转义纯文本，不运行模型返回的 HTML；引用表示已供应给生成器的材料，
仍不代表逐句验证模型引用正确性。本页不自动保存笔记或更新画像/长期记忆。

- QUEUED/RUNNING：上次查询结束后 2 秒再发一次，无重叠请求；终态自动停止。
- 15 秒客户端超时、网络或 HTTP 错误：暂停查询，由用户手动刷新；中止 HTTP 不取消后台执行。
  401/404 清除可见结果，503 保留最近快照。无效 JSON/任务响应暂停且不冒充受理成功。
- 提交结果未知：冻结原输入，重发复用内存幂等键。显式「新建预览」确认后才放弃该键。
  未得到 taskId 前刷新/离开会丢失键，旧任务仍可能已经受理；不把输入、计划或键写入浏览器存储。
- 局部重试：选择失败根并携带当前 revision；不选即所有失败根。后继重算、成功无关节点复用，
  每节点跨 revision 最多 3 次尝试，最终预算与状态由后端判定。409 只查询最新状态，不重复 mutation。
- 删除：仅终态按钮可用，二次确认后删除任务及执行记录，不是取消；失败不会清空快照（401/404 除外）。
- 切换任务/卸载页面：清理定时器、中止客户端请求并忽略旧请求迟到结果。

前端职责：`api/learningPlans.js` 封装四个现有 HTTP 路由；`composables/useLearningPlanPreview.js`
负责状态与轮询；页面负责 URL/表单/操作，`views/learning/` 负责节点和纯文本展示。
不新增列表、自动重试、取消、新基础设施、后台协议或 DB 迁移。
验证范围与命令见 `verification.md` 的第四步章节。

## local-lite 启动与降级边界

`local-lite` profile 默认开启持久学习计划预览后台任务：

```yaml
app:
  agent:
    execution:
      enabled: true
```

因此本地启动后可直接使用 `/api/learning/plan-previews` 的异步任务接口，不再需要额外追加 `APP_AGENT_EXECUTION_ENABLED=true`。最小运行依赖是 MySQL 与 Flyway V5；任务提交、租约、节点状态和最终结果都落在 MySQL。Neo4j 不参与 durable execution 事务，Neo4j 不可用不应影响学习计划预览的提交与查询。

`local-lite` 同时关闭通用 Milvus VectorStore 与图片 Milvus store：

```yaml
spring:
  ai:
    vectorstore:
      type: none
      milvus:
        enabled: false
        initialize-schema: false
app:
  multimodal:
    image-store:
      enabled: false
```

无 Milvus/向量库时，证据读取按空材料或词法路径诚实降级；无画像或无证据但模型生成成功时返回 `DEGRADED`，并通过 `PROFILE_EMPTY`、`EVIDENCE_EMPTY` 等原因标明。模型 Key 无效或模型全部失败时不会伪造成功计划。

## 受控动态规划模式

同步、请求内的 Learning Plan Preview 支持以下运行模式，配置前缀为
`app.learning.plan-preview.dynamic-planning`，环境变量入口为 `APP_LEARNING_PLAN_DYNAMIC_MODE`：

| 模式 | 实际执行图 | 说明 |
| --- | --- | --- |
| `FIXED` | 固定 `profile + evidence -> compose` | 仓库默认值，也是 `local-lite` 未显式配置时的安全默认值；不发起动态规划。 |
| `RULES` | 经校验并编译的确定性规则计划 | 只允许已注册、只读且符合 Learning 场景策略的 capability；规划、校验或编译失败时在执行前回退固定图。 |
| `SHADOW` | 与 `RULES` 相同的规则计划 | 正式结果始终来自规则计划；LLM 候选只在独立有界资源中做非执行比较。影子能力缺失、超时、过载或失败均不得影响正式结果。 |

动态选择只作用于同步的 request-scoped preview。节点开始执行后不会因局部失败换图或重新规划；失败仍按现有节点状态和降级语义返回。持久化预览继续使用版本化的固定三节点 `LearningPlanWorkflow`，不会保存、恢复或执行动态/LLM 候选图。本次能力不修改前端页面、HTTP 路由或响应契约。

动态计划只包含结构、稳定 capability ID/版本和有界标量参数；可信 owner、topic 与 request 由执行上下文提供，计划不得指定 Bean、实现类、Tool executor 或持久化 adapter。LLM shadow 的 prompt、候选原文、业务输入、身份、画像、证据和异常消息不得写入观测事件。

自动化测试不需要真实模型或 API Key。后续经操作者批准进行真实 shadow smoke test 时，沿用已有模型供应商配置，并仅把 Key 写入未跟踪文件：

```text
/Users/bytedance/bits/StudyAgent/Study-Agent/application-private.yml
```

不得把 Key 写入仓库配置、测试资源、命令行记录或提交内容。`local-lite` 默认仍为 `FIXED`，但可通过 `APP_LEARNING_PLAN_DYNAMIC_MODE=SHADOW` 做本地影子观测 smoke test；真实 smoke test 结束后恢复 `FIXED`。
