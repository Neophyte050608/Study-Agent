# Module Boundary M0 Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立模块边界治理的 M0 基线：记录现有跨模块持久化依赖、阻止新增同类违规，并把已确认的目标模块与公开契约规则同步到架构文档。

**Architecture:** 保持现有 Spring Boot 单体和生产代码行为不变。ArchUnit 使用“通用检测器 + 精确历史 allowlist”检查跨顶层模块访问 `infrastructure.persistence`；每条例外绑定清理里程碑，迁移消除依赖时必须同步删除。M0 不创建任何生产 facade、port 或 adapter，也不移动生产类。

**Tech Stack:** Java 21, Spring Boot 3.3, ArchUnit 1.3.0, JUnit 5, AssertJ, Maven, Markdown.

---

## Scope and sequencing

本计划只实施已确认设计中的 M0。后续阶段分别编写独立实施计划：

- M1：`intent` 合并到 `routing`，统一 understanding ownership。
- M2：结构化模型输出能力迁入 `modelrouting`。
- M3：修正 `chat` ownership 和 `interview -> chat persistence`。
- M4：建立 `practice`。
- M5：面试专属 Agent 回归 `interview`。
- M6：精简通用 `agent` 并改为注册式扩展。
- M7：拆分 `knowledge/observability/graph/rag` 职责。
- M8：删除历史 allowlist，启用最终 contract-only 与 cycle 规则。

M0 完成后，源码行为、HTTP API、数据库结构和 SSE 协议均应保持不变。

## File structure

### Create

- `src/test/java/io/github/imzmq/interview/architecture/ModuleBoundaryRules.java`
  - 提供顶层模块识别、跨模块 persistence dependency 提取，以及可传入 allowlist 的 ArchUnit condition/rule。
- `src/test/java/io/github/imzmq/interview/architecture/LegacyCrossModulePersistenceDependencies.java`
  - 保存当前 25 条精确源类→目标持久化类依赖及计划清理里程碑。
- `src/test/java/io/github/imzmq/interview/architecturefixturesource/application/CrossModulePersistenceConsumer.java`
  - 规则自测的违规调用方 fixture。
- `src/test/java/io/github/imzmq/interview/architecturefixturetarget/infrastructure/persistence/ForeignPersistenceDO.java`
  - 规则自测的外部 persistence fixture。
- `docs/architecture/module-boundary-baseline.md`
  - 记录模块地图、25 条历史边、迁移 owner 和后续删除规则。

### Modify

- `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`
  - 增加规则自测、生产边界规则和“allowlist 不得过期”测试。
- `ARCHITECTURE.md`
  - 将当前架构基线更新为已确认的目标模块地图和 `application.contract` 规则，并明确仍处于 M0 过渡期。
- `PACKAGE_CONVENTIONS.md`
  - 增加公开契约位置、目标 ownership 和跨模块依赖硬规则；删除会鼓励新代码继续写入旧错位包的 placement 指引。

### Explicitly unchanged

- `src/main/**`
- `pom.xml`
- `src/main/resources/**`
- `frontend/**`
- 数据库脚本和运行配置

---

### Task 1: Build and prove the reusable cross-module persistence rule

**Files:**
- Create: `src/test/java/io/github/imzmq/interview/architecture/ModuleBoundaryRules.java`
- Create: `src/test/java/io/github/imzmq/interview/architecturefixturesource/application/CrossModulePersistenceConsumer.java`
- Create: `src/test/java/io/github/imzmq/interview/architecturefixturetarget/infrastructure/persistence/ForeignPersistenceDO.java`
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`

- [ ] **Step 1: Create the target persistence fixture**

Create `src/test/java/io/github/imzmq/interview/architecturefixturetarget/infrastructure/persistence/ForeignPersistenceDO.java`:

```java
package io.github.imzmq.interview.architecturefixturetarget.infrastructure.persistence;

public final class ForeignPersistenceDO {
}
```

- [ ] **Step 2: Create the cross-module consumer fixture**

Create `src/test/java/io/github/imzmq/interview/architecturefixturesource/application/CrossModulePersistenceConsumer.java`:

```java
package io.github.imzmq.interview.architecturefixturesource.application;

import io.github.imzmq.interview.architecturefixturetarget.infrastructure.persistence.ForeignPersistenceDO;

public final class CrossModulePersistenceConsumer {

    private final ForeignPersistenceDO persistenceObject;

    public CrossModulePersistenceConsumer(ForeignPersistenceDO persistenceObject) {
        this.persistenceObject = persistenceObject;
    }

    public ForeignPersistenceDO persistenceObject() {
        return persistenceObject;
    }
}
```

- [ ] **Step 3: Write failing rule tests**

Add these imports to `ArchitectureRulesTest.java`:

```java
import io.github.imzmq.interview.architecturefixturesource.application.CrossModulePersistenceConsumer;
import io.github.imzmq.interview.architecturefixturetarget.infrastructure.persistence.ForeignPersistenceDO;

import java.util.Set;
```

Add the following tests before the existing private helper methods:

```java
    @Test
    void cross_module_persistence_rule_rejects_unlisted_dependency() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                CrossModulePersistenceConsumer.class,
                ForeignPersistenceDO.class
        );

        EvaluationResult result = ModuleBoundaryRules
                .noCrossModulePersistenceAccess(Set.of())
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(detail -> {
                    assertThat(detail).contains(CrossModulePersistenceConsumer.class.getName());
                    assertThat(detail).contains(ForeignPersistenceDO.class.getName());
                });
    }

    @Test
    void cross_module_persistence_rule_allows_same_module_persistence() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                ApplicationServiceDependingOnMapper.class,
                SamplePersistenceMapper.class,
                SamplePersistenceDO.class
        );

        EvaluationResult result = ModuleBoundaryRules
                .noCrossModulePersistenceAccess(Set.of())
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isFalse();
    }

    @Test
    void cross_module_persistence_rule_accepts_exact_allowlisted_dependency() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                CrossModulePersistenceConsumer.class,
                ForeignPersistenceDO.class
        );
        ModuleBoundaryRules.DependencyKey allowed = new ModuleBoundaryRules.DependencyKey(
                CrossModulePersistenceConsumer.class.getName(),
                ForeignPersistenceDO.class.getName()
        );

        EvaluationResult result = ModuleBoundaryRules
                .noCrossModulePersistenceAccess(Set.of(allowed))
                .evaluate(fixtures);

        assertThat(result.hasViolation()).isFalse();
    }
```

- [ ] **Step 4: Run the focused tests and verify RED**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest#cross_module_persistence_rule_rejects_unlisted_dependency+cross_module_persistence_rule_allows_same_module_persistence+cross_module_persistence_rule_accepts_exact_allowlisted_dependency test
```

Expected: test compilation fails because `ModuleBoundaryRules` does not exist. The failure must not be caused by unrelated production compilation errors.

- [ ] **Step 5: Implement the reusable boundary rule**

Create `src/test/java/io/github/imzmq/interview/architecture/ModuleBoundaryRules.java`:

```java
package io.github.imzmq.interview.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

final class ModuleBoundaryRules {

    static final String ROOT_PACKAGE = "io.github.imzmq.interview";
    private static final String ROOT_PREFIX = ROOT_PACKAGE + ".";
    private static final String PERSISTENCE_SEGMENT = ".infrastructure.persistence";

    private ModuleBoundaryRules() {
    }

    static ArchRule noCrossModulePersistenceAccess(Set<DependencyKey> allowedDependencies) {
        Set<DependencyKey> immutableAllowlist = Set.copyOf(allowedDependencies);
        return classes()
                .that().resideInAPackage(ROOT_PACKAGE + "..")
                .should(notAccessAnotherModulesPersistence(immutableAllowlist));
    }

    static Set<DependencyKey> findCrossModulePersistenceDependencies(JavaClasses classes) {
        Set<DependencyKey> dependencies = new LinkedHashSet<>();
        for (JavaClass javaClass : classes) {
            for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                if (isCrossModulePersistenceDependency(dependency)) {
                    dependencies.add(DependencyKey.from(dependency));
                }
            }
        }
        return Set.copyOf(dependencies);
    }

    private static ArchCondition<JavaClass> notAccessAnotherModulesPersistence(
            Set<DependencyKey> allowedDependencies
    ) {
        return new ArchCondition<>("not access another top-level module's infrastructure.persistence") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    if (!isCrossModulePersistenceDependency(dependency)) {
                        continue;
                    }
                    DependencyKey key = DependencyKey.from(dependency);
                    if (!allowedDependencies.contains(key)) {
                        events.add(SimpleConditionEvent.violated(
                                item,
                                key.originClassName() + " depends on " + key.targetClassName()
                        ));
                    }
                }
            }
        };
    }

    private static boolean isCrossModulePersistenceDependency(Dependency dependency) {
        JavaClass origin = dependency.getOriginClass();
        JavaClass target = dependency.getTargetClass();
        Optional<String> originModule = topLevelModule(origin.getPackageName());
        Optional<String> targetModule = topLevelModule(target.getPackageName());

        return target.getPackageName().contains(PERSISTENCE_SEGMENT)
                && originModule.isPresent()
                && targetModule.isPresent()
                && !originModule.get().equals(targetModule.get());
    }

    private static Optional<String> topLevelModule(String packageName) {
        if (!packageName.startsWith(ROOT_PREFIX)) {
            return Optional.empty();
        }
        String remainder = packageName.substring(ROOT_PREFIX.length());
        int separator = remainder.indexOf('.');
        return Optional.of(separator < 0 ? remainder : remainder.substring(0, separator));
    }

    record DependencyKey(String originClassName, String targetClassName) {

        DependencyKey {
            if (originClassName == null || originClassName.isBlank()) {
                throw new IllegalArgumentException("originClassName must not be blank");
            }
            if (targetClassName == null || targetClassName.isBlank()) {
                throw new IllegalArgumentException("targetClassName must not be blank");
            }
        }

        private static DependencyKey from(Dependency dependency) {
            return new DependencyKey(
                    dependency.getOriginClass().getName(),
                    dependency.getTargetClass().getName()
            );
        }
    }
}
```

- [ ] **Step 6: Run the focused tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest#cross_module_persistence_rule_rejects_unlisted_dependency+cross_module_persistence_rule_allows_same_module_persistence+cross_module_persistence_rule_accepts_exact_allowlisted_dependency test
```

Expected: PASS. The first test proves an unlisted cross-module persistence edge is rejected; the second proves same-module persistence remains legal; the third proves only the exact source-target pair is exempted.

- [ ] **Step 7: Run the existing architecture test class**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: PASS. Existing Controller/Mapper and retired-package rules remain effective.

- [ ] **Step 8: Commit the rule primitive and its self-tests**

```bash
git add \
  src/test/java/io/github/imzmq/interview/architecture/ModuleBoundaryRules.java \
  src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java \
  src/test/java/io/github/imzmq/interview/architecturefixturesource/application/CrossModulePersistenceConsumer.java \
  src/test/java/io/github/imzmq/interview/architecturefixturetarget/infrastructure/persistence/ForeignPersistenceDO.java
git commit -m "test(architecture): 添加跨模块持久化边界检测"
```

---

### Task 2: Freeze the 25 known production violations with an exact allowlist

**Files:**
- Create: `src/test/java/io/github/imzmq/interview/architecture/LegacyCrossModulePersistenceDependencies.java`
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`

- [ ] **Step 1: Add the production rule without an allowlist to prove the baseline is red**

Temporarily add this rule next to the other `@ArchTest` fields:

```java
    @ArchTest
    static final ArchRule modules_should_not_access_other_modules_persistence =
            ModuleBoundaryRules.noCrossModulePersistenceAccess(Set.of());
```

- [ ] **Step 2: Run the production rule and verify RED**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: FAIL with cross-module dependencies including at least:

```text
io.github.imzmq.interview.interview.application.WebChatService
  -> io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper
io.github.imzmq.interview.media.application.ImageService
  -> io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentMapper
io.github.imzmq.interview.search.application.PersonalRanker
  -> io.github.imzmq.interview.learning.infrastructure.persistence.UserKnowledgeStateMapper
```

Save the full failure output in the task log. If the rule finds additional real production edges, add each exact pair to the allowlist and baseline document with an M3/M5/M7 cleanup owner; do not broaden the package exclusion.

- [ ] **Step 3: Create the explicit legacy dependency catalog**

Create `src/test/java/io/github/imzmq/interview/architecture/LegacyCrossModulePersistenceDependencies.java`:

```java
package io.github.imzmq.interview.architecture;

import java.util.LinkedHashSet;
import java.util.Set;

final class LegacyCrossModulePersistenceDependencies {

    private static final Set<LegacyDependency> DEPENDENCIES = Set.of(
            dependency("io.github.imzmq.interview.agent.runtime.InterviewOrchestratorAgent",
                    "io.github.imzmq.interview.interview.infrastructure.persistence.SessionRepository", "M5"),
            dependency("io.github.imzmq.interview.agent.runtime.RollingSummaryAgent",
                    "io.github.imzmq.interview.interview.infrastructure.persistence.InterviewSessionMapper", "M5"),
            dependency("io.github.imzmq.interview.agent.runtime.RollingSummaryAgent",
                    "io.github.imzmq.interview.interview.infrastructure.persistence.SessionRepository", "M5"),
            dependency("io.github.imzmq.interview.feedback.application.FeedbackApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagFeedbackDO", "M7"),
            dependency("io.github.imzmq.interview.feedback.application.FeedbackApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagFeedbackMapper", "M7"),
            dependency("io.github.imzmq.interview.feedback.application.MetricsSnapshotScheduler",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagMetricsSnapshotDO", "M7"),
            dependency("io.github.imzmq.interview.feedback.application.MetricsSnapshotScheduler",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagMetricsSnapshotMapper", "M7"),
            dependency("io.github.imzmq.interview.feedback.application.MetricsSnapshotScheduler",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagTraceDO", "M7"),
            dependency("io.github.imzmq.interview.feedback.application.MetricsSnapshotScheduler",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagTraceMapper", "M7"),
            dependency("io.github.imzmq.interview.interview.api.ChatController",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO", "M3"),
            dependency("io.github.imzmq.interview.interview.api.ChatController",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO", "M3"),
            dependency("io.github.imzmq.interview.interview.api.CodingPracticeController",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO", "M4"),
            dependency("io.github.imzmq.interview.interview.application.WebChatService",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO", "M3"),
            dependency("io.github.imzmq.interview.interview.application.WebChatService",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageMapper", "M3"),
            dependency("io.github.imzmq.interview.interview.application.WebChatService",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionDO", "M3"),
            dependency("io.github.imzmq.interview.interview.application.WebChatService",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatSessionMapper", "M3"),
            dependency("io.github.imzmq.interview.interview.application.ObservabilityApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagFeedbackDO", "M7"),
            dependency("io.github.imzmq.interview.interview.application.ObservabilityApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagFeedbackMapper", "M7"),
            dependency("io.github.imzmq.interview.interview.application.ObservabilityApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagMetricsSnapshotDO", "M7"),
            dependency("io.github.imzmq.interview.interview.application.ObservabilityApplicationService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagMetricsSnapshotMapper", "M7"),
            dependency("io.github.imzmq.interview.knowledge.application.chatstream.ChatStreamingSupport",
                    "io.github.imzmq.interview.chat.infrastructure.persistence.ChatMessageDO", "M3"),
            dependency("io.github.imzmq.interview.media.application.ImageService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentDO", "M7"),
            dependency("io.github.imzmq.interview.media.application.ImageService",
                    "io.github.imzmq.interview.knowledge.infrastructure.persistence.RagParentMapper", "M7"),
            dependency("io.github.imzmq.interview.search.application.PersonalRanker",
                    "io.github.imzmq.interview.learning.infrastructure.persistence.UserKnowledgeStateDO", "M7"),
            dependency("io.github.imzmq.interview.search.application.PersonalRanker",
                    "io.github.imzmq.interview.learning.infrastructure.persistence.UserKnowledgeStateMapper", "M7")
    );

    private LegacyCrossModulePersistenceDependencies() {
    }

    static Set<LegacyDependency> all() {
        return DEPENDENCIES;
    }

    static Set<ModuleBoundaryRules.DependencyKey> keys() {
        Set<ModuleBoundaryRules.DependencyKey> keys = new LinkedHashSet<>();
        for (LegacyDependency dependency : DEPENDENCIES) {
            keys.add(dependency.key());
        }
        return Set.copyOf(keys);
    }

    private static LegacyDependency dependency(String origin, String target, String milestone) {
        return new LegacyDependency(new ModuleBoundaryRules.DependencyKey(origin, target), milestone);
    }

    record LegacyDependency(ModuleBoundaryRules.DependencyKey key, String cleanupMilestone) {

        LegacyDependency {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            if (cleanupMilestone == null || cleanupMilestone.isBlank()) {
                throw new IllegalArgumentException("cleanupMilestone must not be blank");
            }
        }
    }
}
```

- [ ] **Step 4: Replace the temporary empty allowlist with the catalog**

Replace the temporary production rule in `ArchitectureRulesTest.java` with:

```java
    @ArchTest
    static final ArchRule modules_should_not_access_other_modules_persistence =
            ModuleBoundaryRules.noCrossModulePersistenceAccess(
                    LegacyCrossModulePersistenceDependencies.keys()
            );
```

- [ ] **Step 5: Add a test that rejects stale allowlist entries**

Add this test to `ArchitectureRulesTest.java`:

```java
    @Test
    void legacy_cross_module_persistence_allowlist_matches_real_dependencies() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ModuleBoundaryRules.ROOT_PACKAGE);
        Set<ModuleBoundaryRules.DependencyKey> actual =
                ModuleBoundaryRules.findCrossModulePersistenceDependencies(productionClasses);

        assertThat(actual)
                .as("Remove an allowlist entry in the same commit that removes its production dependency")
                .containsAll(LegacyCrossModulePersistenceDependencies.keys());
    }
```

This assertion deliberately fails after a dependency is removed until the matching allowlist entry is deleted. The main `@ArchTest` fails if a new dependency appears without an exact allowlist entry.

- [ ] **Step 6: Run the architecture tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: PASS with exactly the known production edges allowlisted. If it fails because the graph contains additional direct type dependencies that the import inventory missed, add only the exact source-target pair and assign its real cleanup milestone.

- [ ] **Step 7: Verify the allowlist cannot hide a new source class**

Temporarily change the fixture allowlist test to use an unrelated origin class, run it, and confirm it fails; then restore the exact key. No code from this mutation should remain staged.

Run after restoring:

```bash
mvn -q -Dtest=ArchitectureRulesTest#cross_module_persistence_rule_accepts_exact_allowlisted_dependency test
```

Expected: PASS only with the exact fixture origin and target names.

- [ ] **Step 8: Commit the production guardrail**

```bash
git add \
  src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java \
  src/test/java/io/github/imzmq/interview/architecture/LegacyCrossModulePersistenceDependencies.java
git commit -m "test(architecture): 冻结跨模块持久化违规基线"
```

---

### Task 3: Record the M0 dependency baseline

**Files:**
- Create: `docs/architecture/module-boundary-baseline.md`

- [ ] **Step 1: Create the baseline document**

Create `docs/architecture/module-boundary-baseline.md` with this content:

```markdown
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
| interview | `interview.api.ChatController` | chat | `ChatMessageDO` | M3 |
| interview | `interview.api.ChatController` | chat | `ChatSessionDO` | M3 |
| interview | `interview.api.CodingPracticeController` | chat | `ChatMessageDO` | M4 |
| interview | `interview.application.WebChatService` | chat | `ChatMessageDO` | M3 |
| interview | `interview.application.WebChatService` | chat | `ChatMessageMapper` | M3 |
| interview | `interview.application.WebChatService` | chat | `ChatSessionDO` | M3 |
| interview | `interview.application.WebChatService` | chat | `ChatSessionMapper` | M3 |
| interview | `interview.application.ObservabilityApplicationService` | knowledge | `RagFeedbackDO` | M7 |
| interview | `interview.application.ObservabilityApplicationService` | knowledge | `RagFeedbackMapper` | M7 |
| interview | `interview.application.ObservabilityApplicationService` | knowledge | `RagMetricsSnapshotDO` | M7 |
| interview | `interview.application.ObservabilityApplicationService` | knowledge | `RagMetricsSnapshotMapper` | M7 |
| knowledge | `knowledge.application.chatstream.ChatStreamingSupport` | chat | `ChatMessageDO` | M3 |
| media | `media.application.ImageService` | knowledge | `RagParentDO` | M7 |
| media | `media.application.ImageService` | knowledge | `RagParentMapper` | M7 |
| search | `search.application.PersonalRanker` | learning | `UserKnowledgeStateDO` | M7 |
| search | `search.application.PersonalRanker` | learning | `UserKnowledgeStateMapper` | M7 |

Baseline count: **25 exact class dependency pairs**.

## Removal protocol

For each repaired edge:

1. Add or reuse a real provider-owned `application.contract` only when there is an actual cross-module caller.
2. Add focused tests for the contract and caller behavior.
3. Switch the caller away from the foreign persistence type.
4. Remove the matching entry from `LegacyCrossModulePersistenceDependencies` and this table in the same commit.
5. Run `mvn -q -Dtest=ArchitectureRulesTest test` before broader verification.

If a new violation is found, fix it rather than adding it to the baseline. The baseline represents M0 history only and must monotonically shrink.
```

- [ ] **Step 2: Verify the Markdown has no placeholders or malformed whitespace**

Run:

```bash
grep -nE 'TODO|TBD|待补|待定' docs/architecture/module-boundary-baseline.md
```

Expected: no output and exit code 1.

Run this check while the new file is still untracked:

```bash
git diff --no-index --check /dev/null docs/architecture/module-boundary-baseline.md
diff_exit=$?
test "$diff_exit" -eq 1
```

Expected: the first command prints the new-file diff without whitespace errors and returns 1 because the files differ; the final `test` returns 0. Any whitespace error appears before the final status check and must be fixed.

- [ ] **Step 3: Cross-check Java and Markdown counts**

Run:

```bash
grep -c 'dependency("io.github.imzmq.interview' \
  src/test/java/io/github/imzmq/interview/architecture/LegacyCrossModulePersistenceDependencies.java
grep -c '^| [a-z].* | M[0-9] |$' \
  docs/architecture/module-boundary-baseline.md
```

Expected: both commands print `25`.

- [ ] **Step 4: Commit the baseline inventory**

Because `/docs/` is ignored for new files, force-add only this exact file:

```bash
git add -f docs/architecture/module-boundary-baseline.md
git commit -m "docs: 记录模块依赖治理基线"
```

---

### Task 4: Update architecture and package ownership documentation

**Files:**
- Modify: `ARCHITECTURE.md`
- Modify: `PACKAGE_CONVENTIONS.md`

- [ ] **Step 1: Replace the current-domain list in `ARCHITECTURE.md`**

Replace the section beginning with `Current domain-owned packages include:` through the end of the current knowledge subpackage list with:

```markdown
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
```

- [ ] **Step 2: Replace `ARCHITECTURE.md` dependency direction**

Replace the existing `Dependency direction:` list with:

```markdown
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
```

- [ ] **Step 3: Replace `ARCHITECTURE.md` placement quick rules**

Replace the current `## Placement Quick Rules` section with:

```markdown
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
```

- [ ] **Step 4: Update `PACKAGE_CONVENTIONS.md` public contract rules**

Insert this section after `## 4. Dependency boundaries`:

```markdown
## 4.1 Cross-module public contracts

- The only normal cross-module business entry is `<owner>.application.contract`.
- Create a contract only when a real external module calls the capability; do not add empty facades or speculative ports.
- Provider-owned command/query/result types stay with the provider. Do not move business DTOs into `common`.
- Contracts must not expose Mapper, DO, infrastructure adapter, Spring Web, or vendor SDK types.
- Cross-module access to `<owner>.application.dto`, use-case implementations, helpers, and `infrastructure` is forbidden.
- A business module may implement an extension contract owned by `agent`, `chat`, `skill`, or `tool`; the registry depends on the extension interface, not concrete business implementations.
```

- [ ] **Step 5: Replace the `PACKAGE_CONVENTIONS.md` placement cheatsheet**

Replace `## 7. Placement Cheatsheet (Current)` and its knowledge breakdown through the `Forbidden placement:` list with:

```markdown
## 7. Placement Cheatsheet (Target Ownership)

- Generic chat session/message/context/stream orchestration: `chat`.
- Interview lifecycle, answer evaluation, report, resume, and interview-specific Agent/Handler/context: `interview`.
- Coding practice generation/evaluation/session and practice-specific Agent/Handler/context: `practice`.
- Learning profile/events/plans and learning-specific Agent/Handler/context: `learning`.
- Knowledge catalog/indexing/retrieval/evidence/local graph/GraphRAG: `knowledge`.
- Intent understanding, clarification, relation judgment, and routing: `routing`.
- Generic Task/Handler contracts, registry, lifecycle, cancellation, A2A, and context assembly mechanism: `agent`.
- Generic Skill contract/registry/invocation: `skill`; a business-specific Skill implementation belongs to its business owner.
- Generic Tool contract/authorization/execution: `tool`; MCP protocol adaptation belongs to `mcp`.
- Model routing, health, execution, failover, and structured model output: `modelrouting`.
- Trace, span, metrics, and operational query: `observability`.
- Cross-domain SSE connection/transport only: `common.stream`.

Knowledge package ownership for new code:

- `knowledge.application.catalog`: knowledge base and document catalog use cases.
- `knowledge.application.indexing`: chunking, tokenizer, lexical and parent-child index use cases.
- `knowledge.application.retrieval`: retrieval orchestration, fusion, query rewrite, evidence evaluation, and fallback.
- `knowledge.application.localgraph`: local graph retrieval chain.
- `knowledge.domain`: knowledge and GraphRAG domain types without Spring Data or vendor dependencies.
- `knowledge.infrastructure.persistence`: knowledge-owned MyBatis DOs and Mappers.
- `knowledge.infrastructure.graph`: Neo4j/Spring Data graph adapters and repositories.

Historical packages are migration-only:

- Do not add generic chat code to `knowledge.application.chatstream` or `knowledge.application.context`.
- Do not add coding-practice behavior to `knowledge.application.coding`.
- Do not add generic trace/metrics behavior to `knowledge.application.observability`.
- Do not add new code to top-level `intent`, `graph`, or `rag`.
- Do not add business-specific Agent, Handler, context source, or schema to the generic `agent` module.
- `RAGService` remains a compatibility facade during migration; new retrieval behavior belongs to focused knowledge services.
```

- [ ] **Step 6: Expand `PACKAGE_CONVENTIONS.md` hard guardrails**

Append these bullets to `## 8. Hard Guardrails`:

```markdown
- Cross-module business calls must target the provider's `application.contract`.
- A module must not access another module's Mapper, DO, repository implementation, or other infrastructure class.
- `common` must not depend on a business module.
- `modelrouting` must not depend on `chat`; shared structured-output logic belongs to `modelrouting`.
- `observability` must not depend on `knowledge`; business modules record telemetry through observability contracts.
- Generic `agent` runtime must not import concrete interview, practice, learning, or knowledge Agent/Handler implementations.
- Existing M0 exceptions are listed in `docs/architecture/module-boundary-baseline.md`; do not add new exceptions.
```

- [ ] **Step 7: Check documentation consistency**

Run:

```bash
grep -nE 'TODO|TBD|待补|待定' ARCHITECTURE.md PACKAGE_CONVENTIONS.md
```

Expected: no output and exit code 1.

Run:

```bash
git diff --check -- ARCHITECTURE.md PACKAGE_CONVENTIONS.md
```

Expected: no output and exit code 0.

Run:

```bash
grep -n 'knowledge.application.coding\|knowledge.application.chatstream\|knowledge.application.observability' \
  ARCHITECTURE.md PACKAGE_CONVENTIONS.md
```

Expected: matches occur only in text that marks those packages as historical/migration-only, not as valid placement for new code.

- [ ] **Step 8: Commit the architecture documentation**

```bash
git add ARCHITECTURE.md PACKAGE_CONVENTIONS.md
git commit -m "docs: 固化模块公开契约与目标归属"
```

---

### Task 5: Run the M0 verification gate

**Files:**
- Verify only; modify files only to fix failures caused by Tasks 1-4.

- [ ] **Step 1: Confirm only intended files changed in the M0 commit range**

Tasks 1-4 each create one commit. Resolve the commit immediately before Task 1 and inspect the range:

```bash
M0_BASE_COMMIT=$(git rev-parse HEAD~4)
git diff --name-only "$M0_BASE_COMMIT"..HEAD
```

Expected files only:

```text
ARCHITECTURE.md
PACKAGE_CONVENTIONS.md
docs/architecture/module-boundary-baseline.md
src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java
src/test/java/io/github/imzmq/interview/architecture/LegacyCrossModulePersistenceDependencies.java
src/test/java/io/github/imzmq/interview/architecture/ModuleBoundaryRules.java
src/test/java/io/github/imzmq/interview/architecturefixturesource/application/CrossModulePersistenceConsumer.java
src/test/java/io/github/imzmq/interview/architecturefixturetarget/infrastructure/persistence/ForeignPersistenceDO.java
```

Do not include the user's pre-existing `AGENTS.md` modification or `.DS_Store` files.

- [ ] **Step 2: Run the focused architecture suite**

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: PASS.

- [ ] **Step 3: Compile production code**

```bash
mvn -q compile
```

Expected: exit code 0.

- [ ] **Step 4: Compile all tests**

```bash
mvn -q -DskipTests test-compile
```

Expected: exit code 0.

- [ ] **Step 5: Run the complete test suite**

```bash
mvn test
```

Expected: exit code 0. If an environment-specific failure occurs, record the exact failing test and stack trace; do not claim the suite passed.

- [ ] **Step 6: Run the verification lifecycle without rerunning tests**

```bash
mvn -q verify -DskipTests
```

Expected: exit code 0.

- [ ] **Step 7: Check repository status**

```bash
git status --short --branch
```

Expected: no M0 implementation files remain unstaged or uncommitted. The user's pre-existing `AGENTS.md` modification and `.DS_Store` files may remain and must not be altered.

- [ ] **Step 8: Create a verification-only fix commit if required**

If Tasks 2-6 required changes to the M0 files, rerun the failed command and then commit only those fixes:

```bash
git add \
  ARCHITECTURE.md \
  PACKAGE_CONVENTIONS.md \
  src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java \
  src/test/java/io/github/imzmq/interview/architecture/LegacyCrossModulePersistenceDependencies.java \
  src/test/java/io/github/imzmq/interview/architecture/ModuleBoundaryRules.java

git add -f docs/architecture/module-boundary-baseline.md
git commit -m "test(architecture): 修正模块边界基线验证"
```

Skip this commit when verification requires no fixes.

---

## M0 completion criteria

M0 is complete only when all conditions hold:

1. `ArchitectureRulesTest` rejects an unlisted cross-module `infrastructure.persistence` dependency.
2. Existing production violations are represented by exact source-target keys, not package-wide exclusions.
3. Each legacy key names a cleanup milestone, and stale keys fail a test after the production edge disappears.
4. The baseline document and Java allowlist both contain the same 25 initial edges.
5. `ARCHITECTURE.md` and `PACKAGE_CONVENTIONS.md` describe the confirmed target ownership and `application.contract` boundary.
6. No production Java, runtime configuration, database script, HTTP contract, or frontend file changes.
7. Focused ArchUnit, compile, test-compile, full tests, and verify commands have actual recorded outcomes.
8. User-owned uncommitted files remain untouched.

## Follow-up planning gate

After M0 passes, create the M1 implementation plan from the confirmed spec and the new baseline. Do not combine M1 with M2: routing ownership and modelrouting structured-output ownership have independent tests, rollback points, and ArchUnit rules.
