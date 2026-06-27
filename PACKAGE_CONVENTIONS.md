# Package Conventions

## 1. Naming
- Package names use lowercase and meaningful domain nouns.
- Class names use `UpperCamelCase`.
- Method/field names use `lowerCamelCase`.

## 2. Macro-module ownership
- New code should belong to one of the target macro-modules: `interfaces`, `interview`, `conversation`, `agent`, `tools`, `knowledge`, `model`, `integration`, `platform`, or `shared`.
- Prefer a module's public facade or `<module>.api` for cross-module calls.
- Do not import another module's `<module>.internal..` package.
- Do not add new top-level business packages without updating `ARCHITECTURE.md` and ArchUnit rules in the same change.

## 3. Domain-first placement
- New code must be placed by business capability first, not by technical type first.
- Example:
  - Good: `io.github.imzmq.interview.knowledge.KnowledgeFacade` or `io.github.imzmq.interview.knowledge.api.KnowledgeQueryPort`
  - Avoid: `io.github.imzmq.interview.service.KnowledgeQueryService`
- Retired type-first/aggregate roots: do not add new business code under `io.github.imzmq.interview.service`, `io.github.imzmq.interview.entity`, `io.github.imzmq.interview.mapper`, or `io.github.imzmq.interview.dto`.

## 4. Recommended module surfaces
- `<module>.api`: public contracts intended for controllers or other macro-modules.
- `<Module>Facade`: small public entry point for cross-module use cases.
- `<module>.internal`: implementation details hidden from other macro-modules once migrated.
- `interfaces`: transport-facing controllers, handlers, and request/response DTOs.
- `shared`: minimal business-neutral primitives only; do not use it as a dumping ground.

## 5. Dependency boundaries
- Transport entry points in `interfaces` call module facades or `<module>.api`; they must not own business rules or access persistence directly.
- Cross-module calls should target a module facade or `<module>.api` package.
- Cross-module imports from another module's `<module>.internal..` package are forbidden.
- `agent` may orchestrate `knowledge`, `tools`, and `model`, but must not own vendor protocol details.
- `knowledge` must not depend on `agent`; RAG is a capability, not the system center.
- `integration` adapts external protocols and must not own core business workflows.
- `platform` provides technical infrastructure and must not own use cases.
- Controllers must not directly call MyBatis Mapper interfaces.
- Application/use-case code should not expose persistence-specific DTOs upward.

## 6. Transitional package inventory

Current remaining top-level packages under `io.github.imzmq.interview`:
`agent`, `common`, `conversation`, `feedback`, `integration`, `intent`, `interview`, `knowledge`, `learning`, `menu`, `model`, `platform`, `routing`, `tools`.

Allowed-but-transitional packages: `common`, `feedback`, `intent`, `learning`, `menu`, and `routing`. They remain in `ArchitectureRulesTest` because code still exists there after the first migration pass. Treat them as remaining cleanup inventory for later package ownership decisions, not preferred destinations for new code unless a later design explicitly assigns ownership.

Required local gate for this migration: `mvn -q compile`, `mvn -q -Dtest=ArchitectureRulesTest test`, and `mvn -q verify -DskipTests`. Full `mvn test` should be rerun in a JVM/environment that supports Mockito inline attachment; in this local environment it fails with Mockito inline Byte Buddy self-attach (`Could not initialize MockMaker` / `Could not self-attach to current VM`). Do not claim the full suite is green locally.

## 7. Migration rule
- First-phase migration may keep existing code in legacy domain-owned packages until each area is moved into its target macro-module.
- Old package names are documentation-only migration references, not preferred placement for new code.
- Top-level `entity`, `mapper`, and `dto` are retired for new business code; do not reintroduce them as acceptable placement.
- Any new module or major refactor must follow this document.
- Any package move should be done in small batches with compile + test pass per batch.

## 8. Persistence Placement

MyBatis persistence classes are domain-owned during migration. New or moved persistence code should live behind the owning module's internal persistence adapter when that module migrates.

Do not add new business persistence code under top-level `entity`, `mapper`, or `dto` packages.

DTO placement:
- Transport-facing DTOs belong in `interfaces`.
- Module public contracts belong in a module facade or `<module>.api`.
- Persistence-specific DTOs/records belong with the owning persistence adapter and must not be exposed as public API DTOs.

## 9. Placement Cheatsheet
- HTTP, IM, webhook controllers and transport DTOs: `interfaces`.
- Interview sessions, feedback, and learning-loop entry points: `interview`.
- Chat, prompt, context, memory, and streaming response protocol: `conversation`.
- Agent task lifecycle, planning/execution orchestration, and runtime abstraction: `agent`.
- Executable action contracts, permissions, safety policy, and execution results: `tools`.
- RAG, catalog, indexing, retrieval, graph, knowledge ingestion, and knowledge-specific media extraction: `knowledge`.
- Model providers, model routing, health probes, and model execution policy: `model`.
- External adapters/clients such as IM, MCP, search, and vendor APIs: `integration`.
- Identity, security, configuration, observability, async, and HTTP infrastructure: `platform`.
- Minimal business-neutral types only: `shared`.

Migration reference only; do not use these as preferred placement for new code:
- Chat/prompt/context and streaming response protocol code lives under `conversation`.
- Identity and observability infrastructure lives under `platform/identity` and `platform/observability`.
- Retired `modelrouting` package code has migrated to `model`; keep new model providers, routing, runtime health, and execution policy under `model`.
- Retired top-level IM, MCP, and search package code has migrated to `integration`; keep external adapters/clients and vendor APIs under `integration`.
- Retired top-level RAG, graph, ingestion, and media package code has migrated to `knowledge`; keep retrieval, indexing, catalog, graph, ingestion, and knowledge-specific media extraction under `knowledge`.

## 10. Hard Guardrails
- Controllers cannot use MyBatis Mapper interfaces directly.
- Cross-module imports from `<module>.internal..` are forbidden.
- Persistence-specific DTOs must not be exposed upward as transport or public API DTOs.
- New code that violates package placement should be moved before merge.
