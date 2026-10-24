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
- A business module may implement an extension contract owned by `agent`, `chat`, `skill`, or `tool`; the registry depends on the extension interface, not concrete business implementations.

## 4.2 Routing public surface after M1

Routing owns intent understanding, turn-relation classification, intent domain types, and routing decisions:

- Stable cross-module entry: `routing.application.contract.UnderstandingService`.
- Provider-owned public results: `routing.application.contract.result`.
- Intent domain model: `routing.domain`.
- Internal implementation only: `routing.application.understanding`; callers must not import it.
- Top-level `intent` and `turn` packages are prohibited and must not be recreated.

The current shadow caller is `chat.application.IntentShadowObserver`. It observes the result after legacy routing and must not use it to select a handler or mutate the existing route response. The canonical configuration prefix is `app.routing.understanding.shadow.*`; the legacy `app.turn.understanding.shadow` key is enable-only compatibility, not a package ownership rule.

## 4.3 Chat public surface after M3

- Stable cross-module entries: `chat.application.contract.ChatSessionOperations`, `ChatMessageOperations`, `ChatContextOperations`, `ChatTaskOperations`, and `TaskResponsePresentationOperations`.
- Stable output records: `chat.application.contract.result.ChatSessionView` and `ChatMessageView`.
- Scenario extension SPI: `chat.application.contract.extension.ChatScenarioHandler`, `StreamingChatContext`, `ChatStreamingOperations`, `ChatIntentPolicy`, and `ChatTaskResultPresenter`.
- Generic registry and streaming implementations stay internal to `chat.application`; external handlers must not inject `WebChatService`, `ChatStreamingSupport`, or other chat implementation classes.
- Concrete interview/default handlers belong to `interview.application.chat`; knowledge QA belongs to `knowledge.application.chat`.
- Coding chat handlers and result presentation belong to `practice.application.chat` and implement only chat-owned extension contracts.
- `knowledge.application.chatstream` is retired and must not be recreated.

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
- Cross-module task-visible results: `interview.application.contract.result.InterviewAnswerResult` and `InterviewFinalReport`. These records may contain only stable business/JDK types.
- Interview start keeps the existing `InterviewSession` result for behavior compatibility; do not add an empty or speculative `InterviewStartResult`.
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

## 4.8 Observability and Feedback ownership after M7A

- Trace runtime, node definitions, event bus, and local trace orchestration: `observability.application.trace`.
- Operational trace/metrics/dashboard queries: `observability.application.ObservabilityQueryService`.
- Hourly metrics aggregation and retention: `observability.application.metrics.MetricsSnapshotScheduler`.
- Trace and metrics persistence: `observability.infrastructure.persistence`; the existing `t_rag_trace`, `t_rag_trace_node`, and `t_rag_metrics_snapshot` table names remain unchanged.
- Explicit user feedback use cases and `t_rag_feedback` persistence: `feedback.application` and `feedback.infrastructure.persistence`.
- Cross-module feedback aggregates are exposed only through `feedback.application.contract.FeedbackMetricsQuery` and `feedback.application.contract.result.FeedbackMetrics`; callers must not depend on the concrete service or persistence types.
- `interview.application.ObservabilityApplicationService` is a compatibility orchestrator for existing API signatures and Knowledge-owned evaluation. New operational observability behavior belongs to Observability.
- `knowledge.application.observability` is retired and must not be recreated.

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
- `knowledge.application.indexing`: chunking, tokenizer, lexical and parent-child index use cases.
- `knowledge.application.retrieval`: retrieval orchestration, fusion, query rewrite, evidence evaluation, and fallback.
- `knowledge.application.localgraph`: local graph retrieval chain.
- `knowledge.domain`: knowledge and GraphRAG domain types without Spring Data or vendor dependencies.
- `knowledge.infrastructure.persistence`: knowledge-owned MyBatis DOs and Mappers.
- `knowledge.infrastructure.graph`: Neo4j/Spring Data graph adapters and repositories.

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
