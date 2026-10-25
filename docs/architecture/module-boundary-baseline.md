# Module Boundary M0 Baseline

## Purpose

This document records the cross-module persistence dependencies that exist when module-boundary enforcement starts. They are migration debt, not approved architecture. `ArchitectureRulesTest` blocks any new edge and requires each listed edge to be removed from the Java allowlist in the same commit that removes the production dependency.

The target architecture is defined by `docs/superpowers/specs/2026-10-23-module-boundary-and-package-ownership-design.md`. Daily package placement remains governed by `PACKAGE_CONVENTIONS.md`.

## Target top-level modules

| Category | Modules |
| --- | --- |
| Product capabilities | `chat`, `interview`, `practice`, `learning`, `knowledge`, `feedback` |
| Understanding and decision | `routing` |
| Execution and extension | `agent`, `skill`, `tool`, `mcp` |
| Platform support | `modelrouting`, `observability`, `ingestion`, `media`, `search`, `im`, `identity`, `security`, `menu` |
| Technical support | `common`, `bootstrap`, `config` |

`intent` has merged into `routing`. M7D retired the ambiguous top-level `graph` and `rag` owners after their remaining types moved to the actual Knowledge owner or were proven inert and removed.

## Enforcement model

- Cross-module business calls may target only the provider's `application.contract` package.
- Mapper, DO, repository implementation, and other `infrastructure` types are module-private.
- M0 enforces cross-module `infrastructure.persistence` access first.
- The Java allowlist is exact: it names both source and target classes.
- An allowlist entry also has a cleanup milestone. Whole-package exclusions are forbidden.
- M1-M7 remove entries as ownership and public contracts are repaired.
- M8 enables the final contract-only and module-cycle rules after the historical debt reaches zero; both rules run without a debt or cycle ignore list.
- M9 closes focused implementation leaks: external Agent runtime/A2A dependencies and external Interview domain dependencies are prohibited, and all public application contracts share one purity rule.

## Legacy cross-module persistence edges

| Source module | Source class | Target module | Target persistence type | Cleanup |
| --- | --- | --- | --- | --- |

Current allowlist count after M8: **0 exact class dependency pairs** (down from the M0 baseline of 26). The executable allowlist is permanently empty, is asserted by a dedicated test, and rejects every new cross-module persistence edge directly.

M3 removed all eight M3 chat persistence pairs by moving generic chat ownership to `chat` and exposing immutable application contracts. It also removed the one former M4 pair `interview.api.CodingPracticeController -> chat.infrastructure.persistence.ChatMessageDO` early by switching the controller to `ChatMessageOperations`. There are no remaining chat persistence exceptions.

M4 moved all concrete coding-practice behavior to `practice` and introduced no persistence dependency, so the allowlist remained at 17 pairs.

M5 moved Interview Agents and their persistence access into the `interview` owner. The former three `agent.runtime` to Interview persistence pairs are now same-module dependencies and were removed from the executable allowlist and this table, reducing it from 17 to 14.

M7A moved trace runtime plus trace/metrics persistence and operational queries to `observability`, moved explicit feedback persistence to `feedback`, and introduced `FeedbackMetricsQuery` for aggregate counts. The ten former Feedback/Observability-to-Knowledge persistence pairs were removed, reducing the allowlist from 14 to 4. Table names and schemas remain unchanged.

M7B introduced Knowledge-owned parent image-reference query/command contracts and a stable result view. `ImageService` no longer imports `RagParentDO` or `RagParentMapper`; the two former Media-to-Knowledge persistence pairs were removed, reducing the allowlist from 4 to 2. Image lookup, scoring, ordering, limits, and parent reference replacement semantics remain unchanged.

M7C introduced the Learning-owned `UserKnowledgeStateQuery` and stable `UserKnowledgeStateView`. `PersonalRanker` no longer imports `UserKnowledgeStateDO`, `UserKnowledgeStateMapper`, or MyBatis query wrappers; the final two Search-to-Learning persistence pairs were removed, reducing the allowlist from 2 to 0. Personal ranking weights, weak-topic matching, sorting, copying, and limits remain unchanged.

M7D moved all Spring Data Neo4j nodes, projections, and repositories to `knowledge.infrastructure.graph`. Ingestion and startup warmup now consume Knowledge-owned application contracts rather than the repository; the empty top-level RAG configuration placeholder was removed. Top-level `graph` and `rag` are guarded against recreation, and the persistence allowlist remains 0.

M8 enforces provider-contract-only application calls, controller/domain isolation, fixed dependency directions, unified retired packages, and an acyclic top-level public-contract graph. The graph comes from the explicit module catalog, excludes `bootstrap`/`config` as technical composition origins, and has no cycle ignore list. Real reverse collaborations use consumer-owned extension SPIs: Agent/Chat and Routing/Chat prompt rendering, Knowledge/Ingestion support, Media/Ingestion source paths, Media/Knowledge parent-image references, and Learning/Knowledge plan generation.

M9 adds public-surface closure on top of that graph. `AgentTaskOperations` replaces business dependencies on `TaskRouterAgent`; `AgentMessagingOperations` hides business publish/subscribe from internal A2A records; `AgentA2AOperations` hides idempotency and DLQ administration from controllers. Routing result types are owned by `routing.application.contract.result`. `INTERVIEW_START` now emits `InterviewSessionStarted`, so IM no longer imports `InterviewSession`. Focused ArchUnit rules reject dependencies from business modules to `agent.runtime`, dependencies outside Agent/config to `agent.a2a`, and dependencies outside Interview/config to `interview.domain`. The generic public-contract purity rule rejects domain, infrastructure, Spring Web/AI, MyBatis, and vendor SDK types from every module contract.

## Removal protocol

For each repaired edge:

1. Add or reuse a real provider-owned `application.contract` only when there is an actual cross-module caller.
2. Add focused tests for the contract and caller behavior.
3. Switch the caller away from the foreign persistence type.
4. Remove the matching entry from `LegacyCrossModulePersistenceDependencies` and this table in the same commit.
5. Run `mvn -q -Dtest=ArchitectureRulesTest test` before broader verification.

If a new violation is found, fix it rather than adding it to the baseline. The baseline represents M0 history only and must monotonically shrink.
