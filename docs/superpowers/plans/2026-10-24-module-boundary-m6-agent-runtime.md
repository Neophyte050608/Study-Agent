# M6 Generic Agent Runtime Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 让 generic agent 只拥有通用任务扩展契约、registry、A2A/lifecycle 与 context assembly，并将 Knowledge/Learning 业务实现和业务 schema 迁回真实 owner。

**Architecture:** 将 task/context 的跨模块签名闭包放入 `agent.application.contract`，其中只有 `TaskHandler`、`AgentContextSource`、`AgentContextSchemaProvider` 是 Spring 扩展 SPI；registry 和 assembler 继续是 agent 内部实现。Knowledge、Interview、Practice 和 generic Tool Task 分别提供 schema provider bean，避免 generic assembler 中出现业务中央 switch 或手写实现清单。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, ArchUnit, Maven

---

### Task 1: Freeze M6 ownership with failing ArchUnit rules

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`

- [x] Add rules requiring `TaskHandler`, `AgentContextSource`, and `AgentContextSchemaProvider` to live under `agent.application.contract.extension`.
- [x] Add ownership rules requiring Knowledge QA and Learning implementations to live under their owners.
- [x] Add a rule preventing classes under generic agent from depending on business-owned Agent/Handler/ContextSource implementation packages.
- [x] Run `mvn -q -Dtest=ArchitectureRulesTest test` and confirm the new rules fail on the current misplaced classes.

### Task 2: Publish the real agent extension contract

**Files:**
- Move: `src/main/java/io/github/imzmq/interview/agent/task/*.java` to `src/main/java/io/github/imzmq/interview/agent/application/contract/task/`
- Move: context value types from `agent/application/context` to `agent/application/contract/context/`
- Move: `agent/router/TaskHandler.java` to `agent/application/contract/extension/TaskHandler.java`
- Move: `agent/application/context/AgentContextSource.java` to `agent/application/contract/extension/AgentContextSource.java`
- Create: `agent/application/contract/extension/AgentContextSchemaProvider.java`
- Modify: all production/test imports using the moved contract types

- [x] Move task request/response/type/execution-mode records and enums without changing fields or enum values.
- [x] Move context query/schema/slot/result records and enums without changing rendering, defaults, budgets, or attribute behavior.
- [x] Define `AgentContextSchemaProvider#schema()` as the bean-discovered schema extension.
- [x] Update `TaskHandlerRegistry` and `AgentContextSourceRegistry` to depend only on the extension interfaces.
- [x] Run `mvn -q -DskipTests test-compile` and fix only mechanical package fallout.

### Task 3: Remove the central business context schema table

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/context/AgentContextAssembler.java`
- Create: `src/main/java/io/github/imzmq/interview/agent/application/context/ToolTaskAgentContextSchemaProvider.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/context/KnowledgeQaAgentContextSchemaProvider.java`
- Create: `src/main/java/io/github/imzmq/interview/interview/application/context/InterviewAgentContextSchemaProvider.java`
- Create: `src/main/java/io/github/imzmq/interview/practice/application/context/CodingPracticeAgentContextSchemaProvider.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/context/AgentContextAssemblerTest.java`

- [x] Change assembler construction to receive `List<AgentContextSchemaProvider>` through Spring and reject duplicate modes.
- [x] Preserve query-level schema override and all four existing slot orders/budgets exactly.
- [x] Keep a test-friendly constructor that accepts a source registry and explicit providers; do not restore business defaults in agent.
- [x] Add tests proving provider registration, schema override, duplicate rejection, and unchanged Tool Task assembly.
- [x] Run `mvn -q -Dtest=AgentContextAssemblerTest test`.

### Task 4: Move Knowledge QA implementations to Knowledge

**Files:**
- Move: `agent/runtime/KnowledgeQaAgent.java` to `knowledge/application/agent/KnowledgeQaAgent.java`
- Move: `agent/router/handler/KnowledgeQaTaskHandler.java` to `knowledge/application/agent/handler/KnowledgeQaTaskHandler.java`
- Move: `agent/application/context/{BasicConstraintsContextSource,DialogSignalContextSource,KnowledgeContextSource,KnowledgeQaContextAttributes}.java` to `knowledge/application/context/`
- Move: corresponding context source tests to `src/test/java/io/github/imzmq/interview/knowledge/application/context/`

- [x] Move classes and update imports without changing component names, receiver name, payload/context keys, retrieval mode, async executor, streaming, tracing, prompt, or fallback behavior.
- [x] Resolve retrieval mode inside the Knowledge-owned handler; do not keep a Knowledge type in generic helper code.
- [x] Run the moved Knowledge context tests and `ParentChildRetrievalHydrationTest`.

### Task 5: Move Learning implementations to Learning

**Files:**
- Move: `agent/runtime/NoteMakingAgent.java` to `learning/application/agent/NoteMakingAgent.java`
- Move: four Learning handlers to `learning/application/agent/handler/`
- Move: `agent/application/context/LearningProfileContextSource.java` to `learning/application/context/`
- Move: its test to `src/test/java/io/github/imzmq/interview/learning/application/context/`

- [x] Move implementations without changing receiver names, output maps, Markdown path/content, MCP invocation, timestamp parsing, user-id normalization, defaults, or fallback behavior.
- [x] Put Learning-specific task payload parsing beside Learning handlers; delete business-specific helpers from generic agent.
- [x] Run the moved Learning context test and focused handler/agent tests where present.

### Task 6: Make TaskRouter and helpers genuinely generic

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/agent/runtime/TaskRouterAgent.java`
- Delete: `src/main/java/io/github/imzmq/interview/agent/router/TaskHandlerSupport.java`
- Modify: owner-specific handlers using the helper
- Modify: `src/test/java/io/github/imzmq/interview/agent/TaskRouterAgentTest.java`

- [x] Remove unused concrete `NoteMakingAgent`, `LearningProfileAgent`, and `KnowledgeQaAgent` constructor fields/parameters.
- [x] Replace `TaskHandlerSupport` uses with owner-local parsing or methods on the task contract while keeping parsing semantics.
- [x] Preserve `STREAM_ROUTE_ONLY`, A2A receiver/status/reply messages, task dispatch errors, route source, and response map fields.
- [x] Run `mvn -q -Dtest=TaskRouterAgentTest test`.

### Task 7: Document and enforce the completed M6 boundary

**Files:**
- Modify: `ARCHITECTURE.md`
- Modify: `PACKAGE_CONVENTIONS.md`
- Modify: `docs/superpowers/specs/2026-10-24-module-boundary-roadmap-refresh-design.md`
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`

- [x] Document the public task/context extension contracts, owner-provided schema mechanism, and retained generic Tool Task context.
- [x] Mark only M6/G4 exit conditions complete; leave M7/M8 and the persistence allowlist unchanged at 14.
- [x] Run `mvn -q -Dtest=ArchitectureRulesTest test` and confirm all M6 rules pass.

### Task 8: Full verification

- [x] Run `mvn -q compile`.
- [x] Run `mvn -q -DskipTests test-compile`.
- [x] Run `mvn -q -Dtest=ArchitectureRulesTest test`.
- [x] Run `mvn test`.
- [x] Run `mvn -q verify -DskipTests`.
- [x] Run `git diff --check`.
- [x] Run `git status --short --branch` and verify no user-owned Flyway/startup or local files were overwritten or staged.


## Verification record

Completed on 2026-10-24:

```text
mvn -q compile                                                        exit 0
mvn -q -DskipTests test-compile                                       exit 0
mvn -q -Dtest=ArchitectureRulesTest test                              exit 0
mvn test (outside restricted sandbox for Mockito/Byte Buddy attach)   332 tests, 0 failures, 0 errors, 2 skipped
mvn -q verify -DskipTests                                             exit 0
git diff --check                                                      exit 0
```

The unrestricted test run was required only because the sandbox blocks Byte Buddy self-attachment; the same focused test command passed outside the restricted sandbox before the full suite. No commit or push was performed as part of M6 implementation.
