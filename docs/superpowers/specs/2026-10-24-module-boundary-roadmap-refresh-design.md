# Study-Agent 模块边界改造路线刷新设计

## 1. 文档定位

本文基于 2026-10-24 的最新代码和工作树状态，刷新 Study-Agent 模块边界改造的后续路线。

本文不覆盖 `docs/superpowers/specs/2026-10-23-module-boundary-and-package-ownership-design.md`。旧文档继续记录最初的 ownership 设计和历史决策；本文负责说明已经完成的内容、当前尚未提交的工作、剩余依赖关系，以及从当前基线继续推进的顺序。

本轮只规划模块 ownership、公开契约和 ArchUnit 约束，不修改业务行为，也不引入新的执行架构。

## 2. 当前基线

### 2.1 已提交状态

M0 和 M1 已完成并提交：

- M0 建立跨模块持久化违规基线和增量 ArchUnit 保护。
- M1 将顶层 `intent`、`turn` 相关 ownership 合并到 `routing`，建立 understanding 公开契约和隔离的 shadow observation。
- `main` 当前与 `origin/main` 对齐；M1 最近提交以 `0b20bde` 为结束点。

### 2.2 当前工作树状态

M7A 已在工作树完成，前序 M2-M6 边界保持不变：

- `/api/chat` 入口迁到 `chat.api.ChatController`。
- 通用会话、消息、上下文、流式编排和展示编排归 `chat.application`。
- 跨模块聊天能力从 `chat.application.contract` 暴露。
- 业务扩展点从 `chat.application.contract.extension` 暴露。
- interview/default chat handler 归 `interview.application.chat`。
- knowledge QA handler 归 `knowledge.application.chat`。
- `knowledge.application.chatstream` 已退休。
- `TaskResponsePresentationService` 通过 presenter extension 与 interview 结果解耦。
- M3 chat persistence allowlist 已清零。
- `CodingPracticeController -> ChatMessageDO` 的原 M4 例外已提前清除。
- `practice` 已成为 coding-practice API、service、agent、task handler、chat handler/presenter、context source、DTO 和 skill 的唯一 owner。
- Chat、Agent、Skill 均通过各自已有扩展注册机制发现 Practice 实现；通用模块不 import Practice 具体类。
- `knowledge.application.coding` 与原 interview coding handler 例外已退休，并由 ArchUnit 防回归。
- M4 未新增或删除 persistence allowlist。
- M5 已将 Interview Agent、TaskHandler、context、answer evaluation、report skill 和 resume loader 归还 `interview`。
- 跨模块任务结果公开 `InterviewSessionStarted`、`InterviewAnswerResult` 与 `InterviewFinalReport`；`INTERVIEW_START` 在 Interview 模块内部将 `InterviewSession` 映射为稳定公开结果，外部模块不再依赖 Interview domain。
- generic agent 不再注入或 import concrete Interview Agent；knowledge 不再反向拥有 Interview evaluation delegate。
- 三条 M5 persistence 例外已删除，allowlist 从 17 降至 14。
- M6 已将 generic agent task/context 签名闭包和 extension SPI 公开到 `agent.application.contract`，业务实现及 schema 由各 owner 的 Spring bean 提供。
- M7A 已将 trace runtime、trace/metrics persistence、snapshot aggregation 和 operational query 归还 Observability，将显式反馈 persistence 归还 Feedback。
- Observability 通过 `FeedbackMetricsQuery` 获取反馈聚合；Interview 仅保留现有 API 的兼容委托和 Knowledge-owned evaluation。
- M7A 删除 10 条 persistence 例外，allowlist 从 14 降至 4；剩余仅为 M7B Media/Knowledge 与 M7C Search/Learning。
- M7B 已由 Knowledge 提供父块图片引用查询/替换 contract 和稳定 result view，Media 不再访问 Knowledge persistence；两条例外已删除，allowlist 从 4 降至 2。
- M7C 已由 Learning 提供用户知识状态 query contract 和稳定 result view，Search 不再访问 Learning persistence；最后两条例外已删除，allowlist 从 2 降至 0。
- M7D 已将 Spring Data Neo4j 节点、projection 和 repository 归入 `knowledge.infrastructure.graph`，Ingestion 与启动预热改用 Knowledge 公开契约；空的顶层 RAG 配置已删除，顶层 `graph`、`rag` 已退休。

当前工作树中还有用户维护的 `AGENTS.md` 修改和 `.DS_Store` 文件。它们不属于本路线的产物，不得在后续边界改造提交中被覆盖、删除或误提交。

### 2.3 验证基线

M3 工作树最近一次完整验证结果：

```text
Tests run: 297
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

已实际通过的检查包括：

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn test
mvn -q verify -DskipTests
git diff --check
```

后续每个 gate 都必须重新运行与影响范围匹配的验证，不能把本节结果当作未来改动已经通过的证明。

### 2.4 剩余结构问题

当前显式跨模块 persistence allowlist 已从 26 条下降到 0 条，新的跨模块 persistence 穿透会被 ArchUnit 直接拒绝；源码 import 图的其他历史边界债务仍需按后续 gate 收敛。此前较大的强连通分量涉及：

```text
agent, chat, config, feedback, ingestion, interview, knowledge,
media, modelrouting, observability, routing, search, skill
```

G1-G7/M7D 已依次修正 structured output、Practice、Interview、generic Agent runtime、Observability/Feedback ownership、Media/Knowledge、Search/Learning persistence 边界和 Graph/RAG ownership。顶层 `graph`、`rag` 已退休；剩余工作是 M8 的 contract-only、module cycle 与最终退休包硬规则收紧。

## 3. 设计不变量

所有后续 gate 必须遵守以下约束：

- 保持 Spring Boot 模块化单体和单 Maven module，不拆微服务或 Maven 多模块。
- 不改变现有 HTTP 路径、请求参数和响应结构。
- 不改变 SSE event 名称、payload、顺序、终止和取消语义。
- 不改变数据库表名、字段或 schema。
- 不为了形式完整创建空 facade、空 port、空 adapter 或空目录。
- 跨模块只有真实调用者存在时才创建 `application.contract`。
- 先建立真实契约和行为测试，再切换调用者，最后删除旧实现和 ArchUnit 例外。
- package ownership 迁移不与 DAG、竞速、replan、Agent Runtime 重写或大规模强类型化同时进行。
- 每个 gate 必须可独立编译、测试、review 和回退。
- 用户维护的未提交文件不得混入架构改造提交。

## 4. 路线方案与决策

### 4.1 方案 A：继续按旧编号机械推进

先在当前工作树上继续实施 M2，再处理 M3 的提交边界。

优点是表面上保持 `M0 -> M1 -> M2 -> M3` 顺序；缺点是会把 structured-output 迁移叠加到尚未提交的 chat 迁移上，问题归因、review 和回退都更困难。

不采用。

### 4.2 方案 B：一次完成剩余 ownership 迁移

同时迁 structured output、practice、interview agent、observability、graph 和 rag。

优点是目标状态一次到位；缺点是会同时改变数千行代码、Spring 注入图、handler 注册、HTTP/SSE 主链路和持久化访问边界，无法形成可靠的小步验证。

不采用。

### 4.3 方案 C：固化 M3，按依赖推进，并拆分原 M7

先把当前 M3 作为独立稳定基线，再处理它下游依赖的 structured output、practice、interview 和 agent，最后把原 M7 拆成多个风险独立的 gate。

优点是每个阶段都有唯一主要 owner、明确退出条件和可回退边界；缺点是提交数量更多，需要持续维护文档和 ArchUnit。

采用该方案。

## 5. 刷新后的 gate 总览

```text
G0  固化当前 M3 Chat Ownership
G1  M2 Structured Output Ownership
G2  M4 Practice Ownership
G3  M5 Interview Ownership
G4  M6 Generic Agent Runtime Cleanup
G5  M7A Observability and Feedback Persistence
G6  M7B Media/Knowledge + M7C Search/Learning
G7  M7D Graph/RAG Retirement
G8  Final ArchUnit Tightening
```

依赖关系：

```text
G0 M3 固化
  ↓
G1 Structured Output
  ├──────────────┐
  ↓              ↓
G2 Practice    G3 Interview
  └──────┬───────┘
         ↓
G4 Agent Runtime 精简
         ↓
G5 Observability / Feedback
         ↓
G6 Media/Knowledge + Search/Learning
         ↓
G7 Graph/RAG 退休
         ↓
G8 最终边界硬化
```

G2 和 G3 在 G1 完成后不存在直接代码依赖，可分别规划；为控制工作树规模，默认仍按 G2、G3 顺序串行实施。G4 必须等 G2、G3 的业务实现回归 owner 后才能真正收紧 generic agent。

## 6. G0：固化当前 M3 Chat Ownership

### 6.1 目标

把当前已完成的 M3 变成一个可审查、可提交、可作为后续迁移起点的稳定基线，不继续扩大 M3 范围。

### 6.2 保留的职责边界

- `chat.api`：通用 web chat HTTP/SSE 入口。
- `chat.application`：会话、消息、上下文、流式编排、handler registry 和通用展示编排。
- `chat.application.contract`：会话、消息、上下文、任务和展示公开能力。
- `chat.application.contract.extension`：scenario、streaming、intent policy 和 result presenter 扩展点。
- `interview.application.chat`：interview/default 具体 handler。
- `knowledge.application.chat`：knowledge QA 具体 handler。
- `interview.application.chat.ActiveCodingChatScenarioHandler`：仅作为 G2 前的显式临时归属，不得继续增加 practice 行为。

### 6.3 禁止扩大范围

G0 不实施 structured-output 迁移，不迁 practice，不修改 handler 优先级，不重构 SSE payload，不改变 `/api/chat` 路径，也不引入 DAG 或并行执行。

### 6.4 退出条件

- M3 diff 完成人工复查。
- `AGENTS.md` 和所有 `.DS_Store` 不进入提交。
- chat 相关 persistence allowlist 保持为零。
- `knowledge.application.chatstream` 不存在且有 ArchUnit 防回归。
- 编译、测试、ArchitectureRulesTest、verify 和 `git diff --check` 重新通过。
- M3 形成独立提交；是否 commit/push 由用户单独明确授权。

## 7. G1：M2 Structured Output Ownership

### 7.1 当前问题

以下通用模型输出类型仍错误地位于 `chat.application`：

```text
LlmJsonParser
JsonResult
SchemaSpec
RetryCall
```

它们被 modelrouting、routing、knowledge、agent 和 search 等模块使用，导致 `modelrouting -> chat`、`routing -> chat.application` 等反向或内部实现依赖。

### 7.2 目标归属

稳定跨模块类型放在：

```text
modelrouting.application.contract.structuredoutput
```

包括：

```text
StructuredOutputParser
StructuredOutputResult
StructuredOutputSchema
StructuredOutputRetry
```

默认解析实现放在：

```text
modelrouting.application.structuredoutput
```

接口不是为了分层形式创建：当前已经存在多个真实跨模块调用者，因此 structured-output 是 modelrouting 的真实公开能力。

### 7.3 迁移策略

1. 先用现有 `LlmJsonParserTest` 和相关消费者测试冻结 JSON 提取、修复、schema 校验、retry、warning 和 failure 行为。
2. 建立 modelrouting structured-output 公开契约和默认实现，算法保持不变。
3. 依次切换 modelrouting、routing、knowledge、agent 和 search 调用者。
4. 迁移测试包并删除 chat 中四个旧类型。
5. 新增 ArchUnit：
   - `modelrouting` 不得依赖 `chat`。
   - structured-output 类型只能由 `modelrouting` 拥有。
   - 外部模块只能依赖 modelrouting 的公开 contract，不得依赖其内部实现。

### 7.4 禁止范围

- 不顺便替换 Jackson。
- 不重写修复算法。
- 不改变重试次数或提示语义。
- 不修改各消费者的业务 fallback。
- 不在该 gate 迁移 practice 或 interview 类型。

### 7.5 退出条件

- `modelrouting -> chat` 为零。
- chat 不再拥有通用结构化输出解析类型。
- routing、knowledge、agent、search 通过 modelrouting contract 使用该能力。
- 原 parser 行为测试及所有受影响消费者测试通过。
- HTTP、SSE 和数据库行为不变。

## 8. G2：M4 Practice Ownership

### 8.1 当前问题

practice 业务实现分散在：

```text
interview.api.CodingPracticeController
interview.application.chat.ActiveCodingChatScenarioHandler
knowledge.application.coding.CodingPracticeService
agent.runtime.CodingPracticeAgent
agent.router.handler.CodingPracticeTaskHandler
agent.application.context.Coding*
skill.builtin.CodingInterviewCoachSkill
```

其中 `CodingPracticeAgent` 和 `CodingPracticeService` 体量较大，整批迁移风险过高。

### 8.2 目标归属

```text
practice.api
practice.application
practice.application.agent
practice.application.chat
practice.application.context
practice.application.dto
practice.domain
practice.infrastructure
```

只创建实际承载代码的包。若没有跨模块调用者，不创建额外 facade 或 port。

### 8.3 分批顺序

1. 建立 `/api/coding` characterization tests，固定现有请求和响应。
2. 迁 `CodingPracticeService` 及 application DTO/result。
3. 迁 `CodingPracticeAgent`，将其 nested records 提取到 `practice.application.dto`。
4. 迁 `CodingPracticeController`，保持所有 HTTP 路径不变。
5. 迁 `ActiveCodingChatScenarioHandler`，继续实现 chat-owned extension SPI。
6. 迁 `CodingPracticeTaskHandler` 和 Coding context sources，继续实现 agent-owned extension SPI。
7. 迁 `CodingInterviewCoachSkill`；通用 Skill registry 不感知 practice 具体实现。
8. 删除历史包并增加 ownership ArchUnit。

### 8.4 行为不变量

必须保持以下路径：

```text
POST /api/coding/batch-quiz/submit
POST /api/coding/scenario-card/submit
POST /api/coding/fill-card/submit
POST /api/coding/scenario-card/next
POST /api/coding/fill-card/next
```

只迁 owner，不改变当前 practice session 的内存状态、模型调用顺序、fallback、评分语义或部署模型。chat 只能依赖 `ChatScenarioHandler` SPI；agent 只能依赖通用 TaskHandler/context extension；skill registry 不得 import practice 实现。

### 8.5 退出条件（已完成）

- `practice` 成为 coding-practice 实现的唯一 owner。
- interview、knowledge、agent、skill 不再拥有 practice 具体实现。
- `knowledge.application.coding` 被删除并有 ArchUnit 防回归。
- `/api/coding` contract 和现有行为测试通过。
- 不新增跨模块 persistence allowlist。

## 9. G3：M5 Interview Ownership

### 9.1 迁移前问题（已解决）

M5 开始前，interview 业务实现分散在：

```text
agent.runtime.EvaluationAgent
agent.runtime.InterviewOrchestratorAgent
agent.runtime.KnowledgeLayerAgent
agent.runtime.RollingSummaryAgent
agent.router.handler.Interview*
agent.application.context.Interview*
knowledge.application.evaluation.InterviewAnswerEvaluationService
rag.core.ResumeLoader
skill.builtin.InterviewReportGeneratorSkill
```

当时剩余 allowlist 中还有三条 generic agent 访问 interview persistence 的 M5 例外；M5 已删除这些例外。

### 9.2 迁移原则

这些 Agent 本身是 interview 业务实现，应优先迁回 interview，而不是先为原错误 owner 创建 repository facade。迁移后同模块 application 对自身 persistence 的访问不再构成跨模块违规。

### 9.3 分批顺序

1. 固定面试开始、回答、结束、评价和滚动摘要行为。
2. 迁 interview agents。
3. 迁 interview task handlers。
4. 迁 interview context attributes 和 context sources。
5. 迁 `InterviewAnswerEvaluationService`。
6. 按真实职责迁移 `ResumeLoader`，默认归 interview；若源码证明其为通用 ingestion adapter，则归 ingestion。
7. 迁 interview report skill。
8. 删除三条 M5 allowlist 并增加 generic agent ownership 规则。

### 9.4 退出条件（已完成）

- generic agent 不再拥有 interview 具体 Agent、Handler 或 ContextSource。
- knowledge 不再拥有 interview answer evaluation。
- 三条 M5 persistence allowlist 删除。
- 面试 HTTP/SSE、会话 persistence 和报告行为不变。

## 10. G4：M6 Generic Agent Runtime Cleanup

### 10.1 已存在的扩展机制

项目已经具有：

```text
TaskHandler
TaskHandlerRegistry
AgentContextSource
AgentContextSourceRegistry
AgentContextAssembler
```

因此 G4 不重新设计 registry，而是把现有真实机制固化为 agent-owned extension contract，并清除 generic agent 对业务实现的感知。

### 10.2 目标

- generic agent 只拥有 Task/Handler contract、registry、生命周期、取消、A2A 和通用 context assembly。
- interview、practice、learning、knowledge 的 Handler 和 ContextSource 留在各自 owner。
- Spring 继续通过 `List<TaskHandler>`、`List<AgentContextSource>` 和 `List<AgentContextSchemaProvider>` 完成注册。
- task/context 签名闭包公开在 `agent.application.contract`，registry 与 assembler 保持内部实现。
- Knowledge、Interview、Practice 由 owner-local provider bean 提供 schema；generic Tool Task schema 因描述通用工具执行机制而保留在 agent。
- 删除 agent runtime 中的业务硬编码分支、中央业务 schema 和手写实现清单。

### 10.3 禁止范围

不在 G4 引入 DAG、重写 A2A、改变 task lifecycle，或一次性把所有 `Map<String, Object>` 替换为强类型。`TaskRequest` 强类型化需要单独设计和行为迁移计划。

### 10.4 退出条件（已完成）

- generic agent 不 import interview、practice、learning、knowledge 的具体实现。
- registry 只依赖 extension contract。
- 业务模块通过 Spring bean 实现扩展点。
- 不存在用于恢复旧方向的 central switch 或手写业务 class list。

## 11. G5：M7A Observability 与 Feedback Persistence

### 11.1 owner 决策

按业务语义而不是历史表名前缀确定 owner：

```text
t_rag_trace
t_rag_trace_node
t_rag_metrics_snapshot
    -> observability.infrastructure.persistence

t_rag_feedback
    -> feedback.infrastructure.persistence
```

数据库表名和 schema 保持不变。

### 11.2 职责边界

- 通用 trace、span、event、metrics、snapshot aggregation 和 operational query 归 observability。
- 用户点赞、点踩、复制等显式反馈用例和 persistence 归 feedback。
- observability 需要满意度汇总时，通过 feedback query contract 获取稳定聚合结果。
- feedback 关联 trace 时只保存稳定 trace ID；如需 trace 信息，通过 observability query contract 获取。
- 当前 `interview.application.ObservabilityApplicationService` 的跨域观测查询迁 observability。
- `knowledge.application.observability` 的通用实现和 persistence 迁 observability。

### 11.3 退出条件（已完成）

- observability 不依赖 knowledge。
- feedback 不依赖 knowledge persistence。
- knowledge 不再拥有通用 trace、metrics 和 feedback persistence。
- feedback/observability 相关的 10 条 M7 allowlist 删除；media/knowledge 和 search/learning 的 4 条保留到 G6。
- `knowledge.application.observability` 被退休并有 ArchUnit 防回归。
- trace、metrics、feedback 数据表和查询结果保持兼容。

## 12. G6：M7B Media/Knowledge 与 M7C Search/Learning

两个边界彼此独立，可以拆成两个独立提交，但共同目标是用稳定 query contract 替代 persistence 穿透。

### 12.1 M7B Media/Knowledge（已完成）

实现结果：

- Knowledge 提供 `ParentImageReferenceQuery`、`ParentImageReferenceCommand` 和稳定的 `ParentImageReferences` result view。
- `ParentImageReferenceService` 在 Knowledge 内部完成按文件路径查询和按 parent ID 替换图片引用，持久化 ownership 未移动。
- `media.application.ImageService` 只消费 Knowledge contract，不再 import Knowledge 的 `RagParentDO` 或 `RagParentMapper`；公开契约不暴露 MyBatis 查询/分页类型。
- ArchUnit 禁止 Media 依赖 Knowledge persistence，并禁止 Knowledge contract 暴露 infrastructure/framework 类型。
- 两条 media/knowledge allowlist 已删除；图片关联、评分、去重、排序、limit、空结果和父块引用替换行为保持不变。

### 12.2 M7C Search/Learning（已完成）

实现结果：

- Learning 提供 `UserKnowledgeStateQuery` 和稳定的 `UserKnowledgeStateView`，公开结果只包含 Search 实际需要的 topic 和 mastery score。
- `UserKnowledgeStateService` 在 Learning 内部完成 `user_id` 查询以及 DO 到稳定 result 的映射，persistence DO 和 Mapper ownership 未移动。
- `search.application.PersonalRanker` 只消费 Learning contract，不再 import `UserKnowledgeStateDO`、`UserKnowledgeStateMapper` 或 MyBatis wrapper。
- ArchUnit 禁止 Search 依赖 Learning persistence，并禁止 Learning contract 暴露 infrastructure/framework 类型。
- 最后两条 search/learning allowlist 已删除；弱项加权、空分数、重复 topic、排序、copy 和 limit 行为保持不变，跨模块 persistence allowlist 达到 0。

## 13. G7：M7D Graph/RAG Retirement

### 13.1 目标

退休含义模糊的顶层 `graph` 和 `rag` 包，将类型放回真实 owner。

### 13.2 实现结果（已完成）

- 核实后剩余四个 GraphRAG 类型全部带有 Spring Data Neo4j 节点、repository 或 projection 语义，因此整体迁入 `knowledge.infrastructure.graph`，没有把框架类型伪装为 `knowledge.domain`。
- Knowledge 新增 `KnowledgeGraphIndexCommand` 与 `KnowledgeGraphWarmup` 两个真实调用者驱动的公开契约；Ingestion 和启动预热不再跨模块注入 Neo4j repository。
- `KnowledgeGraphIndexService` 在 Knowledge 内部保持原有文件名到概念名、描述截断、wiki link 过滤、关系保存和 warmup 查询语义。
- `ResumeLoader` 已在 G3/M5 归 `interview.infrastructure.resume`，真实文件系统不存在顶层 RAG 副本。
- `VectorStoreConfig` 无调用方、无显式 `@Import`、没有活动 Bean，仅有注释示例；删除后 `VectorStore` 继续由 Spring AI 自动装配。
- 顶层 `graph`、`rag` 不再存在，ArchUnit 禁止重建，并禁止 Knowledge 外部模块依赖 `knowledge.infrastructure.graph`。
- Characterization tests 锁定节点标签、关系、默认类型、projection、repository 签名和两条 Cypher 查询。

## 14. G8：最终 ArchUnit 收紧（已完成）

G8 已在前述 gate 完成后启用以下硬规则：

1. 跨模块 persistence allowlist 必须为空。
2. 跨模块业务调用只能进入 provider 的：
   ```text
   application.contract
   application.contract.result
   application.contract.extension
   ```
3. `common` 不依赖业务模块。
4. `modelrouting` 不依赖 `chat`。
5. `observability` 不依赖 `knowledge`。
6. generic `agent` 不依赖 interview、practice、learning、knowledge 的具体实现。
7. Controller 不依赖 Mapper、外部 SDK、具体 Agent runtime 或 Tool executor implementation。
8. domain 不依赖 api、infrastructure、Spring Web、MyBatis 或供应商 SDK。
9. 顶层模块 cycle check 为硬规则。
10. 禁止退休包：
    ```text
    intent
    turn
    graph
    rag
    knowledge.application.chatstream
    knowledge.application.coding
    knowledge.application.observability
    ```

实现结果：

- 跨模块 persistence allowlist 永久保持 0，并有独立测试断言。
- 显式模块目录驱动 contract-only 与 cycle 规则；`bootstrap`、`config` 仅作为技术装配根，不作为业务边起点。
- 顶层 public-contract 图已无环，且没有 cycle ignore list。
- 真实反向协作改为消费者自有 extension SPI：Agent/Chat 提示词、Routing/Chat 提示词、Knowledge/Ingestion 支撑、Media/Ingestion 源路径、Media/Knowledge 父块图片引用、Learning/Knowledge 学习计划生成。
- `AgentTaskOperations` 统一 generic task dispatch，Agent 与 Interview 不再互相依赖具体 application implementation。
- Controller、domain、retired packages 以及 `common`、`modelrouting`、`observability`、generic Agent 的固定方向均由 ArchUnit 执行。

## 15. 每个 gate 的统一实施协议

每个 gate 的详细 implementation plan 必须包含：

1. 记录开始时的 `git status --short --branch`，保护用户未提交文件。
2. 先增加或确认 characterization tests。
3. 先建立真实公开契约，再切调用者。
4. 一次只迁一个职责切片，不同时改业务算法。
5. 每修复一条 persistence 穿透，同一批删除对应 allowlist。
6. 为已退休包和已修复依赖方向增加 ArchUnit。
7. 运行最窄测试后，再运行受影响模块和全量检查。
8. 同步 `ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md` 和边界 baseline。
9. 每个提交只承载一个可说明、可回退的架构结果。

后端 gate 的默认完整验证命令为：

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn test
mvn -q verify -DskipTests
git diff --check
```

如测试环境仍需要 Byte Buddy javaagent，implementation plan 必须记录本机实际使用的命令，不能省略后宣称全量测试通过。

## 16. 明确后置的架构主题

下列内容不属于本路线，必须等模块 ownership 和依赖方向稳定后另开设计：

- DAG、任务图、并行调度、竞速和 replan。
- 新 Agent Runtime 或外部 Agent 框架。
- Maven 多模块或微服务拆分。
- 数据库 schema 调整。
- SSE 协议升级。
- durable multi-instance task state。
- `TaskRequest` 和所有 context/payload Map 的全量强类型化。

原因是这些主题会改变执行或状态语义，而当前路线只解决“能力由谁拥有、跨模块通过什么契约协作”。两类风险不得在同一个迁移 gate 中叠加。

## 17. 下一步

1. 复查并固化 M8 最终边界治理的提交边界。
2. 在 M8 边界治理完成后，另开设计评估 Outbox、PostgreSQL 真相源和 `local-lite` 降级增强。
