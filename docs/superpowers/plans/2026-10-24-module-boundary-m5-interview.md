# M5 Interview Ownership Implementation Plan

> **Execution:** Implement inline in this session with `superpowers:executing-plans`; the user explicitly asked not to use subagents.

**Goal:** Make `interview` the owner of the interview lifecycle, evaluation, resume input, report skill, interview Agents, task handlers, context sources, and cross-module result contracts while preserving HTTP/SSE/task behavior.

**Architecture:** Keep the single-module modular monolith and the existing Spring-discovered task, context, chat, A2A, and skill extension mechanisms. Move business implementations to `interview`, expose only the task results actually consumed by `im` and chat under `interview.application.contract.result`, and remove the generic router's unused concrete Interview Agent dependency. Remove knowledge's interview-evaluation facade delegates so `knowledge` no longer owns or imports interview evaluation. This gate does not rewrite the Agent runtime, DAG, persistence schema, prompts, SSE protocol, or session model.

**Tech Stack:** Java 21, Spring Boot 3.3, JUnit 5, Mockito, AssertJ, ArchUnit, Maven.

---

## Working-tree safety

The worktree already contains M4 and unrelated user-owned Flyway/startup changes. Preserve all existing modifications. Never use `git add .`, `git add -A`, reset, checkout, or stash over them. M5 is not committed unless the user explicitly asks.

Unrelated paths include `AGENTS.md`, `README.md`, `docker-compose.yml`, `pom.xml`, `sql/**`, `src/main/resources/application.yml`, `src/main/resources/db/**`, database tests, and `.DS_Store` files.

## Target file map

### Interview-owned implementations

```text
interview/application/agent/InterviewOrchestratorAgent.java
interview/application/agent/EvaluationAgent.java
interview/application/agent/KnowledgeLayerAgent.java
interview/application/agent/RollingSummaryAgent.java
interview/application/agent/handler/InterviewStartTaskHandler.java
interview/application/agent/handler/InterviewAnswerTaskHandler.java
interview/application/agent/handler/InterviewReportTaskHandler.java
interview/application/context/InterviewContextAttributes.java
interview/application/context/InterviewProfileContextSource.java
interview/application/context/InterviewStrategyContextSource.java
interview/application/context/InterviewKnowledgeContextSource.java
interview/application/evaluation/InterviewAnswerEvaluationService.java
interview/application/skill/InterviewReportGeneratorSkill.java
interview/infrastructure/resume/ResumeLoader.java
```

### Public result contracts

```text
interview/application/contract/result/InterviewAnswerResult.java
interview/application/contract/result/InterviewFinalReport.java
```

These are real contracts consumed by chat presentation and IM. Interview start continues to return the existing `InterviewSession` to preserve task serialization and caller behavior; no synthetic `InterviewStartResult` is introduced. Internal evaluation records remain implementation details under the Interview Agent.

### Generic/knowledge code made independent of Interview implementations

```text
agent/runtime/TaskRouterAgent.java
knowledge/application/RAGService.java
im/application/service/ImWebhookService.java
```

`TaskRouterAgent` loses its unused `InterviewOrchestratorAgent` constructor field. `RAGService` loses the interview evaluation dependency and delegate methods; `EvaluationAgent` calls the moved evaluation service directly. IM recognizes only Interview public result contracts.

## Task 1: Add failing ownership and contract rules

**Files:**
- Modify `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`
- Modify `src/test/java/io/github/imzmq/interview/architecture/LegacyCrossModulePersistenceDependencies.java`

- [x] Add an ArchUnit rule requiring concrete Interview Agents, handlers, context sources, answer evaluation, resume loader, and report skill to reside under `interview..`.
- [x] Add rules forbidding Interview concrete implementations in generic `agent`, `knowledge`, top-level `rag`, and generic `skill` packages.
- [x] Add a rule that generic `agent` must not depend on Interview implementation packages; permit only contract/result dependencies if ever needed.
- [x] Run `mvn -q -Dtest=ArchitectureRulesTest test` and confirm the new ownership rules fail against the old packages for the expected reason.
- [x] Do not remove M5 allowlist entries until production types have moved.

## Task 2: Freeze public Interview task results

**Files:**
- Create the two real cross-module result records under `interview/application/contract/result`
- Modify Interview chat/presentation tests and IM-facing behavior tests as needed

- [x] Copy the exact existing task-visible fields and accessor names from `InterviewOrchestratorAgent.AnswerResult` and `FinalReport`.
- [x] Keep the existing `InterviewSession` start result unchanged; do not create a speculative `InterviewStartResult`.
- [x] Update focused tests to assert start, answer, report, IM session-clear, and presentation semantics against these contracts.
- [x] Run the focused tests before implementation changes; compile failures or assertion failures must identify the old nested-result coupling.

## Task 3: Move Interview Agents, handlers, contexts, resume adapter, and skill

**Files:** Move the implementation types listed in the target map and move their tests under `src/test/java/io/github/imzmq/interview/interview/**`.

- [x] Move Agent classes without changing Spring bean names, prompts, A2A receiver strings, session persistence, async profile update, rolling summary, fallback, or stage-transition behavior.
- [x] Keep the start handler's existing `InterviewSession` result and make answer/report handlers return the public result contracts.
- [x] Move context attributes and sources while preserving IDs, modes, slot kinds, metadata, and generated text.
- [x] Move PDF resume loading to `interview.infrastructure.resume` without changing parsing configuration.
- [x] Move the report skill while preserving skill ID, execution policy, inputs, outputs, and report strategy text.
- [x] Update imports in production and tests.

## Task 4: Return answer evaluation to Interview and retire knowledge facade delegates

**Files:**
- Move `knowledge/application/evaluation/InterviewAnswerEvaluationService.java` to `interview/application/evaluation`
- Modify `interview/application/agent/EvaluationAgent.java`
- Modify `knowledge/application/RAGService.java`
- Move/update evaluation tests and RAG construction tests

- [x] Preserve model route, prompts, context assembly, structured parsing, citation validation, observability, skill orchestration, fallback JSON, and redaction behavior.
- [x] Inject the moved service into `EvaluationAgent`; call it directly for `processAnswer` and `evaluateWithKnowledge` behavior.
- [x] Remove `InterviewAnswerEvaluationService` from `RAGService` constructor and delete only its interview-specific delegate methods.
- [x] Update RAG tests/fixtures for the constructor change; keep retrieval behavior unchanged.

## Task 5: Remove generic concrete coupling and M5 persistence exceptions

**Files:**
- Modify `agent/runtime/TaskRouterAgent.java`
- Modify `src/test/java/io/github/imzmq/interview/agent/TaskRouterAgentTest.java`
- Modify `im/application/service/ImWebhookService.java`
- Modify Interview application/chat classes
- Modify `LegacyCrossModulePersistenceDependencies.java`

- [x] Delete the unused concrete Interview Agent field and constructor parameter from `TaskRouterAgent`; routing continues through `TaskHandlerRegistry`.
- [x] Update chat, Interview application services, and IM to consume public result contracts rather than nested Agent records.
- [x] Delete exactly the three M5 persistence allowlist entries. The allowlist must shrink from 17 to 14 without changing M7 entries.
- [x] Run focused tests for router, Interview chat/application, rolling summary, evaluation, and architecture.

## Task 6: Update governance documentation

**Files:**
- Modify `ARCHITECTURE.md`
- Modify `PACKAGE_CONVENTIONS.md`
- Modify `docs/architecture/module-boundary-baseline.md`
- Modify `docs/superpowers/specs/2026-10-24-module-boundary-roadmap-refresh-design.md`
- Update this plan's checkboxes

- [x] Document Interview's public result surface and implementation ownership.
- [x] Record M5 allowlist cleanup (17 to 14) and prohibit recreation of old packages.
- [x] Mark M5 status accurately without claiming later gates are complete.

## Task 7: Verify the complete worktree

Run fresh checks:

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn test
mvn -q verify -DskipTests
git diff --check
git status --short --branch
```

- [x] Record actual test counts and failures/skips: 324 tests, 0 failures, 0 errors, 2 skipped.
- [x] Mockito inline attachment failed inside the sandbox as expected; focused and full tests were rerun with approved escalation and passed.
- [x] Review the final diff and status: Flyway/user-owned changes and existing `.DS_Store` files remain untouched; no generated artifacts were added.
