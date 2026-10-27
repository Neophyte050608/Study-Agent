# Package Conventions

## 1. Naming
- Package names use lowercase and meaningful domain nouns.
- Class names use `UpperCamelCase`.
- Method/field names use `lowerCamelCase`.

## 2. Domain-first placement
- New code must be placed by business domain first, not by technical type first.
- Example:
  - Good: `io.github.imzmq.interview.knowledge.application.KnowledgeQueryService`
  - Avoid: `io.github.imzmq.interview.service.KnowledgeQueryService`
- Retired type-first/aggregate roots: do not add new business code under `io.github.imzmq.interview.service`, `io.github.imzmq.interview.entity`, `io.github.imzmq.interview.mapper`, or `io.github.imzmq.interview.dto`.

## 3. Recommended subpackages
- `api`: controllers and controller-facing request/response DTOs.
- `application`: use-case orchestration and application-internal DTOs.
- `domain`: entities, value objects, core policy.
- `infrastructure`: persistence and external system adapters.

## 4. Dependency boundaries
- Controllers must not directly call MyBatis Mapper interfaces.
- Domain objects must not depend on controller/service packages.
- Agent layer must not depend on controller layer.
- Application code should not expose persistence-specific DTOs upward.

## 4.1 Cross-module public contracts

- The only normal cross-module business entry is `<owner>.application.contract`.
- Create a contract only when a real external module calls the capability; do not add empty facades or speculative ports.
- Provider-owned command/query/result types stay with the provider. Do not move business DTOs into `common`.
- Contracts must not expose Mapper, DO, infrastructure adapter, Spring Web, or vendor SDK types.
- Cross-module access to `<owner>.application.dto`, use-case implementations, helpers, and `infrastructure` is forbidden.
- A module may implement a real consumer-owned extension contract under `<consumer>.application.contract.extension`; the consumer depends on its SPI, not the collaborating module’s concrete implementation. Use this dependency-inversion pattern only when a normal provider contract would create a reverse edge.

## 4.2 Routing public surface after M1

Routing owns intent understanding, turn-relation classification, intent domain types, and routing decisions:

- Stable cross-module entry: `routing.application.contract.UnderstandingService`.
- Provider-owned public results: `routing.application.contract.result`.
- Intent domain model: `routing.domain`.
- Internal implementation only: `routing.application.understanding`; callers must not import it.
- Top-level `intent` and `turn` packages are prohibited and must not be recreated.

The current shadow caller is `chat.application.IntentShadowObserver`. It observes the result after legacy routing and must not use it to select a handler or mutate the existing route response. The canonical configuration prefix is `app.routing.understanding.shadow.*`; the legacy `app.turn.understanding.shadow` key is enable-only compatibility, not a package ownership rule.

## 4.3 Chat public surface after M3

- Stable cross-module entries: `chat.application.contract.ChatSessionOperations`, `ChatMessageOperations`, `ChatContextOperations`, `ChatTaskOperations`, `TaskResponsePresentationOperations`, and `ChatMemoryOperations`.
- Stable output records: `chat.application.contract.result.ChatSessionView`, `ChatMessageView`, and `ChatMemoryView`.
- Scenario extension SPI: `chat.application.contract.extension.ChatScenarioHandler`, `StreamingChatContext`, `ChatStreamingOperations`, `ChatIntentPolicy`, and `ChatTaskResultPresenter`.
- Generic registry and streaming implementations stay internal to `chat.application`; external handlers must not inject `WebChatService`, `ChatStreamingSupport`, or other chat implementation classes.
- Concrete interview/default handlers belong to `interview.application.chat`; knowledge QA belongs to `knowledge.application.chat`.
- Coding chat handlers and result presentation belong to `practice.application.chat` and implement only chat-owned extension contracts.
- `knowledge.application.chatstream` is retired and must not be recreated.


### Chat structured-memory ownership

Structured cross-session memory remains part of the Chat domain; it is not a top-level module and does not justify a separate Maven module:

- `chat.domain.memory`: framework-neutral categories, states, candidate/source values, normalization, and quote-validation rules. It must not depend on Spring, MyBatis, Milvus, Chat API, or infrastructure types.
- `chat.application.memory`: extraction-task submission, candidate validation/commit, recall, projection/outbox workers, repair, management, configuration, and orchestration. It owns the use-case flow but does not expose persistence or Milvus SDK types through Chat's public contracts.
- `chat.infrastructure.persistence.memory`: MySQL truth-source DOs and MyBatis mappers for memory items, sources, extraction tasks, and outbox events. Other modules must not import this package.
- `chat.infrastructure.milvus`: versioned vector projection, query/delete/scan adapter, and schema initialization. This is the only Chat package allowed to import the Milvus SDK; Milvus is a derived projection, never the memory truth source.

Cross-module callers use `chat.application.contract.ChatMemoryOperations` and stable JDK-only result records. Chat memory publishes operational events only through `observability.application.contract.OperationalObservationPublisher`; it must not import Observability's internal application implementation, local persistence, or an external telemetry SDK. The legacy `t_user_chat_memory.memory_text` path is not an active writer or reader for structured memory; existing schema, rows, and V1/V2 seed data remain untouched.

## 4.4 Modelrouting structured-output public surface after M2

- Stable cross-module entry: `modelrouting.application.contract.structuredoutput.StructuredOutputParser`.
- Provider-owned public types: `StructuredOutputResult`, `StructuredOutputSchema`, and `StructuredOutputRetry` in the same contract package.
- Internal default implementation: `modelrouting.application.structuredoutput.DefaultStructuredOutputParser`.
- External production modules depend on the parser interface and contract types only; they must not import the default implementation.
- Jackson `JsonNode` remains the current result tree shape for behavior compatibility. Do not broaden the contract with Spring Web, persistence, MyBatis, or model-provider SDK types.
- Do not recreate `LlmJsonParser`, `JsonResult`, `SchemaSpec`, or `RetryCall` under chat or common.


## 4.5 Practice ownership after M4

- HTTP entry: `practice.api.CodingPracticeController`; all `/api/coding` paths remain owned by Practice.
- Use-case logic and DTOs: `practice.application` and `practice.application.dto`.
- Coding Agent and task handler: `practice.application.agent`; the generic agent router discovers the handler through `TaskHandlerRegistry` and must not inject `CodingPracticeAgent`.
- Coding context sources: `practice.application.context`; they implement the generic agent context extension contracts.
- Coding chat scenarios and task-result presenter: `practice.application.chat`; Chat discovers them through its extension registry.
- Coding interview skill: `practice.application.skill`; the generic skill registry discovers it through `ExecutableSkill`.
- `knowledge.application.coding` and coding-practice implementations under `agent`, `interview`, or `skill` are retired.

## 4.6 Interview ownership after M5

- Interview Agents and task handlers: `interview.application.agent`; generic `agent` discovers handlers through its registry and must not import Interview implementations.
- Interview context attributes and sources: `interview.application.context`; they implement generic agent context contracts.
- Answer evaluation: `interview.application.evaluation`; Knowledge may expose retrieval output, but must not own or delegate Interview evaluation.
- Cross-module task-visible results: `interview.application.contract.result.InterviewSessionStarted`, `InterviewAnswerResult`, and `InterviewFinalReport`. These records may contain only stable business/JDK types.
- `INTERVIEW_START` maps the internal `InterviewSession` entity to `InterviewSessionStarted`; modules outside Interview must not inspect or import Interview domain types.
- Report skill: `interview.application.skill`; resume loading adapter: `interview.infrastructure.resume`.
- Historical Interview implementations under `agent.runtime`, `agent.router.handler`, `agent.application.context`, `knowledge.application.evaluation`, top-level `rag`, or generic `skill.builtin` are retired and must not be recreated.

## 4.7 Generic Agent public surface after M6

- Task request/response/type/execution mode: `agent.application.contract.task`.
- Context query/schema/slot/result value types: `agent.application.contract.context`.
- Extension SPI: `agent.application.contract.extension.TaskHandler`, `AgentContextSource`, and `AgentContextSchemaProvider`.
- Registry and assembly implementations stay internal to `agent`; external modules implement the SPI as Spring beans and must not inject or import concrete registry internals unless they are assembling the generic runtime itself.
- Knowledge QA Agents, handlers, context sources, attributes, and schema provider belong to `knowledge.application.agent` or `knowledge.application.context`.
- Learning Agents, handlers, and context sources belong to `learning.application.agent` or `learning.application.context`.
- Interview and Practice provide owner-local context schema beans from `interview.application.context` and `practice.application.context`.
- Tool Task context sources, attributes, and schema stay in `agent.application.context` because they are generic tool-execution mechanics. Do not move a business schema into generic agent for central registration.
- Add a new context mode by supplying an owner-local `AgentContextSchemaProvider`; do not restore static defaults, a mode switch, or a handwritten provider list in `AgentContextAssembler`.

### M9 Agent public-surface closure

- Generic task dispatch: `agent.application.contract.AgentTaskOperations`; external modules must not inject `TaskRouterAgent` or any other `agent.runtime` implementation.
- Business asynchronous publish/subscribe: `agent.application.contract.AgentMessagingOperations` with `command.AgentMessageCommand` and `message.AgentMessage`/`AgentMessageIntent`. External modules must not construct `A2AMessage` or import `agent.a2a`.
- A2A operational administration: `agent.application.contract.AgentA2AOperations` and `result.A2ABatchReplayResult`. Controllers call this contract for idempotency inspection/clear and DLQ replay; RocketMQ-specific checks remain inside Agent.
- `agent.a2a` is internal to Agent. `config.agent` may depend on it only as the Spring composition root. Do not expose A2A metadata/status/trace types from a public contract.
- Every public application contract is covered by the repository-wide contract-purity rule; keep public records limited to stable business concepts and JDK types.

## 4.8 Observability and Feedback ownership after M7A

- Trace runtime, node definitions, event bus, and local trace orchestration: `observability.application.trace`.
- Operational trace/metrics/dashboard queries: `observability.application.ObservabilityQueryService`.
- Hourly metrics aggregation and retention: `observability.application.metrics.MetricsSnapshotScheduler`.
- Trace and metrics persistence: `observability.infrastructure.persistence`; the existing `t_rag_trace`, `t_rag_trace_node`, and `t_rag_metrics_snapshot` table names remain unchanged.
- Explicit user feedback use cases and `t_rag_feedback` persistence: `feedback.application` and `feedback.infrastructure.persistence`.
- Cross-module feedback aggregates are exposed only through `feedback.application.contract.FeedbackMetricsQuery` and `feedback.application.contract.result.FeedbackMetrics`; callers must not depend on the concrete service or persistence types.
- `interview.application.ObservabilityApplicationService` is a compatibility orchestrator for existing API signatures and Knowledge-owned evaluation. New operational observability behavior belongs to Observability.
- `knowledge.application.observability` is retired and must not be recreated.

## 4.9 Media and Knowledge public surface after M7B/M8

- M7B first removed the persistence penetration through Knowledge-owned query/command contracts. M8 then inverted the remaining public-contract cycle through the Media-owned `media.application.contract.extension.ParentImageReferenceSupport`.
- `ParentImageReferenceSupport.ParentImageReferences` contains only parent ID, file path, and image IDs; it must not expose `RagParentDO`, `RagParentMapper`, query wrappers, or MyBatis pagination types.
- `knowledge.application.indexing.ParentImageReferenceService` implements the Media SPI while Knowledge keeps parent persistence under `knowledge.infrastructure.persistence`. Media does not import Knowledge contracts or infrastructure.
- Source paths used by image resolution and file-serving validation come from the Media-owned `MediaSourcePathSupport`; Ingestion implements it by mapping the existing `paths` and `imagePath` configuration values. Path ordering, newline splitting, normalization, and validation behavior stay in Media.
- New parent/image persistence behavior stays inside Knowledge; new ingestion-configuration behavior stays inside Ingestion. The Media SPIs expose only the values required by Media.

## 4.10 Search and Learning public surface after M7C

- User knowledge-state lookup is exposed by `learning.application.contract.UserKnowledgeStateQuery`.
- The public result `learning.application.contract.result.UserKnowledgeStateView` contains only the stable topic and mastery-score fields required by Search; do not expose `UserKnowledgeStateDO`, `UserKnowledgeStateMapper`, query wrappers, or MyBatis pagination types.
- The implementation belongs to `learning.application.profile.UserKnowledgeStateService`; Learning persistence remains under `learning.infrastructure.persistence`.
- Search may consume this contract but must not access Learning infrastructure. Personal ranking weights, weak-topic matching, sorting, and limits stay owned by `search.application.PersonalRanker`.

## 4.11 Knowledge graph ownership after M7D

- Spring Data Neo4j nodes, projections, and repositories belong to `knowledge.infrastructure.graph`; framework-annotated persistence models must not be placed in `knowledge.domain`.
- Cross-module graph writes use `knowledge.application.contract.command.KnowledgeGraphIndexCommand` and its stable source-document record.
- Cross-module startup probing uses `knowledge.application.contract.KnowledgeGraphWarmup`; callers must not inject `TechConceptRepository`.
- `knowledge.application.indexing.KnowledgeGraphIndexService` is the adapter-facing implementation. Only Knowledge code may depend on `knowledge.infrastructure.graph`.
- Top-level `graph` and `rag` packages are retired. Vector-store runtime beans continue to come from Spring AI auto-configuration; do not recreate an empty placeholder configuration.

## 4.12 Final M8 contract and cycle rules

- `ModuleBoundaryRules` uses the explicit top-level module catalog; adding a capability owner requires updating the catalog and the architecture documentation together.
- `bootstrap` and `config` are technical composition roots. They may wire concrete beans but are excluded as business edge origins.
- Cross-module application implementation imports are forbidden. Normal calls use the provider’s `application.contract`; dependency inversion uses a narrow consumer-owned `application.contract.extension` SPI with an active adapter.
- The public-contract module graph must stay acyclic. Do not add a cycle allowlist, package exclusion, or synthetic facade to silence the rule.
- M8 consumer-owned SPIs include Agent and Routing prompt rendering, Knowledge/Ingestion collaboration, Media source paths and parent-image references, and Learning plan generation.
- The cross-module persistence allowlist is permanently empty. A new violation is a defect to repair, not baseline debt to record.

## 5. Migration rule
- Existing migration to domain packages is complete for main business services and MyBatis persistence code.
- Top-level `entity`, `mapper`, and `dto` are retired for new business code; do not reintroduce them as acceptable placement.
- Any new module or major refactor must follow this document.
- Any package move should be done in small batches with compile + test pass per batch.

## 6. Persistence Placement

MyBatis persistence classes are domain-owned. DO classes and Mapper interfaces live under:

`io.github.imzmq.interview.<domain>.infrastructure.persistence`

Do not add new business persistence code under top-level `entity`, `mapper`, or `dto` packages.

DTO placement:
- Controller-facing DTOs belong in `<domain>.api`.
- Application-internal DTOs belong in `<domain>.application.dto`.
- Persistence-specific DTOs/records belong in `<domain>.infrastructure.persistence` with their owning domain adapter.

## 7. Placement Cheatsheet (Target Ownership)

- Generic chat session/message/context/stream orchestration: `chat`.
- Interview lifecycle, answer evaluation, report, resume, and interview-specific Agent/Handler/context: `interview`.
- Coding practice generation/evaluation/session and practice-specific Agent/Handler/context: `practice`.
- Learning profile/events/plans and learning-specific Agent/Handler/context: `learning`.
- Knowledge catalog/indexing/retrieval/evidence/local graph/GraphRAG: `knowledge`.
- Intent understanding, clarification, relation judgment, and routing: `routing`.
- Generic Task/Handler contracts, registry, lifecycle, cancellation, A2A, and context assembly mechanism: `agent`.
- Generic Skill contract/registry/invocation: `skill`; a business-specific Skill implementation belongs to its business owner.
- Generic Tool contract/authorization/execution: `tool`; MCP protocol adaptation belongs to `mcp`.
- Model routing, health, execution, failover, and structured model output: `modelrouting`.
- Trace, span, metrics, and operational query: `observability`.
- Cross-domain SSE connection/transport only: `common.stream`.

Knowledge package ownership for new code:

- `knowledge.application.catalog`: knowledge base and document catalog use cases.
- `knowledge.application.indexing`: chunking, tokenizer, lexical, parent-child, and Neo4j graph index use cases.
- `knowledge.application.retrieval`: retrieval orchestration, fusion, query rewrite, evidence evaluation, and fallback.
- `knowledge.application.localgraph`: local graph retrieval chain.
- `knowledge.application.evaluation.goldset`: dataset-neutral JSONL contracts, validation, retrieval metrics, local BM25,
  isolated-vector comparison, application ports, and checkpoint contracts. It must not depend on Milvus SDK,
  Spring AI embedding SDK, or `knowledge.infrastructure.evaluation`.
- `knowledge.domain`: knowledge and GraphRAG domain types without Spring Data or vendor dependencies.
- `knowledge.infrastructure.persistence`: knowledge-owned MyBatis DOs and Mappers.
- `knowledge.infrastructure.graph`: Neo4j/Spring Data graph adapters and repositories.
- `knowledge.infrastructure.evaluation`: JSONL file loading, atomic checkpoint/report files, explicit operation runner,
  and isolated Milvus adapter/schema. Keep benchmark infrastructure separate from normal knowledge ingestion and retrieval.

Evaluation guardrails:

- Do not add dataset-specific adapters, collection names, configuration properties, or Java classes; dataset identity is runtime data.
- Do not route isolated external-dataset comparisons through `RAGService` or label isolated-vector results as production/hybrid retrieval.
- `VALIDATE`, `INDEX`, `COMPARE`, and `CLEAN` remain explicit operations; real embedding calls require operator awareness and approval.

Current retrieval implementation (no additional module or plugin framework):

- `KnowledgePacketBuilder` remains the knowledge-packet workflow coordinator: rewrite, retrieve, images, evidence decision and Web fallback. It directly composes the retrieval services and records child traces; the former `RetrievalDelegate` callback into `RAGService` is removed.
- `knowledge.application.retrieval.HybridEvidenceRetriever` owns the three fixed lexical/vector/graph recall channels and calls fusion, parent-child hydration, then reranking in the existing order. It uses the existing executor and Knowledge adapters.
- `knowledge.application.retrieval.EvidenceFusionService` owns RRF deduplication, source/channel weights, overlap reranking and quality scores. It does not access vector/graph/persistence adapters or external services.
- `knowledge.application.retrieval.GroundingContextAssembler` owns parent-child context windows, evidence numbering, document summaries and image filtering. Parent lookup reuses `ParentChildIndexService`; it does not perform vector/graph/Web recall.
- `RAGService` retains the knowledge-packet compatibility entry/record and existing interview/learning generation methods. It no longer owns retrieval adapters or algorithms; root trace lifecycle stays here.
- Public `KnowledgeRetrievalOperations` and its SDK-neutral result are unchanged. Internal Spring AI `Document` remains internal; do not expose it in application contracts. No `RetrievedEvidence`, generic channel interface, registry or engine wrapper is needed for this slice.

Historical packages are migration-only:

- Do not add generic chat code to `knowledge.application.chatstream` or `knowledge.application.context`.
- Do not add coding-practice behavior to `knowledge.application.coding`.
- `knowledge.application.observability` is retired; do not recreate it or place generic trace/metrics behavior under Knowledge.
- Do not add new code to top-level `intent`, `turn`, `graph`, or `rag`.
- Do not add business-specific Agent, Handler, context source, or schema to the generic `agent` module.
- `RAGService` remains a compatibility facade during migration; new retrieval behavior belongs to focused knowledge services.

## 8. Hard Guardrails
- Controllers cannot use MyBatis Mapper interfaces directly.
- `domain` packages cannot depend on controller packages.
- `application` should not expose persistence-specific DTOs upward.
- MyBatis DO classes and Mapper interfaces must stay in `<domain>.infrastructure.persistence`.
- Controller-facing DTOs must stay in `<domain>.api`; application-internal DTOs must stay in `<domain>.application.dto`.
- New code that violates package placement should be moved before merge.
- Cross-module business calls must target the provider's `application.contract`.
- A module must not access another module's Mapper, DO, repository implementation, or other infrastructure class.
- `common` must not depend on a business module.
- `modelrouting` must not depend on `chat`; shared structured-output logic belongs to `modelrouting`.
- `observability` must not depend on `knowledge`; business modules record telemetry through observability contracts.
- Generic `agent` runtime must not import concrete interview, practice, learning, or knowledge Agent/Handler implementations.
- Existing M0 exceptions are listed in `docs/architecture/module-boundary-baseline.md`; do not add new exceptions.

## Fixed learning-plan preview DAG (2026-10-27)

- Generic execution contract: `agent.application.contract.execution` (`DagNode`, `DagNodeResult`, `DagExecutionResult`, `DagExecutionOperations`). Dependency edges express waiting; successful-input requirements are explicit. Do not expose executors, futures, persistence types, or business implementations.
- Pure graph validation: `agent.domain.execution.DagStructure`; bounded request-scoped scheduling: `agent.application.execution.FixedDagExecutor`. Neither owns Learning/Knowledge business nodes.
- Learning owns `learning.application.planning.LearningPlanPreviewService`, the three-node graph, profile summarization, and degradation semantics. The explicit `LEARNING_PLAN_PREVIEW` handler lives in `learning.application.agent.handler`; it is distinct from the existing note-writing `LEARNING_PLAN` handler.
- Learning-owned SPIs: `learning.application.contract.extension.LearningPlanEvidenceReader` and `LearningPlanPreviewGenerator`; immutable inputs/results/references live in `learning.application.contract.result`.
- Knowledge supplies both SPIs from `knowledge.application.planning`: a bounded lexical-only evidence reader and content-only model generator. Learning imports no Knowledge implementation or contract, preserving the existing dependency direction.
- This preview must not invoke NoteMakingAgent, legacy LearningPlanGenerator, Skill, MCP, tools, profile writes, or full RAG/KnowledgePacketBuilder. Evidence reading only calls lexical `search`; generation consumes supplied materials without additional retrieval.
- TaskHandler may return an explicit `TaskResponse` to preserve failure/degradation semantics; legacy handlers returning ordinary data remain wrapped as successful data.

### 持久固定图预览补充

- Agent durable 公开能力和 workflow SPI：`agent.application.contract.execution.durable`。Learning 实现 Agent-owned SPI，不允许 Agent 引用 Learning 具体类。
- JSON 预算、registry、短事务、后台控制器：`agent.application.execution`；节点依赖闭包/图结构：`agent.domain.execution`（纯 Java）。
- 任务/节点 DO、三表 MyBatis SQL：`agent.infrastructure.persistence.execution`，迁移 V5；attempt 只经内部 mapper 操作，不向公开结果暴露。
- Learning 固定图与版本化 codec/提交服务：`learning.application.planning`，公开 `learning.application.contract.DurableLearningPlanOperations`。Knowledge 继续实现原来的只读窄 SPI，不访问 Agent 存储。
- 数据库持久失败不得降级成未检查点计算；技术任务状态不并入 Chat 长期记忆。


### 持久预览 HTTP 归属（第三步）

- `learning.api`：四路由 Controller、提交/重试 DTO、API-owned `LearningPlanTaskResponse`（含计划/证据/节点响应）、局部最高优先级错误 advice；只做参数/可信身份/HTTP 转换，不访问 Agent、DO/Mapper、Learning planning 实现或执行 JSON ObjectMapper。
- `learning.application.contract.DurableLearningPlanOperations` 返回 Learning-owned `result.DurableLearningPlanView`，最终结果为类型化 `LearningPlanPreview`；固定错误原因由 `LearningPlanAccessException` 暴露，不携带原始异常 cause/message。
- `learning.application.planning.LearningPlanTaskPresenter` 严格校验 v1 结果字段、预算及节点状态；提交服务负责工作流类型/版本隔离和底层错误翻译。Agent `DurableTaskView` 的 workflow 元数据仅给 application 验证，不对 HTTP 返回。
- 可信身份仍经 Identity 公开契约 `resolveTrusted`；不自行解析 JWT、不信任请求 owner/userId/context。此入口拒绝会被清理/截断的 principal，open 明确为单用户。

## Controlled dynamic planning ownership (2026-10-28)

- Generic candidate/validated plan contracts, structural validation, capability registry, fingerprinting, and compilation into the existing bounded DAG executor belong to Agent execution. These types remain independent of product modules and model/vendor SDKs.
- Business capability implementations and scenario policy belong to the business owner. Learning owns its profile/evidence/compose capabilities and the policy that permits their graph shapes; Agent must not discover business implementations by Bean name or class name.
- LLM planning calls enter ModelRouting only through the provider-owned `modelrouting.application.contract.DynamicPlanningModelOperations`. ModelRouting infrastructure implements that contract, preserving the one-way `agent -> modelrouting` dependency and avoiding a module cycle.
- A dynamic plan may reference only stable capability IDs and versions. It must not reference Spring Bean names, implementation class names, Tool executors, Agent runtime implementations, MyBatis Mapper/DO types, or persistence adapters.
- Only request-scoped Learning Plan Preview may select a validated dynamic graph. The versioned durable workflow remains the fixed three-node graph until a separately designed persistence migration explicitly changes it.
