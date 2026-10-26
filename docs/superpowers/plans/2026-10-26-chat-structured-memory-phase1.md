# Chat Structured Long-Term Memory Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Do not dispatch subagents; execute in the current session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a recoverable Chat-owned cross-session memory loop for new user messages: durable extraction tasks, verified structured facts in MySQL, transactional Milvus projection, query-aware recall, ownership-safe management, and deletion propagation.

**Architecture:** MySQL is the sole truth source. Saving a new user message creates an extraction task in the same transaction; background workers claim tasks with MySQL leases, validate quoted user text, and atomically commit memory/source/outbox rows. Milvus stores only immutable versioned projections and returns candidates that are always revalidated against MySQL before use. Existing data is neither migrated nor deleted.

**Tech Stack:** Java 21, Spring Boot, Spring Security, MyBatis-Plus, MySQL 8, Flyway, Spring AI `EmbeddingModel`, Milvus Java SDK, JUnit 5, Mockito, AssertJ, ArchUnit.

**Approved spec:** `docs/superpowers/specs/2026-10-26-chat-structured-memory-phase1-design.md`

---

## 0. Execution rules and file map

Before every task:

```bash
git status --short --branch
```

Do not stage or overwrite the existing user changes in `AGENTS.md` or any `.DS_Store`. Do not modify V1/V2. Do not introduce a top-level `memory` module, Maven module, message broker, Neo4j memory projection, frontend page, or old-data backfill. Commit steps are checkpoints only: execute them only after the user explicitly authorizes committing; otherwise leave the verified changes uncommitted and report that fact.

### Production file ownership

| Area | Files | Responsibility |
| --- | --- | --- |
| Identity boundary | `identity/application/contract/UserIdentityQuery.java`, `identity/application/UserIdentityResolver.java`, `chat/api/ChatController.java`, `chat/api/ChatApiExceptionHandler.java` | Resolve Chat users from the verified Spring Security principal in JWT mode; preserve `default-user` in open mode and map non-disclosing access errors. |
| Chat ownership | `chat/application/contract/ChatSessionOperations.java`, `ChatMessageOperations.java`, `chat/application/WebChatService.java`, `chat/application/ChatStreamingService.java` | Owner-aware session/message commands and validation before async chat work. |
| Domain | `chat/domain/memory/*` | Framework-free categories, states, candidate/source values, normalization and quote validation. |
| Configuration | `chat/application/memory/ChatMemoryProperties.java`, `application.yml`, `application-local-lite.yml` | Bounded defaults and one master switch, off by default. |
| Persistence | `chat/infrastructure/persistence/memory/*` | DOs and mappers for item, source, task and outbox, including lease SQL. |
| Application | `chat/application/memory/*` | Message/task transaction, extraction, candidate commit, recall, management, workers and repair orchestration. |
| Milvus | `chat/infrastructure/milvus/*` | Schema initialization, projection/query/delete/scan, safe user filtering and SDK status validation. |
| API | `chat/api/ChatMemoryResponse.java`, `chat/api/ChatMemoryController.java` | Current-user list/delete only. |
| Migrations | `db/migration/V3__create_chat_memory_schema.sql`, `V4__seed_chat_memory_prompts.sql` | Additive schema/prompt seed. |
| Architecture/docs | `PACKAGE_CONVENTIONS.md`, `docs/development/local-startup.md`, `docs/development/observability-guidelines.md` | Ownership, operation and telemetry rules. |

### Test file map

- `src/test/java/io/github/imzmq/interview/identity/application/UserIdentityResolverTest.java`
- `src/test/java/io/github/imzmq/interview/chat/api/ChatControllerOwnershipTest.java`
- `src/test/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryNormalizerTest.java`
- `src/test/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryCandidateValidatorTest.java`
- `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryMessageTransactionServiceTest.java`
- `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryExtractionServiceTest.java`
- `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryCommitServiceTest.java`
- `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProjectionWorkerTest.java`
- `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepairServiceTest.java`
- `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRecallServiceTest.java`
- `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementServiceTest.java`
- `src/test/java/io/github/imzmq/interview/chat/infrastructure/milvus/ChatMemoryMilvusAdapterIntegrationTest.java`
- existing Flyway, WebChat, controller contract, local-lite and architecture tests.

---

### Task 1: Establish trusted identity and session ownership

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/identity/application/contract/UserIdentityQuery.java`
- Modify: `src/main/java/io/github/imzmq/interview/identity/application/UserIdentityResolver.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/contract/ChatSessionOperations.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/contract/ChatMessageOperations.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/WebChatService.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/ChatStreamingService.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/api/ChatController.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/ChatSessionAccessException.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/api/ChatApiExceptionHandler.java`
- Create: `src/test/java/io/github/imzmq/interview/identity/application/UserIdentityResolverTest.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/api/ChatControllerOwnershipTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/WebChatServiceTest.java`

- [ ] **Step 1: Write identity and controller ownership tests**

Test these exact behaviors:

```java
@Test
void jwtModeUsesOnlyVerifiedPrincipal() {
    ReflectionTestUtils.setField(resolver, "securityMode", "jwt");
    assertThat(resolver.resolveTrusted("alice", () -> "session-1"))
            .isEqualTo("subject:alice");
    assertThat(resolver.resolveTrusted("", () -> "session-1")).isEmpty();
}

@Test
void openModeAlwaysUsesDefaultUser() {
    ReflectionTestUtils.setField(resolver, "securityMode", "open");
    assertThat(resolver.resolveTrusted("forged", () -> "session-1"))
            .isEqualTo("default-user");
}

@Test
void renamePassesCurrentUserToApplicationContract() throws Exception {
    mockMvc.perform(put("/api/chat/sessions/session-b")
                    .principal(() -> "alice")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"new\"}"))
            .andExpect(status().isOk());
    verify(chatSessionOperations).renameSession("subject:alice", "session-b", "new");
}
```

Add equivalent controller tests for list messages, delete and stream. Verify `Authorization` and `X-User-Id` cannot override `principal.getName()` in JWT mode.

- [ ] **Step 2: Run the focused tests and confirm the contract is missing**

```bash
mvn -q -Dtest=UserIdentityResolverTest,ChatControllerOwnershipTest,WebChatServiceTest test
```

Expected: FAIL because `resolveTrusted` and owner-aware Chat methods do not exist.

- [ ] **Step 3: Add the trusted identity contract and owner-aware Chat methods**

Add this framework-neutral method while retaining the old `resolve(...)` temporarily for unaffected callers:

```java
String resolveTrusted(String authenticatedPrincipal, Supplier<String> sessionIdSupplier);
```

Implement it as:

```java
@Override
public String resolveTrusted(String authenticatedPrincipal, Supplier<String> sessionIdSupplier) {
    if ("open".equalsIgnoreCase(securityMode)) {
        return "default-user";
    }
    String principal = sanitizePrincipal(authenticatedPrincipal);
    return principal.isBlank() ? "" : "subject:" + principal;
}
```

Add owner-aware signatures:

```java
ChatSessionView renameSession(String userId, String sessionId, String newTitle);
void deleteSession(String userId, String sessionId);
void requireOwnedSession(String userId, String sessionId);
List<ChatMessageView> listMessages(String userId, String sessionId, int limit, Long beforeId);
ChatMessageView saveUserMessage(String userId, String sessionId, String content);
```

Every SQL wrapper must include both `session_id` and `user_id` through a session lookup. A missing or foreign session must throw `ChatSessionAccessException`; `ChatApiExceptionHandler` maps it to the same HTTP 404 body for both cases, so ownership is not disclosed. Validate ownership synchronously in `streamChat` before submitting work to `interviewStreamingExecutor`; validate again in `saveUserMessage` inside its later transaction.

In `ChatController`, pass `request.getUserPrincipal() == null ? "" : request.getUserPrincipal().getName()` to `resolveTrusted`. Do not use bearer parsing or `X-User-Id` for these Chat endpoints in JWT mode.

- [x] **Step 4: Run focused and security tests**

```bash
mvn -q -Dtest=UserIdentityResolverTest,ChatControllerOwnershipTest,ChatControllerContractTest,WebChatServiceTest,PublicConfigurationSecurityTest test
```

Expected: PASS; cross-user session operations return the non-disclosing failure and no mutation mapper is called.

- [ ] **Step 5: Commit this boundary independently**

```bash
git add src/main/java/io/github/imzmq/interview/identity src/main/java/io/github/imzmq/interview/chat src/test/java/io/github/imzmq/interview/identity src/test/java/io/github/imzmq/interview/chat
git commit -m "fix(chat): enforce verified session ownership"
```

---

### Task 2: Add bounded configuration, domain rules and additive Flyway migrations

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProperties.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryCategory.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryStatus.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryCandidate.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemorySourceQuote.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryNormalizer.java`
- Create: `src/main/resources/db/migration/V3__create_chat_memory_schema.sql`
- Create: `src/main/resources/db/migration/V4__seed_chat_memory_prompts.sql`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local-lite.yml`
- Modify: `src/test/java/io/github/imzmq/interview/config/database/FlywayDatabaseMigrationTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/config/database/FlywayMySqlMigrationIntegrationTest.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryNormalizerTest.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/domain/memory/ChatMemoryCandidateValidatorTest.java`

- [ ] **Step 1: Add failing domain and migration tests**

Cover NFKC normalization, whitespace collapsing, case preservation, SHA-256 hash stability, Unicode code-point quote offsets, quote mismatch rejection, 500-code-point content limit and the four allowed categories.

Extend Flyway tests to require:

```java
assertThat(schemaV3)
        .contains("t_chat_memory_item")
        .contains("t_chat_memory_source")
        .contains("t_chat_memory_extraction_task")
        .contains("t_chat_memory_outbox")
        .contains("COLLATE utf8mb4_bin")
        .contains("SKIP LOCKED"); // only if lease SQL is included in migration comments; otherwise assert in mapper test
assertThat(promptV4)
        .contains("chat-memory-extract-system")
        .contains("chat-memory-extract-task")
        .contains("INSERT IGNORE")
        .doesNotContain("ON DUPLICATE KEY UPDATE");
```

For clean MySQL migration, expect four successful migrations. For adoption baseline V1, expect three migrations executed after baseline and four successful history rows.

- [ ] **Step 2: Run domain and Flyway contract tests and confirm failure**

```bash
mvn -q -Dtest=ChatMemoryNormalizerTest,ChatMemoryCandidateValidatorTest,FlywayDatabaseMigrationTest test
```

Expected: FAIL because V3/V4 and domain types do not exist.

- [ ] **Step 3: Implement framework-free domain values**

Use these shapes:

```java
public enum ChatMemoryCategory { PREFERENCE, GOAL, FACT, CONSTRAINT }
public enum ChatMemoryStatus { ACTIVE, QUARANTINED, DELETED }

public record ChatMemorySourceQuote(String sourceMessageId, int start, int end, String quote) {
    public ChatMemorySourceQuote {
        if (sourceMessageId == null || sourceMessageId.isBlank() || start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid memory source quote");
        }
    }
}

public record ChatMemoryCandidate(
        ChatMemoryCategory category,
        String content,
        ChatMemorySourceQuote source
) {}
```

`ChatMemoryNormalizer` must use `Normalizer.Form.NFKC`, collapse Unicode whitespace, preserve case, compute offsets with `String.codePoints()`, and hash `category.name() + "\n" + normalizedContent` with SHA-256 lowercase hex.

- [ ] **Step 4: Create V3 schema and V4 prompts**

V3 must use InnoDB, `utf8mb4`, explicit binary collation for user/hash uniqueness, BIGINT versions, bounded status columns, lease indexes and these unique keys:

```sql
UNIQUE KEY `uk_chat_memory_content` (`user_id`, `category`, `content_hash`),
UNIQUE KEY `uk_chat_memory_source` (`memory_id`, `source_message_id`, `source_start`, `source_end`),
UNIQUE KEY `uk_chat_memory_task_message` (`source_message_id`),
UNIQUE KEY `uk_chat_memory_outbox_event` (`memory_id`, `memory_version`, `target`, `operation`)
```

Do not add foreign-key cascades or alter/drop `t_user_chat_memory`. V4 seeds separate SYSTEM/TASK templates with `INSERT IGNORE` and explicitly instructs the model to return at most eight candidates with `category`, `content`, `sourceMessageId`, and an exact `quote` from the claimed user message.

- [ ] **Step 5: Add bounded properties and keep local-lite disabled**

Bind `app.chat.memory` with validated defaults:

```yaml
app:
  chat:
    memory:
      enabled: false
      extraction-delay: 15s
      extraction-batch-size: 8
      extraction-input-code-points: 10000
      extraction-max-attempts: 5
      collection-name: chat_memories
      initialize-schema: true
      worker-poll: 1s
      worker-batch-size: 50
      worker-lease: 60s
      rpc-timeout: 10s
      projection-max-attempts: 10
      repair-delay: 30m
      repair-page-size: 200
      recall-timeout: 2s
      recall-candidates: 20
      recall-limit: 6
      recall-context-code-points: 2000
      fallback-scan-limit: 50
```

`application-local-lite.yml` must explicitly set `app.chat.memory.enabled: false`.

- [ ] **Step 6: Run domain and migration tests**

```bash
mvn -q -Dtest=ChatMemoryNormalizerTest,ChatMemoryCandidateValidatorTest,FlywayDatabaseMigrationTest test
```

Expected: PASS.

If temporary MySQL URLs are available, also run:

```bash
mvn -q -Dtest=FlywayMySqlMigrationIntegrationTest \
  -Dtest.mysql.jdbc-url="$TEST_MYSQL_JDBC_URL" \
  -Dtest.mysql.adoption-jdbc-url="$TEST_MYSQL_ADOPTION_JDBC_URL" \
  -Dtest.mysql.username="$TEST_MYSQL_USERNAME" \
  -Dtest.mysql.password="$TEST_MYSQL_PASSWORD" test
```

Expected: PASS, or tests are explicitly reported skipped when properties are absent.

- [ ] **Step 7: Commit schema/domain/config**

```bash
git add src/main/java/io/github/imzmq/interview/chat/domain src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProperties.java src/main/resources src/test/java/io/github/imzmq/interview/chat/domain src/test/java/io/github/imzmq/interview/config/database
git commit -m "feat(chat): add structured memory schema and domain"
```

---

### Task 3: Create persistence adapters and atomic message/task submission

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryItemDO.java`
- Create: `.../ChatMemorySourceDO.java`
- Create: `.../ChatMemoryExtractionTaskDO.java`
- Create: `.../ChatMemoryOutboxDO.java`
- Create: `.../ChatMemoryItemMapper.java`
- Create: `.../ChatMemorySourceMapper.java`
- Create: `.../ChatMemoryExtractionTaskMapper.java`
- Create: `.../ChatMemoryOutboxMapper.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryMessageTransactionService.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/WebChatService.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryMessageTransactionServiceTest.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryMessageTransactionMySqlIntegrationTest.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory/ChatMemoryLeaseSqlTest.java`

- [ ] **Step 1: Write failing transaction and SQL contract tests**

Verify:

```java
@Test
void enabledSavePersistsMessageAndOneTask() {
    transactionService.saveUserMessage("user-a", "session-a", "I prefer Java");
    verify(messageMapper).insert(any(ChatMessageDO.class));
    verify(taskMapper).insert(argThat(task ->
            task.getUserId().equals("user-a") && task.getStatus().equals("PENDING")));
}

@Test
void disabledSavePersistsMessageWithoutTask() {
    properties.setEnabled(false);
    transactionService.saveUserMessage("user-a", "session-a", "hello");
    verify(messageMapper).insert(any(ChatMessageDO.class));
    verifyNoInteractions(taskMapper);
}
```

Add `ChatMemoryMessageTransactionMySqlIntegrationTest` alongside the unit test. Against the same temporary MySQL used by the Flyway integration suite, make the task insert violate `uk_chat_memory_task_message`, assert the service throws, then query both tables and prove the second message row did not commit. When MySQL properties are absent, the integration test must be skipped and reported as unverified rather than replaced by an H2 claim. The SQL contract test must inspect mapper annotations and require `FOR UPDATE SKIP LOCKED`, lease token predicates on completion, and bounded `LIMIT` parameters.

- [ ] **Step 2: Run tests and confirm missing adapters**

```bash
mvn -q -Dtest=ChatMemoryMessageTransactionServiceTest,ChatMemoryLeaseSqlTest test
```

Expected: FAIL because the four persistence adapters and transaction service do not exist.

- [ ] **Step 3: Implement DOs/mappers and lease statements**

DOs mirror V3 exactly and remain under `chat.infrastructure.persistence.memory`. Mapper custom methods must cover:

```java
List<ChatMemoryExtractionTaskDO> lockClaimableTasks(LocalDateTime now, int limit);
int leaseTask(String taskId, String leaseToken, LocalDateTime leaseUntil, LocalDateTime now);
int markTaskDone(String taskId, String leaseToken, LocalDateTime now);
int rescheduleTask(String taskId, String leaseToken, int attemptCount,
                   LocalDateTime nextAttemptAt, String errorCode, LocalDateTime now);
List<ChatMemoryOutboxDO> lockClaimableEvents(LocalDateTime now, int limit);
int completeEvent(String eventId, String leaseToken, LocalDateTime now);
```

Claim rows in one short `@Transactional` method: select with `FOR UPDATE SKIP LOCKED`, update each selected row with a fresh UUID token and lease expiry, commit, and return leased copies. Never hold the transaction while calling an LLM or Milvus.

- [ ] **Step 4: Implement atomic user-message submission**

`ChatMemoryMessageTransactionService.saveUserMessage(...)` must:

1. Lock or re-read the active owned session.
2. Insert the user `ChatMessageDO`.
3. Touch the session.
4. Insert one PENDING task only when `enabled=true`.
5. Return `ChatMessageView` after the transaction commits.

Wire `WebChatService.saveUserMessage(userId, sessionId, content)` through this service. Do not catch task insert exceptions.

- [ ] **Step 5: Run focused tests**

```bash
mvn -q -Dtest=ChatMemoryMessageTransactionServiceTest,ChatMemoryLeaseSqlTest,WebChatServiceTest test
```

Expected: PASS, including rollback and disabled-path assertions.

- [ ] **Step 6: Commit persistence and submission**

```bash
git add src/main/java/io/github/imzmq/interview/chat/infrastructure/persistence/memory src/main/java/io/github/imzmq/interview/chat/application/memory src/main/java/io/github/imzmq/interview/chat/application/WebChatService.java src/test/java/io/github/imzmq/interview/chat
git commit -m "feat(chat): persist durable memory extraction tasks"
```

---

### Task 4: Implement strict extraction and atomic candidate commit

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryExtractionWorker.java`
- Create: `.../ChatMemoryExtractionService.java`
- Create: `.../ChatMemoryCandidateValidator.java`
- Create: `.../ChatMemoryCommitService.java`
- Create: `.../ChatMemoryRetryPolicy.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryExtractionServiceTest.java`
- Create: `.../ChatMemoryCommitServiceTest.java`
- Modify: `src/main/java/io/github/imzmq/interview/config/async/AsyncConfig.java`

- [ ] **Step 1: Write failing extraction validation tests**

Cover valid candidate parsing plus rejection of invalid JSON, more than eight candidates, unknown category, content over 500 code points, nonexistent/assistant/deleted/foreign source messages, quote mismatch, invalid offsets, prompt injection and credential-like candidates. Verify failures never call `ChatMemoryCommitService`.

A success fixture must be exact JSON:

```json
{"memories":[{"category":"PREFERENCE","content":"用户偏好使用 Java","sourceMessageId":"m-1","quote":"我更喜欢使用 Java"}]}
```

- [ ] **Step 2: Run tests and confirm failure**

```bash
mvn -q -Dtest=ChatMemoryExtractionServiceTest,ChatMemoryCommitServiceTest test
```

Expected: FAIL because extraction and commit services do not exist.

- [ ] **Step 3: Implement bounded extraction outside database locks**

The worker claims up to eight tasks, groups only compatible tasks, loads claimed user messages plus at most four prior completed messages for context, enforces 10,000 input code points, renders `chat-memory-extract-system`/`chat-memory-extract-task`, calls `RoutingChatService`, and parses using `StructuredOutputParser.parseTree(...)`.

The parser schema must require a root `memories` array; `ChatMemoryCandidateValidator` performs nested item validation because the current structured-output schema validates root fields only. A malformed response produces a controlled retry code. No natural-language fallback is allowed.

Use a bounded `chatMemoryWorkerExecutor` with core/max 2, queue 20, `AbortPolicy`, graceful shutdown, and no caller-runs policy that could make a request thread execute LLM extraction.

- [ ] **Step 4: Implement atomic commit with idempotency and fencing**

Inside one short transaction:

1. Recheck the task lease token.
2. Lock the source session and verify user/session/message are active and role=`user`.
3. Normalize candidates and lock matching item rows in deterministic hash order.
4. Insert a new ACTIVE item or attach a new source to the existing ACTIVE item.
5. Refuse automatic activation of QUARANTINED/DELETED tombstones.
6. Increment `version` with `WHERE id=? AND version=?` when sources change.
7. Insert or reactivate a MILVUS UPSERT outbox event for the current version.
8. Mark the claimed task DONE using the same lease token.

A zero-row CAS or lost lease rolls back and triggers bounded retry; unique-key races re-read and retry a finite number of times.

- [ ] **Step 5: Test retry and concurrency contracts**

Add tests for duplicate delivery, two tasks with the same fact, expired lease, deletion between extraction and commit, task restart, unique-key race, CAS conflict, DEAD after five attempts, and controlled reset of an already persisted DEAD task. Do not add a history scanner.

```bash
mvn -q -Dtest=ChatMemoryExtractionServiceTest,ChatMemoryCommitServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit extraction loop**

```bash
git add src/main/java/io/github/imzmq/interview/chat/application/memory src/main/java/io/github/imzmq/interview/config/async/AsyncConfig.java src/test/java/io/github/imzmq/interview/chat/application/memory
git commit -m "feat(chat): extract and commit verified memories"
```

---

### Task 5: Implement Milvus projection and transactional Outbox worker

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/chat/infrastructure/milvus/ChatMemoryProjection.java`
- Create: `.../ChatMemoryVectorCandidate.java`
- Create: `.../ChatMemoryMilvusAdapter.java`
- Create: `.../ChatMemoryMilvusSchemaInitializer.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProjectionWorker.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProjectionWorkerTest.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/infrastructure/milvus/ChatMemoryMilvusAdapterIntegrationTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/config/startup/LocalLiteConditionalConfigTest.java`

- [ ] **Step 1: Write failing worker/status/configuration tests**

Test ACTIVE UPSERT, stale-version no-op plus cleanup, DELETED/QUARANTINED/no-source delete, duplicate event idempotency, SDK exception, SDK non-success business status, RPC timeout, expired lease, ACK-before-crash retry and out-of-order versions. Extend local-lite test to assert that no memory Milvus initializer/worker bean exists when disabled.

- [ ] **Step 2: Run focused tests and confirm failure**

```bash
mvn -q -Dtest=ChatMemoryProjectionWorkerTest,LocalLiteConditionalConfigTest test
```

Expected: FAIL because projection components do not exist.

- [ ] **Step 3: Implement conditional schema initialization**

Under `@ConditionalOnProperty(name="app.chat.memory.enabled", havingValue="true")`, initialize an independent `chat_memories` collection with:

```text
projection_id VARCHAR primary key
memory_id INT64
user_id VARCHAR
memory_version INT64
content_hash VARCHAR
category VARCHAR
embedding_model_id VARCHAR
embedding FLOAT_VECTOR[configured text dimension]
```

Check existing schema, vector dimension and model identifier. An incompatible existing collection disables vector projection with a controlled health error; never drop/recreate it. Every Milvus operation must check `response.getStatus() == R.Status.Success.getCode()`, exception, and non-null data where applicable.

- [ ] **Step 4: Implement immutable projection IDs and safe filtering**

Use `projection_id = memoryId + ":" + version`. Embed content only after re-reading the current ACTIVE item with an effective source. Store no content in Milvus. Encode filter string literals in one private method that escapes backslash and quote, then use both `user_id` and `memory_id` filters for deletion. Search filters on user first and returns only IDs/version/hash/category/score.

- [ ] **Step 5: Implement worker leasing and retries**

Claim up to 50 events for 60 seconds, release the transaction, execute each RPC with a 10-second application deadline, and ACK only with a matching lease token after confirmed business success. Retry up to ten attempts with capped exponential backoff of five minutes. Re-read truth before every operation; a stale UPSERT must not embed stale content.

- [ ] **Step 6: Run unit and optional real-Milvus integration tests**

```bash
mvn -q -Dtest=ChatMemoryProjectionWorkerTest,LocalLiteConditionalConfigTest test
```

Expected: PASS.

When `TEST_MILVUS_HOST`/`TEST_MILVUS_PORT` and a real embedding provider are explicitly configured:

```bash
mvn -q -Dtest=ChatMemoryMilvusAdapterIntegrationTest \
  -Dtest.milvus.host="$TEST_MILVUS_HOST" \
  -Dtest.milvus.port="$TEST_MILVUS_PORT" test
```

The test creates a uniquely named collection, verifies insert/search/delete/status handling, and drops only that collection. Otherwise report it skipped.

- [ ] **Step 7: Commit projection loop**

```bash
git add src/main/java/io/github/imzmq/interview/chat/infrastructure/milvus src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryProjectionWorker.java src/test/java/io/github/imzmq/interview/chat src/test/java/io/github/imzmq/interview/config/startup/LocalLiteConditionalConfigTest.java
git commit -m "feat(chat): project memory outbox to milvus"
```

---

### Task 6: Add projection repair by scanning both truth and projection

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepairService.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepairScheduler.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepairServiceTest.java`

- [ ] **Step 1: Write failing repair tests**

Feed paged MySQL truth and paged Milvus projections containing: one missing projection, one old version, one hash mismatch, one orphan, one deleted residue and one correct row. Assert repair reactivates the exact existing DONE/DEAD outbox key or inserts the missing current key; it must not write Milvus directly.

- [ ] **Step 2: Run and confirm failure**

```bash
mvn -q -Dtest=ChatMemoryRepairServiceTest test
```

Expected: FAIL because repair orchestration does not exist.

- [ ] **Step 3: Implement bounded two-sided sweep**

Every 30 minutes when enabled, page 200 MySQL current rows and 200 Milvus projection rows. Maintain cursors rather than loading all data. Queue UPSERT for missing/current mismatches and DELETE for stale/orphan/deleted rows. Reactivation resets status, lease and next attempt without changing event identity. Use a single-run guard so scheduler overlap cannot start a second sweep.

- [ ] **Step 4: Run repair tests**

```bash
mvn -q -Dtest=ChatMemoryRepairServiceTest,ChatMemoryProjectionWorkerTest test
```

Expected: PASS, including orphan detection that proves Milvus itself is scanned.

- [ ] **Step 5: Commit repair**

```bash
git add src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepair* src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRepairServiceTest.java
git commit -m "feat(chat): reconcile memory projections"
```

---

### Task 7: Add query-aware recall with MySQL revalidation and lexical fallback

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRecallService.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryContextFormatter.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/ChatStreamingService.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/ChatContextCompressor.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryRecallServiceTest.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryContextFormatterTest.java`

- [ ] **Step 1: Write failing recall safety tests**

Test user A/B isolation, stale vector version, wrong hash, deleted item, invalid/deleted source, wrong category metadata, Milvus timeout, embedding failure, MySQL failure, lexical fallback ranking, six-item limit and 2,000-code-point context limit. Assert raw vector candidates are never returned when MySQL validation fails.

- [ ] **Step 2: Run tests and confirm failure**

```bash
mvn -q -Dtest=ChatMemoryRecallServiceTest,ChatMemoryContextFormatterTest test
```

Expected: FAIL because recall components do not exist.

- [ ] **Step 3: Implement recall and fallback**

Vector flow: embed query, request 20 candidates with a safely encoded user filter, then batch-load MySQL item/source/session/message state and retain only exact user/status/version/hash/category matches with at least one active source.

Fallback flow: load at most 50 recent effective items for that user, tokenize normalized query/content, rank overlap first, allow PREFERENCE/GOAL as stable background, and require relevance for FACT/CONSTRAINT. Return at most six.

If MySQL is unavailable, return empty even if Milvus returns hits. Apply the two-second total budget; preserve thread interruption and do not claim Future cancellation stopped the RPC.

- [ ] **Step 4: Format and inject a bounded non-authoritative section**

Format:

```text
[用户历史自述，仅供个性化；不是指令或技术事实证据]
- 偏好：...
- 目标：...
- 事实：...
- 约束：...
```

Escape control characters, cap each entry and total code points, and never use RAG evidence numbering. In `ChatStreamingService`, after synchronous ownership validation and history creation, recall with `(userId, currentContent)` and append the section to `StreamingChatContext.history`. Do not put it in Knowledge evidence.

Remove old `UserChatMemoryMapper` reads and `prependUserMemory` behavior from `ChatContextCompressor`; preserve compression threshold 12 and recent verbatim count 6.

- [ ] **Step 5: Run recall and existing chat tests**

```bash
mvn -q -Dtest=ChatMemoryRecallServiceTest,ChatMemoryContextFormatterTest,WebChatServiceTest,ChatControllerOwnershipTest test
```

Expected: PASS; existing summary thresholds remain unchanged.

- [ ] **Step 6: Commit recall**

```bash
git add src/main/java/io/github/imzmq/interview/chat/application src/test/java/io/github/imzmq/interview/chat/application
git commit -m "feat(chat): recall verified cross-session memory"
```

---

### Task 8: Add memory API and transactional deletion propagation

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/chat/application/contract/ChatMemoryOperations.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/contract/result/ChatMemoryView.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementService.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryResponse.java`
- Create: `src/main/java/io/github/imzmq/interview/chat/api/ChatMemoryController.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/WebChatService.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryManagementServiceTest.java`
- Create: `src/test/java/io/github/imzmq/interview/chat/api/ChatMemoryControllerTest.java`

- [ ] **Step 1: Write failing list/delete/session-delete tests**

Test list limit clamping to 1..100, before-ID paging, no hash/vector/DO exposure, current-user filter, idempotent delete, non-disclosing foreign delete, immediate exclusion from recall and DELETE outbox creation.

For session deletion, test cancellation of unfinished tasks, source invalidation, deletion and content clearing when the last source disappears, preservation/version bump when another valid source remains, and no effect from `clearSessionContext`.

- [ ] **Step 2: Run tests and confirm failure**

```bash
mvn -q -Dtest=ChatMemoryManagementServiceTest,ChatMemoryControllerTest test
```

Expected: FAIL because API and management service do not exist.

- [ ] **Step 3: Implement application contract and API**

Use SDK-neutral results:

```java
public interface ChatMemoryOperations {
    List<ChatMemoryView> list(String userId, int limit, Long beforeId);
    void delete(String userId, long memoryId);
}

public record ChatMemoryView(
        long id, String category, String content, long version,
        Instant createdAt, Instant updatedAt, List<String> sourceMessageIds
) {}
```

Expose only:

```text
GET    /api/chat/memories?limit=20&beforeId=...
DELETE /api/chat/memories/{memoryId}
```

Resolve identity through the trusted principal path from Task 1. Controller maps DTOs and delegates; it contains no mapper logic.

- [ ] **Step 4: Implement deletion transactions**

Explicit memory deletion locks the item by `(id,userId)`, marks DELETED, clears content, increments version by CAS, invalidates sources and writes a DELETE outbox event. Repeat deletion succeeds without disclosing existence.

Session deletion locks the owned session, logically deletes session/messages, cancels unfinished tasks, invalidates its sources, and handles affected memory IDs in ascending order. Items with no valid source become DELETED and clear content; items with remaining sources stay ACTIVE but increment version and write UPSERT. All changes commit or roll back together.

- [ ] **Step 5: Run API/management/ownership tests**

```bash
mvn -q -Dtest=ChatMemoryManagementServiceTest,ChatMemoryControllerTest,ChatControllerOwnershipTest,WebChatServiceTest test
```

Expected: PASS.

- [ ] **Step 6: Commit API and deletion**

```bash
git add src/main/java/io/github/imzmq/interview/chat src/test/java/io/github/imzmq/interview/chat
git commit -m "feat(chat): manage and delete structured memories"
```

---

### Task 9: Retire the old memory writers and add architecture/observability guards

**Files:**
- Delete: `src/main/java/io/github/imzmq/interview/chat/application/ChatMemoryExtractorAgent.java`
- Delete or reduce to a no-memory role: `src/main/java/io/github/imzmq/interview/chat/application/AutoDreamService.java`
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/ChatContextCompressor.java`
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ModuleBoundaryRules.java` only if a focused rule cannot live in `ArchitectureRulesTest`
- Modify: `PACKAGE_CONVENTIONS.md`
- Modify: `docs/development/observability-guidelines.md`
- Create: `src/test/java/io/github/imzmq/interview/chat/application/memory/ChatMemoryObservabilityTest.java`

- [x] **Step 1: Write failing architecture and observability tests**

Add ArchUnit rules proving:

```text
chat.domain.memory does not depend on Spring, MyBatis, Milvus or chat.api
chat.api does not depend on chat.infrastructure.persistence or Milvus SDK
chat.application.contract does not expose DO, Mapper, Milvus or Spring AI types
knowledge does not depend on chat.infrastructure.persistence.memory
only chat.infrastructure.milvus imports io.milvus for the new memory path
```

Add an event test that permits counts/status/error codes/lag but rejects original content, quote, raw JSON, prompt, embedding, content hash and raw user/session/memory IDs from exported attributes.

- [x] **Step 2: Run architecture tests and confirm old-path failure**

```bash
mvn -q -Dtest=ArchitectureRulesTest,ChatMemoryObservabilityTest test
```

Expected: FAIL until old writer references and any boundary leaks are removed.

- [x] **Step 3: Remove old writers without touching old data**

Remove `CROSS_SESSION_MEMORIZE` publication/subscription, all reads/writes of `t_user_chat_memory` from active Chat flow, and AutoDream's memory-text rewrite. Keep V1 table, old rows and old prompt seeds unchanged. Do not add a migration/backfill command or compatibility dual-write.

If AutoDream has no responsibility after memory rewrite removal, delete it and its scheduling test/config references. If it has an independently verified non-memory responsibility, retain only that responsibility and rename the class to match it.

- [x] **Step 4: Publish sanitized operational events and update docs**

Use the existing `AiObservationPublisher` with event types such as `chat.memory.extract`, `chat.memory.project`, `chat.memory.recall`, and `chat.memory.repair`. Attributes are bounded aggregate values only. Update observability guidelines with the allow/deny list and `PACKAGE_CONVENTIONS.md` with the four Chat memory packages and their ownership.

- [x] **Step 5: Run architecture and old-path scans**

```bash
mvn -q -Dtest=ArchitectureRulesTest,ChatMemoryObservabilityTest test
rg -n "UserChatMemoryMapper|CROSS_SESSION_MEMORIZE|cross-session-memorize|auto-dream" src/main/java
```

Expected: tests PASS; scan returns no active production reference except explicitly documented legacy migration/prompt data where retained.

- [ ] **Step 6: Commit retirement and guards**

```bash
git add src/main/java/io/github/imzmq/interview/chat src/test/java/io/github/imzmq/interview/architecture src/test/java/io/github/imzmq/interview/chat PACKAGE_CONVENTIONS.md docs/development/observability-guidelines.md
git commit -m "refactor(chat): retire legacy memory text writers"
```

---

### Task 10: Document operation and perform staged verification

**Files:**
- Modify: `docs/development/local-startup.md`
- Modify: `README.md` only if it currently enumerates Chat memory settings
- Modify: `docs/superpowers/specs/2026-10-26-chat-structured-memory-phase1-design.md` status line after implementation
- Modify: `docs/superpowers/plans/2026-10-26-chat-structured-memory-phase1.md` checkboxes during execution

- [x] **Step 1: Document enablement and failure behavior**

Document that the feature defaults off, starts collecting only new user messages after enablement, never reads/imports/clears old `memory_text`, requires migrated MySQL tables, can use MySQL lexical fallback when Milvus is unavailable, and retains submitted task/outbox rows across disable/restart. Include bounded retry commands for new-system DEAD tasks only; explicitly state that no historical message scan/backfill command exists.

List the collection name, worker/recall bounds, metrics, safe logs and rollback procedure: set `app.chat.memory.enabled=false`, retain V3/V4 data, and do not run a down migration.

- [x] **Step 2: Run focused functional suites**

```bash
mvn -q -Dtest='io.github.imzmq.interview.chat.**,io.github.imzmq.interview.identity.**,FlywayDatabaseMigrationTest,LocalLiteConditionalConfigTest' test
```

Expected: PASS.

- [x] **Step 3: Run required architecture/build checks**

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q verify -DskipTests
```

Expected: all commands exit 0.

- [x] **Step 4: Run full unit regression**

```bash
mvn test
```

Expected: BUILD SUCCESS. Report the actual test/failure/error/skipped counts from this run; do not reuse counts from the earlier RAG work.

本轮结果：`mvn test` 为 BUILD SUCCESS；Tests run: 556，Failures: 0，Errors: 0，Skipped: 4。新增的第 4 个 skip 是未提供显式 Milvus/Embedding 配置时跳过的真实 Milvus 集成测试。

- [ ] **Step 5: Run external integration checks when infrastructure is available**

Run the temporary MySQL and uniquely named Milvus collection tests from Tasks 2 and 5. If credentials/services are unavailable, leave them explicitly unverified; unit mocks cannot be reported as proving MySQL locking/collation or Milvus business-status behavior.

本轮结果：临时 MySQL 空库 V1–V4、既有库 adoption 和消息/提取任务事务回滚已在真实 MySQL 8 上通过；真实 Milvus 测试资产已补齐并验证默认安全跳过，但当前 Docker 无 Milvus 且未提供真实 Embedding 凭证，因此 Milvus upsert/search/delete 仍未执行、未验证。

- [x] **Step 6: Inspect final diff and user-owned files**

```bash
git diff --check
git status --short --branch
git diff --stat
git diff -- AGENTS.md
```

Expected: no whitespace errors; `AGENTS.md` and `.DS_Store` remain untouched by this work. Review for secrets, generated assets, `target/`, logs and credentials before staging.

- [ ] **Step 7: Commit documentation only after checks reflect reality**

```bash
git add docs/development/local-startup.md docs/development/observability-guidelines.md PACKAGE_CONVENTIONS.md
git add -f docs/superpowers/specs/2026-10-26-chat-structured-memory-phase1-design.md docs/superpowers/plans/2026-10-26-chat-structured-memory-phase1.md
git commit -m "docs(chat): document structured memory operations"
```

Because `docs/` is currently ignored, run this commit step only after explicit user commit authorization. The `-f` scope is limited to the approved spec/plan files; never force-add unrelated ignored files.

---

## Final acceptance checklist

- [ ] Only new messages saved while enabled produce extraction tasks; no old-data import, backfill, delete or dual-write exists.
- [ ] User message + extraction task and item + source + outbox + DONE each have real transaction boundaries.
- [ ] Extraction accepts only exact quotes from active owned user messages.
- [ ] Workers use short MySQL 8 leases with token fencing; LLM/Milvus calls do not hold locks.
- [ ] MySQL is the truth source; every vector candidate is revalidated before context injection.
- [ ] Milvus projection IDs are immutable per memory version and stale/orphan rows are repairable.
- [ ] `local-lite` starts with memory disabled and no required Milvus memory bean.
- [ ] Chat stream/list/rename/delete and memory CRUD enforce verified current-user ownership.
- [ ] Session/memory deletion immediately blocks reads and eventually removes projections.
- [ ] Old `memory_text` writers/readers are inactive, while old rows and V1/V2 remain untouched.
- [ ] Architecture rules prevent SDK/DO leaks and cross-module persistence access.
- [ ] Documentation and verification report only commands actually run in the implementation session.
