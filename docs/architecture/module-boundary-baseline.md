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

`intent` will merge into `routing`. `graph` and `rag` will disappear as ambiguous top-level owners after their classes move to the actual knowledge, interview, observability, or configuration owner.

## Enforcement model

- Cross-module business calls may target only the provider's `application.contract` package.
- Mapper, DO, repository implementation, and other `infrastructure` types are module-private.
- M0 enforces cross-module `infrastructure.persistence` access first.
- The Java allowlist is exact: it names both source and target classes.
- An allowlist entry also has a cleanup milestone. Whole-package exclusions are forbidden.
- M1-M7 remove entries as ownership and public contracts are repaired.
- M8 enables the final contract-only and module-cycle rules after the historical debt reaches zero.

## Legacy cross-module persistence edges

| Source module | Source class | Target module | Target persistence type | Cleanup |
| --- | --- | --- | --- | --- |
| agent | `agent.runtime.InterviewOrchestratorAgent` | interview | `SessionRepository` | M5 |
| agent | `agent.runtime.RollingSummaryAgent` | interview | `InterviewSessionMapper` | M5 |
| agent | `agent.runtime.RollingSummaryAgent` | interview | `SessionRepository` | M5 |
| feedback | `feedback.application.FeedbackApplicationService` | knowledge | `RagFeedbackDO` | M7 |
| feedback | `feedback.application.FeedbackApplicationService` | knowledge | `RagFeedbackMapper` | M7 |
| feedback | `feedback.application.MetricsSnapshotScheduler` | knowledge | `RagMetricsSnapshotDO` | M7 |
| feedback | `feedback.application.MetricsSnapshotScheduler` | knowledge | `RagMetricsSnapshotMapper` | M7 |
| feedback | `feedback.application.MetricsSnapshotScheduler` | knowledge | `RagTraceDO` | M7 |
| feedback | `feedback.application.MetricsSnapshotScheduler` | knowledge | `RagTraceMapper` | M7 |
| interview | `interview.application.ObservabilityApplicationService` | knowledge | `RagFeedbackDO` | M7 |
| interview | `interview.application.ObservabilityApplicationService` | knowledge | `RagFeedbackMapper` | M7 |
| interview | `interview.application.ObservabilityApplicationService` | knowledge | `RagMetricsSnapshotDO` | M7 |
| interview | `interview.application.ObservabilityApplicationService` | knowledge | `RagMetricsSnapshotMapper` | M7 |
| media | `media.application.ImageService` | knowledge | `RagParentDO` | M7 |
| media | `media.application.ImageService` | knowledge | `RagParentMapper` | M7 |
| search | `search.application.PersonalRanker` | learning | `UserKnowledgeStateDO` | M7 |
| search | `search.application.PersonalRanker` | learning | `UserKnowledgeStateMapper` | M7 |

Current allowlist count after M3: **17 exact class dependency pairs** (down from the M0 baseline of 26).

M3 removed all eight M3 chat persistence pairs by moving generic chat ownership to `chat` and exposing immutable application contracts. It also removed the one former M4 pair `interview.api.CodingPracticeController -> chat.infrastructure.persistence.ChatMessageDO` early by switching the controller to `ChatMessageOperations`. There are no remaining chat persistence exceptions.

## Removal protocol

For each repaired edge:

1. Add or reuse a real provider-owned `application.contract` only when there is an actual cross-module caller.
2. Add focused tests for the contract and caller behavior.
3. Switch the caller away from the foreign persistence type.
4. Remove the matching entry from `LegacyCrossModulePersistenceDependencies` and this table in the same commit.
5. Run `mvn -q -Dtest=ArchitectureRulesTest test` before broader verification.

If a new violation is found, fix it rather than adding it to the baseline. The baseline represents M0 history only and must monotonically shrink.
