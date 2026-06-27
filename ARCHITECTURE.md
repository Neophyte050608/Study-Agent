# Architecture Baseline

## Goal
- Keep business logic easy to locate for interview review and daily maintenance.
- Prevent architecture drift with automated guardrails.
- Keep package placement domain-first and avoid regression to type-first buckets.

## Package Strategy
- Root package: `io.github.imzmq.interview`
- Main style: `package-by-feature` (domain-first), then layered subpackages.
- Legacy aggregate packages `...service`, top-level `entity`, top-level `mapper`, and top-level `dto` have been retired for main business code.

Current domain-owned packages include:
- `agent.api`, `agent.application`, `agent.infrastructure.persistence`
- `chat.api`, `chat.application`, `chat.infrastructure.persistence`
- `feedback.api`, `feedback.application`, `feedback.domain`
- `identity.application`
- `im.api`, `im.application`, `im.domain`
- `ingestion.application`, `ingestion.infrastructure.persistence`
- `interview.api`, `interview.application`, `interview.domain`, `interview.infrastructure.persistence`
- `knowledge.api`, `knowledge.application`, `knowledge.domain`, `knowledge.infrastructure.persistence`
- `learning.application`, `learning.domain`, `learning.infrastructure.persistence`
- `mcp.application`, `mcp.infrastructure`
- `media.api`, `media.application`, `media.infrastructure.persistence`
- `menu.api`, `menu.application`, `menu.infrastructure.persistence`
- `modelrouting.api`, `modelrouting.application`, `modelrouting.infrastructure.persistence`
- `observability.api`, `observability.application`
- `routing.api`, `routing.application`, `routing.infrastructure.persistence`
- `search.api`, `search.application`, `search.application.dto`, `search.infrastructure.persistence`

Knowledge application subpackages (current):
- `knowledge.application` (root): only `RAGService` as RAG core pipeline entry.
- `knowledge.application.indexing`: local index build/scope/tokenizer/lexical/parent-child index.
- `knowledge.application.localgraph`: local graph retrieval chain (candidate recall, route, note resolve, graph expand).
- `knowledge.application.retrieval`: retrieval orchestration and RAG adapter (`KnowledgeRetrievalCoordinator`, `RagKnowledgeService`).
- `knowledge.application.context`: multi-turn topic tracking and dynamic context assembly.
- `knowledge.application.chatstream`: streaming scenario handlers and stream support.
- `knowledge.application.evaluation`: offline retrieval and generation quality evaluation.
- `knowledge.application.observability`: RAG trace/event bus and trace service abstractions.
- `knowledge.application.catalog`: knowledge base/document catalog query and operations.

## Layering Rules
- API layer: controllers and controller-facing DTO/input-output types.
- Application layer: orchestration, use-case flow, and application-internal DTOs.
- Domain layer: business model, value objects, pure rules.
- Infrastructure layer: domain-owned persistence and external system adapters.

Dependency direction:
- `api -> application -> domain`
- `application -> infrastructure` through injected interfaces/components
- `domain` must not depend on `api`
- Cross-domain direct mapper/repository access is prohibited.
- Controllers must not directly access MyBatis Mapper interfaces.

## Persistence Placement

MyBatis persistence classes are domain-owned. DO classes and Mapper interfaces live under:

`io.github.imzmq.interview.<domain>.infrastructure.persistence`

Do not add new business persistence code under top-level `entity`, `mapper`, or `dto` packages. These type-first packages are retired for main business code and must not be treated as an acceptable baseline.

DTO placement follows caller-facing ownership:
- Controller-facing request/response DTOs belong in `<domain>.api`.
- Application-internal DTOs belong in `<domain>.application.dto`.
- Persistence-specific transfer objects belong with the domain persistence adapter under `<domain>.infrastructure.persistence`, not in API DTO packages.

## Placement Quick Rules
- New prompt/template/context orchestration code -> `chat.application`
- New model routing/runtime selection/health/VLM code -> `modelrouting`
- New learning profile/event logic -> `learning.application` and `learning.domain`
- New identity/auth identity extraction -> `identity.application`
- New observability sanitize/trace helper -> `observability.application`
- New menu/workspace config logic -> `menu.application`
- New local graph retrieval node/routing/note parsing -> `knowledge.application.localgraph`
- New retrieval orchestration/fusion entry -> `knowledge.application.retrieval`
- New multi-turn topic/context policy -> `knowledge.application.context`
- New knowledge base/document catalog ops -> `knowledge.application.catalog`

## Enforcement
- ArchUnit tests enforce key boundaries.
- Checkstyle enforces naming/package/import conventions.
- Required validation before merge:
  - `mvn -q compile`
  - `mvn test`
  - `mvn -q verify -DskipTests`
