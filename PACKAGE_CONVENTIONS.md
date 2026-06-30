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

## 7. Placement Cheatsheet (Current)
- Agent configuration/evaluation/skills and runtime context assembly: `agent.application`; context assembly implementation lives in `agent.application.context`.
- Prompt/template/chat context memory: `chat.application`
- User identity extraction: `identity.application`
- Knowledge ingestion config & sync: `ingestion.application`
- Interview orchestration facade: `interview.application`
- Retrieval/rag/local graph: `knowledge.application` + `knowledge.domain`
- Learning profile and events: `learning.application` + `learning.domain`
- MCP gateway and audit: `mcp.application`
- Image embedding/index/retrieval: `media.application`
- Menu/workspace config: `menu.application`
- Model routing candidates/executor/runtime health: `modelrouting`
- Trace attribute sanitization and related helpers: `observability.application`
- Intent tree/routing: `routing.application`
- Autocomplete and ranking: `search.application`; internal autocomplete DTOs: `search.application.dto`
- Cross-domain SSE/stream transport support: `common.stream`; business-specific stream orchestration stays in its owning domain.
- Agent prompt/runtime context assembly belongs in `agent.application.context`; knowledge-specific retrieval and topic policy remain in `knowledge.application.*` and may be wrapped by context sources.

Knowledge package breakdown (mandatory for new code):
- RAG compatibility entry and packet builder: `knowledge.application` (`RAGService` remains a facade; new code should prefer focused services where possible).
- Indexing and chunk/lexical tokenizer/index structures: `knowledge.application.indexing`.
- Local graph chain (candidate recall, llm route, note resolve, wiki/backlink expansion): `knowledge.application.localgraph`.
- Retrieval orchestration/fusion, query rewrite, evidence evaluation, Web fallback, and RAG adapters: `knowledge.application.retrieval`.
- Multi-turn topic state and dynamic context policy: `knowledge.application.context`.
- Streaming chat handlers and stream utilities: `knowledge.application.chatstream`.
- Coding practice question generation/evaluation/quiz fallback: `knowledge.application.coding`.
- Retrieval/generation offline eval and interview answer evaluation/scoring: `knowledge.application.evaluation`.
- RAG trace/event/trace-service: `knowledge.application.observability`.
- Knowledge base/document catalog operations: `knowledge.application.catalog`.

Forbidden placement:
- Do not add new non-core classes into `knowledge.application` root; do not add new responsibilities directly to `RAGService`. New answer-scoring or evidence-validation logic belongs in `knowledge.application.evaluation`; new coding-practice logic belongs in `knowledge.application.coding`.
- Do not place new knowledge features under legacy `...service`.
- Do not place new business persistence classes under top-level `entity`, `mapper`, or `dto`.

## 8. Hard Guardrails
- Controllers cannot use MyBatis Mapper interfaces directly.
- `domain` packages cannot depend on controller packages.
- `application` should not expose persistence-specific DTOs upward.
- MyBatis DO classes and Mapper interfaces must stay in `<domain>.infrastructure.persistence`.
- Controller-facing DTOs must stay in `<domain>.api`; application-internal DTOs must stay in `<domain>.application.dto`.
- New code that violates package placement should be moved before merge.
