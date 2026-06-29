# Stream Runtime Package Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move cross-domain stream runtime support from the top-level `stream.runtime` package into `common.stream` and prevent the legacy package from returning.

**Architecture:** The stream runtime classes are shared transport/support primitives used by interview, knowledge, and observability modules. They belong in `common.stream`, while business-specific stream orchestration remains in `knowledge.application.chatstream` and `interview.application`.

**Tech Stack:** Java 21, Spring Boot, ArchUnit, Maven.

---

## File Structure

- Move `src/main/java/io/github/imzmq/interview/stream/runtime/StreamEventEmitter.java` to `src/main/java/io/github/imzmq/interview/common/stream/StreamEventEmitter.java`.
- Move `src/main/java/io/github/imzmq/interview/stream/runtime/InterviewStreamTaskManager.java` to `src/main/java/io/github/imzmq/interview/common/stream/InterviewStreamTaskManager.java`.
- Move `src/main/java/io/github/imzmq/interview/stream/runtime/InterviewStreamEventType.java` to `src/main/java/io/github/imzmq/interview/common/stream/InterviewStreamEventType.java`.
- Move `src/main/java/io/github/imzmq/interview/stream/runtime/InterviewSseEmitterSender.java` to `src/main/java/io/github/imzmq/interview/common/stream/InterviewSseEmitterSender.java`.
- Move `src/main/java/io/github/imzmq/interview/stream/runtime/ObservableStreamEmitter.java` to `src/main/java/io/github/imzmq/interview/common/stream/ObservableStreamEmitter.java`.
- Modify all imports currently referencing `io.github.imzmq.interview.stream.runtime.*`.
- Modify `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java` to forbid production classes in `io.github.imzmq.interview.stream..`.
- Update `PACKAGE_CONVENTIONS.md` and `AGENTS.md` to mention `common.stream` as shared stream support.

---

### Task 1: Add architecture guard for retired stream package

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`

- [ ] **Step 1: Add failing architecture rule**

Add this rule to `ArchitectureRulesTest` near the retired top-level package rule:

```java
    @ArchTest
    static final ArchRule main_code_should_not_use_retired_top_level_stream_package =
            noClasses().that().resideInAnyPackage("io.github.imzmq.interview.stream..")
                    .should(exist())
                    .allowEmptyShould(true);
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: fails because current production classes still reside in `io.github.imzmq.interview.stream.runtime`.

- [ ] **Step 3: Do not commit yet**

Keep this failing guard in the working tree. It should pass after Task 2 migrates the package.

---

### Task 2: Move stream runtime classes to common.stream

**Files:**
- Move five files from `src/main/java/io/github/imzmq/interview/stream/runtime/` to `src/main/java/io/github/imzmq/interview/common/stream/`.
- Modify imports in affected production files.

- [ ] **Step 1: Create target directory and move files**

Run:

```bash
mkdir -p src/main/java/io/github/imzmq/interview/common/stream
mv src/main/java/io/github/imzmq/interview/stream/runtime/*.java src/main/java/io/github/imzmq/interview/common/stream/
rmdir src/main/java/io/github/imzmq/interview/stream/runtime
rmdir src/main/java/io/github/imzmq/interview/stream
```

- [ ] **Step 2: Update package declarations**

In all moved files, replace:

```java
package io.github.imzmq.interview.stream.runtime;
```

with:

```java
package io.github.imzmq.interview.common.stream;
```

- [ ] **Step 3: Update imports**

Replace every production/test import:

```java
import io.github.imzmq.interview.stream.runtime.
```

with:

```java
import io.github.imzmq.interview.common.stream.
```

Use:

```bash
grep -R "io.github.imzmq.interview.stream.runtime" -n src/main/java src/test/java
```

Expected after replacement: no matches.

- [ ] **Step 4: Run architecture test**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: pass.

- [ ] **Step 5: Run compile**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: pass.

- [ ] **Step 6: Commit migration**

```bash
git add src/main/java/io/github/imzmq/interview/common/stream \
  src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java
git add -u src/main/java/io/github/imzmq/interview
git commit -m "refactor: 迁移流式运行时到通用模块"
```

---

### Task 3: Update placement documentation

**Files:**
- Modify: `AGENTS.md`
- Modify: `PACKAGE_CONVENTIONS.md`

- [ ] **Step 1: Update AGENTS.md common ownership**

In `AGENTS.md`, update the `common` ownership bullet to mention stream support:

```markdown
- `common`：通用 API 响应、异常、跨模块 stream 支撑、极少量无业务归属的基础设施；不要变成杂物区。
```

- [ ] **Step 2: Update PACKAGE_CONVENTIONS.md cheatsheet**

In `PACKAGE_CONVENTIONS.md`, add a placement cheatsheet bullet:

```markdown
- Cross-domain SSE/stream transport support: `common.stream`; business-specific stream orchestration stays in its owning domain.
```

- [ ] **Step 3: Run docs diff check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Commit docs**

```bash
git add AGENTS.md PACKAGE_CONVENTIONS.md
git commit -m "docs: 记录通用流式支撑归属"
```

---

### Task 4: Final verification

**Files:**
- No intended production edits beyond prior tasks.

- [ ] **Step 1: Ensure no legacy stream package remains**

Run:

```bash
find src/main/java/io/github/imzmq/interview -path '*stream*' -type f | sort
grep -R "io.github.imzmq.interview.stream.runtime" -n src/main/java src/test/java || true
```

Expected: no `src/main/java/io/github/imzmq/interview/stream/runtime` files and no legacy import matches.

- [ ] **Step 2: Run focused architecture test**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: pass.

- [ ] **Step 3: Run compile**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: pass.

- [ ] **Step 4: Run diff and status checks**

Run:

```bash
git diff --check
git status -sb
git log --oneline --decorate -8
```

Expected: no uncommitted changes except recurring local `AGENTS.md` memory noise; remove `<claude-mem-context>` if present.
