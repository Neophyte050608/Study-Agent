# Dynamic Planning Execution Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the transitional three-mode request-scoped planner with one rule-planning path, fixed pre-execution fallback, and an independent shadow comparison switch.

**Architecture:** Learning always creates a validated rule plan before using the existing DAG executor. Fixed nodes remain only as pre-execution fallback and durable workflow definition. LLM shadow stays non-executing and is controlled by one Learning-owned boolean while Agent retains bounded runner resources.

**Tech Stack:** Java 21, Spring Boot configuration properties, JUnit 5, Mockito, AssertJ, ArchUnit, Maven.

---

### Task 1: Freeze the final configuration contract

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningPropertiesTest.java`
- Modify: `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local-lite.yml`

- [ ] Replace assertions for `Mode.FIXED` and `maxPlanBytes` with assertions that `shadowEnabled` defaults to `false` and can be set to `true`.
- [ ] Run `mvn -q -Dtest=LearningPlanDynamicPlanningPropertiesTest test`; expect compilation failure because `isShadowEnabled()` and `setShadowEnabled(boolean)` do not exist.
- [ ] Delete `Mode`, `mode`, and `maxPlanBytes`; add `private boolean shadowEnabled` with bean getters/setters; retain the existing planner and graph limit validation.
- [ ] Replace YAML `mode` and `max-plan-bytes` entries with `shadow-enabled: ${APP_LEARNING_PLAN_DYNAMIC_SHADOW_ENABLED:false}`; remove the redundant local-lite mode override.
- [ ] Re-run the focused properties test and expect success.

### Task 2: Enforce one request-scoped execution path

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewTestFixtures.java`
- Modify: `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningService.java`

- [ ] Rewrite service tests so the default path plans, validates and compiles; invalid/exception/timeout cases fall back before execution; disabled shadow submits nothing; enabled shadow submits once; shadow failure leaves the rule graph unchanged; execution failure does not trigger replanning; durable workflow stays fixed.
- [ ] Construct the service in every test with its full production dependency list, including a mocked `DynamicPlanningShadowOperations`; do not retain a short test-only constructor.
- [ ] Run `mvn -q -Dtest=LearningPlanDynamicPlanningServiceTest test`; expect compilation or behavior failures against the old mode-based service.
- [ ] Remove the `FIXED` branch, `ObjectProvider`, optional shadow handling, and all extra constructors. Use one public constructor and one internal `LearningPlanScenarioPolicy` instance.
- [ ] Gate only shadow submission with `properties.isShadowEnabled()`; catch shadow submission failure without changing the compiled rule graph.
- [ ] Re-run the service test and expect success.

### Task 3: Update Spring and profile wiring

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewWiringTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/infrastructure/planning/DynamicPlanningShadowRunnerSpringWiringTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/config/startup/LocalLiteConditionalConfigTest.java`

- [ ] Register a real or mocked `DynamicPlanningShadowOperations` wherever the production service is wired.
- [ ] Replace `APP_LEARNING_PLAN_DYNAMIC_MODE=SHADOW` with `APP_LEARNING_PLAN_DYNAMIC_SHADOW_ENABLED=true` and assert the bound boolean.
- [ ] Change local-lite assertions to verify the switch defaults to `false` and accepts a `true` override.
- [ ] Run the three wiring/config tests together and expect success with zero model calls during context initialization.

### Task 4: Make current documentation describe one truth

**Files:**
- Modify: `docs/development/learning-plan-preview.md`
- Modify: `docs/development/local-startup.md`
- Modify: `docs/development/observability-guidelines.md`
- Modify: `PACKAGE_CONVENTIONS.md`

- [ ] Replace the mode table with the single rule-plan flow, pre-execution fixed fallback, independent `shadow-enabled` switch, and fixed durable workflow.
- [ ] Remove current-operational references to `APP_LEARNING_PLAN_DYNAMIC_MODE`, `FIXED`, `RULES`, `SHADOW`, and `max-plan-bytes`. Leave dated historical specs/plans intact.
- [ ] State that no API Key is needed for automated tests and that real shadow smoke tests use untracked private configuration only.
- [ ] Record ownership: Learning owns the scenario enable switch; Agent owns bounded shadow resources and execution contracts.

### Task 5: Verify architecture and repository hygiene

**Files:**
- Verify all modified files above.

- [ ] Run the focused dynamic planning/property/wiring/local-lite test set with the repository Byte Buddy agent argument.
- [ ] Run `mvn -q -Dtest=ArchitectureRulesTest test` with the same agent argument.
- [ ] Run the wider Agent dynamic-planning and Learning planning package tests.
- [ ] Run `git diff --check` and search current runtime code, tests, and current operational docs for removed configuration names.
- [ ] Review `git status --short --branch`; stage only task files and never stage `AGENTS.md`, `.DS_Store`, `.dev/`, build output, credentials, or local private configuration.
- [ ] Commit as `refactor(planning): converge dynamic planning execution` and push `main` only after all verification succeeds.
