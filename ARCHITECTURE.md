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

The map is a migration target. During M0-M7, existing classes may remain in historical packages only when listed in the module-boundary design or baseline. New code must use the target owner immediately.

Ambiguous top-level ownership is retired as follows:

- `intent` merges into `routing.domain`.
- GraphRAG domain types move to `knowledge.domain`; Spring Data and Neo4j adapters move to `knowledge.infrastructure.graph`.
- `rag` classes move to their concrete knowledge, interview, observability, or configuration owner.
- Coding practice becomes the independent `practice` capability.

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
- Top-level module cycles are forbidden after the M8 migration gate.

M0 freezes existing cross-module persistence violations in `docs/architecture/module-boundary-baseline.md`; the allowlist is migration debt and must only shrink.

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

Do not add new responsibilities to historical `knowledge.application.chatstream`, `knowledge.application.coding`, `knowledge.application.observability`, top-level `intent`, `graph`, or `rag` packages.

## Enforcement
- ArchUnit tests enforce key boundaries.
- Checkstyle enforces naming/package/import conventions.
- Required validation before merge:
  - `mvn -q compile`
  - `mvn test`
  - `mvn -q verify -DskipTests`
