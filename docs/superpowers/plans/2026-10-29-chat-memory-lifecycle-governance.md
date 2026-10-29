# Chat Memory Lifecycle Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add deterministic subject-based lifecycle governance to Chat long-term memory so current facts replace safely, ambiguous facts remain quarantined, and only effective leaf memories reach recall and Milvus.

**Architecture:** Keep MySQL as the sole truth source. Extend the existing item row into the current-state read model, add one append-only evolution table for `SUPERSEDES` and `CONFLICTS_WITH`, and place subject normalization/cardinality in framework-neutral domain types. The existing commit transaction delegates candidate lifecycle writes to one focused writer; management, recall, repair, and projection all consume the same item-state semantics without compatibility branches.

**Tech Stack:** Java 21, Spring Boot, MyBatis-Plus, MySQL 8, Flyway, Jackson strict JSON parsing, Milvus derived projection, JUnit 5, Mockito, AssertJ, MockMvc, ArchUnit, Maven.

---

## File and responsibility map

### Database and migration verification

- Create `src/main/resources/db/migration/V6__evolve_chat_memory_lifecycle.sql`
  - Backfill lifecycle columns, replace content-only uniqueness, and create the append-only evolution table.
- Create `src/main/resources/db/migration/V7__upgrade_chat_memory_extraction_prompts.sql`
  - Upgrade only untouched built-in extraction prompts to the strict `subjectKey` contract.
- Modify `src/test/java/io/github/imzmq/interview/config/database/FlywayDatabaseMigrationTest.java`
  - Assert both migrations contain the required schema, constraints, and conditional prompt update.
- Modify `src/test/java/io/github/imzmq/interview/config/database/FlywayMySqlMigrationIntegrationTest.java`
  - Verify empty-database migration, old-row backfill, generated-column uniqueness, and preservation of customized prompts.

### Domain and extraction contract

- Create `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemorySubjectKey.java`
  - Normalize and validate open subject keys.
- Create `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryCardinality.java`
- Create `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryQuarantineReason.java`
- Create `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryRelationType.java`
- Create `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemorySubjectPolicy.java`
  - Own category/cardinality and the small explicit singleton-fact registry.
- Modify `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryCandidate.java`
  - Carry the normalized subject key.
- Modify `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryStatus.java`
  - Add `SUPERSEDED`.
- Modify `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCandidateValidator.java`
  - Require exactly `category`, `subjectKey`, `content`, `sourceMessageId`, and `quote`.
- Modify `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemorySourceMessage.java`
- Modify `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemorySourceLoader.java`
  - Preserve source-message creation time for stable ordering.

### Lifecycle persistence and writing

- Modify `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryItemDO.java`
  - Map lifecycle and origin fields.
- Create `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryEvolutionDO.java`
- Create `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryEvolutionMapper.java`
  - Insert immutable relations and query them by current user.
- Modify `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryItemMapper.java`
  - Lock active theme rows and expose explicit CAS transitions.
- Create `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleRequest.java`
  - Carry validated candidate, user/task identity, and source ordering into the writer.
- Create `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecyclePolicy.java`
  - Own the nested persistence-neutral `Action`, `CurrentItem`, and `Decision` types and return deterministic `ATTACH_SOURCE`, `ACTIVATE`, `SUPERSEDE`, or `QUARANTINE` decisions.
- Create `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleWriter.java`
  - Apply decisions, sources, evolution rows, versions, and Outbox events inside the caller transaction.
- Modify `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCommitTransactionService.java`
  - Retain task/source fencing and delegate lifecycle persistence.

### Recall, projection, and source invalidation

- Modify `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryConfiguration.java`
  - Provide the single application `Clock` used by lifecycle validity checks.
- Modify `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRecallService.java`
- Modify `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProjectionWorker.java`
- Modify `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepairService.java`
- Modify `src/main/java/io/github/imzmq/interview/chat/application/ChatSessionDeletionService.java`
- Modify their existing focused tests.

### Management contract and HTTP API

- Create `src/main/java/io/github/imzmq/interview/chat/application/contract/result/ChatMemoryRelationView.java`
- Modify `src/main/java/io/github/imzmq/interview/chat/application/contract/result/ChatMemoryView.java`
- Modify `src/main/java/io/github/imzmq/interview/chat/application/contract/ChatMemoryOperations.java`
- Create `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementException.java`
- Modify `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementService.java`
- Modify `src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryResponse.java`
- Create `src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryRelationResponse.java`
- Modify `src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryController.java`
- Modify `src/main/java/io/github/imzmq/interview/chat/api/ChatApiExceptionHandler.java`

### Observability and documentation

- Modify `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryObservationPublisher.java`
- Modify `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryObservabilityTest.java`
- Modify `PACKAGE_CONVENTIONS.md`
- Modify `ARCHITECTURE.md`
- Modify `docs/development/observability-guidelines.md`

## Implementation constraints

- Do not alter or stage `AGENTS.md`, `.DS_Store`, `.dev/`, credentials, build output, or local configuration.
- Do not add legacy/new runtime switches, compatibility constructors, tolerant candidate parsing, dual reads, or dual writes.
- Do not call a real model, Milvus service, or external API in unit tests. No API Key is required.
- Do not modify V3 through V5. V6 and V7 must be forward-only and safe for existing installations.
- Use precise `git add -- <paths>` commands; never use `git add -A`.
- If Mockito requires the JDK 21 Byte Buddy agent, append:

```bash
-DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar'
```

---

### Task 1: Add the final MySQL lifecycle schema and conditional prompt migration

**Files:**
- Create: `src/main/resources/db/migration/V6__evolve_chat_memory_lifecycle.sql`
- Create: `src/main/resources/db/migration/V7__upgrade_chat_memory_extraction_prompts.sql`
- Modify: `src/test/java/io/github/imzmq/interview/config/database/FlywayDatabaseMigrationTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/config/database/FlywayMySqlMigrationIntegrationTest.java`

- [ ] **Step 1: Write failing migration-content tests**

Extend `versionedMigrationsCreateSchemaAndSeedEveryStartupRequiredPrompt()` to load V6 and V7 and assert:

```java
String lifecycle = readResource("db/migration/V6__evolve_chat_memory_lifecycle.sql");
String promptUpgrade = readResource("db/migration/V7__upgrade_chat_memory_extraction_prompts.sql");

assertThat(lifecycle)
        .contains("subject_key", "subject_cardinality", "valid_from", "valid_until")
        .contains("origin_message_created_at", "origin_message_pk", "origin_quote_start")
        .contains("quarantine_reason", "active_singleton_marker", "active_content_marker")
        .contains("t_chat_memory_evolution", "SUPERSEDES", "CONFLICTS_WITH")
        .contains("DROP INDEX `uk_chat_memory_content`");
assertThat(promptUpgrade)
        .contains("subjectKey")
        .contains("WHERE `name` = 'chat-memory-extract-task'")
        .contains("AND `content` =");
```

In the real-MySQL test, change the expected migration count from `5` to `7`. Add a pre-V6 fixture test that migrates through V5, inserts an active memory and source, then migrates through V7 and asserts:

```java
assertThat(singleString(statement,
        "SELECT subject_key FROM t_chat_memory_item WHERE id = " + memoryId))
        .isEqualTo("fact.legacy_" + memoryId);
assertThat(singleString(statement,
        "SELECT subject_cardinality FROM t_chat_memory_item WHERE id = " + memoryId))
        .isEqualTo("MULTI");
```

Also insert `USER_CUSTOM_MEMORY_PROMPT` before V7 and assert it remains unchanged.

- [ ] **Step 2: Run the narrow tests and verify failure**

Run:

```bash
mvn -q -Dtest=FlywayDatabaseMigrationTest,FlywayMySqlMigrationIntegrationTest test
```

Expected: `FlywayDatabaseMigrationTest` fails because V6/V7 do not exist. The MySQL methods may be skipped when `test.mysql.*` properties are absent.

- [ ] **Step 3: Implement V6 with one final schema**

V6 must execute in this order:

1. Add nullable lifecycle/origin columns.
2. Backfill each row with `<category-lowercase>.legacy_<id>`, category default cardinality, and `valid_from = item.created_at`. For origin ordering, choose the earliest effective source by joining `t_chat_memory_source.source_message_pk` to `t_chat_message.id`, then use `t_chat_message.created_at`, the message PK, and source offset; do not substitute `t_chat_memory_source.created_at` for message time. Use `item.created_at`, zero PK, and zero offset only when no valid source-message join exists.
3. Change required columns to `NOT NULL`.
4. Drop `uk_chat_memory_content`.
5. Add stored generated markers and both unique indexes.
6. Create `t_chat_memory_evolution` with a unique `(user_id, from_memory_id, to_memory_id, relation_type)` key and inbound/outbound indexes.

Use generated expressions equivalent to:

```sql
`active_singleton_marker` TINYINT
  GENERATED ALWAYS AS (
    CASE WHEN `status` = 'ACTIVE' AND `subject_cardinality` = 'SINGLE'
         THEN 1 ELSE NULL END
  ) STORED,
`active_content_marker` TINYINT
  GENERATED ALWAYS AS (
    CASE WHEN `status` = 'ACTIVE' THEN 1 ELSE NULL END
  ) STORED
```

Create the unique indexes exactly on:

```sql
(`user_id`, `category`, `subject_key`, `active_singleton_marker`)
(`user_id`, `category`, `subject_key`, `content_hash`, `active_content_marker`)
```

Keep `subject_key` at `VARCHAR(191) COLLATE utf8mb4_bin`. With the existing `user_id VARCHAR(128)`, `category VARCHAR(16)`, `content_hash CHAR(64)`, and MySQL 8/InnoDB's 3072-byte index-key limit, the larger composite index remains below the limit; the real-MySQL migration test must create both indexes rather than relying only on text assertions.

- [ ] **Step 4: Implement V7 without overwriting customized prompts**

Update each built-in prompt only when its current `content` exactly equals the V4 text. The task prompt must require exactly:

```text
category, subjectKey, content, sourceMessageId, quote
```

It must explicitly forbid lifecycle fields and describe the generic `<category-lowercase>.<segment>` subject format. Do not use `INSERT IGNORE`, broad `is_builtin=1`, or an unconditional update.

- [ ] **Step 5: Run migration tests**

Run:

```bash
mvn -q -Dtest=FlywayDatabaseMigrationTest test
```

Expected: PASS.

If temporary MySQL URLs are available, also run:

```bash
mvn -q -Dtest=FlywayMySqlMigrationIntegrationTest \
  -Dtest.mysql.jdbc-url="$TEST_MYSQL_JDBC_URL" \
  -Dtest.mysql.adoption-jdbc-url="$TEST_MYSQL_ADOPTION_JDBC_URL" test
```

Expected: PASS; old rows receive category-prefixed legacy subjects, generated indexes reject duplicate active singleton/content rows, and customized prompts remain unchanged.

- [ ] **Step 6: Commit the migration**

```bash
git add -- \
  src/main/resources/db/migration/V6__evolve_chat_memory_lifecycle.sql \
  src/main/resources/db/migration/V7__upgrade_chat_memory_extraction_prompts.sql \
  src/test/java/io/github/imzmq/interview/config/database/FlywayDatabaseMigrationTest.java \
  src/test/java/io/github/imzmq/interview/config/database/FlywayMySqlMigrationIntegrationTest.java
git commit -m "feat(memory): add lifecycle schema"
```

---

### Task 2: Introduce strict subject identity and extraction contract

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemorySubjectKey.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryCardinality.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryQuarantineReason.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryRelationType.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemorySubjectPolicy.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryCandidate.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryStatus.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCandidateValidator.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemorySourceMessage.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemorySourceLoader.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/domain/memory/ChatMemorySubjectKeyTest.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/domain/memory/ChatMemorySubjectPolicyTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryCandidateValidatorTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryExtractionServiceTest.java`

- [ ] **Step 1: Write failing domain tests**

Cover normalization and prefix enforcement:

```java
assertThat(ChatMemorySubjectKey.parse(
        ChatMemoryCategory.PREFERENCE, "ＰＲＥＦＥＲＥＮＣＥ.Response_Language").value())
        .isEqualTo("preference.response_language");
assertThatThrownBy(() -> ChatMemorySubjectKey.parse(
        ChatMemoryCategory.GOAL, "preference.response_language"))
        .isInstanceOf(IllegalArgumentException.class);
assertThatThrownBy(() -> ChatMemorySubjectKey.parse(
        ChatMemoryCategory.FACT, "fact.current-city"))
        .isInstanceOf(IllegalArgumentException.class);
```

Cover cardinality:

```java
assertThat(ChatMemorySubjectPolicy.cardinality(
        ChatMemoryCategory.PREFERENCE,
        ChatMemorySubjectKey.parse(ChatMemoryCategory.PREFERENCE,
                "preference.response_language")))
        .isEqualTo(ChatMemoryCardinality.SINGLE);
assertThat(ChatMemorySubjectPolicy.cardinality(
        ChatMemoryCategory.FACT,
        ChatMemorySubjectKey.parse(ChatMemoryCategory.FACT, "fact.known_language")))
        .isEqualTo(ChatMemoryCardinality.MULTI);
```

Start the singleton-fact registry empty unless a fact key already has an explicit product requirement. This avoids inventing semantic rules merely to exercise the enum. Add only these conservative aliases, all within the same category: `preference.language -> preference.response_language`, `goal.target_job -> goal.target_role`, `constraint.study_time -> constraint.available_study_time`, and `fact.city -> fact.current_city`. Test each alias and reject cross-category aliasing.

- [ ] **Step 2: Write failing strict-validator tests**

Update valid fixtures to include `"subjectKey":"preference.response_language"`. Add tests proving:

- missing `subjectKey` fails with `MEMORY_ITEM_SCHEMA_INVALID`;
- `status`, `cardinality`, `relation`, or `validUntil` as an extra field fails;
- category/key prefix mismatch fails with `MEMORY_CANDIDATE_INVALID`;
- normalized subject reaches `ChatMemoryCandidate.subjectKey()`.

- [ ] **Step 3: Run the narrow tests and verify failure**

Run:

```bash
mvn -q -Dtest=ChatMemorySubjectKeyTest,ChatMemorySubjectPolicyTest,ChatMemoryCandidateValidatorTest,ChatMemoryExtractionServiceTest test
```

Expected: FAIL because the subject types and five-field extraction contract do not exist.

- [ ] **Step 4: Implement the framework-neutral domain types**

`ChatMemorySubjectKey` must be an immutable value with a `value()` accessor. Normalize with NFKC and `Locale.ROOT`, accept 2–6 dot-separated segments, cap the final key at 191 characters, require each segment to match `[a-z0-9_]+`, and require the first segment to equal `category.name().toLowerCase(Locale.ROOT)`.

Define enums exactly as:

```java
public enum ChatMemoryCardinality { SINGLE, MULTI }
public enum ChatMemoryQuarantineReason {
    AMBIGUOUS_BATCH_ORDER,
    MULTIPLE_ACTIVE_SINGLETONS,
    SUBJECT_CARDINALITY_CONFLICT,
    OLDER_THAN_CURRENT
}
public enum ChatMemoryRelationType { SUPERSEDES, CONFLICTS_WITH }
```

Add `SUPERSEDED` to `ChatMemoryStatus`. `ChatMemorySubjectPolicy` owns the exact alias map from Step 1 and applies aliases only after syntax and category-prefix validation; canonical keys bypass the map unchanged.

Change the candidate signature to:

```java
public record ChatMemoryCandidate(
        ChatMemoryCategory category,
        ChatMemorySubjectKey subjectKey,
        String content,
        ChatMemorySourceQuote source) { ... }
```

- [ ] **Step 5: Implement the strict extraction boundary**

Set `REQUIRED_FIELDS` to:

```java
Set.of("category", "subjectKey", "content", "sourceMessageId", "quote")
```

Parse category first, then call `ChatMemorySubjectKey.parse(category, subjectKeyText)`. Do not accept aliases through permissive JSON fields; if a small alias table is introduced, keep it inside `ChatMemorySubjectPolicy` and return a single canonical key.

Add `LocalDateTime createdAt` to `ChatMemorySourceMessage`, populate it from `ChatMessageDO.getCreatedAt()`, and update all constructors in tests. This timestamp is ordering metadata, not model input; `boundedPromptMessages()` must continue to expose only message ID, role, and content.

- [ ] **Step 6: Run extraction tests**

Run:

```bash
mvn -q -Dtest=ChatMemorySubjectKeyTest,ChatMemorySubjectPolicyTest,ChatMemoryCandidateValidatorTest,ChatMemoryExtractionServiceTest,ChatMemorySourceLoaderTest test
```

Expected: PASS.

- [ ] **Step 7: Commit the domain and extraction contract**

```bash
git add -- \
  src/main/java/io/github/imzmq/interview/chat/domain/memory \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCandidateValidator.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemorySourceMessage.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemorySourceLoader.java \
  src/test/java/io/github/imzmq/interview/chat/domain/memory \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryExtractionServiceTest.java \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemorySourceLoaderTest.java
git commit -m "feat(memory): define subject identity"
```

---

### Task 3: Implement deterministic lifecycle decisions and atomic persistence

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryItemDO.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryEvolutionDO.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryEvolutionMapper.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryItemMapper.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleRequest.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecyclePolicy.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleWriter.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCommitTransactionService.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecyclePolicyTest.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleWriterTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCommitServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCommitCoordinatorRetryTest.java`

- [ ] **Step 1: Write failing policy tests**

Use a persistence-neutral current-item input and assert these exact outcomes:

```java
assertThat(policy.decide(singleNewerRequest, List.of(currentSingle)))
        .extracting(ChatMemoryLifecyclePolicy.Decision::action)
        .isEqualTo(SUPERSEDE);
assertThat(policy.decide(singleOlderRequest, List.of(currentSingle)))
        .extracting(ChatMemoryLifecyclePolicy.Decision::quarantineReason)
        .isEqualTo(OLDER_THAN_CURRENT);
assertThat(policy.decide(multiDifferentRequest, List.of(currentMulti)))
        .extracting(ChatMemoryLifecyclePolicy.Decision::action)
        .isEqualTo(ACTIVATE);
assertThat(policy.decide(sameContentRequest, List.of(currentSameContent)))
        .extracting(ChatMemoryLifecyclePolicy.Decision::action)
        .isEqualTo(ATTACH_SOURCE);
```

Also cover `MULTIPLE_ACTIVE_SINGLETONS`, `SUBJECT_CARDINALITY_CONFLICT`, and the batch-level `AMBIGUOUS_BATCH_ORDER` override for different normalized contents under one singleton subject.

- [ ] **Step 2: Write failing writer tests**

Mock mappers and verify:

- `ACTIVATE` inserts one active item, source, and UPSERT Outbox;
- `ATTACH_SOURCE` adds/restores a source and bumps the current item version;
- `SUPERSEDE` marks the old item `SUPERSEDED`, inserts the new active item, writes one relation from new to old, and enqueues old DELETE plus new UPSERT;
- `QUARANTINE` inserts the candidate, source, and one `CONFLICTS_WITH` relation per related current item, but no Outbox event;
- all writes remain inside `ChatMemoryCommitTransactionService.commitOnce/commitBatchOnce` transaction boundaries.

- [ ] **Step 3: Run tests and verify failure**

Run:

```bash
mvn -q -Dtest=ChatMemoryLifecyclePolicyTest,ChatMemoryLifecycleWriterTest,ChatMemoryCommitServiceTest,ChatMemoryCommitCoordinatorRetryTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: FAIL because lifecycle decisions and persistence types do not exist.

- [ ] **Step 4: Map lifecycle persistence**

Add these fields to `ChatMemoryItemDO`:

```java
private String subjectKey;
private String subjectCardinality;
private LocalDateTime validFrom;
private LocalDateTime validUntil;
private LocalDateTime originMessageCreatedAt;
private Long originMessagePk;
private Integer originQuoteStart;
private String quarantineReason;
```

`ChatMemoryEvolutionMapper` must provide focused operations:

```java
int insert(ChatMemoryEvolutionDO relation);
List<ChatMemoryEvolutionDO> selectOwnedRelations(String userId, long memoryId);
```

`ChatMemoryItemMapper` must provide SQL that locks all rows for `(userId, category, subjectKey)` ordered by ID, plus CAS transitions for `ACTIVE -> SUPERSEDED`, `QUARANTINED -> ACTIVE`, version bumps, and deletion. Do not build lifecycle transitions with arbitrary MyBatis wrapper chains.

- [ ] **Step 5: Implement the pure decision policy**

Define these nested framework-neutral types inside `ChatMemoryLifecyclePolicy`; do not create extra one-class files for policy-only types:

```java
public enum Action { ATTACH_SOURCE, ACTIVATE, SUPERSEDE, QUARANTINE }

public record CurrentItem(
        long id, String contentHash, ChatMemoryStatus status,
        ChatMemoryCardinality cardinality, LocalDateTime originMessageCreatedAt,
        long originMessagePk, int originQuoteStart, long version) {}

public record Decision(
        Action action, ChatMemoryQuarantineReason quarantineReason,
        List<Long> relatedMemoryIds) {}
```

`quarantineReason` is null only for non-quarantine actions; `relatedMemoryIds` is immutable and sorted.

Define `ChatMemoryLifecycleRequest` exactly as the writer input:

```java
public record ChatMemoryLifecycleRequest(
        String userId, String sessionId, ChatMemoryCandidate candidate,
        String contentHash, ChatMemoryCardinality cardinality,
        LocalDateTime originMessageCreatedAt, long originMessagePk,
        int originQuoteStart, boolean ambiguousBatch) {}
```

The policy compares source order lexicographically:

```text
originMessageCreatedAt, originMessagePk, originQuoteStart
```

It must never use extraction completion time. It accepts already-detected batch ambiguity, the backend cardinality, candidate content hash, and locked current rows, then returns one explicit decision. It must not call mappers, Milvus, the model, or Spring.

- [ ] **Step 6: Implement the lifecycle writer and delegate from the commit transaction**

The writer must:

1. group requests by `(userId, category, subjectKey)` and sort groups deterministically;
2. detect multiple distinct singleton contents in one batch before applying any member;
3. lock the current theme rows;
4. ask the policy for a decision;
5. write item/source/relation/outbox mutations;
6. preserve exact-source idempotency;
7. throw stable `ChatMemoryCommitException` codes on CAS/constraint conflicts.

For supersession set the old row's `valid_until` to the new row's `valid_from`. For quarantine set `quarantine_reason`; for active rows leave it null. Do not revive `SUPERSEDED` or `DELETED` items when content repeats later.

`ChatMemoryCommitTransactionService` retains lease fencing, source quote revalidation, task completion, and the transaction annotations. It already locks the authoritative `ChatMessageDO`; use that row's `createdAt`, primary key, and the validated quote offset to construct each `ChatMemoryLifecycleRequest`. Do not derive lifecycle order from extraction context or task completion time. Replace its current content-hash-only `commitCandidate` branch with one batch call to the writer so all candidates are grouped and ambiguity is detected before the first item is written.

- [ ] **Step 7: Run lifecycle unit tests**

Run the command from Step 3 again.

Expected: PASS; no test expects content-hash-only reactivation.

- [ ] **Step 8: Commit deterministic lifecycle writes**

```bash
git add -- \
  src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleRequest.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecyclePolicy.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleWriter.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCommitTransactionService.java \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecyclePolicyTest.java \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleWriterTest.java \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCommitServiceTest.java \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCommitCoordinatorRetryTest.java
git commit -m "feat(memory): govern lifecycle writes"
```

---

### Task 4: Prove MySQL transaction and concurrency behavior

**Files:**
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleMySqlIntegrationTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryLeaseSqlTest.java`

- [ ] **Step 1: Add failing SQL-contract tests**

Assert the mapper methods contain the required owner, subject, status, version, and `FOR UPDATE` predicates. In particular, the theme lock must order by ID and the supersede/activate transitions must include expected status and version.

- [ ] **Step 2: Add real-MySQL integration scenarios**

Reuse the conditional `test.mysql.jdbc-url`, username, and password properties. After Flyway migration, create isolated users/sessions/messages and assert:

1. two concurrent different writes to one singleton subject leave exactly one `ACTIVE` row;
2. the winner has one valid predecessor relationship when a committed old row existed;
3. failed relation or Outbox insertion rolls back both status changes;
4. repeated old content creates a new node after an intervening supersession rather than reviving the old row;
5. two active multi-valued facts with different content can coexist;
6. duplicate active content violates the database constraint.

Use `ExecutorService`, latches, and separate Spring-managed transactions; every test must delete only its UUID-prefixed fixture data in `finally`.

- [ ] **Step 3: Run SQL tests**

Run:

```bash
mvn -q -Dtest=ChatMemoryLeaseSqlTest test
```

Expected: PASS.

Run the MySQL integration when configured:

```bash
mvn -q -Dtest=ChatMemoryLifecycleMySqlIntegrationTest \
  -Dtest.mysql.jdbc-url="$TEST_MYSQL_JDBC_URL" test
```

Expected: PASS with MySQL 8; otherwise the test reports skipped, not passed.

- [ ] **Step 4: Commit MySQL verification**

```bash
git add -- \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleMySqlIntegrationTest.java \
  src/test/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryLeaseSqlTest.java
git commit -m "test(memory): verify lifecycle concurrency"
```

---

### Task 5: Make recall, projection, repair, and source deletion lifecycle-aware

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryConfiguration.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryItemMapper.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRecallService.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProjectionWorker.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepairService.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/ChatSessionDeletionService.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRecallServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProjectionWorkerTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepairServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/ChatSessionDeletionServiceTest.java`

- [ ] **Step 1: Add failing recall and projection tests**

Add cases proving:

```text
ACTIVE + current validity + effective source      -> recall/project
ACTIVE + future valid_from                        -> no recall/project
ACTIVE + expired valid_until                      -> no recall/project
QUARANTINED / SUPERSEDED / DELETED                -> no recall/project
stale Milvus candidate for a superseded item      -> rejected by MySQL validation
```

Use a fixed `Clock` in lifecycle checks rather than scattered `LocalDateTime.now()` calls. Add `@Bean Clock chatMemoryClock() { return Clock.systemUTC(); }` to `ChatMemoryConfiguration`, inject it through the normal production constructors, and use fixed clocks in tests; do not add compatibility or test-only constructors. Convert with UTC consistently when producing `LocalDateTime`.

- [ ] **Step 2: Add failing source-invalidation tests**

Extend session-deletion tests:

- losing the last source deletes an `ACTIVE` item and queues DELETE;
- losing the last source deletes a `QUARANTINED` item without queuing DELETE;
- a `QUARANTINED` item with another effective source only bumps its version;
- a `SUPERSEDED` item remains `SUPERSEDED` when sources disappear;
- an `ACTIVE` item with another source bumps version and queues UPSERT.

- [ ] **Step 3: Run tests and verify failure**

Run:

```bash
mvn -q -Dtest=ChatMemoryRecallServiceTest,ChatMemoryProjectionWorkerTest,ChatMemoryRepairServiceTest,ChatSessionDeletionServiceTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: FAIL because current code checks only `status = ACTIVE` and does not distinguish quarantined projection cleanup.

- [ ] **Step 4: Apply one effective-memory predicate everywhere**

SQL recall queries must include:

```sql
item.status = 'ACTIVE'
AND item.valid_from <= #{now}
AND (item.valid_until IS NULL OR item.valid_until > #{now})
```

Pass `Clock`-derived `LocalDateTime now` into mapper calls. Mirror the same predicate in projection worker and repair service before UPSERT. Keep source/session/message validation in MySQL recall revalidation.

Projection behavior remains:

```text
projectable current truth -> UPSERT current version
anything else             -> delete memory projection
```

A delayed DELETE/UPSERT event must re-read current MySQL truth and cannot revive an invalid item.

- [ ] **Step 5: Correct source invalidation semantics**

Add explicit mapper transitions that can delete `ACTIVE` or `QUARANTINED` rows with expected version. `ChatSessionDeletionService` must enqueue DELETE only when the item was projectable before the transition. It must not change `SUPERSEDED` or already `DELETED` rows.

Do not invalidate all sources when a user deletes a memory merely to hide it; status and validity are the truth for recall, while sources remain provenance unless their session/message is actually deleted.

- [ ] **Step 6: Run lifecycle read-path tests**

Run the command from Step 3 again.

Expected: PASS.

- [ ] **Step 7: Commit read and projection convergence**

```bash
git add -- \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryConfiguration.java \
  src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryItemMapper.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRecallService.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProjectionWorker.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepairService.java \
  src/main/java/io/github/imzmq/interview/chat/application/ChatSessionDeletionService.java \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRecallServiceTest.java \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProjectionWorkerTest.java \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepairServiceTest.java \
  src/test/java/io/github/imzmq/interview/chat/application/ChatSessionDeletionServiceTest.java
git commit -m "fix(memory): enforce effective leaf reads"
```

---

### Task 6: Complete the backend quarantine-management contract

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/chat/application/contract/result/ChatMemoryRelationView.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/contract/result/ChatMemoryView.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/contract/ChatMemoryOperations.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementException.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementService.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryEvolutionMapper.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryItemMapper.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementServiceTest.java`

- [ ] **Step 1: Define failing contract and service tests**

Change the contract to:

```java
List<ChatMemoryView> list(String userId, String status, int limit, Long beforeId);
List<ChatMemoryRelationView> evolution(String userId, long memoryId);
void confirmReplacement(String userId, long memoryId, long expectedVersion);
void confirmCoexistence(String userId, long memoryId, long expectedVersion);
void delete(String userId, long memoryId, long expectedVersion);
```

Test these rules:

- list accepts only known `ChatMemoryStatus` values in the application service, rejects unknown values with `MEMORY_STATUS_INVALID`, and returns lifecycle fields;
- evolution returns only relations owned by the current user;
- replacement locks the quarantined item and current theme rows, rejects `OLDER_THAN_CURRENT`, supersedes all still-current conflict targets, activates the quarantined item, and emits DELETE/UPSERT Outbox events;
- coexistence activates only a `MULTI` item;
- delete of `ACTIVE` emits DELETE; delete of `QUARANTINED` emits no Outbox event;
- missing/foreign item yields `MEMORY_NOT_FOUND` without disclosure;
- stale version or changed state yields `MEMORY_STATE_CONFLICT`;
- every write method is transactional.

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
mvn -q -Dtest=ChatMemoryManagementServiceTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: FAIL because the existing contract only lists and deletes without version fencing.

- [ ] **Step 3: Implement provider-owned result types**

`ChatMemoryView` must expose:

```java
long id,
String category,
String subjectKey,
String subjectCardinality,
String content,
String status,
String quarantineReason,
long version,
Instant validFrom,
Instant validUntil,
Instant createdAt,
Instant updatedAt,
List<String> sourceMessageIds
```

`ChatMemoryRelationView` must expose only SDK-neutral values:

```java
long fromMemoryId,
long toMemoryId,
String relationType,
Instant createdAt
```

- [ ] **Step 4: Implement management transactions**

Use explicit owner-scoped mapper methods and stable lock order by memory ID. Before replacement, revalidate that every `CONFLICTS_WITH` target is still active, same user/category/subject, and older than the quarantined candidate. Reject `OLDER_THAN_CURRENT` replacement. Append `SUPERSEDES`; never mutate or delete historical conflict relations.

For coexistence, require stored cardinality `MULTI`; for singleton return `MEMORY_CARDINALITY_CONFLICT`. For every command require exact `expectedVersion` and increment the affected item's version.

Delete must retain source-row metadata and evolution relationships, set `valid_until` for an active item, set `deleted_at`, and clear the item `content` to preserve the existing privacy behavior. Source quote accessibility must disappear because deleted items are never returned as effective memories; do not invalidate otherwise-valid source rows merely to implement item deletion. Lifecycle IDs, hashes, ordering metadata, status, and relationships remain for consistency/audit, while deleted content is not retained.

- [ ] **Step 5: Run management tests**

Run the command from Step 2 again.

Expected: PASS.

- [ ] **Step 6: Commit the management contract**

```bash
git add -- \
  src/main/java/io/github/imzmq/interview/chat/application/contract/ChatMemoryOperations.java \
  src/main/java/io/github/imzmq/interview/chat/application/contract/result/ChatMemoryView.java \
  src/main/java/io/github/imzmq/interview/chat/application/contract/result/ChatMemoryRelationView.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementException.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementService.java \
  src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryEvolutionMapper.java \
  src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryItemMapper.java \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementServiceTest.java
git commit -m "feat(memory): resolve quarantined facts"
```

---

### Task 7: Expose owner-safe lifecycle management over HTTP

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryResponse.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryRelationResponse.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryController.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/api/ChatApiExceptionHandler.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/api/ChatMemoryControllerTest.java`

- [ ] **Step 1: Add failing MockMvc tests**

Cover:

```text
GET    /api/chat/memories?status=QUARANTINED&limit=20
GET    /api/chat/memories/7/evolution
POST   /api/chat/memories/7/resolve/replacement?expectedVersion=3
POST   /api/chat/memories/7/resolve/coexistence?expectedVersion=3
DELETE /api/chat/memories/7?expectedVersion=3
```

Verify every operation uses `UserIdentityQuery.resolveTrusted(...)`, ignores forged user headers, returns lifecycle fields, and delegates only to `ChatMemoryOperations`.

Register `ChatApiExceptionHandler` in the standalone MockMvc test and assert:

```text
MEMORY_NOT_FOUND       -> 404
MEMORY_STATE_CONFLICT  -> 409
MEMORY_CARDINALITY_CONFLICT -> 409
unexpected exception   -> no exception message disclosure
```

- [ ] **Step 2: Run controller tests and verify failure**

Run:

```bash
mvn -q -Dtest=ChatMemoryControllerTest test
```

Expected: FAIL because the routes and response fields do not exist.

- [ ] **Step 3: Implement the thin HTTP adapter**

Add the routes exactly as tested. `status` is optional; the application service validates it against known `ChatMemoryStatus` values. Keep `expectedVersion` required on every write. Do not add request fields for user ID, relation type, target memory ID, cardinality, or status.

Map `ChatMemoryManagementException.code()` in the scoped Chat advice. Return low-information Chinese messages and never serialize the underlying database exception.

- [ ] **Step 4: Run controller tests**

Run the command from Step 2 again.

Expected: PASS.

- [ ] **Step 5: Commit the HTTP API**

```bash
git add -- \
  src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryResponse.java \
  src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryRelationResponse.java \
  src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryController.java \
  src/main/java/io/github/imzmq/interview/chat/api/ChatApiExceptionHandler.java \
  src/test/java/io/github/imzmq/interview/chat/api/ChatMemoryControllerTest.java
git commit -m "feat(memory): expose lifecycle management"
```

---

### Task 8: Add content-free lifecycle observations, architecture checks, and current documentation

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryObservationPublisher.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleWriter.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementService.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryObservabilityTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`
- Modify: `PACKAGE_CONVENTIONS.md`
- Modify: `ARCHITECTURE.md`
- Modify: `docs/development/observability-guidelines.md`

- [ ] **Step 1: Add failing observation tests**

Add `lifecycle` to the bounded event set and test events for:

```text
AUTO_SUPERSEDED
QUARANTINED
CONFIRMED_REPLACEMENT
CONFIRMED_COEXISTENCE
DELETED
STATE_CONFLICT
```

Allowed lifecycle attributes are only controlled aggregate values:

```text
stage, resultCount, reasonCode, previousStatus, nextStatus, errorCode, latencyMs
```

Assert that memory content, quote, subject key, user/session/message/memory IDs, prompt, raw JSON, embedding, and exception text never reach `OperationalObservationPublisher`.

- [ ] **Step 2: Add or tighten architecture tests**

Assert:

- `chat.domain.memory` does not depend on Spring, MyBatis, Milvus, Chat API, or infrastructure;
- `ChatMemoryController` depends on `ChatMemoryOperations`, not mappers or management implementation;
- `ChatMemoryOperations` and result types do not expose DOs, mappers, Spring Web, or Milvus SDK;
- no new top-level `memory` package exists.

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```bash
mvn -q -Dtest=ChatMemoryObservabilityTest,ArchitectureRulesTest test
```

Expected: the lifecycle observation test fails until the publisher and call sites are added.

- [ ] **Step 4: Publish content-free lifecycle events**

Add:

```java
public void lifecycle(String status, Map<String, ?> attributes) {
    publish("lifecycle", status, attributes);
}
```

Have `lifecycle(...)` copy only the listed lifecycle attribute keys instead of forwarding arbitrary keys. Add `lifecycleAfterCommit(...)` to `ChatMemoryObservationPublisher`. When Spring transaction synchronization is active, register a `TransactionSynchronization.afterCommit()` callback that invokes the bounded `lifecycle(...)` publisher; otherwise publish immediately for non-transactional callers. Writer and management service call only `lifecycleAfterCommit(...)`, after their mutations have succeeded. Tests must prove a rollback produces no lifecycle event and a commit produces one. Do not add an external SDK adapter or durable telemetry table; these events are best-effort operational observations, not business audit records.

- [ ] **Step 5: Update architecture and observability documentation**

Document the final state, not migration chronology:

- MySQL item rows are the current lifecycle read model;
- evolution rows are append-only audit relationships;
- the model only proposes `subjectKey`; backend rules own cardinality and lifecycle;
- recall and Milvus include only effective active leaves;
- management operations are version-fenced through `ChatMemoryOperations`;
- Neo4j, natural-language validity parsing, Agent Context `RECALL`, and the management UI remain out of scope;
- lifecycle observation events and fields are content-free.

Do not copy the full 577-line design into architecture docs; keep each document at its established abstraction level.

- [ ] **Step 6: Run focused and broad verification**

Run:

```bash
mvn -q -Dtest=ChatMemoryObservabilityTest,ArchitectureRulesTest test
mvn -q -DskipTests test-compile
mvn -q -Dtest='ChatMemory*Test,ChatSessionDeletionServiceTest,FlywayDatabaseMigrationTest,ArchitectureRulesTest' test
mvn test
```

Expected: every command exits 0. If a real MySQL URL is available, additionally run both MySQL lifecycle/migration integration tests and report them separately; never describe skipped tests as passed.

- [ ] **Step 7: Inspect the final diff for forbidden transitional design**

Run:

```bash
git diff --check
git status --short --branch
rg -n "legacy mode|compatib|fallback constructor|temporary switch|dual read|dual write" \
  src/main/java/io/github/imzmq/interview/chat \
  src/main/resources/db/migration/V6__evolve_chat_memory_lifecycle.sql \
  src/main/resources/db/migration/V7__upgrade_chat_memory_extraction_prompts.sql \
  PACKAGE_CONVENTIONS.md ARCHITECTURE.md docs/development/observability-guidelines.md
```

Expected: no whitespace errors, no accidental local files, and no temporary compatibility mode. Legitimate references to legacy backfilled subject keys in migration tests are acceptable.

- [ ] **Step 8: Commit observations and documentation**

```bash
git add -- \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryObservationPublisher.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryLifecycleWriter.java \
  src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementService.java \
  src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryObservabilityTest.java \
  src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java \
  PACKAGE_CONVENTIONS.md ARCHITECTURE.md docs/development/observability-guidelines.md
git commit -m "docs(memory): document lifecycle governance"
```

---

## Final acceptance checklist

- [ ] The model emits only candidate data and cannot drive status, cardinality, relations, or validity.
- [ ] MySQL guarantees at most one active singleton and one active identical content per subject.
- [ ] Newer singleton values atomically supersede older values; late older tasks quarantine.
- [ ] Ambiguous singleton batches quarantine without model arbitration.
- [ ] Quarantine has query, replacement, coexistence, and deletion operations with version fencing.
- [ ] Only effective `ACTIVE` leaves reach lexical recall, vector revalidation, repair, and Milvus.
- [ ] Source deletion handles active and quarantined items without changing superseded history.
- [ ] Customized prompts survive migration; old rows use category-prefixed legacy subjects under the same runtime model.
- [ ] No Neo4j, new API Key, new startup dependency, top-level memory module, empty abstraction, or temporary dual mode is introduced.
- [ ] Unit, architecture, migration, and available real-MySQL tests are reported accurately.
