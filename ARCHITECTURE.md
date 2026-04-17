# Architecture Baseline

## Goal
- Keep business logic easy to locate for interview review and daily maintenance.
- Prevent architecture drift with automated guardrails.
- Keep package placement domain-first and avoid regression to type-first buckets.

## Package Strategy
- Root package: `io.github.imzmq.interview`
- Main style: `package-by-feature` (domain-first), then layered subpackages.
- Legacy aggregate package `...service` has been fully migrated (no main code remains there).

Current domain/application packages:
- `agent.application`
- `chat.application`
- `identity.application`
- `ingestion.application`
- `interview.application`
- `knowledge.application`
- `learning.application`
- `learning.domain`
- `mcp.application`
- `media.application`
- `menu.application`
- `modelruntime.application`
- `modelrouting`
- `observability.application`
- `routing.application`
- `search.application`

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
- API layer: controller and transport-facing DTO/input-output.
- Application layer: orchestration and use-case flow.
- Domain layer: business model, value objects, pure rules.
- Infrastructure layer: mapper/repository/external gateways.

Dependency direction:
- `api -> application -> domain`
- `application -> infrastructure` through injected interfaces/components
- `domain` must not depend on `api`
- Cross-domain direct mapper/repository access is prohibited.

## Placement Quick Rules
- New prompt/template/context orchestration code -> `chat.application`
- New model runtime selection/health/VLM code -> `modelruntime.application`
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
- Checkstyle enforces naming/package/import baseline.
- Required validation before merge:
  - `mvn -q compile`
  - `mvn test`
  - `mvn -q verify -DskipTests`
