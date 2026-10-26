# Architecture Baseline

## Goal
- Keep business logic easy to locate for interview review and daily maintenance.
- Prevent architecture drift with automated guardrails.
- Keep package placement domain-first and avoid regression to type-first buckets.

## Package Strategy
- Root package: `io.github.imzmq.interview`
- Main style: `package-by-feature` (domain-first), then layered subpackages.
- Legacy aggregate packages `...service`, top-level `entity`, top-level `mapper`, and top-level `dto` have been retired for main business code.

## Target Module Map

The codebase remains a modular monolith. Top-level packages are capability owners, not deployment units.

- Product capabilities: `chat`, `interview`, `practice`, `learning`, `knowledge`, `feedback`.
- Understanding and decision: `routing`.
- Execution and extension: `agent`, `skill`, `tool`, `mcp`.
- Platform support: `modelrouting`, `observability`, `ingestion`, `media`, `search`, `im`, `identity`, `security`, `menu`.
- Technical support: `common`, `bootstrap`, `config`.

The M0-M7 ownership migration is complete. New code must use the target owner immediately; retired top-level packages are guarded by architecture tests.

Ambiguous top-level ownership is retired as follows:

- `intent` has merged into `routing.domain`.
- Spring Data Neo4j nodes, projections, and repositories belong to `knowledge.infrastructure.graph`; they are persistence adapter types, not pure domain objects.
- The ambiguous top-level `graph` and `rag` packages are retired; Knowledge graph behavior is exposed to real cross-module callers through Knowledge application contracts.
- Coding practice is the independent `practice` capability.

The full class migration catalog is defined in `docs/superpowers/specs/2026-10-23-module-boundary-and-package-ownership-design.md`.

## Public Application Surface

A module exposes business capabilities only from `<module>.application.contract`. Create a contract only for a real cross-module caller.

Recommended contract structure:

```text
<module>/
  api/
  application/
    contract/
      command/
      query/
      result/
    dto/
  domain/
  infrastructure/
```

The `command`, `query`, and `result` subpackages are optional. Public contracts must use business language and must not expose MyBatis DOs, Mapper interfaces, Spring Web types, vendor SDK types, or persistence pagination objects.

### Chat public surface after M3

Generic web chat is owned by `chat`: `chat.api.ChatController` is the unchanged `/api/chat` HTTP/SSE entry, and session/message/stream orchestration stays under `chat.application`.

- Cross-module session, message, context, task, presentation, and structured-memory management capabilities are exposed from `chat.application.contract`; session/message/memory results are immutable records under `chat.application.contract.result` and do not expose persistence DOs, hashes, or vector SDK types.
- Business-owned scenario handlers implement the chat-owned SPI in `chat.application.contract.extension`. The chat registry discovers `List<ChatScenarioHandler>` through Spring ordering and never imports a concrete interview or knowledge handler.
- Interview result formatting is supplied by the interview-owned `InterviewTaskResultPresenter`; chat's generic presenter no longer imports interview domain/runtime result types.
- Interview, knowledge, and practice handlers may depend on chat contracts only. They cannot access `chat.application` implementations or `chat.infrastructure`.
- Practice supplies `ActiveCodingChatScenarioHandler`, `CodingPracticeCardChatScenarioHandler`, and `CodingPracticeTaskResultPresenter` through the chat-owned extension SPI. Generic chat does not import these concrete implementations.

M3 removed every chat persistence allowlist entry. It also removed the former M4 `CodingPracticeController -> ChatMessageDO` edge early because that controller now uses `ChatMessageOperations`.

Structured Chat memory remains Chat-owned: `ChatMemoryOperations` is the SDK-neutral management boundary, while MySQL mappers and the Milvus adapter remain internal. Session deletion is coordinated transactionally inside Chat so logical session/message deletion, unfinished extraction cancellation, source invalidation, memory versioning, and projection Outbox creation cannot diverge.

### Practice ownership after M4

Coding practice is owned end-to-end by `practice`: `/api/coding`, question generation and evaluation, the in-memory practice session, the coding Agent and TaskHandler, chat handlers/presenter, context sources, application DTOs, and the coding interview skill all live under `practice`.

- Chat discovers practice handlers and result presentation through `ChatScenarioHandler` and `ChatTaskResultPresenter`.
- Agent discovers `CodingPracticeTaskHandler` and coding context sources through the existing handler/context registries; generic routing does not inject `CodingPracticeAgent`.
- Skill discovery uses the generic `ExecutableSkill` registry; generic skill code does not import `CodingInterviewCoachSkill`.
- `knowledge.application.coding` and the former interview-owned coding handler exception are retired and guarded by ArchUnit.
- M4 adds no cross-module persistence exception. The allowlist remains at 17 exact dependency pairs.

### Interview ownership after M5

Interview lifecycle, answer evaluation, report generation, resume input, interview-specific Agents, TaskHandlers, and context sources are owned by `interview`. Generic `agent`, `knowledge`, top-level `rag`, and generic `skill` no longer own or import these concrete implementations.

- Interview-specific Agents and handlers live under `interview.application.agent`; generic routing continues to dispatch through `TaskHandlerRegistry` and has no concrete `InterviewOrchestratorAgent` dependency.
- Interview context sources implement the generic agent context extension contracts from `interview.application.context`.
- Answer and final-report task results consumed across modules are immutable records under `interview.application.contract.result`: `InterviewAnswerResult` and `InterviewFinalReport`. Start continues to return the existing `InterviewSession`; no synthetic start-result contract was introduced.
- `InterviewAnswerEvaluationService` belongs to Interview and consumes Knowledge output; `RAGService` no longer exposes Interview evaluation delegates or depends on Interview evaluation.
- Resume loading and the report-generation skill live under `interview.infrastructure.resume` and `interview.application.skill`.
- M5 removed all three generic-agent-to-Interview persistence exceptions. At the M5 checkpoint, the allowlist contained 14 exact M7 dependency pairs.

### Generic Agent Runtime boundary after M6

Generic `agent` owns the reusable task/context extension contract and runtime mechanics, not business-specific Agents, handlers, context sources, or schemas.

- Public task and context value types live under `agent.application.contract.task` and `agent.application.contract.context`.
- Spring extension SPI lives under `agent.application.contract.extension`: `TaskHandler`, `AgentContextSource`, and `AgentContextSchemaProvider`.
- `TaskHandlerRegistry`, `AgentContextSourceRegistry`, and `AgentContextAssembler` remain internal implementations. Spring discovers `List<TaskHandler>`, `List<AgentContextSource>`, and `List<AgentContextSchemaProvider>`; registries and the assembler do not maintain concrete business class lists.
- Knowledge QA and Learning implementations now live under their real owners. Interview and Practice implementations remain under their owners from M5/M4.
- Knowledge, Interview, and Practice provide their context schemas from owner-local provider beans. Generic Tool Task context and its schema remain in `agent` because they describe reusable execution plans, tool state, task memory, and generic tool constraints rather than a business capability.
- Query-level context schema override, slot order and budgets remain supported. Duplicate provider modes and missing registered schemas fail explicitly.
- M6 changes no HTTP/SSE contract, A2A behavior, task lifecycle, model invocation, fallback, persistence ownership, or deployment topology. At the M6 checkpoint, the cross-module persistence allowlist therefore remained at 14 exact M7 pairs.

### Observability and Feedback ownership after M7A

M7A assigns operational telemetry and explicit user feedback to their semantic owners without changing the existing tables, HTTP/SSE contracts, trace lifecycle, scheduling windows, or fallback behavior.

- Trace runtime and event delivery live under `observability.application.trace`; trace and metrics DOs/Mappers live under `observability.infrastructure.persistence`.
- `observability.application.ObservabilityQueryService` owns recent/active trace queries, trace detail and overview, feedback-backed dashboard aggregates, metrics history/summary, and manual snapshot triggering.
- `observability.application.metrics.MetricsSnapshotScheduler` owns hourly snapshot aggregation and consumes only `feedback.application.contract.FeedbackMetricsQuery` for thumbs-up, thumbs-down, and copy counts.
- `feedback.application.FeedbackApplicationService` owns `t_rag_feedback` persistence and implements the stable feedback aggregate query contract without exposing MyBatis types.
- `interview.application.ObservabilityApplicationService` remains a compatibility orchestrator for existing controller signatures and Knowledge-owned evaluation; operational observability methods delegate to `ObservabilityQueryService` and no longer access Observability or Feedback persistence.
- Existing table names remain `t_rag_trace`, `t_rag_trace_node`, `t_rag_metrics_snapshot`, and `t_rag_feedback`.
- `knowledge.application.observability` is retired and guarded by ArchUnit. M7A removed ten persistence exceptions; exactly four M7B/M7C exceptions remained at that checkpoint.

### Media and Knowledge boundary after M7B

M7B keeps Knowledge parent persistence private while preserving image indexing and associated-image retrieval behavior.

- Knowledge exposes parent-image lookup through `knowledge.application.contract.ParentImageReferenceQuery` and replacement through `ParentImageReferenceCommand`.
- `knowledge.application.contract.result.ParentImageReferences` is the stable immutable cross-module view; it contains only parent ID, file path, and image IDs.
- `knowledge.application.indexing.ParentImageReferenceService` implements both use cases and is the only M7B service that accesses `RagParentDO` and `RagParentMapper`.
- `media.application.ImageService` consumes only the Knowledge contracts. Scoring, deduplication, ordering, limits, parent selection, and replacement semantics are unchanged.
- ArchUnit prevents Media from accessing Knowledge persistence and prevents Knowledge contracts from exposing infrastructure, Spring, MyBatis-Plus, or MyBatis types.
- M7B removed two persistence exceptions; exactly two M7C Search/Learning exceptions remain.

### Search and Learning boundary after M7C

M7C keeps Learning profile persistence private while preserving personal autocomplete ranking behavior.

- Learning exposes user knowledge-state lookup through `learning.application.contract.UserKnowledgeStateQuery`.
- `learning.application.contract.result.UserKnowledgeStateView` is the stable cross-module view and contains only the topic and mastery score required by Search.
- `learning.application.profile.UserKnowledgeStateService` owns the `user_id` persistence query and maps Learning DOs to stable results.
- `search.application.PersonalRanker` consumes only the Learning contract. Weak-topic matching, null-score handling, duplicate-topic resolution, ranking weights, sorting, copying, and limits are unchanged.
- ArchUnit prevents Search from accessing Learning persistence and prevents Learning contracts from exposing infrastructure, Spring, MyBatis-Plus, or MyBatis types.
- M7C removed the final two persistence exceptions; the cross-module persistence allowlist is now empty.

### Graph/RAG retirement after M7D

M7D retires the ambiguous top-level `graph` and `rag` packages without changing GraphRAG queries or Spring AI vector-store assembly.

- `TechConcept`, `TechConceptRepository`, `TechConceptSnippetView`, and `BatchedConceptSnippetView` live together under `knowledge.infrastructure.graph` because they carry Spring Data Neo4j annotations or projection/repository semantics.
- `knowledge.application.indexing.KnowledgeGraphIndexService` owns concept graph writes and warmup queries. Ingestion uses `KnowledgeGraphIndexCommand`; startup warmup uses `KnowledgeGraphWarmup`; neither caller imports Knowledge infrastructure.
- Node labels, relationship type/direction, default concept type, description truncation, link filtering, repository method signatures, and both Cypher queries are preserved by characterization tests.
- The former `rag.core.VectorStoreConfig` was an empty configuration with only a commented example bean. It was removed after confirming no imports or explicit configuration references; runtime `VectorStore` creation remains Spring AI auto-configuration.
- ArchUnit prevents top-level `graph`/`rag` production packages from returning and prevents modules outside Knowledge from depending on `knowledge.infrastructure.graph`.

## Final boundary enforcement after M8

M8 turns the completed ownership migration into executable repository constraints without splitting the Maven module or changing deployment topology.

- The historical cross-module persistence allowlist is permanently empty; `ArchitectureRulesTest` asserts the zero-debt invariant and rejects every new cross-module `infrastructure.persistence` edge.
- The module catalog is explicit. `bootstrap` and `config` are technical composition roots and may assemble implementations; they do not contribute business-origin edges to the cycle graph.
- Cross-module application calls enter the provider through `<provider>.application.contract`. The top-level graph is built from those public-contract business edges and must remain acyclic; there is no module-cycle ignore list.
- When a normal provider contract would create a reverse dependency, the consumer owns a narrow `application.contract.extension` SPI and the collaborating module implements it. M8 uses this pattern for Agent task prompts, Routing prompts, Knowledge/Ingestion collaboration, Media source paths and parent-image references, and Learning plan generation.
- Generic task dispatch is exposed by `agent.application.contract.AgentTaskOperations`; Agent evaluation and Interview orchestration no longer depend on each other's concrete application implementation.
- Controller isolation, domain isolation, retired-package enforcement, `common`, `modelrouting`, `observability`, and generic-Agent dependency directions are all executable ArchUnit rules.

These extension SPIs are caller-driven stable contracts with active Spring implementations. Do not create an empty port or adapter merely to make the graph appear layered.

## Public-surface closure after M9

M9 closes the highest-risk implementation leaks that remained after ownership and cycle governance. It does not split the Maven module or change the A2A transport, task lifecycle, persistence schema, or HTTP/SSE contracts.

- Every `<module>.application.contract` is guarded by one generic purity rule: public contracts cannot expose another module's domain type, infrastructure type, Spring Web/AI type, MyBatis type, or vendor SDK type.
- Agent task dispatch is exposed by `AgentTaskOperations`; business modules cannot depend on `agent.runtime` implementations.
- Operational A2A administration is exposed by `AgentA2AOperations`. Idempotency inspection/clear and RocketMQ DLQ replay stay inside Agent application services instead of leaking `A2ABus`, `A2AIdempotencyStore`, or `RocketMqA2ABus` to controllers.
- Business asynchronous messaging is exposed by `AgentMessagingOperations`, `AgentMessageCommand`, `AgentMessage`, and `AgentMessageIntent`. Chat and Interview publish/subscribe through this public surface; only Agent internals and the configuration composition root know `agent.a2a`.
- Interview start task dispatch returns `InterviewSessionStarted`, a stable result containing only session ID, current question, and total-question count. IM no longer imports `interview.domain.InterviewSession`; modules outside Interview are prohibited from depending on Interview domain types.
- Routing intent candidates, decisions, tree nodes, and pre-filter results live under `routing.application.contract.result`; Agent runtime consumes Routing only through its public contracts.

Focused ArchUnit rules enforce the Agent runtime/A2A isolation and the Interview domain boundary in addition to the generic contract-purity, persistence, controller, and cycle rules.

## RAG retrieval kernel after simplification

The conceptual RAG lifecycle can be understood as catalog, ingestion, indexing, retrieval, grounding and evaluation. These are responsibility boundaries, not six deployment units or six new frameworks. This slice only changes the retrieval kernel.

```text
KnowledgeRetrievalOperations -> KnowledgeApplicationContractAdapter -> RAGService
                                                                     | root trace
                                                                     v
                                                            KnowledgePacketBuilder
                                                            | rewrite / images / quality / Web fallback / child traces
                                                            +-> HybridEvidenceRetriever
                                                            |   +-> lexical / vector / graph (existing executor)
                                                            |   +-> EvidenceFusionService (RRF)
                                                            |   +-> GroundingContextAssembler (parent-child hydration)
                                                            |   +-> EvidenceFusionService (rerank)
                                                            +-> EvidenceFusionService (best quality score)
                                                            +-> GroundingContextAssembler (context / evidence / image selection)
```

`KnowledgeRetrievalCoordinator` continues to select RAG/local-graph/hybrid modes; `RagKnowledgeService` continues to adapt the RAG packet for that coordinator. Evaluation continues to reuse `RAGService.buildKnowledgePacket(..., false)` rather than creating a second retrieval algorithm. The old Builder-to-RAGService `RetrievalDelegate` is removed, not wrapped by another engine. Only internal constructor/delegate surfaces change; public contracts, fallback semantics, citation formats, ranking and trace schema remain unchanged.

ArchUnit prevents the facade from accessing retrieval adapters, the Builder from regaining the delegate, fusion from accessing retrieval adapters/external services, and grounding from performing vector/graph/Web recall. Catalog/Ingestion consistency, Outbox/projections, unified evidence types and retirement of remaining compatibility entry points are separate future decisions.

## Layering Rules
- API layer: controllers and controller-facing DTO/input-output types.
- Application layer: orchestration, use-case flow, and application-internal DTOs.
- Domain layer: business model, value objects, pure rules.
- Infrastructure layer: domain-owned persistence and external system adapters.

Dependency direction inside one module:

- `api -> application`
- `application -> domain`
- `infrastructure -> application contract / domain`
- `domain` does not depend on `api` or `infrastructure`

Cross-module dependency direction:

- Call only the provider's `application.contract`.
- Do not access another module's Mapper, DO, repository implementation, infrastructure adapter, internal DTO, or helper.
- Business modules may implement extension contracts owned by `agent`, `chat`, `skill`, or `tool`; the contract owner must not import concrete business implementations.
- `common` does not depend on business modules.
- `modelrouting` does not depend on `chat`.
- `observability` does not depend on `knowledge`.
- The public-contract top-level module graph is acyclic and guarded without an ignore list.

M0 froze the historical cross-module persistence violations in `docs/architecture/module-boundary-baseline.md`; M8 makes the now-empty allowlist a permanent invariant.

## Persistence Placement

MyBatis persistence classes are domain-owned. DO classes and Mapper interfaces live under:

`io.github.imzmq.interview.<domain>.infrastructure.persistence`

Do not add new business persistence code under top-level `entity`, `mapper`, or `dto` packages. These type-first packages are retired for main business code and must not be treated as an acceptable baseline.

DTO placement follows caller-facing ownership:
- Controller-facing request/response DTOs belong in `<domain>.api`.
- Application-internal DTOs belong in `<domain>.application.dto`.
- Persistence-specific transfer objects belong with the domain persistence adapter under `<domain>.infrastructure.persistence`, not in API DTO packages.

## Placement Quick Rules

- Generic session/message/context/streaming chat orchestration -> `chat`.
- Interview lifecycle, answer evaluation, report, resume input, interview-specific Agent/Handler/context -> `interview`.
- Coding practice lifecycle, question generation, answer evaluation, practice-specific Agent/Handler/context -> `practice`.
- Learning profile, events, weak points, and plan generation -> `learning`.
- Knowledge catalog, indexing, retrieval, evidence, local graph, and GraphRAG -> `knowledge`.
- Intent understanding, clarification, relation classification, and routing decision -> `routing`.
- Generic task/handler lifecycle, A2A, cancellation, registry, and context assembly mechanism -> `agent`.
- Model selection, health, execution, retry, and structured model output -> `modelrouting`.
- Trace, span, runtime metrics, and observability queries -> `observability`.
- Cross-domain SSE connection and transport only -> `common.stream`; task state belongs to `agent`, message state belongs to `chat`.

The historical `knowledge.application.chatstream`, `knowledge.application.coding`, and `knowledge.application.observability` packages are retired and must not be recreated. Top-level `intent`, `turn`, `graph`, and `rag` packages are also retired and guarded by ArchUnit.

## Enforcement
- ArchUnit tests enforce key boundaries.
- Checkstyle enforces naming/package/import conventions.
- Required validation before merge:
  - `mvn -q compile`
  - `mvn test`
  - `mvn -q verify -DskipTests`

## M1 Routing Ownership Status

M1 has completed the first routing boundary migration while keeping the application a single-module modular monolith:

- The former top-level `intent` domain types now belong to `routing.domain`; production code may not recreate top-level `intent` or `turn` packages.
- The routing module exposes the real cross-module understanding API at `routing.application.contract.UnderstandingService`, with provider-owned result types under `routing.application.contract.result`.
- `chat.application.IntentShadowObserver` is the current caller after the M3 ownership move. It starts only after legacy routing has produced and stored its result, runs fire-and-forget, and writes an `INTENT_SHADOW` trace/log only. Its result must not change route selection, scenario handling, SSE events, or terminal state.
- Shadow execution is disabled by default. The canonical configuration prefix is `app.routing.understanding.shadow.*`; the enable flag also accepts the legacy design key `app.turn.understanding.shadow` and environment variable `APP_TURN_UNDERSTANDING_SHADOW` as fallbacks.
- The dedicated executor, timeout (capped at 3000 ms), fallback, and consecutive-failure circuit breaker isolate model and capacity failures from the chat chain.

## M2 Structured Output Ownership Status

M2 moves generic LLM structured-output parsing out of chat and into modelrouting:

- Cross-module callers depend on `modelrouting.application.contract.structuredoutput.StructuredOutputParser`.
- Provider-owned result, schema, and retry types live in the same public contract package.
- `modelrouting.application.structuredoutput.DefaultStructuredOutputParser` is the only Spring default implementation and remains internal to modelrouting.
- The migration preserves JSON extraction, repair, schema warning/failure, retry, and fallback behavior; chat no longer owns or wraps these types.
- `modelrouting` has no dependency on `chat`, and external production modules cannot import the implementation package.
