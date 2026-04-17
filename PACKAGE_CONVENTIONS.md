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
- Current baseline: do not add new classes under `io.github.imzmq.interview.service`.

## 3. Recommended subpackages
- `api`: controllers and transport DTOs.
- `application`: use-case orchestration.
- `domain`: entities, value objects, core policy.
- `infrastructure`: persistence and external system adapters.

## 4. Dependency boundaries
- Controllers must not directly call mappers.
- Domain objects must not depend on controller/service packages.
- Agent layer must not depend on controller layer.

## 5. Migration rule
- Existing migration to domain packages is complete for main business services.
- Any new module or major refactor must follow this document.
- Any package move should be done in small batches with compile + test pass per batch.

## 6. Placement Cheatsheet (Current)
- Agent configuration/evaluation/skills: `agent.application`
- Prompt/template/chat context memory: `chat.application`
- User identity extraction: `identity.application`
- Knowledge ingestion config & sync: `ingestion.application`
- Interview orchestration facade: `interview.application`
- Retrieval/rag/local graph: `knowledge.application` + `knowledge.domain`
- Learning profile and events: `learning.application` + `learning.domain`
- MCP gateway and audit: `mcp.application`
- Image embedding/index/retrieval: `media.application`
- Menu/workspace config: `menu.application`
- Dynamic model runtime and health: `modelruntime.application`
- Model routing candidates/executor: `modelrouting`
- Trace attribute sanitization and related helpers: `observability.application`
- Intent tree/routing: `routing.application`
- Autocomplete and ranking: `search.application`

Knowledge package breakdown (mandatory for new code):
- RAG core entry only: `knowledge.application` (currently only `RAGService`).
- Indexing and chunk/lexical tokenizer/index structures: `knowledge.application.indexing`.
- Local graph chain (candidate recall, llm route, note resolve, wiki/backlink expansion): `knowledge.application.localgraph`.
- Retrieval orchestration/fusion and RAG adapter: `knowledge.application.retrieval`.
- Multi-turn topic state and dynamic context policy: `knowledge.application.context`.
- Streaming chat handlers and stream utilities: `knowledge.application.chatstream`.
- Retrieval/generation offline eval: `knowledge.application.evaluation`.
- RAG trace/event/trace-service: `knowledge.application.observability`.
- Knowledge base/document catalog operations: `knowledge.application.catalog`.

Forbidden placement:
- Do not add new non-core classes into `knowledge.application` root.
- Do not place new knowledge features under legacy `...service`.

## 7. Hard Guardrails
- Controllers cannot use `mapper` directly.
- `domain` packages cannot depend on controller packages.
- `application` should not expose persistence-specific DTOs upward.
- New code that violates package placement should be moved before merge.
