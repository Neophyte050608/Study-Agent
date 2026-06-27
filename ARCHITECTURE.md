# Architecture Baseline

## Goal
- Keep business logic easy to locate for interview review and daily maintenance.
- Prevent architecture drift with automated guardrails.
- Keep package placement domain-first and avoid regression to type-first buckets.

## Package Strategy
- Root package: `io.github.imzmq.interview`.
- Main style: modular monolith. Top-level packages represent stable capability ownership, not technical type buckets.
- Each large module exposes a small public surface (`<Module>Facade` and/or `<module>.api`) and hides implementation under `<module>.internal` when the module is migrated.
- Legacy aggregate packages `...service`, top-level `entity`, top-level `mapper`, and top-level `dto` are retired for main business code.

Target macro-modules:
- `interfaces`: HTTP/IM/Webhook ingress and transport DTOs; no business rules or persistence access.
- `interview`: interview sessions, feedback, learning-loop entry points.
- `conversation`: chat, prompt, context, memory, streaming response protocol.
- `agent`: agent task lifecycle, planning/execution orchestration, runtime abstraction.
- `tools`: executable action contracts, permissions, safety policy, execution results.
- `knowledge`: RAG, catalog, indexing, retrieval, graph, knowledge ingestion, knowledge-specific media extraction.
- `model`: model providers, model routing, health probes, model execution policy.
- `integration`: external adapters/clients such as IM, MCP, search, vendor APIs.
- `platform`: identity, security, configuration, observability, async/http infrastructure.
- `shared`: minimal business-neutral types only.

Dependency direction:
- `interfaces -> module facade/api -> module internal`.
- Cross-module dependencies should target a module facade or `<module>.api` package.
- Cross-module access to another module's `<module>.internal..` is forbidden.
- `agent` may orchestrate `knowledge`, `tools`, and `model`, but must not own vendor protocol details.
- `knowledge` must not depend on `agent`; RAG is a capability, not the system center.
- `integration` adapts external protocols and must not own core business workflows.
- `platform` provides technical infrastructure and must not own use cases.

## Migration Rules
- First-phase migration may keep existing code in legacy domain-owned packages until each area is moved into its target macro-module.
- New business code should be placed in the target macro-module that owns the capability.
- Old package names are documentation-only migration references, not preferred placement for new code.
- MyBatis persistence classes remain domain-owned during migration and should move behind the owning module's internal persistence adapter when that module migrates.
- Controller-facing request/response DTOs should move toward `interfaces`; module-level public contracts should live in a module facade or `<module>.api`.
- Persistence-specific transfer objects stay with their owning persistence adapter and must not be exposed as public API DTOs.

## Placement Quick Rules
- HTTP, IM, webhook controllers and transport DTOs -> `interfaces`.
- Interview session, feedback, and learning-loop use cases -> `interview`.
- Prompt/template/context/memory and streaming response protocol -> `conversation`.
- Agent task lifecycle, planning, execution orchestration, and runtime abstraction -> `agent`.
- Tool contracts, permission checks, safety policy, and execution result types -> `tools`.
- RAG, indexing, retrieval, local graph, catalog, ingestion, and knowledge-specific media extraction -> `knowledge`.
- Model providers, routing, runtime health, and execution policy -> `model`.
- IM, MCP, search, vendor API clients, and external protocol adapters -> `integration`.
- Identity, security, configuration, observability, async, and HTTP infrastructure -> `platform`.
- Business-neutral shared primitives only -> `shared`.

## Enforcement
- ArchUnit tests enforce key boundaries.
- Checkstyle enforces naming/package/import conventions.
- Required validation before merge:
  - `mvn -q compile`
  - `mvn test`
  - `mvn -q verify -DskipTests`
