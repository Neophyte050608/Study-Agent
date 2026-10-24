# M7A Observability and Feedback Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 将通用 Trace/Metrics persistence 与查询归还 Observability，将显式用户反馈 persistence 归还 Feedback，并删除对应 10 条跨模块 persistence allowlist。

**Architecture:** 数据表、字段、HTTP 返回和调度语义保持不变，只调整 owner 与依赖方向。Feedback 暴露最小聚合查询契约；Observability 拥有 trace runtime、event bus、trace/metrics persistence、聚合调度和 operational query。Interview 保留面向现有 controller 的兼容编排，但不再接触 Feedback/Observability persistence；Knowledge 评测能力本轮不迁移。

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, JUnit 5, Mockito, ArchUnit, Maven

---

### Task 1: Freeze M7A ownership with failing ArchUnit rules

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`

- [x] Require `RagTraceDO`, `RagTraceNodeDO`, `RagMetricsSnapshotDO` and their Mappers to live under `observability.infrastructure.persistence`.
- [x] Require `RagFeedbackDO` and `RagFeedbackMapper` to live under `feedback.infrastructure.persistence`.
- [x] Forbid `knowledge.application.observability` and generic observability persistence under Knowledge.
- [x] Run `mvn -q -Dtest=ArchitectureRulesTest test` and confirm only the new M7A ownership rules fail.

### Task 2: Move Feedback persistence and publish aggregate query contract

**Files:**
- Move: `knowledge/infrastructure/persistence/RagFeedbackDO.java` to `feedback/infrastructure/persistence/RagFeedbackDO.java`
- Move: `knowledge/infrastructure/persistence/RagFeedbackMapper.java` to `feedback/infrastructure/persistence/RagFeedbackMapper.java`
- Create: `feedback/application/contract/FeedbackMetricsQuery.java`
- Create: `feedback/application/contract/result/FeedbackMetrics.java`
- Modify: `feedback/application/FeedbackApplicationService.java`
- Test: focused Feedback service/contract tests

- [x] Characterize type-count and time-window behavior, including empty counts.
- [x] Move the DO/Mapper without changing `t_rag_feedback`, fields, ID strategy, or insert failure fallback.
- [x] Implement the query contract in `FeedbackApplicationService`; keep MyBatis details private to Feedback.
- [x] Run focused tests and `mvn -q -DskipTests test-compile`.

### Task 3: Move Trace runtime and Trace/Metrics persistence to Observability

**Files:**
- Move: `knowledge/application/observability/*.java` to `observability/application/trace/`
- Move: `knowledge/infrastructure/persistence/RagTrace{DO,Mapper}.java` to `observability/infrastructure/persistence/`
- Move: `knowledge/infrastructure/persistence/RagTraceNode{DO,Mapper}.java` to `observability/infrastructure/persistence/`
- Move: `knowledge/infrastructure/persistence/RagMetricsSnapshot{DO,Mapper}.java` to `observability/infrastructure/persistence/`
- Modify: all production/test imports

- [x] Move packages mechanically while preserving Spring bean names, trace state, SSE event payloads, persistence retries, redaction, pagination, filters, and fallback behavior.
- [x] Update consumers to import Observability-owned trace API; do not add compatibility wrappers under Knowledge.
- [x] Move existing tests to the Observability owner where package ownership is asserted.
- [x] Run `RAGObservabilityServiceTest`, `RagTraceEventBusTest`, trace streaming tests, and test compile.

### Task 4: Move snapshot aggregation to Observability and consume Feedback contract

**Files:**
- Move: `feedback/application/MetricsSnapshotScheduler.java` to `observability/application/metrics/MetricsSnapshotScheduler.java`
- Modify: scheduler constructor and aggregation implementation
- Test: create/move scheduler aggregation tests

- [x] Replace direct Feedback implementation/persistence access with `FeedbackMetricsQuery`.
- [x] Preserve cron, hourly windows, 720-hour cap, percentile calculation, status/fallback/empty-retrieval counters, messages, retention and duplicate-hour handling.
- [x] Keep `t_rag_metrics_snapshot` schema and snapshot fields unchanged.
- [x] Run focused aggregation tests.

### Task 5: Move operational query ownership to Observability

**Files:**
- Create: `observability/application/ObservabilityQueryService.java`
- Modify: `interview/application/ObservabilityApplicationService.java`
- Modify: `interview/application/InterviewService.java` only if constructor/method delegation requires it
- Test: move/split `ObservabilityApplicationServiceTest` coverage

- [x] Move trace list/detail/overview, feedback stats, metrics trend/summary and snapshot trigger methods to `ObservabilityQueryService`.
- [x] Make Observability use its own persistence and `FeedbackMetricsQuery`; no Observability-to-Knowledge dependency.
- [x] Keep Interview service as a thin compatibility orchestrator for existing controller methods plus Knowledge-owned retrieval/quality evaluation.
- [x] Preserve all response maps, formatting, switch behavior, limits and error messages.
- [x] Run focused application/controller contract tests.

### Task 6: Remove the ten M7A allowlist entries and retire old packages

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/architecture/LegacyCrossModulePersistenceDependencies.java`
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`
- Modify: `docs/architecture/module-boundary-baseline.md`

- [x] Delete the 10 Feedback/Observability M7 entries; retain exactly 4 Media/Search entries.
- [x] Add rules that Observability and Feedback do not depend on Knowledge persistence and that `knowledge.application.observability` stays retired.
- [x] Run `mvn -q -Dtest=ArchitectureRulesTest test` and confirm green.

### Task 7: Document M7A boundary

**Files:**
- Modify: `ARCHITECTURE.md`
- Modify: `PACKAGE_CONVENTIONS.md`
- Modify: `docs/development/observability-guidelines.md`
- Modify: `docs/superpowers/specs/2026-10-24-module-boundary-roadmap-refresh-design.md`

- [x] Document Feedback query contract, Observability persistence/runtime/query ownership, unchanged table names, and compatibility delegation.
- [x] Mark only G5/M7A complete; leave M7B, M7C and M8 open.
- [x] Record allowlist count as 4.

### Implemented boundary and focused verification record

- [x] Trace runtime moved to `observability.application.trace`; trace/metrics persistence moved to `observability.infrastructure.persistence`.
- [x] Feedback persistence moved to `feedback.infrastructure.persistence`; `FeedbackMetricsQuery` exposes aggregate counts without persistence types.
- [x] `MetricsSnapshotScheduler` and `ObservabilityQueryService` now belong to Observability; Interview keeps only compatibility delegation plus Knowledge-owned evaluation.
- [x] Existing table names remain `t_rag_trace`, `t_rag_trace_node`, `t_rag_metrics_snapshot`, and `t_rag_feedback`.
- [x] M7A removed ten allowlist entries; four M7B/M7C entries remain.
- [x] Focused verification completed during implementation:
  - `mvn -q -Dtest=FeedbackApplicationServiceTest test`
  - `mvn -q -Dtest=DefaultTraceServiceTest,RagTraceEventBusTest,RAGObservabilityServiceTest test`
  - `mvn -q -Dtest=MetricsSnapshotSchedulerTest test`
  - `mvn -q -Dtest=ObservabilityQueryServiceTest,ObservabilityApplicationServiceTest test`
  - `mvn -q -Dtest=ArchitectureRulesTest test`
  - `mvn -q -DskipTests test-compile`

### Task 8: Full verification

- [x] Run `mvn -q compile`.
- [x] Run `mvn -q -DskipTests test-compile`.
- [x] Run `mvn -q -Dtest=ArchitectureRulesTest test`.
- [x] Run `mvn test` outside the restricted sandbox if Mockito/Byte Buddy attach is blocked.
- [x] Run `mvn -q verify -DskipTests`.
- [x] Run `git diff --check`.
- [x] Run `git status --short --branch`; verify no user-owned Flyway/startup/local changes are staged or overwritten.

### Final verification result

- `mvn test` first failed only in the restricted sandbox because Mockito/Byte Buddy could not self-attach. The same command was rerun outside the restricted sandbox and passed with 340 tests, 0 failures, 0 errors, and 2 skipped.
- No files were staged, committed, or pushed as part of M7A verification.
