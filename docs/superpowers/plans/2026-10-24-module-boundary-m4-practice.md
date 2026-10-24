# M4 Practice Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make `practice` the sole owner of coding-practice API, application service, agent, chat handlers, context sources, task handler, DTOs, and skill without changing HTTP/SSE behavior.

**Architecture:** Preserve the modular monolith and the current Spring-discovered extension mechanisms. Move coding-practice behavior behind the existing chat, agent, and skill contracts; remove the generic router and knowledge facade's concrete knowledge of `CodingPracticeAgent`; split coding card presentation out of the interview-owned default handler and chat-owned generic presenter. This gate changes ownership only: session storage remains in memory, task payloads remain map-based, and no DAG/runtime rewrite is introduced.

**Tech Stack:** Java 21, Spring Boot 3.3, JUnit 5, Mockito, AssertJ, ArchUnit, Maven.

---

## Working-tree safety

The repository already contains unrelated Flyway and user-owned changes. Every implementation step must preserve these paths and must not stage them as part of M4:

```text
AGENTS.md
README.md
docker-compose.yml
docs/development/backend-guidelines.md
docs/development/local-startup.md
pom.xml
sql/**
src/main/resources/application.yml
src/main/resources/db/**
src/test/java/io/github/imzmq/interview/config/database/**
**/.DS_Store
```

Use exact paths for inspection and any later staging. Do not use `git add .` or `git add -A`.

## Target file map

### Practice-owned production types

```text
src/main/java/io/github/imzmq/interview/practice/api/CodingPracticeController.java
src/main/java/io/github/imzmq/interview/practice/application/CodingPracticeService.java
src/main/java/io/github/imzmq/interview/practice/application/agent/CodingPracticeAgent.java
src/main/java/io/github/imzmq/interview/practice/application/agent/CodingPracticeTaskHandler.java
src/main/java/io/github/imzmq/interview/practice/application/chat/ActiveCodingChatScenarioHandler.java
src/main/java/io/github/imzmq/interview/practice/application/chat/CodingPracticeCardChatScenarioHandler.java
src/main/java/io/github/imzmq/interview/practice/application/chat/CodingPracticeTaskResultPresenter.java
src/main/java/io/github/imzmq/interview/practice/application/context/CodingPracticeContextAttributes.java
src/main/java/io/github/imzmq/interview/practice/application/context/CodingConstraintsContextSource.java
src/main/java/io/github/imzmq/interview/practice/application/context/CodingProfileContextSource.java
src/main/java/io/github/imzmq/interview/practice/application/context/CodingTaskPlanContextSource.java
src/main/java/io/github/imzmq/interview/practice/application/dto/CodingAssessment.java
src/main/java/io/github/imzmq/interview/practice/application/dto/QuizPayload.java
src/main/java/io/github/imzmq/interview/practice/application/dto/QuizQuestion.java
src/main/java/io/github/imzmq/interview/practice/application/dto/ScenarioPayload.java
src/main/java/io/github/imzmq/interview/practice/application/dto/FillPayload.java
src/main/java/io/github/imzmq/interview/practice/application/skill/CodingInterviewCoachSkill.java
```

`CodingSession` remains a private implementation record inside `CodingPracticeAgent`; it has no cross-module caller and does not justify a public abstraction.

### Existing types modified to forget practice implementation

```text
src/main/java/io/github/imzmq/interview/agent/runtime/TaskRouterAgent.java
src/main/java/io/github/imzmq/interview/agent/router/TaskHandlerSupport.java
src/main/java/io/github/imzmq/interview/chat/application/TaskResponsePresentationService.java
src/main/java/io/github/imzmq/interview/interview/application/chat/DefaultRoutedChatScenarioHandler.java
src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java
```

`TaskHandlerSupport.enrichCodingPayload(...)` moves into the practice-owned task handler as a private helper; generic agent support must not encode coding payload semantics.

### Tests and governance

Move coding-owned tests under `src/test/java/io/github/imzmq/interview/practice/**`, update affected generic tests, and add focused tests for controller contracts, coding card chat presentation, task-result presentation, and ownership rules. Update:

```text
src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java
ARCHITECTURE.md
PACKAGE_CONVENTIONS.md
docs/architecture/module-boundary-baseline.md
docs/superpowers/specs/2026-10-24-module-boundary-roadmap-refresh-design.md
```

## Task 1: Freeze existing Practice contracts

**Files:**
- Create: `src/test/java/io/github/imzmq/interview/practice/api/CodingPracticeControllerContractTest.java`
- Create: `src/test/java/io/github/imzmq/interview/practice/application/chat/CodingPracticeTaskResultPresenterTest.java`
- Create: `src/test/java/io/github/imzmq/interview/practice/application/chat/CodingPracticeCardChatScenarioHandlerTest.java`
- Reference: `src/test/java/io/github/imzmq/interview/chat/application/TaskResponsePresentationServiceTest.java`

- [x] **Step 1: Add endpoint characterization tests**

Instantiate the controller with mocked collaborators and assert that its class-level mapping remains `/api/coding`. Reflect over `@PostMapping` methods and freeze these five paths:

```java
assertThat(postMappings).containsExactlyInAnyOrder(
        "/batch-quiz/submit",
        "/scenario-card/submit",
        "/fill-card/submit",
        "/scenario-card/next",
        "/fill-card/next"
);
```

Also verify a representative submit delegates to the agent with the existing action value and returns the agent map unchanged.

- [x] **Step 2: Freeze coding result text**

Extract the current expectations from `TaskResponsePresentationServiceTest` into a practice presenter test. Cover `evaluated`, `question_generated`, `completed`, and fallback maps. The expected evaluated shape remains:

```text
【得分】80

【解析与反馈】
不错

【提示】继续优化

进度：1/3
```

- [x] **Step 3: Freeze routed coding-card SSE behavior**

For batch quiz, scenario card, and fill card results, verify the exact event names, route labels, message types, metadata fields, terminal `done()`, and `completeTask(...)` call. Use mocks for `ChatStreamingOperations` and the existing stream-emitter abstraction; do not start Spring.

- [x] **Step 4: Run characterization tests**

Run:

```bash
mvn -q -Dtest=CodingPracticeControllerContractTest,CodingPracticeTaskResultPresenterTest,CodingPracticeCardChatScenarioHandlerTest,TaskResponsePresentationServiceTest test
```

Expected: tests for not-yet-created production presenters may initially fail to compile; after placing test fixtures against current behavior, existing assertions pass before ownership moves.

## Task 2: Extract Practice DTOs and move the application service

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/practice/application/dto/CodingAssessment.java`
- Create: `src/main/java/io/github/imzmq/interview/practice/application/dto/QuizPayload.java`
- Create: `src/main/java/io/github/imzmq/interview/practice/application/dto/QuizQuestion.java`
- Create: `src/main/java/io/github/imzmq/interview/practice/application/dto/ScenarioPayload.java`
- Create: `src/main/java/io/github/imzmq/interview/practice/application/dto/FillPayload.java`
- Move: `src/main/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeService.java` → `src/main/java/io/github/imzmq/interview/practice/application/CodingPracticeService.java`
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`
- Move: `src/test/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeServiceTest.java` → `src/test/java/io/github/imzmq/interview/practice/application/CodingPracticeServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/service/ParentChildRetrievalHydrationTest.java`

- [x] **Step 1: Define immutable practice DTOs**

Move the current public nested record components without renaming JSON-visible accessors. For example:

```java
package io.github.imzmq.interview.practice.application.dto;

public record QuizQuestion(
        String id,
        String question,
        java.util.List<String> options,
        int correctIndex,
        String explanation
) {}
```

`CodingAssessment` must retain the current `score`, `feedback`, `nextHint`, and `nextQuestion` accessors. Copy the exact existing component lists for all card payload records.

- [x] **Step 2: Move `CodingPracticeService` and its tests**

Change its package to `practice.application`, switch nested agent DTO references to the new DTO imports, and replace `RAGService.CodingAssessment` with `CodingAssessment`. Keep prompts, retries, structured output behavior, fallback text, skill orchestration, and model routing unchanged.

- [x] **Step 3: Remove the knowledge facade's practice methods**

Delete `CodingPracticeService` injection and the practice-only delegate methods from `RAGService`, including `generateCodingQuestion`, `generateBatchQuiz`, and `evaluateCodingAnswer`. Update RAG tests to construct `RAGService` without a coding service; their retrieval assertions must remain unchanged.

- [x] **Step 4: Run service tests**

Run:

```bash
mvn -q -Dtest=CodingPracticeServiceTest,RAGServiceTest,ParentChildRetrievalHydrationTest test
```

Expected: PASS with the same question generation, parsing, fallback, and retrieval behavior.

## Task 3: Move the Practice agent and API

**Files:**
- Move: `src/main/java/io/github/imzmq/interview/agent/runtime/CodingPracticeAgent.java` → `src/main/java/io/github/imzmq/interview/practice/application/agent/CodingPracticeAgent.java`
- Move: `src/main/java/io/github/imzmq/interview/interview/api/CodingPracticeController.java` → `src/main/java/io/github/imzmq/interview/practice/api/CodingPracticeController.java`
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/AgentEvaluationService.java` only if imports require it; string-based task inference remains unchanged.
- Modify all tests that import the old agent package.

- [x] **Step 1: Make the agent depend directly on the practice service**

Replace the `RAGService` field with `CodingPracticeService` and switch calls one-for-one:

```java
private final CodingPracticeService codingPracticeService;
```

Use the extracted DTOs and `CodingAssessment`. Preserve the component name `CodingPracticeAgent`, output map field `"agent": "CodingPracticeAgent"`, action names, session map, asynchronous profile update, prompts, and all fallback behavior.

- [x] **Step 2: Move the controller without changing HTTP contracts**

Change only its package and DTO imports. Preserve `/api/coding`, all five endpoint paths, request map keys, response status behavior, message updates, card metadata, and JSON serialization.

- [x] **Step 3: Run API and agent-adjacent tests**

Run:

```bash
mvn -q -Dtest=CodingPracticeControllerContractTest,CodingPracticeServiceTest,TaskRouterAgentTest test
```

Expected: PASS after updating test imports; generic router behavior is still unchanged at this checkpoint.

## Task 4: Return coding Chat behavior to Practice

**Files:**
- Move: `src/main/java/io/github/imzmq/interview/interview/application/chat/ActiveCodingChatScenarioHandler.java` → `src/main/java/io/github/imzmq/interview/practice/application/chat/ActiveCodingChatScenarioHandler.java`
- Create: `src/main/java/io/github/imzmq/interview/practice/application/chat/CodingPracticeCardChatScenarioHandler.java`
- Create: `src/main/java/io/github/imzmq/interview/practice/application/chat/CodingPracticeTaskResultPresenter.java`
- Modify: `src/main/java/io/github/imzmq/interview/interview/application/chat/DefaultRoutedChatScenarioHandler.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/TaskResponsePresentationService.java`
- Modify tests under `src/test/java/io/github/imzmq/interview/chat/application/**`

- [x] **Step 1: Move the active coding handler**

Retain `@Order(20)` and the existing `ChatScenarioHandler` SPI. Replace nested DTO type checks with practice DTO imports. Do not change active-session lookup, route execution, SSE names, cancellation, placeholder update, or completion behavior.

- [x] **Step 2: Extract routed card handling**

Move the `batch_quiz`, `scenario_question_generated`, and `fill_question_generated` branches from `DefaultRoutedChatScenarioHandler` into `CodingPracticeCardChatScenarioHandler` with `@Order(190)`. The handler returns `false` for every non-coding result and emits byte-for-byte equivalent payloads for matching results.

After extraction, `DefaultRoutedChatScenarioHandler` remains `@Order(200)` and owns only default/interview response behavior.

- [x] **Step 3: Extract coding text presentation**

Implement `ChatTaskResultPresenter` in `CodingPracticeTaskResultPresenter`. `supports(...)` returns true only for maps whose `agent` equals `CodingPracticeAgent`; `format(...)` contains the current coding formatter unchanged. Remove the coding branch and `formatCodingResult(...)` from generic chat presentation.

- [x] **Step 4: Run Chat behavior tests**

Run:

```bash
mvn -q -Dtest=CodingPracticeCardChatScenarioHandlerTest,CodingPracticeTaskResultPresenterTest,TaskResponsePresentationServiceTest,ChatStreamingServiceTest test
```

Expected: PASS with unchanged event and formatted-text behavior.

## Task 5: Move task handler, context sources, and skill

**Files:**
- Move: `src/main/java/io/github/imzmq/interview/agent/router/handler/CodingPracticeTaskHandler.java` → `src/main/java/io/github/imzmq/interview/practice/application/agent/CodingPracticeTaskHandler.java`
- Move: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingPracticeContextAttributes.java` → `src/main/java/io/github/imzmq/interview/practice/application/context/CodingPracticeContextAttributes.java`
- Move: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingConstraintsContextSource.java` → `src/main/java/io/github/imzmq/interview/practice/application/context/CodingConstraintsContextSource.java`
- Move: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingProfileContextSource.java` → `src/main/java/io/github/imzmq/interview/practice/application/context/CodingProfileContextSource.java`
- Move: `src/main/java/io/github/imzmq/interview/agent/application/context/CodingTaskPlanContextSource.java` → `src/main/java/io/github/imzmq/interview/practice/application/context/CodingTaskPlanContextSource.java`
- Move: `src/main/java/io/github/imzmq/interview/skill/builtin/CodingInterviewCoachSkill.java` → `src/main/java/io/github/imzmq/interview/practice/application/skill/CodingInterviewCoachSkill.java`
- Move corresponding tests to `src/test/java/io/github/imzmq/interview/practice/**`
- Modify: `src/main/java/io/github/imzmq/interview/agent/runtime/TaskRouterAgent.java`
- Modify: `src/main/java/io/github/imzmq/interview/agent/router/TaskHandlerSupport.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/TaskRouterAgentTest.java`

- [x] **Step 1: Move context types and tests**

Change package declarations and imports while continuing to implement the existing agent-owned `AgentContextSource` extension types. Preserve source IDs, supported slot kinds, item priorities, attributes, and text.

- [x] **Step 2: Move the skill and its tests/callers**

Keep the existing `ExecutableSkill` ID, definition, execution mode, failure policy, generated guidance, truncation, and normalization. The generic `SkillRegistry` must continue discovering it through Spring and must not import the concrete practice implementation.

- [x] **Step 3: Move the task handler and localize coding payload enrichment**

Copy the current `enrichCodingPayload(...)` behavior into a private method on `CodingPracticeTaskHandler`. Remove that method from generic `TaskHandlerSupport`. Keep task type `CODING_PRACTICE` and receiver name `CodingAgent` unchanged.

- [x] **Step 4: Remove direct Practice injection from the generic router**

Delete the `CodingPracticeAgent` field and constructor argument from `TaskRouterAgent`. Routing must continue through:

```java
TaskHandler handler = taskHandlerRegistry.require(request.taskType());
return handler.handle(request);
```

Update all `TaskRouterAgentTest` constructors accordingly and retain existing routing assertions.

- [x] **Step 5: Run agent, context, and skill tests**

Run:

```bash
mvn -q -Dtest=TaskRouterAgentTest,CodingPracticeContextAttributesTest,CodingConstraintsContextSourceTest,CodingProfileContextSourceTest,CodingTaskPlanContextSourceTest,CodingPracticeServiceTest test
```

Expected: PASS; Spring discovery contracts and output values remain unchanged.

## Task 6: Enforce Practice ownership and retire historical packages

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`
- Modify: `ARCHITECTURE.md`
- Modify: `PACKAGE_CONVENTIONS.md`
- Modify: `docs/superpowers/specs/2026-10-24-module-boundary-roadmap-refresh-design.md`
- Verify: `docs/architecture/module-boundary-baseline.md`

- [x] **Step 1: Add ownership rules**

Add rules that fail when coding-practice implementations exist under historical owners. The rules must cover at least:

```text
knowledge.application.coding..
agent.runtime.CodingPracticeAgent
agent.router.handler.CodingPracticeTaskHandler
agent.application.context.Coding*
interview.api.CodingPracticeController
interview.application.chat.ActiveCodingChatScenarioHandler
skill.builtin.CodingInterviewCoachSkill
```

Also enforce that generic `agent`, `chat`, `interview`, `knowledge`, and `skill` do not depend on concrete classes under `practice..`; collaboration must occur through existing extension discovery or stable generic task/result values.

- [x] **Step 2: Update architecture documentation**

Record M4 as complete:

- `practice` owns all concrete coding-practice behavior.
- Chat discovers practice handlers and presenter through chat-owned extension contracts.
- Agent discovers the practice task handler and context sources through its existing registries.
- Skill registry discovers the practice skill through `ExecutableSkill`.
- `knowledge.application.coding` and the pre-M4 interview exceptions are retired.
- No cross-module persistence allowlist entry was added; the remaining count stays 17.

- [x] **Step 3: Prove historical packages are absent**

Run:

```bash
find src/main/java/io/github/imzmq/interview -type f | grep -E 'knowledge/application/coding|agent/runtime/CodingPracticeAgent|agent/router/handler/CodingPracticeTaskHandler|interview/api/CodingPracticeController|interview/application/chat/ActiveCodingChatScenarioHandler|skill/builtin/CodingInterviewCoachSkill'
```

Expected: no output and exit status 1 from `grep`.

- [x] **Step 4: Run architecture tests**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: PASS, allowlist still exactly matches the 17 remaining real dependencies.

## Task 7: Full M4 verification

**Files:** all M4 production, test, and documentation files above.

- [x] **Step 1: Compile production code**

```bash
mvn -q compile
```

Expected: exit 0.

- [x] **Step 2: Compile tests**

```bash
mvn -q -DskipTests test-compile
```

Expected: exit 0.

- [x] **Step 3: Run architecture rules**

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: exit 0.

- [x] **Step 4: Run the complete test suite**

```bash
mvn test
```

Expected: exit 0 with no failures or errors. Existing environment-driven skips are reported, not hidden.

- [x] **Step 5: Run verification lifecycle without rerunning tests**

```bash
mvn -q verify -DskipTests
```

Expected: exit 0.

- [x] **Step 6: Validate diff hygiene and working-tree isolation**

```bash
git diff --check
git status --short
git diff --name-status
```

Expected: no whitespace errors. Pre-existing Flyway/user changes remain present and untouched; M4 changes are limited to practice ownership, affected generic callers/tests, architecture documentation, and this plan.

- [x] **Step 7: Review before commit**

Do not commit or push until the user explicitly requests it. When authorized, stage M4 paths explicitly and exclude all pre-existing Flyway and `.DS_Store` paths.
