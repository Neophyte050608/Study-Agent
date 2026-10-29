# Existing Chat Experience Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/chat` the only product entry for conversation, interview, and coding practice; guarantee stable message chronology; and remove overlap between assistant content, execution metadata, and actions.

**Architecture:** Three agents work in isolated branches with disjoint file ownership. Navigation cleanup owns menu sources, routing, and a forward-only Flyway migration; backend chronology owns only the chat query contract; frontend experience owns response normalization and `ChatView` rendering/scrolling. Integration happens only after each branch passes its focused checks.

**Tech Stack:** Spring Boot 3, MyBatis-Plus, Flyway/MySQL, JUnit 5/Mockito/AssertJ, Vue 3, Vite, Tailwind-style utility classes, Node built-in test runner.

---

## Parallel ownership map

| Agent | Branch | Exclusive write scope | Dependencies |
| --- | --- | --- | --- |
| A — navigation/menu cleanup | `feat/chat-entry-cleanup` | `frontend/src/router/index.js`, `frontend/src/shell/AppShell.vue`, menu service/tests, Flyway V8 and migration tests | None |
| B — backend chronology | `fix/chat-message-chronology` | `WebChatService.java`, `WebChatServiceTest.java` | None |
| C — frontend chat experience | `feat/chat-message-experience` | `ChatView.vue`, new `chatMessages.js`, new frontend test | Uses existing API fields `id`, `messageId`, `createdAt`; does not wait for B |
| Integration/review | main integration branch | Conflict resolution, broad tests, browser acceptance only | A, B, C complete |

Agents must not modify `AGENTS.md`, Docker files, startup docs, memory mapper files, generated `src/main/resources/static/spa`, `.dev`, `.DS_Store`, or any file outside their assigned scope. Each agent must commit only its owned files and report the commit hash and commands actually run.

## Task 1 (Agent A): Delete obsolete product entries at every source

**Files:**
- Modify: `frontend/src/router/index.js`
- Modify: `frontend/src/shell/AppShell.vue`
- Modify: `src/main/java/io/github/imzmq/interview/menu/application/MenuConfigService.java`
- Create: `src/test/java/io/github/imzmq/interview/menu/application/MenuConfigServiceTest.java`
- Create: `src/main/resources/db/migration/V8__consolidate_chat_menu.sql`
- Modify: `src/test/java/io/github/imzmq/interview/config/database/FlywayDatabaseMigrationTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/config/database/FlywayMySqlMigrationIntegrationTest.java`

- [ ] **Step 1: Add failing default-menu tests**

Create `MenuConfigServiceTest` with a mocked `MenuConfigMapper`. Cover both an empty database and a persisted menu whose path is blank:

```java
@Test
void defaultsExposeChatWithoutLegacyInterviewOrCodingEntries() {
    when(menuConfigMapper.selectList(any())).thenReturn(List.of());

    List<MenuConfig> menus = service.getAllMenus();

    assertThat(menus).extracting(MenuConfig::getId)
            .contains("CHAT")
            .doesNotContain("DASHBOARD", "CODING");
    assertThat(menus.stream().filter(menu -> "CHAT".equals(menu.getId())).findFirst().orElseThrow().getUrl())
            .isEqualTo("/chat");
}

@Test
void blankPersistedPathDefaultsToChat() {
    MenuConfigDO row = menu("CUSTOM", "");
    when(menuConfigMapper.selectList(any())).thenReturn(List.of(row));

    assertThat(service.getAllMenus().stream()
            .filter(menu -> "CUSTOM".equals(menu.getId()))
            .findFirst().orElseThrow().getUrl()).isEqualTo("/chat");
}
```

- [ ] **Step 2: Run the menu test and verify the legacy defaults fail it**

Run:

```bash
mvn -q -Dtest=MenuConfigServiceTest test
```

Expected: FAIL because `DASHBOARD` and `CODING` are still merged and blank paths normalize to `/interview`.

- [ ] **Step 3: Remove the legacy routes and SPA path aliases**

Delete route records for:

```text
/interview
/interview.html
/coding
/coding.html
/practice
/practice.html
```

Delete the matching cases from `AppShell.vue::toSpaPath`. Keep the generic unknown-route behavior; do not add redirects or compatibility pages.

Update the fallback menu to begin with:

```js
{ id: 'CHAT', title: 'AI 助手', icon: 'chat_bubble', url: '/chat' }
```

and remove `DASHBOARD` and `CODING` entirely.

- [ ] **Step 4: Make the backend default menu represent the final entry set**

In `MenuConfigService`:

```java
if (url == null || url.isBlank()) {
    return "/chat";
}
```

Delete the old interview/coding normalization cases. Remove the `DASHBOARD` and `CODING` defaults. Put `CHAT` first, followed by `NOTES`, `PROFILE`, and `MONITORING`; preserve existing extension entries without creating compatibility records.

- [ ] **Step 5: Add the forward-only menu migration**

Create `V8__consolidate_chat_menu.sql`:

```sql
DELETE FROM `t_menu_config`
WHERE `menu_code` IN ('DASHBOARD', 'CODING');

UPDATE `t_menu_config`
SET `sort_order` = CASE `menu_code`
    WHEN 'CHAT' THEN 1
    WHEN 'NOTES' THEN 2
    WHEN 'PROFILE' THEN 3
    WHEN 'MONITORING' THEN 4
    WHEN 'MODEL_PROVIDERS' THEN 5
    WHEN 'OPS' THEN 6
    WHEN 'RAG_DASHBOARD' THEN 7
    WHEN 'SETTINGS' THEN 8
    WHEN 'MCP' THEN 9
    WHEN 'INTENT_TREE' THEN 10
    WHEN 'WORKSPACE' THEN 11
    WHEN 'PROMPTS' THEN 12
    ELSE `sort_order`
END
WHERE `menu_code` IN (
    'CHAT', 'NOTES', 'PROFILE', 'MONITORING', 'MODEL_PROVIDERS', 'OPS',
    'RAG_DASHBOARD', 'SETTINGS', 'MCP', 'INTENT_TREE', 'WORKSPACE', 'PROMPTS'
);
```

Do not edit V1–V7.

- [ ] **Step 6: Extend migration tests for V8**

Read `db/migration/V8__consolidate_chat_menu.sql` in `FlywayDatabaseMigrationTest` and assert that it deletes only `DASHBOARD`/`CODING` and orders `CHAT` first. Update version/count assertions in `FlywayMySqlMigrationIntegrationTest` from 7 to 8 where they describe the complete migration set. Add SQL assertions that both legacy codes are absent and `CHAT.sort_order = 1` after migration.

- [ ] **Step 7: Run focused and frontend checks**

Run:

```bash
mvn -q -Dtest=MenuConfigServiceTest,FlywayDatabaseMigrationTest test
cd frontend && npm test && npm run build
```

If `RUN_MYSQL_INTEGRATION_TESTS=true` is already configured, also run:

```bash
mvn -q -Dtest=FlywayMySqlMigrationIntegrationTest test
```

Do not claim the MySQL integration test passed if it was skipped.

- [ ] **Step 8: Commit only Agent A files**

```bash
git add frontend/src/router/index.js \
  frontend/src/shell/AppShell.vue \
  src/main/java/io/github/imzmq/interview/menu/application/MenuConfigService.java \
  src/test/java/io/github/imzmq/interview/menu/application/MenuConfigServiceTest.java \
  src/main/resources/db/migration/V8__consolidate_chat_menu.sql \
  src/test/java/io/github/imzmq/interview/config/database/FlywayDatabaseMigrationTest.java \
  src/test/java/io/github/imzmq/interview/config/database/FlywayMySqlMigrationIntegrationTest.java
git commit -m "refactor(chat): remove obsolete product entries"
```

## Task 2 (Agent B): Enforce recent-page and stable chronology semantics

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/chat/application/WebChatService.java`
- Modify: `src/test/java/io/github/imzmq/interview/chat/application/WebChatServiceTest.java`

- [ ] **Step 1: Add failing chronology tests**

Add a helper that creates `ChatMessageDO` rows and tests that a mapper result returned newest-first is exposed oldest-first, including two rows with the same `createdAt`:

```java
@Test
void messagePageIsReturnedChronologicallyWithIdAsTieBreaker() {
    LocalDateTime sameSecond = LocalDateTime.of(2026, 10, 29, 20, 0);
    ChatMessageDO assistant = message(12L, "assistant", sameSecond);
    ChatMessageDO user = message(11L, "user", sameSecond);
    when(messageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(assistant, user));

    assertThat(service.listMessages("session-1", 50, null))
            .extracting(ChatMessageView::id)
            .containsExactly(11L, 12L);
}
```

Extend the existing page-size test to assert the persistence query uses descending IDs before `LIMIT 200`. Add a `beforeId` assertion proving `id < beforeId` remains in the query.

- [ ] **Step 2: Run the test and verify it fails on current ascending-query behavior**

Run:

```bash
mvn -q -Dtest=WebChatServiceTest test
```

Expected: FAIL because current code requests ascending rows and does not restore a deterministic `(createdAt, id)` public order.

- [ ] **Step 3: Query the most recent page, then restore public chronological order**

Build the MyBatis wrapper with session filter, optional `id < beforeId`, `orderByDesc(ChatMessageDO::getId)`, and capped limit. After selection, sort a copy using:

```java
Comparator.comparing(
        ChatMessageDO::getCreatedAt,
        Comparator.nullsLast(Comparator.naturalOrder()))
    .thenComparing(
        ChatMessageDO::getId,
        Comparator.nullsLast(Comparator.naturalOrder()))
```

Map the sorted rows to `ChatMessageView`. Do not mutate the mapper-owned list and do not change the controller/API signature.

- [ ] **Step 4: Run focused checks**

```bash
mvn -q -Dtest=WebChatServiceTest,ChatControllerOwnershipTest test
mvn -q -DskipTests test-compile
```

- [ ] **Step 5: Commit only Agent B files**

```bash
git add src/main/java/io/github/imzmq/interview/chat/application/WebChatService.java \
  src/test/java/io/github/imzmq/interview/chat/application/WebChatServiceTest.java
git commit -m "fix(chat): stabilize message chronology"
```

## Task 3 (Agent C): Normalize messages and repair the chat reading experience

**Files:**
- Create: `frontend/src/views/chat/chatMessages.js`
- Create: `frontend/test/chatMessages.test.js`
- Modify: `frontend/src/views/ChatView.vue`

- [ ] **Step 1: Write failing pure-function tests**

Create tests for:

1. same timestamp sorts by numeric `id`;
2. persisted duplicates sharing `messageId` collapse to one item;
3. `scenario_card`, `fill_card`, `interview_card`, `quiz`, and `rich` payloads still parse;
4. metadata fields such as `routeLabel` and `ragUsed` remain exposed;
5. render keys prefer `messageId`, then `clientKey`, then persisted `id` and never require the loop index.

Representative assertion:

```js
test('normalizes server messages in stable chronological order', () => {
  const result = normalizeChatMessages([
    { id: 12, messageId: 'a-12', role: 'assistant', createdAt: '2026-10-29T20:00:00', content: 'answer' },
    { id: 11, messageId: 'u-11', role: 'user', createdAt: '2026-10-29T20:00:00', content: 'question' }
  ])
  assert.deepEqual(result.map(message => message.id), [11, 12])
})
```

- [ ] **Step 2: Run the frontend tests and verify the missing module fails**

```bash
cd frontend && npm test
```

Expected: FAIL because `chatMessages.js` does not exist.

- [ ] **Step 3: Implement the pure message module**

Move image and structured-message normalization out of `ChatView.vue` into:

```js
export function normalizeChatMessages(rawMessages = []) { /* parse, dedupe, stable sort */ }
export function messageRenderKey(message) { /* messageId -> clientKey -> persisted id */ }
export function createClientMessage(fields) { /* attach crypto.randomUUID or deterministic fallback */ }
```

Sorting uses `createdAt ASC`, then numeric `id ASC`, then original source position. Only messages with a nonblank `messageId` participate in persisted deduplication. Parsing failure preserves the original message instead of dropping it.

- [ ] **Step 4: Integrate normalization and stable keys into ChatView**

Import the three helpers. Replace the local normalization functions. Change the loop key to:

```vue
:key="messageRenderKey(msg)"
```

Create every optimistic/error/stopped local message through `createClientMessage`, including the final streamed assistant message when the server omits `assistantMessageId`.

A refresh continues to replace local state with server state. Do not merge by message content and do not keep a second compatibility path.

- [ ] **Step 5: Separate assistant content, execution data, and actions**

Restrict `.markdown-body` to the rendered answer only. The assistant container must use normal-flow sections:

```vue
<div class="assistant-message-content min-w-0">
  <div class="markdown-body ..." v-html="renderMarkdown(msg.content)"></div>
  <!-- image and structured cards -->
</div>
<div class="assistant-execution-meta ...">...</div>
<div class="assistant-actions mt-4 pt-3 border-t ...">...</div>
```

Keep all current technical labels directly visible. Let labels and actions wrap. Add `min-w-0`, `overflow-hidden`, and scoped Markdown rules so long code blocks scroll inside the answer instead of expanding the card. Do not use absolute positioning for metadata or actions.

Apply the same content-width behavior to the streaming card, but do not show reaction actions before the final message exists.

- [ ] **Step 6: Make scrolling respect user intent**

Track whether the user is near the bottom using a threshold of about 80 px:

```js
const isNearMessagesBottom = () => {
  const el = messagesContainer.value
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight <= 80
}
```

Attach a passive scroll handler that updates `shouldFollowMessages`. `scrollToBottom({ force })` should scroll when forced or when the user was already near the bottom. Use forced scrolling for send, session load, and session switch; use conditional scrolling for streaming deltas, polling refreshes, and length changes. Remove the unconditional watcher that drags a user back to the bottom while reading history.

Ensure the chat column and message scroller use `min-h-0`, while the composer uses `shrink-0`; the composer must remain in flex flow and never cover the last message.

- [ ] **Step 7: Run frontend checks**

```bash
cd frontend && npm test && npm run build
```

- [ ] **Step 8: Commit only Agent C files**

```bash
git add frontend/src/views/chat/chatMessages.js \
  frontend/test/chatMessages.test.js \
  frontend/src/views/ChatView.vue
git commit -m "fix(chat): improve message flow and layout"
```

## Task 4 (Integration owner): Merge, review, and verify the complete behavior

**Files:**
- No planned production edits; only resolve genuine integration issues in the owning file and attribute them to the relevant task.

- [ ] **Step 1: Review each branch before integration**

For each branch, inspect:

```bash
git show --stat <commit>
git diff main...<branch> --check
git diff main...<branch> --name-only
```

Reject out-of-scope edits, generated assets, secrets, or changes to pre-existing local work.

- [ ] **Step 2: Integrate in dependency order**

Merge or cherry-pick A, then B, then C. Their declared write sets do not overlap. If an undeclared overlap appears, stop and review rather than accepting an automatic resolution blindly.

- [ ] **Step 3: Run the combined automated suite**

```bash
mvn -q -Dtest=MenuConfigServiceTest,FlywayDatabaseMigrationTest,WebChatServiceTest,ChatControllerOwnershipTest test
mvn -q -DskipTests test-compile
cd frontend && npm test && npm run build
```

If the environment provides MySQL integration opt-in, also run `FlywayMySqlMigrationIntegrationTest`. Do not set real model API keys; these checks do not require them.

- [ ] **Step 4: Restart the local app and perform browser acceptance**

Verify at minimum:

- sidebar has AI 助手 but not 面试控制台/算法刷题;
- old routes do not redirect to `/chat`;
- user message precedes its answer before and after refresh;
- long Markdown/code/structured cards never overlap technical labels or action buttons;
- `route`, `via`, `mode`, `local graph`, `rag`, and `fallback` remain directly visible when present;
- streaming follows only while near the bottom;
- the composer does not cover the last message at desktop and a narrow viewport.

- [ ] **Step 5: Final repository hygiene**

Run:

```bash
git diff --check
git status --short --branch
```

Do not stage existing Docker, startup-documentation, memory-mapper, `.dev`, or `.DS_Store` changes as part of this feature. Report every check actually run and any skipped integration check.
