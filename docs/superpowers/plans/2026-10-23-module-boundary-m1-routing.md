# Module Boundary M1 Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成模块边界治理 M1：消除顶层 `intent`/`turn`，让 routing 拥有意图领域模型和统一理解公开契约，并以可关闭、超时隔离的 shadow 模式接入现有聊天链路而不改变路由结果。

**Architecture:** 保持单 Maven 模块与现有 HTTP/SSE/数据库契约。现有 `intent.domain` 机械迁入 `routing.domain`；跨模块调用只面向 `routing.application.contract.UnderstandingService` 和 `routing.application.contract.result`。routing 内部实现一次模型调用和临时复用 chat JSON parser（该反向依赖明确留给 M2）；聊天侧观察器只异步消费结果并写现有 trace/log，不参与任何决策。

**Tech Stack:** Java 21、Spring Boot、Spring AI、Jackson、CompletableFuture、JUnit 5、Mockito、AssertJ、ArchUnit、Maven。

## Scope boundaries

- M1 包含：`intent -> routing.domain`、统一理解契约、LLM resolver/mapper、独立 executor、3 秒内超时、简单连续失败熔断、shadow flag、trace/log、架构规则、文档。
- M1 不包含：standalone query 接管检索、模型结构化输出迁入 modelrouting（M2）、chat ownership 迁移（M3）、多任务规划/执行、HTTP/SSE/数据库变更。
- 兼容键：canonical 配置为 `app.routing.understanding.shadow.*`；`app.turn.understanding.shadow` 仅作为旧设计键的 fallback，不创建顶层 `turn` Java 包。

## File structure

### Create

- `src/main/java/io/github/imzmq/interview/routing/application/contract/UnderstandingService.java`：跨模块异步理解入口。
- `src/main/java/io/github/imzmq/interview/routing/application/contract/result/IntentRecord.java`：统一理解结果。
- `src/main/java/io/github/imzmq/interview/routing/application/contract/result/IntentTask.java`：叶子意图与 slots。
- `src/main/java/io/github/imzmq/interview/routing/application/contract/result/TurnRelation.java`：轮次关系枚举。
- `src/main/java/io/github/imzmq/interview/routing/application/contract/result/DecisionSource.java`：决策来源枚举。
- `src/main/java/io/github/imzmq/interview/routing/application/understanding/IntentRecordMapper.java`：LLM JSON 到 contract，失败回退。
- `src/main/java/io/github/imzmq/interview/routing/application/understanding/IntentLlmResolver.java`：单次模型调用和 prompt。
- `src/main/java/io/github/imzmq/interview/routing/application/understanding/DefaultUnderstandingService.java`：独立 executor、timeout 和连续失败熔断。
- `src/main/java/io/github/imzmq/interview/config/routing/RoutingUnderstandingProperties.java`：理解层配置。
- `src/main/java/io/github/imzmq/interview/interview/application/IntentShadowObserver.java`：现有聊天链路中的真实 contract caller，trace/log only。
- `src/test/java/io/github/imzmq/interview/routing/application/contract/result/IntentRecordTest.java`
- `src/test/java/io/github/imzmq/interview/routing/application/understanding/IntentRecordMapperTest.java`
- `src/test/java/io/github/imzmq/interview/routing/application/understanding/IntentLlmResolverTest.java`
- `src/test/java/io/github/imzmq/interview/routing/application/understanding/DefaultUnderstandingServiceTest.java`
- `src/test/java/io/github/imzmq/interview/interview/application/IntentShadowObserverTest.java`

### Move

- `src/main/java/io/github/imzmq/interview/intent/domain/*.java` -> `src/main/java/io/github/imzmq/interview/routing/domain/*.java`

### Modify

- All main/test imports of `io.github.imzmq.interview.intent.domain`.
- `src/main/java/io/github/imzmq/interview/config/async/AsyncConfig.java`：增加拒绝时不回压主聊天线程的 `routingUnderstandingExecutor`。
- `src/main/java/io/github/imzmq/interview/interview/application/ChatStreamingService.java`：旧路由返回后 fire-and-forget 调用 observer。
- `src/main/java/io/github/imzmq/interview/knowledge/application/observability/TraceNodeDefinitions.java`：增加 `INTENT_SHADOW`；观测 ownership 迁移留给 M7。
- `src/main/resources/application.yml`：增加安全关闭默认配置与兼容 fallback。
- `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`：禁止顶层 `intent`/`turn`，固定两个公开契约位置。
- `ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md`：记录 M1 当前状态、公开契约和 M2 临时依赖。

---

### Task 1: Freeze M1 ownership with red ArchUnit rules

- [ ] 在 `ArchitectureRulesTest` 增加规则：生产类不得位于 `io.github.imzmq.interview.intent..` 或 `io.github.imzmq.interview.turn..`；`IntentRecord` 只能位于 `routing.application.contract.result`；`UnderstandingService` 只能位于 `routing.application.contract`。
- [ ] 运行 `mvn -q -Dtest=ArchitectureRulesTest test`，预期因当前 `intent.domain` 仍存在而 FAIL，保存失败原因作为 RED 证据。
- [ ] 将 5 个 intent 领域类移动到 `routing.domain` 并更新所有生产/测试 import，不改类体行为。
- [ ] 重新运行 `mvn -q -Dtest=ArchitectureRulesTest,TaskRouterAgentTest test -DargLine=-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar`，预期 PASS。
- [ ] 提交 `refactor(routing): merge intent domain into routing`。

### Task 2: Add and validate the routing public result contract

- [ ] 先写 `IntentRecordTest`：验证 lists/maps 防御性复制、null collections 归一为空、confidence/score 必须在 `[0,1]`、空 relation/source 被拒绝。
- [ ] 运行 `mvn -q -Dtest=IntentRecordTest test`，预期因类型尚不存在而 FAIL。
- [ ] 实现 `IntentRecord`、`IntentTask`、`TurnRelation`、`DecisionSource`，只依赖 JDK 类型；公开结果不暴露 Spring、Mapper、DO、SDK 或 routing internal 类型。
- [ ] 增加 `UnderstandingService`，签名为 `CompletionStage<IntentRecord> understand(String sessionId, String userId, String rawQuery, String history)`，使现有聊天编排能非阻塞调用。
- [ ] 重跑 `IntentRecordTest,ArchitectureRulesTest`，预期 PASS。
- [ ] 提交 `feat(routing): add understanding public contract`。

### Task 3: Map one structured model response with safe fallback

- [ ] 先写 `IntentRecordMapperTest`，覆盖：合法单任务、markdown JSON、非法 relation、缺字段、非法 score、tasks 超过一个时 M1 只保留第一项、解析失败返回 `FALLBACK_RULE` 且 standalone 回退原 query。
- [ ] 运行测试，预期因 mapper 尚不存在而 FAIL。
- [ ] 实现 mapper：使用现有 `LlmJsonParser.parseTree` 与 `SchemaSpec`，不启用 retry（保证 resolver 每次只调用一次 LLM），手工校验 task/slot；任何映射异常返回 `NEW_TOPIC + rawQuery + empty tasks + confidence 0 + FALLBACK_RULE`。
- [ ] 重跑 mapper 与 contract 测试，预期 PASS。
- [ ] 提交 `feat(routing): map structured intent records`。

### Task 4: Resolve intent once and isolate timeout/circuit failures

- [ ] 先写 `IntentLlmResolverTest`：捕获 prompt，断言 history/query、七种 relation、JSON-only、单任务限制都存在，并验证一次 `resolve` 恰好一次 `RoutingChatService.call`。
- [ ] 先写 `DefaultUnderstandingServiceTest`：验证在专用 executor 上执行、成功归零失败计数、超时在配置上限完成异常、达到阈值后后续请求不调用 resolver 并返回 `FALLBACK_RULE`、open duration 后允许探测恢复、executor 拒绝不在调用线程运行模型。
- [ ] 运行两个测试，预期因实现尚不存在而 FAIL。
- [ ] 实现 `IntentLlmResolver`，用 system/user 双消息调用 `RoutingChatService` 的 GENERAL route，并交给 mapper。
- [ ] 实现 `RoutingUnderstandingProperties`，默认 timeout `3000ms`、failure threshold `3`、open duration `30000ms`，对非法 timeout 做上限约束。
- [ ] 在 `AsyncConfig` 新增 `routingUnderstandingExecutor`：core 2/max 4/queue 100/`AbortPolicy`，防止饱和时 CallerRuns 阻塞聊天主线程。
- [ ] 实现 `DefaultUnderstandingService`：`CompletableFuture.supplyAsync(...).orTimeout(...)`；异常计数和 open-until 使用原子变量；open 期间直接给出 fallback，不刷模型错误。
- [ ] 重跑上述测试，预期 PASS。
- [ ] 提交 `feat(routing): isolate shadow understanding calls`。

### Task 5: Observe after legacy routing without changing decisions

- [ ] 先写 `IntentShadowObserverTest`：flag false 时不调用；true 时传递四个输入；成功创建 `INTENT_SHADOW` child 并 success；异常只 fail trace 且 observer 本身不抛出；调用立即返回、不等待未完成 future。
- [ ] 运行测试，预期因 observer 尚不存在而 FAIL。
- [ ] 在 `TraceNodeDefinitions` 新增 `new TraceNodeDefinition("INTENT", "INTENT_SHADOW")`。
- [ ] 实现 `IntentShadowObserver`，仅消费 `UnderstandingService` future 并写结构化 log/trace；不返回 routing decision。
- [ ] 注入 `ChatStreamingService`，在 `TaskRouterAgent.dispatch` 完成、routeResponse 写入之后调用 observer；传 `intentRoutingHistory`、root trace id/node id。不得修改 response、context、handler 选择或 SSE payload。
- [ ] 在 `application.yml` 增加默认关闭的 `app.routing.understanding.shadow.enabled`，兼容读取 `app.turn.understanding.shadow`；增加 timeout/failure threshold/open duration 配置。
- [ ] 重跑 observer、TaskRouterAgent 和架构测试，预期 PASS。
- [ ] 提交 `feat(routing): add isolated intent shadow observation`。

### Task 6: Document current boundary and M2 handoff

- [ ] 更新 `ARCHITECTURE.md`：标记 M1 已消除顶层 intent/turn、真实 contract caller、shadow 不决策、canonical/legacy flag。
- [ ] 更新 `PACKAGE_CONVENTIONS.md`：列出 `routing.application.contract`/`result`、routing internal 禁止跨模块使用、领域类型在 `routing.domain`。
- [ ] 明确记录 `routing.application.understanding.IntentRecordMapper -> chat.application.LlmJsonParser/SchemaSpec` 是 M2 前唯一临时反向依赖，不把它扩展成新惯例。
- [ ] 运行 `git diff --check`，预期无空白错误。
- [ ] 提交 `docs: document M1 routing ownership`。

### Task 7: Fresh full verification and rollback audit

- [ ] 运行 `mvn -q compile`。
- [ ] 运行 `mvn -q -DskipTests test-compile`。
- [ ] 运行 `mvn -q -Dtest=ArchitectureRulesTest,TaskRouterAgentTest,IntentRecordTest,IntentRecordMapperTest,IntentLlmResolverTest,DefaultUnderstandingServiceTest,IntentShadowObserverTest test -DargLine=-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar`。
- [ ] 运行全量 `mvn test -DargLine=-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar`。
- [ ] 运行 `mvn -q verify -DskipTests` 与 `git diff --check`。
- [ ] 审核 `git status --short --branch`、`git diff bd0da32..HEAD --stat` 和日志，确认无 `.env`、构建产物、M2 改动或主工作区用户文件。

## Rollback

- 配置级：`app.routing.understanding.shadow.enabled=false`（默认）立即停止所有新模型调用，旧路由和 SSE 不变；旧键 `app.turn.understanding.shadow=false` 仍生效。
- 提交级：按 Task 5 -> Task 4 -> Task 3 -> Task 2 -> Task 1 逆序回退；无数据库、HTTP、前端或持久化迁移。
- 运行时：超时、模型异常、executor 饱和或 circuit open 都只能产生 shadow trace/log/fallback，不向 `ChatStreamingService` 抛出，也不写 routeResponse。
