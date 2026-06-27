# Modular Agent Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize Study-Agent into a maintainable modular monolith oriented around future agent work, without changing runtime behavior or introducing Mastra.

**Architecture:** Start from the already-pushed cleanup branch (`refactor/codebase-structure-cleanup`) because it retires top-level `entity`/`mapper`/`dto`. Then introduce explicit macro-module boundaries (`model`, `integration`, `platform`, `knowledge`, `conversation`, `agent`, `tools`, `interview`, `interfaces`, `shared`) through documentation, ArchUnit guardrails, and small package-move batches. Keep Mastra as a future `agent.internal.infrastructure.runtime.mastra` adapter only; no dependency or placeholder implementation in this phase.

**Tech Stack:** Java 21, Spring Boot, Maven, MyBatis-Plus, JUnit 5, AssertJ, ArchUnit, Git worktrees.

---

## Implementation Baseline

Use this worktree unless the user explicitly requests another base:

```bash
cd /Users/bytedance/bits/StudyAgent/Study-Agent/.worktrees/codebase-structure-cleanup
git status --short --branch
```

Expected branch:

```text
## refactor/codebase-structure-cleanup...origin/refactor/codebase-structure-cleanup
```

If the branch is not up to date, run:

```bash
git pull
```

Do not implement this plan directly on `main` until the cleanup branch has been merged or equivalently applied.

## Files and Responsibilities

### Documentation

- `ARCHITECTURE.md` — update from domain-first cleanup baseline to target modular-monolith architecture.
- `PACKAGE_CONVENTIONS.md` — update placement cheatsheet and forbidden placements for macro-modules and `internal` packages.
- `docs/superpowers/specs/2026-06-27-modular-agent-architecture-design.md` — keep as the approved design reference; update only if implementation intentionally deviates.

### Architecture Tests

- `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java` — add guardrails for macro-module internals, retired top-level packages, and controller/persistence isolation.
- `src/test/java/io/github/imzmq/interview/architecture/fixture/...` — add minimal fixture classes only when a new ArchUnit rule needs a positive failure proof.

### Source Packages

- `src/main/java/io/github/imzmq/interview/modelrouting/**` → `src/main/java/io/github/imzmq/interview/model/**`.
- `src/main/java/io/github/imzmq/interview/im/**` → `src/main/java/io/github/imzmq/interview/integration/im/**`.
- `src/main/java/io/github/imzmq/interview/mcp/**` → `src/main/java/io/github/imzmq/interview/integration/mcp/**`.
- `src/main/java/io/github/imzmq/interview/search/**` → split between `integration.search/**` and `interfaces.web/**` only if the controller move is done in the same batch.
- `src/main/java/io/github/imzmq/interview/identity/**`, `security/**`, `observability/**`, and `config/**` → `platform/**`, preserving subpackage names where possible.
- `src/main/java/io/github/imzmq/interview/rag/**`, `graph/**`, `ingestion/**`, `media/**` → `knowledge/**`, using `knowledge.internal.ingestion` and `knowledge.internal.media` for knowledge-specific import/extraction flows.
- `src/main/java/io/github/imzmq/interview/chat/**` and `stream/**` → `conversation/**`.
- `src/main/java/io/github/imzmq/interview/skill/**` → `tools/**` for executable-skill contracts/runtime; any vendor client remains under `integration`.
- `src/main/java/io/github/imzmq/interview/agent/**` remains top-level `agent` but should gain clearer internal/package conventions after dependent modules are stable.

## Task 1: Confirm Baseline and Record Dependency Snapshot

**Files:**
- Read: `ARCHITECTURE.md`
- Read: `PACKAGE_CONVENTIONS.md`
- Read: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`
- Create: `docs/superpowers/plans/artifacts/2026-06-27-modular-agent-baseline.txt`

- [ ] **Step 1: Enter the cleanup worktree**

```bash
cd /Users/bytedance/bits/StudyAgent/Study-Agent/.worktrees/codebase-structure-cleanup
git status --short --branch
```

Expected: branch is `refactor/codebase-structure-cleanup`. If there are uncommitted user changes, stop and ask before editing.

- [ ] **Step 2: Create the artifacts directory**

```bash
mkdir -p docs/superpowers/plans/artifacts
```

Expected: command exits with code 0.

- [ ] **Step 3: Capture package inventory**

```bash
{
  echo '# Modular Agent Architecture Baseline'
  echo
  echo '## Git'
  git status --short --branch
  echo
  echo '## Top-level packages after cleanup baseline'
  find src/main/java/io/github/imzmq/interview -mindepth 1 -maxdepth 1 -type d | sed 's#src/main/java/io/github/imzmq/interview/##' | sort
  echo
  echo '## Current ArchUnit rules'
  grep -n "static final ArchRule" src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java
} > docs/superpowers/plans/artifacts/2026-06-27-modular-agent-baseline.txt
```

Expected: artifact file exists and includes top-level packages such as `agent`, `knowledge`, `modelrouting`, `im`, `mcp`, `search`, `security`, `config`.

- [ ] **Step 4: Run current narrow verification**

```bash
mvn -q compile
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: both commands pass. If they fail, fix the baseline before continuing; do not start package moves on a red baseline.

- [ ] **Step 5: Commit baseline artifact**

```bash
git add -f docs/superpowers/plans/artifacts/2026-06-27-modular-agent-baseline.txt
git commit -m "docs: 记录模块化架构实施基线"
```

Expected: one documentation commit.

## Task 2: Update Architecture and Package Conventions for Macro-Modules

**Files:**
- Modify: `ARCHITECTURE.md`
- Modify: `PACKAGE_CONVENTIONS.md`

- [ ] **Step 1: Update `ARCHITECTURE.md`**

Replace its package strategy/current packages section with this target text, preserving the existing goal/enforcement sections where still accurate:

```markdown
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
```

- [ ] **Step 2: Update `PACKAGE_CONVENTIONS.md`**

Add this section after the naming section:

```markdown
## 2. Macro-module ownership
- New code should belong to one of the target macro-modules: `interfaces`, `interview`, `conversation`, `agent`, `tools`, `knowledge`, `model`, `integration`, `platform`, or `shared`.
- Prefer a module's public facade or `<module>.api` for cross-module calls.
- Do not import another module's `<module>.internal..` package.
- Do not add new top-level business packages without updating `ARCHITECTURE.md` and ArchUnit rules in the same change.
```

Then update the existing placement cheatsheet so it maps to the target modules from the approved design.

- [ ] **Step 3: Review docs for contradictions**

Run:

```bash
grep -nE "modelrouting|chat\.application|identity\.application|top-level `entity`|Target macro-modules|internal" ARCHITECTURE.md PACKAGE_CONVENTIONS.md
```

Expected: old package names may appear only in migration/retired-package context, not as the preferred placement for new code.

- [ ] **Step 4: Commit docs**

```bash
git add ARCHITECTURE.md PACKAGE_CONVENTIONS.md
git commit -m "docs: 定义模块化单体包边界"
```

Expected: one docs commit.

## Task 3: Add ArchUnit Guardrails for Macro-Modules

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`

- [ ] **Step 1: Add allowed top-level package set**

In `ArchitectureRulesTest`, add these imports if missing:

```java
import java.util.Set;
```

Add this constant inside the class:

```java
private static final Set<String> ALLOWED_TOP_LEVEL_PACKAGES = Set.of(
        "agent",
        "chat",
        "common",
        "config",
        "feedback",
        "graph",
        "identity",
        "im",
        "ingestion",
        "intent",
        "interview",
        "knowledge",
        "learning",
        "mcp",
        "media",
        "menu",
        "modelrouting",
        "observability",
        "rag",
        "routing",
        "search",
        "security",
        "skill",
        "stream",
        "model",
        "integration",
        "platform",
        "tools",
        "conversation",
        "interfaces",
        "shared"
);
```

This set is intentionally transitional. It allows the current cleanup baseline plus target modules.

- [ ] **Step 2: Add top-level package allowlist test**

Add this JUnit test. Do not add an ArchUnit `should().exist()` rule here; ArchUnit 1.3.0 does not expose that DSL method, and this JUnit assertion performs the actual allowlist check.

```java
@Test
void main_code_should_only_use_known_top_level_packages() {
    JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.imzmq.interview");

    assertThat(classes)
            .allSatisfy(javaClass -> {
                String packageName = javaClass.getPackageName();
                if (!packageName.startsWith("io.github.imzmq.interview.")) {
                    return;
                }
                String remainder = packageName.substring("io.github.imzmq.interview.".length());
                String topLevel = remainder.contains(".")
                        ? remainder.substring(0, remainder.indexOf('.'))
                        : remainder;
                assertThat(ALLOWED_TOP_LEVEL_PACKAGES)
                        .as(javaClass.getName() + " top-level package")
                        .contains(topLevel);
            });
}
```

- [ ] **Step 3: Add internal access guardrail helper**

Add this JUnit test to the same class:

```java
@Test
void modules_should_not_depend_on_other_modules_internal_packages() {
    JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.imzmq.interview");

    assertThat(classes).allSatisfy(source -> source.getDirectDependenciesFromSelf().forEach(dependency -> {
        JavaClass target = dependency.getTargetClass();
        String sourceModule = topLevelModule(source.getPackageName());
        String targetModule = topLevelModule(target.getPackageName());
        if (sourceModule == null || targetModule == null || sourceModule.equals(targetModule)) {
            return;
        }
        assertThat(target.getPackageName())
                .as(source.getName() + " must not depend on " + target.getName())
                .doesNotContain("." + targetModule + ".internal");
    }));
}

private static String topLevelModule(String packageName) {
    String root = "io.github.imzmq.interview.";
    if (!packageName.startsWith(root)) {
        return null;
    }
    String remainder = packageName.substring(root.length());
    if (remainder.isBlank()) {
        return null;
    }
    return remainder.contains(".") ? remainder.substring(0, remainder.indexOf('.')) : remainder;
}
```

- [ ] **Step 4: Run ArchUnit test**

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: pass.

- [ ] **Step 5: Commit architecture rules**

```bash
git add src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java
git commit -m "test: 增加模块边界架构规则"
```

Expected: one test commit.

## Task 4: Migrate Model Routing to `model`

**Files:**
- Move: `src/main/java/io/github/imzmq/interview/modelrouting/**` → `src/main/java/io/github/imzmq/interview/model/**`
- Modify: all imports matching `io.github.imzmq.interview.modelrouting`
- Modify: `ARCHITECTURE.md`
- Modify: `PACKAGE_CONVENTIONS.md`

- [ ] **Step 1: Move package directory**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/model
find src/main/java/io/github/imzmq/interview/modelrouting -type f | while read file; do
  target="src/main/java/io/github/imzmq/interview/model/${file#src/main/java/io/github/imzmq/interview/modelrouting/}"
  mkdir -p "$(dirname "$target")"
  mv "$file" "$target"
done
find src/main/java/io/github/imzmq/interview/modelrouting -type d -empty -delete
```

Expected: `src/main/java/io/github/imzmq/interview/modelrouting` no longer exists.

- [ ] **Step 2: Rewrite package declarations and imports**

```bash
python3 - <<'PY'
from pathlib import Path
for path in Path('src').rglob('*.java'):
    text = path.read_text()
    updated = text.replace('io.github.imzmq.interview.modelrouting', 'io.github.imzmq.interview.model')
    if updated != text:
        path.write_text(updated)
PY
```

Expected: no references remain:

```bash
grep -R "io.github.imzmq.interview.modelrouting" -n src/main src/test || true
```

- [ ] **Step 3: Update docs references**

Replace preferred placement references from `modelrouting` to `model` in `ARCHITECTURE.md` and `PACKAGE_CONVENTIONS.md`. Keep `modelrouting` only if describing retired package migration.

- [ ] **Step 4: Verify model migration**

```bash
mvn -q compile
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: pass.

- [ ] **Step 5: Commit model migration**

```bash
git add src/main/java src/test/java ARCHITECTURE.md PACKAGE_CONVENTIONS.md
git commit -m "refactor: 迁移模型路由到model模块"
```

Expected: one refactor commit.

## Task 5: Migrate External Adapters to `integration`

**Files:**
- Move: `im/**` → `integration/im/**`
- Move: `mcp/**` → `integration/mcp/**`
- Move: `search/**` → `integration/search/**` for this phase, including its controller; controller extraction to `interfaces` can be a later task.
- Modify: all imports matching old package names.
- Modify: docs.

- [ ] **Step 1: Move directories**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/integration
for module in im mcp search; do
  if [ -d "src/main/java/io/github/imzmq/interview/$module" ]; then
    mkdir -p "src/main/java/io/github/imzmq/interview/integration/$module"
    find "src/main/java/io/github/imzmq/interview/$module" -type f | while read file; do
      target="src/main/java/io/github/imzmq/interview/integration/$module/${file#src/main/java/io/github/imzmq/interview/$module/}"
      mkdir -p "$(dirname "$target")"
      mv "$file" "$target"
    done
    find "src/main/java/io/github/imzmq/interview/$module" -type d -empty -delete
  fi
done
```

- [ ] **Step 2: Rewrite package declarations and imports**

```bash
python3 - <<'PY'
from pathlib import Path
replacements = {
    'io.github.imzmq.interview.im': 'io.github.imzmq.interview.integration.im',
    'io.github.imzmq.interview.mcp': 'io.github.imzmq.interview.integration.mcp',
    'io.github.imzmq.interview.search': 'io.github.imzmq.interview.integration.search',
}
for path in Path('src').rglob('*.java'):
    text = path.read_text()
    updated = text
    for old, new in replacements.items():
        updated = updated.replace(old, new)
    if updated != text:
        path.write_text(updated)
PY
```

- [ ] **Step 3: Verify no old integration package imports remain**

```bash
grep -R "io.github.imzmq.interview\.\(im\|mcp\|search\)" -n src/main src/test || true
```

Expected: no output.

- [ ] **Step 4: Verify integration migration**

```bash
mvn -q compile
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: pass.

- [ ] **Step 5: Commit integration migration**

```bash
git add src/main/java src/test/java ARCHITECTURE.md PACKAGE_CONVENTIONS.md
git commit -m "refactor: 迁移外部适配到integration模块"
```

Expected: one refactor commit.

## Task 6: Migrate Platform Infrastructure

**Files:**
- Move: `identity/**` → `platform/identity/**`
- Move: `security/**` → `platform/security/**`
- Move: `observability/**` → `platform/observability/**`
- Move: `config/**` → `platform/config/**`
- Modify: package declarations/imports.

- [ ] **Step 1: Move platform-owned directories**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/platform
for module in identity security observability config; do
  if [ -d "src/main/java/io/github/imzmq/interview/$module" ]; then
    mkdir -p "src/main/java/io/github/imzmq/interview/platform/$module"
    find "src/main/java/io/github/imzmq/interview/$module" -type f | while read file; do
      target="src/main/java/io/github/imzmq/interview/platform/$module/${file#src/main/java/io/github/imzmq/interview/$module/}"
      mkdir -p "$(dirname "$target")"
      mv "$file" "$target"
    done
    find "src/main/java/io/github/imzmq/interview/$module" -type d -empty -delete
  fi
done
```

- [ ] **Step 2: Rewrite package declarations and imports**

```bash
python3 - <<'PY'
from pathlib import Path
replacements = {
    'io.github.imzmq.interview.identity': 'io.github.imzmq.interview.platform.identity',
    'io.github.imzmq.interview.security': 'io.github.imzmq.interview.platform.security',
    'io.github.imzmq.interview.observability': 'io.github.imzmq.interview.platform.observability',
    'io.github.imzmq.interview.config': 'io.github.imzmq.interview.platform.config',
}
for path in Path('src').rglob('*.java'):
    text = path.read_text()
    updated = text
    for old, new in replacements.items():
        updated = updated.replace(old, new)
    if updated != text:
        path.write_text(updated)
PY
```

- [ ] **Step 3: Verify no old platform package imports remain**

```bash
grep -R "io.github.imzmq.interview\.\(identity\|security\|observability\|config\)" -n src/main src/test || true
```

Expected: no output.

- [ ] **Step 4: Verify platform migration**

```bash
mvn -q compile
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: pass.

- [ ] **Step 5: Commit platform migration**

```bash
git add src/main/java src/test/java ARCHITECTURE.md PACKAGE_CONVENTIONS.md
git commit -m "refactor: 迁移基础设施到platform模块"
```

Expected: one refactor commit.

## Task 7: Migrate Knowledge-Related Packages

**Files:**
- Move: `rag/**` → `knowledge/internal/rag/**`
- Move: `graph/**` → `knowledge/internal/graph/**`
- Move: `ingestion/**` → `knowledge/internal/ingestion/**`
- Move: `media/**` → `knowledge/internal/media/**`
- Keep: existing `knowledge/api`, `knowledge/application`, `knowledge/domain`, `knowledge/infrastructure` unless a class clearly belongs under `internal` and compiles cleanly.
- Modify: imports and docs.

- [ ] **Step 1: Move knowledge-adjacent directories under `knowledge/internal`**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/knowledge/internal
for module in rag graph ingestion media; do
  if [ -d "src/main/java/io/github/imzmq/interview/$module" ]; then
    mkdir -p "src/main/java/io/github/imzmq/interview/knowledge/internal/$module"
    find "src/main/java/io/github/imzmq/interview/$module" -type f | while read file; do
      target="src/main/java/io/github/imzmq/interview/knowledge/internal/$module/${file#src/main/java/io/github/imzmq/interview/$module/}"
      mkdir -p "$(dirname "$target")"
      mv "$file" "$target"
    done
    find "src/main/java/io/github/imzmq/interview/$module" -type d -empty -delete
  fi
done
```

- [ ] **Step 2: Rewrite package declarations and imports**

```bash
python3 - <<'PY'
from pathlib import Path
replacements = {
    'io.github.imzmq.interview.rag': 'io.github.imzmq.interview.knowledge.internal.rag',
    'io.github.imzmq.interview.graph': 'io.github.imzmq.interview.knowledge.internal.graph',
    'io.github.imzmq.interview.ingestion': 'io.github.imzmq.interview.knowledge.internal.ingestion',
    'io.github.imzmq.interview.media': 'io.github.imzmq.interview.knowledge.internal.media',
}
for path in Path('src').rglob('*.java'):
    text = path.read_text()
    updated = text
    for old, new in replacements.items():
        updated = updated.replace(old, new)
    if updated != text:
        path.write_text(updated)
PY
```

- [ ] **Step 3: Check internal access violations**

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: pass. If a non-knowledge module now depends on `knowledge.internal.*`, do not weaken the rule. Instead, move the depended type to `knowledge.api` or `knowledge.domain` when it is a public contract, or route the call through an existing knowledge application service.

- [ ] **Step 4: Run compile**

```bash
mvn -q compile
```

Expected: pass.

- [ ] **Step 5: Commit knowledge migration**

```bash
git add src/main/java src/test/java ARCHITECTURE.md PACKAGE_CONVENTIONS.md
git commit -m "refactor: 收敛知识相关包到knowledge模块"
```

Expected: one refactor commit.

## Task 8: Migrate Conversation Packages

**Files:**
- Move: `chat/**` → `conversation/chat/**`
- Move: `stream/**` → `conversation/stream/**`
- Modify: imports and docs.

- [ ] **Step 1: Move chat and stream**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/conversation
for module in chat stream; do
  if [ -d "src/main/java/io/github/imzmq/interview/$module" ]; then
    mkdir -p "src/main/java/io/github/imzmq/interview/conversation/$module"
    find "src/main/java/io/github/imzmq/interview/$module" -type f | while read file; do
      target="src/main/java/io/github/imzmq/interview/conversation/$module/${file#src/main/java/io/github/imzmq/interview/$module/}"
      mkdir -p "$(dirname "$target")"
      mv "$file" "$target"
    done
    find "src/main/java/io/github/imzmq/interview/$module" -type d -empty -delete
  fi
done
```

- [ ] **Step 2: Rewrite package declarations and imports**

```bash
python3 - <<'PY'
from pathlib import Path
replacements = {
    'io.github.imzmq.interview.chat': 'io.github.imzmq.interview.conversation.chat',
    'io.github.imzmq.interview.stream': 'io.github.imzmq.interview.conversation.stream',
}
for path in Path('src').rglob('*.java'):
    text = path.read_text()
    updated = text
    for old, new in replacements.items():
        updated = updated.replace(old, new)
    if updated != text:
        path.write_text(updated)
PY
```

- [ ] **Step 3: Verify conversation migration**

```bash
mvn -q compile
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: pass.

- [ ] **Step 4: Commit conversation migration**

```bash
git add src/main/java src/test/java ARCHITECTURE.md PACKAGE_CONVENTIONS.md
git commit -m "refactor: 迁移对话能力到conversation模块"
```

Expected: one refactor commit.

## Task 9: Migrate Skill Runtime to `tools`

**Files:**
- Move: `skill/**` → `tools/skill/**`
- Modify: imports and docs.

- [ ] **Step 1: Move skill package**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/tools/skill
if [ -d src/main/java/io/github/imzmq/interview/skill ]; then
  find src/main/java/io/github/imzmq/interview/skill -type f | while read file; do
    target="src/main/java/io/github/imzmq/interview/tools/skill/${file#src/main/java/io/github/imzmq/interview/skill/}"
    mkdir -p "$(dirname "$target")"
    mv "$file" "$target"
  done
  find src/main/java/io/github/imzmq/interview/skill -type d -empty -delete
fi
```

- [ ] **Step 2: Rewrite package declarations and imports**

```bash
python3 - <<'PY'
from pathlib import Path
for path in Path('src').rglob('*.java'):
    text = path.read_text()
    updated = text.replace('io.github.imzmq.interview.skill', 'io.github.imzmq.interview.tools.skill')
    if updated != text:
        path.write_text(updated)
PY
```

- [ ] **Step 3: Verify tools migration**

```bash
mvn -q compile
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: pass.

- [ ] **Step 4: Commit tools migration**

```bash
git add src/main/java src/test/java ARCHITECTURE.md PACKAGE_CONVENTIONS.md
git commit -m "refactor: 迁移技能执行到tools模块"
```

Expected: one refactor commit.

## Task 10: Finalize Transitional Rules and Documentation

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`
- Modify: `ARCHITECTURE.md`
- Modify: `PACKAGE_CONVENTIONS.md`
- Modify if implementation deviated: `docs/superpowers/specs/2026-06-27-modular-agent-architecture-design.md`

- [ ] **Step 1: Capture remaining top-level packages**

```bash
find src/main/java/io/github/imzmq/interview -mindepth 1 -maxdepth 1 -type d | sed 's#src/main/java/io/github/imzmq/interview/##' | sort
```

Expected: remaining old top-level packages are intentional, such as `agent`, `interview`, `knowledge`, `menu`, `routing`, or `intent` if they have not yet been assigned in this phase.

- [ ] **Step 2: Tighten allowed package set**

Update `ALLOWED_TOP_LEVEL_PACKAGES` in `ArchitectureRulesTest` to remove old packages migrated in Tasks 4-9: `modelrouting`, `im`, `mcp`, `search`, `identity`, `security`, `observability`, `config`, `rag`, `graph`, `ingestion`, `media`, `chat`, `stream`, `skill`.

The resulting set should include only current intentionally remaining packages plus target modules, for example:

```java
private static final Set<String> ALLOWED_TOP_LEVEL_PACKAGES = Set.of(
        "agent",
        "common",
        "feedback",
        "intent",
        "interview",
        "knowledge",
        "learning",
        "menu",
        "routing",
        "model",
        "integration",
        "platform",
        "tools",
        "conversation",
        "interfaces",
        "shared"
);
```

Adjust this exact set to match the output from Step 1. Do not leave migrated old package names in the allowlist.

- [ ] **Step 3: Update docs to reflect completed phase**

In `ARCHITECTURE.md` and `PACKAGE_CONVENTIONS.md`, make sure preferred placements point to new modules:

```text
modelrouting -> model
im/mcp/search -> integration
identity/security/observability/config -> platform
rag/graph/ingestion/media -> knowledge
chat/stream -> conversation
skill -> tools
```

- [ ] **Step 4: Run final verification**

```bash
git diff --check
mvn -q compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q verify -DskipTests
```

Expected: all pass. Do not claim full `mvn test` passes unless it is run and succeeds in the environment.

- [ ] **Step 5: Optionally run full tests and record caveat**

```bash
mvn test
```

Expected: this may fail with Mockito inline Byte Buddy self-attach in the known local environment. If it fails with that exact environment issue, record the failure in the handoff summary and do not treat it as a migration failure.

- [ ] **Step 6: Commit final docs and rules**

```bash
git add src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java ARCHITECTURE.md PACKAGE_CONVENTIONS.md docs/superpowers/specs/2026-06-27-modular-agent-architecture-design.md
git commit -m "docs: 同步模块化架构迁移结果"
```

Expected: one final commit. Stop after Task 10 and wait for user instruction before continuing to later cleanup phases.

## Self-Review

- Spec coverage: baseline selection, modular-monolith target, no Mastra dependency, macro-module migration, ArchUnit guardrails, and verification strategy are covered by Tasks 1-10.
- Scope control: this plan does not split services, introduce Mastra, change APIs intentionally, or redesign persistence.
- Placeholder scan: no task uses TBD/TODO/fill-in placeholders; where exact old-package residue is unknown, the plan gives a concrete command and explicit allowed action.
- Risk control: each package group is migrated in its own commit with `mvn -q compile` and `ArchitectureRulesTest` before continuing.
