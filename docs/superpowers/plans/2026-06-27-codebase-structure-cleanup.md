# Codebase Structure Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move persistence and DTO code into domain-owned packages, remove confirmed dead code, and keep the repository aligned with its domain-first architecture.

**Architecture:** The migration is performed in small domain batches. Each batch moves one domain's DO, Mapper, and DTO classes into `<domain>.infrastructure.persistence`, `<domain>.api`, or `<domain>.application.dto`, then verifies compile, tests, and architecture checks before continuing.

**Tech Stack:** Java 21, Spring Boot 3.3.6, MyBatis-Plus, Maven, ArchUnit, Checkstyle, Vue 3/Vite for frontend reference checks.

---

## File Structure Map

The migration follows these target ownership rules:

- `src/main/java/io/github/imzmq/interview/<domain>/infrastructure/persistence/*DO.java`: MyBatis persistence objects currently under `entity/<domain>`.
- `src/main/java/io/github/imzmq/interview/<domain>/infrastructure/persistence/*Mapper.java`: MyBatis mapper interfaces currently under `mapper/<domain>`.
- `src/main/java/io/github/imzmq/interview/<domain>/api/*`: Controller-facing DTOs currently under `dto/<domain>`.
- `src/main/java/io/github/imzmq/interview/<domain>/application/dto/*`: Internal application DTOs if a DTO is not API-facing.
- `src/test/java/io/github/imzmq/interview/<domain>/**`: Tests whose package names still reference old conceptual package names.
- `ARCHITECTURE.md` and `PACKAGE_CONVENTIONS.md`: Updated after migration to reflect that top-level `entity`, `mapper`, and `dto` are retired.

Use these commands throughout:

```bash
# List current imports for a moved package
rg "io\.github\.imzmq\.interview\.(entity|mapper|dto)" src/main/java src/test/java

# Compile check
mvn -q compile

# Test check
mvn test

# Architecture/checkstyle check
mvn -q verify -DskipTests
```

If `rg` is unavailable, use:

```bash
grep -RInE "io\.github\.imzmq\.interview\.(entity|mapper|dto)" src/main/java src/test/java
```

---

### Task 1: Add Architecture Guardrails for Retired Top-Level Packages

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`

- [ ] **Step 1: Add a failing ArchUnit rule for retired packages**

Modify `ArchitectureRulesTest.java` to add imports and a rule that fails while old packages still exist:

```java
package io.github.imzmq.interview.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "io.github.imzmq.interview",
        importOptions = {ImportOption.DoNotIncludeTests.class}
)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule controller_should_not_access_mapper_directly =
            noClasses().that().resideInAnyPackage("..controller..", "..api..")
                    .should().dependOnClassesThat().resideInAnyPackage("..mapper..");

    @ArchTest
    static final ArchRule agent_should_not_depend_on_controller =
            noClasses().that().resideInAnyPackage("..agent..")
                    .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..api..");

    @ArchTest
    static final ArchRule entity_should_not_depend_on_service_or_controller =
            noClasses().that().resideInAnyPackage("..entity..")
                    .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..", "..api..");

    @ArchTest
    static final ArchRule main_code_should_not_use_retired_top_level_entity_mapper_or_dto_packages =
            noClasses().that().resideInAnyPackage(
                            "io.github.imzmq.interview.entity..",
                            "io.github.imzmq.interview.mapper..",
                            "io.github.imzmq.interview.dto.."
                    )
                    .should().exist();
}
```

- [ ] **Step 2: Run the architecture test and confirm it fails**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: FAIL because classes still reside in `entity`, `mapper`, or `dto`.

- [ ] **Step 3: Commit the guardrail test**

```bash
git add src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java
git commit -m "test: 添加包结构迁移守护规则"
```

---

### Task 2: Migrate `menu` Persistence

**Files:**
- Move: `src/main/java/io/github/imzmq/interview/entity/menu/MenuConfigDO.java` -> `src/main/java/io/github/imzmq/interview/menu/infrastructure/persistence/MenuConfigDO.java`
- Move: `src/main/java/io/github/imzmq/interview/mapper/menu/MenuConfigMapper.java` -> `src/main/java/io/github/imzmq/interview/menu/infrastructure/persistence/MenuConfigMapper.java`
- Modify imports in all files that reference these types.

- [ ] **Step 1: Move files**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/menu/infrastructure/persistence
mv src/main/java/io/github/imzmq/interview/entity/menu/MenuConfigDO.java src/main/java/io/github/imzmq/interview/menu/infrastructure/persistence/MenuConfigDO.java
mv src/main/java/io/github/imzmq/interview/mapper/menu/MenuConfigMapper.java src/main/java/io/github/imzmq/interview/menu/infrastructure/persistence/MenuConfigMapper.java
```

- [ ] **Step 2: Update package declarations**

In `MenuConfigDO.java`, set:

```java
package io.github.imzmq.interview.menu.infrastructure.persistence;
```

In `MenuConfigMapper.java`, set:

```java
package io.github.imzmq.interview.menu.infrastructure.persistence;
```

Also change the mapper's DO import to:

```java
import io.github.imzmq.interview.menu.infrastructure.persistence.MenuConfigDO;
```

- [ ] **Step 3: Replace imports**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
repls = {
    'io.github.imzmq.interview.entity.menu.MenuConfigDO': 'io.github.imzmq.interview.menu.infrastructure.persistence.MenuConfigDO',
    'io.github.imzmq.interview.mapper.menu.MenuConfigMapper': 'io.github.imzmq.interview.menu.infrastructure.persistence.MenuConfigMapper',
}
for root in ['src/main/java', 'src/test/java']:
    for p in Path(root).rglob('*.java'):
        s = p.read_text()
        ns = s
        for old, new in repls.items():
            ns = ns.replace(old, new)
        if ns != s:
            p.write_text(ns)
PY
```

- [ ] **Step 4: Compile and test the batch**

Run:

```bash
mvn -q compile
mvn test
```

Expected: both pass, except the Task 1 ArchUnit rule may still fail in full `verify` until all packages are migrated.

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "refactor: 迁移菜单持久化包"
```

---

### Task 3: Migrate `search` Persistence and DTOs

**Files:**
- Move: `entity/search/AutocompleteDictDO.java` -> `search/infrastructure/persistence/AutocompleteDictDO.java`
- Move: `mapper/search/AutocompleteDictMapper.java` -> `search/infrastructure/persistence/AutocompleteDictMapper.java`
- Move: `dto/search/AutocompleteItem.java` -> `search/application/dto/AutocompleteItem.java` because it is used by `AutocompleteService`, `RadixTreeEngine`, `RadixNode`, and `PersonalRanker` as an application-level ranking object; the controller may return it without forcing the application layer to depend on `search.api`.
- Modify imports.

- [ ] **Step 1: Confirm DTO usage before moving**

Run:

```bash
grep -RIn "AutocompleteItem" src/main/java src/test/java
```

Expected: references include `search/api/AutocompleteController.java`, `search/application/AutocompleteService.java`, `search/application/RadixTreeEngine.java`, `search/application/RadixNode.java`, and `search/application/PersonalRanker.java`. Keep the target package as `search.application.dto` so application classes do not depend on API classes.

- [ ] **Step 2: Move files**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/search/infrastructure/persistence
mv src/main/java/io/github/imzmq/interview/entity/search/AutocompleteDictDO.java src/main/java/io/github/imzmq/interview/search/infrastructure/persistence/AutocompleteDictDO.java
mv src/main/java/io/github/imzmq/interview/mapper/search/AutocompleteDictMapper.java src/main/java/io/github/imzmq/interview/search/infrastructure/persistence/AutocompleteDictMapper.java
```

```bash
mkdir -p src/main/java/io/github/imzmq/interview/search/application/dto
mv src/main/java/io/github/imzmq/interview/dto/search/AutocompleteItem.java src/main/java/io/github/imzmq/interview/search/application/dto/AutocompleteItem.java
```

- [ ] **Step 3: Update package declarations and imports**

Use the same replacement script pattern as Task 2. Replace:

```text
io.github.imzmq.interview.entity.search.AutocompleteDictDO
io.github.imzmq.interview.mapper.search.AutocompleteDictMapper
io.github.imzmq.interview.dto.search.AutocompleteItem
```

with:

```text
io.github.imzmq.interview.search.infrastructure.persistence.AutocompleteDictDO
io.github.imzmq.interview.search.infrastructure.persistence.AutocompleteDictMapper
io.github.imzmq.interview.search.application.dto.AutocompleteItem
```

- [ ] **Step 4: Compile and test**

```bash
mvn -q compile
mvn test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "refactor: 迁移搜索持久化和DTO包"
```

---

### Task 4: Migrate `agent` Persistence

**Files:**
- Move: `entity/agent/AgentConfigDO.java` -> `agent/infrastructure/persistence/AgentConfigDO.java`
- Move: `mapper/agent/AgentConfigMapper.java` -> `agent/infrastructure/persistence/AgentConfigMapper.java`
- Modify imports.

- [ ] **Step 1: Move files and update packages**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/agent/infrastructure/persistence
mv src/main/java/io/github/imzmq/interview/entity/agent/AgentConfigDO.java src/main/java/io/github/imzmq/interview/agent/infrastructure/persistence/AgentConfigDO.java
mv src/main/java/io/github/imzmq/interview/mapper/agent/AgentConfigMapper.java src/main/java/io/github/imzmq/interview/agent/infrastructure/persistence/AgentConfigMapper.java
```

Set package declarations to:

```java
package io.github.imzmq.interview.agent.infrastructure.persistence;
```

- [ ] **Step 2: Replace imports**

Replace:

```text
io.github.imzmq.interview.entity.agent.AgentConfigDO
io.github.imzmq.interview.mapper.agent.AgentConfigMapper
```

with:

```text
io.github.imzmq.interview.agent.infrastructure.persistence.AgentConfigDO
io.github.imzmq.interview.agent.infrastructure.persistence.AgentConfigMapper
```

- [ ] **Step 3: Compile and test**

```bash
mvn -q compile
mvn test
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java src/test/java
git commit -m "refactor: 迁移Agent持久化包"
```

---

### Task 5: Migrate `modelrouting` Persistence and DTOs

**Files:**
- Move: `entity/modelrouting/ModelCandidateDO.java` -> `modelrouting/infrastructure/persistence/ModelCandidateDO.java`
- Move: `mapper/modelrouting/ModelCandidateMapper.java` -> `modelrouting/infrastructure/persistence/ModelCandidateMapper.java`
- Move: `dto/modelrouting/ModelCandidateDTO.java` -> likely `modelrouting/api/ModelCandidateDTO.java`, unless inspection shows it is internal.
- Modify imports.

- [ ] **Step 1: Inspect DTO usage**

```bash
grep -RIn "ModelCandidateDTO" src/main/java src/test/java
```

If used by `ModelRoutingController`, move it to `modelrouting.api`.

- [ ] **Step 2: Move files**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/modelrouting/infrastructure/persistence
mv src/main/java/io/github/imzmq/interview/entity/modelrouting/ModelCandidateDO.java src/main/java/io/github/imzmq/interview/modelrouting/infrastructure/persistence/ModelCandidateDO.java
mv src/main/java/io/github/imzmq/interview/mapper/modelrouting/ModelCandidateMapper.java src/main/java/io/github/imzmq/interview/modelrouting/infrastructure/persistence/ModelCandidateMapper.java
mv src/main/java/io/github/imzmq/interview/dto/modelrouting/ModelCandidateDTO.java src/main/java/io/github/imzmq/interview/modelrouting/api/ModelCandidateDTO.java
```

- [ ] **Step 3: Update package declarations**

Use:

```java
package io.github.imzmq.interview.modelrouting.infrastructure.persistence;
```

for `ModelCandidateDO` and `ModelCandidateMapper`.

Use:

```java
package io.github.imzmq.interview.modelrouting.api;
```

for `ModelCandidateDTO` if API-facing.

- [ ] **Step 4: Replace imports and test**

Replace old imports with target imports, then run:

```bash
mvn -q compile
mvn test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java src/test/java
git commit -m "refactor: 迁移模型路由持久化和DTO包"
```

---

### Task 6: Migrate `chat` Persistence

**Files:**
- Move all files in `entity/chat` to `conversation/chat/infrastructure/persistence`.
- Move all files in `mapper/chat` to `conversation/chat/infrastructure/persistence`.
- Modify imports.

- [ ] **Step 1: Move files**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/conversation/chat/infrastructure/persistence
mv src/main/java/io/github/imzmq/interview/entity/chat/*.java src/main/java/io/github/imzmq/interview/conversation/chat/infrastructure/persistence/
mv src/main/java/io/github/imzmq/interview/mapper/chat/*.java src/main/java/io/github/imzmq/interview/conversation/chat/infrastructure/persistence/
```

- [ ] **Step 2: Update package declarations in moved files**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
base = Path('src/main/java/io/github/imzmq/interview/conversation/chat/infrastructure/persistence')
for p in base.glob('*.java'):
    s = p.read_text()
    s = s.replace('package io.github.imzmq.interview.entity.chat;', 'package io.github.imzmq.interview.conversation.chat.infrastructure.persistence;')
    s = s.replace('package io.github.imzmq.interview.mapper.chat;', 'package io.github.imzmq.interview.conversation.chat.infrastructure.persistence;')
    s = s.replace('import io.github.imzmq.interview.entity.chat.', 'import io.github.imzmq.interview.conversation.chat.infrastructure.persistence.')
    p.write_text(s)
PY
```

- [ ] **Step 3: Replace imports globally**

```bash
python3 - <<'PY'
from pathlib import Path
for root in ['src/main/java', 'src/test/java']:
    for p in Path(root).rglob('*.java'):
        s = p.read_text()
        ns = s.replace('io.github.imzmq.interview.entity.chat.', 'io.github.imzmq.interview.conversation.chat.infrastructure.persistence.')
        ns = ns.replace('io.github.imzmq.interview.mapper.chat.', 'io.github.imzmq.interview.conversation.chat.infrastructure.persistence.')
        if ns != s:
            p.write_text(ns)
PY
```

- [ ] **Step 4: Compile and test chat-related tests**

```bash
mvn -q compile
mvn test -Dtest='*Chat*,*Prompt*,*Memory*'
```

- [ ] **Step 5: Run full tests and commit**

```bash
mvn test
git add src/main/java src/test/java
git commit -m "refactor: 迁移聊天持久化包"
```

---

### Task 7: Migrate `interview` Persistence and Fix Test Package Drift

**Files:**
- Move `entity/interview/*` -> `interview/infrastructure/persistence/`.
- Move `mapper/interview/*` -> `interview/infrastructure/persistence/`.
- Update tests that import `InterviewSessionMapper`.
- Consider moving tests currently under `src/test/java/io/github/imzmq/interview/service` if they target non-service packages.

- [ ] **Step 1: Move files**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/interview/infrastructure/persistence
mv src/main/java/io/github/imzmq/interview/entity/interview/*.java src/main/java/io/github/imzmq/interview/interview/infrastructure/persistence/
mv src/main/java/io/github/imzmq/interview/mapper/interview/*.java src/main/java/io/github/imzmq/interview/interview/infrastructure/persistence/
```

- [ ] **Step 2: Update packages and imports**

Replace:

```text
io.github.imzmq.interview.entity.interview.
io.github.imzmq.interview.mapper.interview.
```

with:

```text
io.github.imzmq.interview.interview.infrastructure.persistence.
```

- [ ] **Step 3: Run targeted tests**

```bash
mvn -q compile
mvn test -Dtest='*Interview*,RollingSummaryAgentTest'
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java src/test/java
git commit -m "refactor: 迁移面试持久化包"
```

---

### Task 8: Migrate `ingestion` Persistence

**Files:**
- Move `entity/ingestion/*` -> `ingestion/infrastructure/persistence/`.
- Move `mapper/ingestion/*` -> `ingestion/infrastructure/persistence/`.
- Modify imports.

- [ ] **Step 1: Move files**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/ingestion/infrastructure/persistence
mv src/main/java/io/github/imzmq/interview/entity/ingestion/*.java src/main/java/io/github/imzmq/interview/ingestion/infrastructure/persistence/
mv src/main/java/io/github/imzmq/interview/mapper/ingestion/*.java src/main/java/io/github/imzmq/interview/ingestion/infrastructure/persistence/
```

- [ ] **Step 2: Update packages/imports**

Replace:

```text
io.github.imzmq.interview.entity.ingestion.
io.github.imzmq.interview.mapper.ingestion.
```

with:

```text
io.github.imzmq.interview.ingestion.infrastructure.persistence.
```

- [ ] **Step 3: Compile and test**

```bash
mvn -q compile
mvn test -Dtest='*Ingest*,*Ingestion*'
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java src/test/java
git commit -m "refactor: 迁移知识入库持久化包"
```

---

### Task 9: Migrate `learning` Persistence

**Files:**
- Move `entity/learning/*` -> `learning/infrastructure/persistence/`.
- Move `mapper/learning/*` -> `learning/infrastructure/persistence/`.
- Modify imports.

- [ ] **Step 1: Move files**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/learning/infrastructure/persistence
mv src/main/java/io/github/imzmq/interview/entity/learning/*.java src/main/java/io/github/imzmq/interview/learning/infrastructure/persistence/
mv src/main/java/io/github/imzmq/interview/mapper/learning/*.java src/main/java/io/github/imzmq/interview/learning/infrastructure/persistence/
```

- [ ] **Step 2: Update packages/imports**

Replace:

```text
io.github.imzmq.interview.entity.learning.
io.github.imzmq.interview.mapper.learning.
```

with:

```text
io.github.imzmq.interview.learning.infrastructure.persistence.
```

- [ ] **Step 3: Compile and test**

```bash
mvn -q compile
mvn test -Dtest='*Learning*,*Profile*'
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java src/test/java
git commit -m "refactor: 迁移学习画像持久化包"
```

---

### Task 10: Migrate `media` Persistence

**Files:**
- Move `entity/media/*` -> `media/infrastructure/persistence/`.
- Move `mapper/media/*` -> `media/infrastructure/persistence/`.
- Modify imports.

- [ ] **Step 1: Move files**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/media/infrastructure/persistence
mv src/main/java/io/github/imzmq/interview/entity/media/*.java src/main/java/io/github/imzmq/interview/media/infrastructure/persistence/
mv src/main/java/io/github/imzmq/interview/mapper/media/*.java src/main/java/io/github/imzmq/interview/media/infrastructure/persistence/
```

- [ ] **Step 2: Update packages/imports**

Replace:

```text
io.github.imzmq.interview.entity.media.
io.github.imzmq.interview.mapper.media.
```

with:

```text
io.github.imzmq.interview.media.infrastructure.persistence.
```

- [ ] **Step 3: Compile and test**

```bash
mvn -q compile
mvn test -Dtest='*Image*,*Media*'
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java src/test/java
git commit -m "refactor: 迁移媒体持久化包"
```

---

### Task 11: Migrate `knowledge` Persistence Last

**Files:**
- Move `entity/knowledge/*` -> `knowledge/infrastructure/persistence/`.
- Move `mapper/knowledge/*` -> `knowledge/infrastructure/persistence/`.
- Update cross-domain references in `feedback`, `observability`, `media`, `knowledge.application.*`, and tests.

- [ ] **Step 1: Move files**

```bash
mkdir -p src/main/java/io/github/imzmq/interview/knowledge/infrastructure/persistence
mv src/main/java/io/github/imzmq/interview/entity/knowledge/*.java src/main/java/io/github/imzmq/interview/knowledge/infrastructure/persistence/
mv src/main/java/io/github/imzmq/interview/mapper/knowledge/*.java src/main/java/io/github/imzmq/interview/knowledge/infrastructure/persistence/
```

- [ ] **Step 2: Update packages/imports**

Replace:

```text
io.github.imzmq.interview.entity.knowledge.
io.github.imzmq.interview.mapper.knowledge.
```

with:

```text
io.github.imzmq.interview.knowledge.infrastructure.persistence.
```

- [ ] **Step 3: Run targeted RAG and feedback tests**

```bash
mvn -q compile
mvn test -Dtest='RAGServiceTest,RetrievalEvaluationServiceTest,*Rag*,*Feedback*,*Knowledge*'
```

- [ ] **Step 4: Run full tests and commit**

```bash
mvn test
git add src/main/java src/test/java
git commit -m "refactor: 迁移知识域持久化包"
```

---

### Task 12: Remove Empty Retired Directories and Verify Mapper Scanning

**Files:**
- Delete empty directories under `src/main/java/io/github/imzmq/interview/entity`, `mapper`, `dto`.
- Modify: `src/main/resources/application.yml` only if mapper scanning is restricted and compile/runtime inspection proves it needs updating.

- [ ] **Step 1: Confirm no old imports remain**

Run:

```bash
grep -RInE "io\.github\.imzmq\.interview\.(entity|mapper|dto)" src/main/java src/test/java || true
find src/main/java/io/github/imzmq/interview/entity src/main/java/io/github/imzmq/interview/mapper src/main/java/io/github/imzmq/interview/dto -type f 2>/dev/null || true
```

Expected: no Java files remain under retired packages and no imports reference them.

- [ ] **Step 2: Check MyBatis mapper scanning behavior**

Run:

```bash
grep -RIn "@MapperScan\|mapper-locations" src/main/java src/main/resources
```

If `@MapperScan` points only to `io.github.imzmq.interview.mapper`, change it to scan the root package or the new infrastructure pattern. Prefer root package scanning only if existing code already relies on broad scanning:

```java
@MapperScan("io.github.imzmq.interview")
```

Do not change `mybatis-plus.mapper-locations` unless XML mapper files exist and moved paths require it.

- [ ] **Step 3: Run full verification**

```bash
mvn -q compile
mvn test
mvn -q verify -DskipTests
```

Expected: all pass, including the Task 1 ArchUnit rule.

- [ ] **Step 4: Commit**

```bash
git add src/main/java src/main/resources src/test/java
git commit -m "refactor: 移除旧持久化聚合包"
```

---

### Task 13: Audit and Relocate Legacy Technical Packages

**Files:**
- Inspect and possibly move:
  - `src/main/java/io/github/imzmq/interview/rag/core/*`
  - `src/main/java/io/github/imzmq/interview/tool/**`
  - `src/main/java/io/github/imzmq/interview/tools/runner/*`
  - `src/main/java/io/github/imzmq/interview/session/repository/*`
  - `src/main/java/io/github/imzmq/interview/core/trace/*`

- [ ] **Step 1: Generate ownership report**

Run:

```bash
for pkg in rag/core tool tools/runner session/repository core/trace; do
  echo "--- $pkg"
  find src/main/java/io/github/imzmq/interview/$pkg -type f -name '*.java' 2>/dev/null
 done
```

For each file, inspect callers:

```bash
grep -RIn "ClassName" src/main/java src/test/java
```

- [ ] **Step 2: Move only files with clear ownership**

Use these mapping rules:

```text
rag.core chunking/markdown/note/image-reference utilities -> knowledge.application.indexing or ingestion.pipeline
tool.gateway and tool.adapter MCP gateways -> mcp.application or mcp.infrastructure
tool.search.WebSearchTool / VectorSearchTool -> search.application or knowledge.application.retrieval
tools.runner.IngestionRunner -> ingestion.application or ingestion.pipeline
session.repository.* -> interview.infrastructure.persistence or conversation.chat.infrastructure.persistence based on stored aggregate
core.trace.RAGTraceContext -> observability.core or knowledge.application.observability
```

Do not delete or move a class if ownership is ambiguous. Create a short note in the commit message for deferred files.

- [ ] **Step 3: Compile, test, commit**

```bash
mvn -q compile
mvn test
git add src/main/java src/test/java
git commit -m "refactor: 归位遗留技术包"
```

---

### Task 14: Dead Code Cleanup Pass

**Files:**
- Modify/delete only files proven unused by reference checks and successful verification.

- [ ] **Step 1: Find candidate unused Java files**

For each suspect class from IDE/static analysis, verify via grep:

```bash
CLASS=ReplaceWithClassName
grep -RIn "$CLASS" src/main/java src/test/java src/main/resources frontend sql || true
```

A class is not dead if it is referenced by annotation scanning, Spring component scanning, MyBatis, configuration properties, scheduled jobs, tests, SQL, frontend API contracts, or reflection.

- [ ] **Step 2: Delete one small group at a time**

```bash
git rm path/to/ConfirmedUnused.java
mvn -q compile
mvn test
```

If verification fails, restore immediately:

```bash
git restore --staged path/to/ConfirmedUnused.java
git restore path/to/ConfirmedUnused.java
```

- [ ] **Step 3: Commit each confirmed cleanup group**

```bash
git add -u src/main/java src/test/java
git commit -m "refactor: 清理确认未使用代码"
```

---

### Task 15: Update Architecture Documentation

**Files:**
- Modify: `ARCHITECTURE.md`
- Modify: `PACKAGE_CONVENTIONS.md`
- Optionally modify: `AGENTS.md` only if it is still a placeholder/memory file and the user explicitly permits replacing it.

- [ ] **Step 1: Update architecture docs**

Add the final persistence rule to both docs:

```markdown
## Persistence Placement

MyBatis persistence classes are domain-owned. DO classes and Mapper interfaces live under:

`io.github.imzmq.interview.<domain>.infrastructure.persistence`

Do not add new business persistence code under top-level `entity`, `mapper`, or `dto` packages. Controller-facing DTOs belong in `<domain>.api`; application-internal DTOs belong in `<domain>.application.dto`.
```

- [ ] **Step 2: Run documentation-adjacent verification**

```bash
mvn -q verify -DskipTests
git diff -- ARCHITECTURE.md PACKAGE_CONVENTIONS.md
```

- [ ] **Step 3: Commit**

```bash
git add ARCHITECTURE.md PACKAGE_CONVENTIONS.md
git commit -m "docs: 更新领域化包结构约定"
```

---

### Task 16: Final Full Verification

**Files:**
- No planned source edits unless verification reveals missed imports or docs drift.

- [ ] **Step 1: Confirm no retired packages remain**

```bash
grep -RInE "package io\.github\.imzmq\.interview\.(entity|mapper|dto)" src/main/java src/test/java || true
grep -RInE "io\.github\.imzmq\.interview\.(entity|mapper|dto)" src/main/java src/test/java || true
```

Expected: no output.

- [ ] **Step 2: Run full verification**

```bash
mvn -q compile
mvn test
mvn -q verify -DskipTests
```

Expected: all pass.

- [ ] **Step 3: Inspect git status and summarize**

```bash
git status --short
git log --oneline -8
```

Expected: clean working tree after final commits.
