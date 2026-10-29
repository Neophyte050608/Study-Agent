# Navigation and Admin Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the dynamic/duplicated navigation and administration experience with four stable product entries and one real administration center, while deleting obsolete routes, disconnected settings, duplicate pages, and their backend persistence.

**Architecture:** The frontend shell owns one static primary-navigation definition and Vue Router owns nested `/admin/**` routes. Administration stays split by the existing runtime owners—`modelrouting`, RAG/observability, and Agent tooling—without adding a backend facade; old views are moved into domain folders, decomposed into focused components/composables, and deleted as they are replaced. The backend removes the unused menu and Agent-config paths with a forward-only Flyway migration, and model candidate write/read contracts are separated so read responses cannot contain plaintext API keys.

**Tech Stack:** Vue 3, Vue Router 4, Vite, Node test runner, Spring Boot, MyBatis-Plus, Flyway, JUnit 5, Mockito, AssertJ, ArchUnit, MySQL, Redis.

---

## Scope and execution invariants

- Execute on `main`, but do not stage or rewrite unrelated existing work in `ChatView.vue`, chat cards, chat-memory SQL/tests, Docker/startup files, `.dev/`, or `frontend/src/utils/markdown.js` and `frontend/test/markdown.test.js`.
- The local edits currently present in `MenuConfigService.java` and `MenuConfigServiceTest.java` are superseded by the approved removal. Capture their diff before deleting those files so no provenance is lost.
- Do not add redirects for `/interview`, `/coding`, `/practice`, or any old management URL. The catch-all page may handle an unknown URL; it must not identify it as a supported compatibility route.
- Do not add a second model configuration source. Candidate data, health, routing, breaker, and degradation state remain owned by `modelrouting`.
- Do not add an administration aggregate API. Each frontend domain calls its existing backend owner directly.
- At each task, stage only the paths named in that task.

## Target file map

### Shell and routes

- `frontend/src/shell/navigation.js` — the only primary-navigation source; exports four immutable entries and active-state matching.
- `frontend/src/router/routes.js` — lazy-loaded route records testable with Node without booting a browser.
- `frontend/src/router/index.js` — creates the browser router from `routes.js`.
- `frontend/src/views/admin/AdminLayout.vue` — administration title, second-level navigation, and nested `RouterView`; no business requests.
- `frontend/src/views/admin/agents/AgentAdminLayout.vue` — prompts/intents/MCP third-level navigation and nested `RouterView`; no business requests.

### Model administration

- `frontend/src/views/admin/models/ModelsAdminView.vue` — page composition only.
- `frontend/src/views/admin/models/ModelCandidatesPanel.vue` — candidate CRUD, enable/disable, and probe UI.
- `frontend/src/views/admin/models/ModelRoutingHealthPanel.vue` — runtime health, topology, breaker, fallback, and logs.
- `frontend/src/views/admin/models/useModelsAdmin.js` — candidate and routing-state loading/actions.
- `frontend/src/views/admin/models/modelPresentation.js` — pure display/state mapping.
- `frontend/src/api/modelAdmin.js` — model-routing HTTP contracts.

### RAG administration

- `frontend/src/views/admin/rag/RagAdminView.vue` — page composition and section selection only.
- `frontend/src/views/admin/rag/RagOverviewPanel.vue` — current summary, history, alerts, and snapshot action.
- `frontend/src/views/admin/rag/RagTracePanel.vue` — trace filters/list.
- `frontend/src/views/admin/rag/RagTraceDetailView.vue` — trace detail under the admin route.
- `frontend/src/views/admin/rag/RagEvaluationPanel.vue` — retrieval and generation quality evaluation.
- `frontend/src/views/admin/rag/RagAuditPanel.vue` — skill telemetry, audits, idempotency, and recovery actions.
- `frontend/src/views/admin/rag/useRagOverview.js`, `useRagTraces.js`, `useRagEvaluation.js`, `useRagAudit.js` — isolated request/state ownership.
- `frontend/src/views/admin/rag/ragPresentation.js` — pure query and presentation mapping.
- `frontend/src/api/ragAdmin.js` — observability/evaluation/operations HTTP contracts.

### Agent administration

- `frontend/src/views/admin/agents/prompts/PromptsAdminView.vue` — prompt browse/edit/preview/cache refresh.
- `frontend/src/views/admin/agents/intents/IntentsAdminView.vue` — tree/list mode and shared intent state.
- `frontend/src/views/admin/agents/intents/IntentTreePanel.vue` — tree visualization/actions.
- `frontend/src/views/admin/agents/intents/IntentListPanel.vue` — filters, pagination, bulk actions.
- `frontend/src/views/admin/agents/intents/IntentEditAdminView.vue` — create/edit at `/admin/agents/intents/:index/edit`.
- `frontend/src/views/admin/agents/mcp/McpAdminView.vue` — capability discovery and controlled invocation.
- `frontend/src/api/promptAdmin.js`, `intentAdmin.js`, `mcpAdmin.js` — domain-specific HTTP contracts.

---

### Task 1: Lock the static navigation and route contract with failing tests

**Files:**
- Create: `frontend/test/navigation.test.js`
- Create: `frontend/test/adminRoutes.test.js`
- Create: `frontend/src/shell/navigation.js`
- Create: `frontend/src/router/routes.js`
- Modify: `frontend/src/router/index.js`

- [ ] **Step 1: Write the failing navigation test**

Create `frontend/test/navigation.test.js`:

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { isPrimaryNavigationActive, primaryNavigation } from '../src/shell/navigation.js'

test('primary navigation contains exactly the four stable product entries', () => {
  assert.deepEqual(primaryNavigation.map(({ id, title, path }) => ({ id, title, path })), [
    { id: 'CHAT', title: 'AI 助手', path: '/chat' },
    { id: 'KNOWLEDGE', title: '知识库', path: '/notes' },
    { id: 'PROFILE', title: '能力画像', path: '/profile' },
    { id: 'ADMIN', title: '管理中心', path: '/admin' }
  ])
})

test('all admin descendants activate only the administration entry', () => {
  assert.equal(isPrimaryNavigationActive('ADMIN', '/admin/models'), true)
  assert.equal(isPrimaryNavigationActive('ADMIN', '/admin/agents/intents/2/edit'), true)
  assert.equal(isPrimaryNavigationActive('CHAT', '/admin/rag'), false)
})
```

- [ ] **Step 2: Write the failing route-manifest test**

Create `frontend/test/adminRoutes.test.js` with a recursive path joiner so nested records are tested as full paths:

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { routes } from '../src/router/routes.js'

function collectPaths(records, parent = '') {
  return records.flatMap(route => {
    const path = route.path.startsWith('/')
      ? route.path
      : `${parent.replace(/\/$/, '')}/${route.path}`.replace(/\/+/g, '/')
    return [path, ...collectPaths(route.children || [], path)]
  })
}

test('router exposes current product and nested admin paths', () => {
  const paths = collectPaths(routes)
  for (const path of [
    '/chat', '/notes', '/profile', '/admin', '/admin/models', '/admin/rag',
    '/admin/rag/traces/:traceId', '/admin/agents/prompts',
    '/admin/agents/intents', '/admin/agents/intents/:index/edit', '/admin/agents/mcp'
  ]) assert.ok(paths.includes(path), `missing ${path}`)
})

test('obsolete product and management routes are not registered', () => {
  const paths = collectPaths(routes)
  for (const path of [
    '/interview', '/coding', '/practice', '/monitoring', '/model-providers', '/ops',
    '/rag-dashboard', '/settings', '/workspace', '/mcp', '/intent-tree',
    '/intent-list', '/prompts'
  ]) assert.equal(paths.includes(path), false, `obsolete route ${path}`)
})
```

- [ ] **Step 3: Run the tests and verify the failure is structural**

Run:

```bash
cd frontend && node --test test/navigation.test.js test/adminRoutes.test.js
```

Expected: FAIL because `navigation.js` and `routes.js` do not exist.

- [ ] **Step 4: Add the static navigation contract**

Create `frontend/src/shell/navigation.js`:

```js
export const primaryNavigation = Object.freeze([
  Object.freeze({ id: 'CHAT', title: 'AI 助手', icon: 'chat_bubble', path: '/chat' }),
  Object.freeze({ id: 'KNOWLEDGE', title: '知识库', icon: 'description', path: '/notes' }),
  Object.freeze({ id: 'PROFILE', title: '能力画像', icon: 'analytics', path: '/profile' }),
  Object.freeze({ id: 'ADMIN', title: '管理中心', icon: 'admin_panel_settings', path: '/admin' })
])

export function isPrimaryNavigationActive(id, currentPath) {
  const path = String(currentPath || '/').replace(/\/$/, '') || '/'
  if (id === 'ADMIN') return path === '/admin' || path.startsWith('/admin/')
  return primaryNavigation.some(item => item.id === id && item.path === path)
}
```

- [ ] **Step 5: Define lazy nested route records and keep browser creation separate**

Create `frontend/src/router/routes.js` with lazy imports and exactly these records:

```js
export const routes = [{
  path: '/',
  component: () => import('../shell/AppShell.vue'),
  children: [
    { path: '', redirect: '/chat' },
    { path: 'chat', name: 'chat', component: () => import('../views/ChatView.vue') },
    { path: 'notes', name: 'notes', component: () => import('../views/NotesView.vue') },
    { path: 'profile', name: 'profile', component: () => import('../views/ProfileView.vue') },
    { path: 'learning/plan-preview', name: 'learning-plan-preview', component: () => import('../views/LearningPlanPreviewView.vue') },
    {
      path: 'admin',
      component: () => import('../views/admin/AdminLayout.vue'),
      redirect: '/admin/models',
      children: [
        { path: 'models', name: 'admin-models', component: () => import('../views/admin/models/ModelsAdminView.vue') },
        { path: 'rag', name: 'admin-rag', component: () => import('../views/admin/rag/RagAdminView.vue') },
        { path: 'rag/traces/:traceId', name: 'admin-rag-trace', component: () => import('../views/admin/rag/RagTraceDetailView.vue') },
        {
          path: 'agents',
          component: () => import('../views/admin/agents/AgentAdminLayout.vue'),
          redirect: '/admin/agents/prompts',
          children: [
            { path: 'prompts', name: 'admin-agent-prompts', component: () => import('../views/admin/agents/prompts/PromptsAdminView.vue') },
            { path: 'intents', name: 'admin-agent-intents', component: () => import('../views/admin/agents/intents/IntentsAdminView.vue') },
            { path: 'intents/:index/edit', name: 'admin-agent-intent-edit', component: () => import('../views/admin/agents/intents/IntentEditAdminView.vue') },
            { path: 'mcp', name: 'admin-agent-mcp', component: () => import('../views/admin/agents/mcp/McpAdminView.vue') }
          ]
        }
      ]
    },
    { path: ':pathMatch(.*)*', name: 'placeholder', component: () => import('../views/PlaceholderView.vue') }
  ]
}]
```

Modify `frontend/src/router/index.js` to import `routes` and create only the web-history router:

```js
import { createRouter, createWebHistory } from 'vue-router'
import { routes } from './routes.js'

export default createRouter({ history: createWebHistory('/'), routes })
```

- [ ] **Step 6: Run the focused tests**

Run:

```bash
cd frontend && node --test test/navigation.test.js test/adminRoutes.test.js
```

Expected: PASS. Vue files may not exist yet because lazy imports are not evaluated by these manifest tests.

- [ ] **Step 7: Commit the contract**

```bash
git add frontend/src/shell/navigation.js frontend/src/router/routes.js frontend/src/router/index.js \
  frontend/test/navigation.test.js frontend/test/adminRoutes.test.js
git commit -m "test(ui): define navigation and admin route contract"
```

### Task 2: Atomically move live administration capabilities under the new shell

**Files:**
- Create: `frontend/src/views/admin/AdminLayout.vue`
- Create: `frontend/src/views/admin/agents/AgentAdminLayout.vue`
- Create: `frontend/src/views/admin/models/ModelsAdminView.vue`
- Move: `frontend/src/views/ModelProvidersView.vue` → `frontend/src/views/admin/models/ModelCandidatesPanel.vue`
- Move: `frontend/src/views/MonitoringView.vue` → `frontend/src/views/admin/models/ModelRoutingHealthPanel.vue`
- Move: `frontend/src/views/OpsView.vue` → `frontend/src/views/admin/rag/RagAdminView.vue`
- Move: `frontend/src/views/RagDashboardView.vue` → `frontend/src/views/admin/rag/RagOverviewPanel.vue`
- Move: `frontend/src/views/RagTraceDetailView.vue` → `frontend/src/views/admin/rag/RagTraceDetailView.vue`
- Move: `frontend/src/views/PromptsView.vue` → `frontend/src/views/admin/agents/prompts/PromptsAdminView.vue`
- Move: `frontend/src/views/IntentTreeView.vue` → `frontend/src/views/admin/agents/intents/IntentTreePanel.vue`
- Move: `frontend/src/views/IntentListView.vue` → `frontend/src/views/admin/agents/intents/IntentListPanel.vue`
- Move: `frontend/src/views/IntentEditView.vue` → `frontend/src/views/admin/agents/intents/IntentEditAdminView.vue`
- Move: `frontend/src/views/McpView.vue` → `frontend/src/views/admin/agents/mcp/McpAdminView.vue`
- Create: `frontend/src/views/admin/agents/intents/IntentsAdminView.vue`
- Modify: moved files' relative imports and internal links
- Modify: `frontend/src/shell/AppShell.vue`
- Delete: `frontend/src/api/menu.js`
- Delete: `frontend/src/views/SettingsView.vue`
- Delete: `frontend/src/views/WorkspaceView.vue`

- [ ] **Step 1: Create shell components that own navigation only**

`AdminLayout.vue` must define the three second-level links and pass the sidebar-collapse prop through nested views:

```vue
<script setup>
defineProps({ sidebarCollapsed: Boolean })
const sections = [
  { title: '模型与路由', to: '/admin/models' },
  { title: 'RAG 运行与评测', to: '/admin/rag' },
  { title: 'Agent 工具与配置', to: '/admin/agents/prompts' }
]
</script>

<template>
  <div class="min-h-screen bg-surface" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
    <header class="border-b border-divider bg-surface-overlay px-8 py-5">
      <h1 class="text-xl font-bold text-brand-text">管理中心</h1>
      <nav class="mt-4 flex gap-2 overflow-x-auto" aria-label="管理中心">
        <RouterLink v-for="item in sections" :key="item.to" :to="item.to" class="rounded-lg px-4 py-2 text-sm font-bold">
          {{ item.title }}
        </RouterLink>
      </nav>
    </header>
    <RouterView v-slot="{ Component }">
      <component :is="Component" :sidebar-collapsed="sidebarCollapsed" />
    </RouterView>
  </div>
</template>
```

`AgentAdminLayout.vue` uses the same pattern with `/admin/agents/prompts`, `/admin/agents/intents`, and `/admin/agents/mcp`; it contains no `onMounted`, API import, or business state.

- [ ] **Step 2: Move old pages into real domain ownership**

Use `mkdir -p` plus `git mv` for every move listed above. In each moved component:

1. Remove its fixed global header and its `ml-64/ml-20` application-shell offset.
2. Keep its real forms, tables, loading/error states, and actions.
3. Fix relative API/composable imports (for example `../../../api/admin` from models/rag and `../../../../api/admin` from nested Agent pages).
4. Replace trace return links with `/admin/rag` and detail links with `/admin/rag/traces/${traceId}`.
5. Replace all intent links with `/admin/agents/intents` and `/admin/agents/intents/${index}/edit`.
6. Do not retain aliases or links to old top-level paths.

- [ ] **Step 3: Compose models and intents without retaining old page shells**

Create `ModelsAdminView.vue` as a real domain page:

```vue
<script setup>
import ModelCandidatesPanel from './ModelCandidatesPanel.vue'
import ModelRoutingHealthPanel from './ModelRoutingHealthPanel.vue'
</script>

<template>
  <main class="space-y-8 px-8 py-8">
    <section><h2 class="text-2xl font-extrabold">模型与路由</h2><p class="text-content-secondary">管理运行时候选模型并查看路由健康。</p></section>
    <ModelCandidatesPanel />
    <ModelRoutingHealthPanel />
  </main>
</template>
```

Create `IntentsAdminView.vue` with a local `mode` ref (`tree` or `list`) and render exactly one of `IntentTreePanel`/`IntentListPanel`; strip duplicated page headers from both panels. This is not a compatibility wrapper: the parent owns the single intent-domain title/mode selection and each panel owns only its visualization.

- [ ] **Step 4: Integrate the RAG overview as a domain panel**

Import `RagOverviewPanel` into the moved `RagAdminView.vue`, place it once above the trace/evaluation/audit sections, and remove `RagOverviewPanel`'s former global header and page margins. Do not duplicate its dashboard/history requests elsewhere during this task; Task 6 will establish unique request ownership.

- [ ] **Step 5: Switch AppShell to the static truth source**

Replace all menu API state, URL normalization, fallback arrays, workspace footer link, menu event listeners, and console logging in `AppShell.vue` with:

```js
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { isPrimaryNavigationActive, primaryNavigation } from './navigation.js'

const route = useRoute()
const menus = primaryNavigation
const sidebarCollapsed = ref(false)
const activeId = computed(() => menus.find(item => isPrimaryNavigationActive(item.id, route.path))?.id)
```

Render `menu.path`, and compare `activeId === menu.id`. Keep the theme and collapse controls. Delete `frontend/src/api/menu.js`, `SettingsView.vue`, and `WorkspaceView.vue`.

- [ ] **Step 6: Run focused and full frontend checks**

Run:

```bash
cd frontend && node --test test/navigation.test.js test/adminRoutes.test.js
cd frontend && npm test
cd frontend && npm run build
```

Expected: all tests PASS and Vite builds without unresolved moved imports. Searching built/source route records must show no old route registration.

- [ ] **Step 7: Commit the atomic frontend cutover**

Stage only the shell, router, moved/deleted views, and their exact imports:

```bash
git add frontend/src/shell/AppShell.vue frontend/src/views/admin frontend/src/views/ModelProvidersView.vue \
  frontend/src/views/MonitoringView.vue frontend/src/views/OpsView.vue frontend/src/views/RagDashboardView.vue \
  frontend/src/views/RagTraceDetailView.vue frontend/src/views/PromptsView.vue frontend/src/views/IntentTreeView.vue \
  frontend/src/views/IntentListView.vue frontend/src/views/IntentEditView.vue frontend/src/views/McpView.vue \
  frontend/src/views/SettingsView.vue frontend/src/views/WorkspaceView.vue frontend/src/api/menu.js
git commit -m "refactor(ui): consolidate navigation and admin routes"
```

### Task 3: Delete dynamic menu and disconnected Agent configuration backends

**Files:**
- Delete: `src/main/java/io/github/imzmq/interview/menu/api/MenuController.java`
- Delete: `src/main/java/io/github/imzmq/interview/menu/application/MenuConfigService.java`
- Delete: `src/main/java/io/github/imzmq/interview/menu/infrastructure/persistence/MenuConfigDO.java`
- Delete: `src/main/java/io/github/imzmq/interview/menu/infrastructure/persistence/MenuConfigMapper.java`
- Delete: `src/main/java/io/github/imzmq/interview/config/menu/MenuConfig.java`
- Delete: `src/main/java/io/github/imzmq/interview/agent/api/AgentConfigController.java`
- Delete: `src/main/java/io/github/imzmq/interview/agent/application/AgentConfigService.java`
- Delete: `src/main/java/io/github/imzmq/interview/agent/infrastructure/persistence/AgentConfigDO.java`
- Delete: `src/main/java/io/github/imzmq/interview/agent/infrastructure/persistence/AgentConfigMapper.java`
- Delete: `src/main/java/io/github/imzmq/interview/config/agent/AgentConfig.java`
- Modify: `src/main/java/io/github/imzmq/interview/modelrouting/registry/ModelBeanResolver.java`
- Delete: `src/test/java/io/github/imzmq/interview/menu/application/MenuConfigServiceTest.java`
- Create: `src/main/resources/db/migration/V9__drop_obsolete_navigation_and_agent_config.sql`
- Modify: `src/test/java/io/github/imzmq/interview/config/database/FlywayDatabaseMigrationTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/config/database/FlywayMySqlMigrationIntegrationTest.java`
- Modify: `frontend/src/api/admin.js`

- [ ] **Step 1: Preserve the superseded local menu diff outside Git**

```bash
mkdir -p .dev/patches
git diff -- src/main/java/io/github/imzmq/interview/menu/application/MenuConfigService.java \
  src/test/java/io/github/imzmq/interview/menu/application/MenuConfigServiceTest.java \
  > .dev/patches/pre-navigation-consolidation-menu.patch
```

Expected: the ignored `.dev/patches` file records the prior partial workaround; it is not staged.

- [ ] **Step 2: Write failing Flyway assertions for final removal**

Add to `FlywayDatabaseMigrationTest`:

```java
@Test
void removesObsoleteNavigationAndAgentConfigTablesOnly() throws IOException {
    String migration = readResource("db/migration/V9__drop_obsolete_navigation_and_agent_config.sql");
    assertThat(migration)
            .contains("DROP TABLE IF EXISTS `t_menu_config`")
            .contains("DROP TABLE IF EXISTS `t_agent_config`")
            .doesNotContain("t_chat_", "t_interview_", "t_question", "t_prompt_template");
}
```

Update both final-state branches in `FlywayMySqlMigrationIntegrationTest` to expect migration/history count `9`, and replace queries against `t_menu_config` with `information_schema.tables` assertions that both obsolete table names have count `0`.

- [ ] **Step 3: Run the migration unit test and verify it fails**

Run:

```bash
mvn -q -Dtest=FlywayDatabaseMigrationTest test
```

Expected: FAIL because V9 does not exist.

- [ ] **Step 4: Add the forward-only cleanup migration**

Create `V9__drop_obsolete_navigation_and_agent_config.sql`:

```sql
DROP TABLE IF EXISTS `t_menu_config`;
DROP TABLE IF EXISTS `t_agent_config`;
```

Do not edit V1–V8.

- [ ] **Step 5: Remove the dead runtime code and API functions**

Delete all files listed above. In `ModelBeanResolver`, remove only the unused `AgentConfig` import and `resolveForAgent(AgentConfig)` overload; retain `resolveByRoutingCandidate`, `defaultBeanName`, and provider resolution. In `frontend/src/api/admin.js`, delete only `loadAgentSettings`, `saveAgentSettings`, `loadMenuSettings`, and `saveMenuLayout`.

Verify no production reference remains:

```bash
grep -RIn --exclude-dir=target --exclude-dir=node_modules -E \
  'AgentConfigService|MenuConfigService|/api/settings/agents|/api/settings/menu|t_agent_config|t_menu_config' \
  src/main/java frontend/src
```

Expected: no output. Historical Flyway V1/V2/V8 and their migration tests may still mention the old tables by design.

- [ ] **Step 6: Run backend checks**

```bash
mvn -q -Dtest=FlywayDatabaseMigrationTest test
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: PASS. If an isolated MySQL URL is available, also run `FlywayMySqlMigrationIntegrationTest`; otherwise record that its database branches were not executed.

- [ ] **Step 7: Commit backend removal and migration**

```bash
git add src/main/java/io/github/imzmq/interview/menu src/main/java/io/github/imzmq/interview/config/menu \
  src/main/java/io/github/imzmq/interview/agent/api/AgentConfigController.java \
  src/main/java/io/github/imzmq/interview/agent/application/AgentConfigService.java \
  src/main/java/io/github/imzmq/interview/agent/infrastructure/persistence/AgentConfigDO.java \
  src/main/java/io/github/imzmq/interview/agent/infrastructure/persistence/AgentConfigMapper.java \
  src/main/java/io/github/imzmq/interview/config/agent/AgentConfig.java \
  src/main/java/io/github/imzmq/interview/modelrouting/registry/ModelBeanResolver.java \
  src/test/java/io/github/imzmq/interview/menu src/main/resources/db/migration/V9__drop_obsolete_navigation_and_agent_config.sql \
  src/test/java/io/github/imzmq/interview/config/database/FlywayDatabaseMigrationTest.java \
  src/test/java/io/github/imzmq/interview/config/database/FlywayMySqlMigrationIntegrationTest.java \
  frontend/src/api/admin.js
git commit -m "refactor(config): remove dynamic menu and agent settings"
```

### Task 4: Consolidate model administration and make API-key reads structurally safe

**Files:**
- Create: `frontend/src/api/modelAdmin.js`
- Create: `frontend/src/views/admin/models/useModelsAdmin.js`
- Create: `frontend/src/views/admin/models/modelPresentation.js`
- Create: `frontend/test/modelAdminPresentation.test.js`
- Modify: `frontend/src/views/admin/models/ModelsAdminView.vue`
- Modify: `frontend/src/views/admin/models/ModelCandidatesPanel.vue`
- Modify: `frontend/src/views/admin/models/ModelRoutingHealthPanel.vue`
- Modify: `frontend/src/api/admin.js`
- Delete: `frontend/src/api/monitoring.js`
- Create: `src/main/java/io/github/imzmq/interview/modelrouting/api/ModelCandidateWriteRequest.java`
- Rename/replace: `src/main/java/io/github/imzmq/interview/modelrouting/api/ModelCandidateDTO.java` → `ModelCandidateResponse.java`
- Modify: `src/main/java/io/github/imzmq/interview/modelrouting/api/ModelRoutingController.java`
- Modify: `src/main/java/io/github/imzmq/interview/modelrouting/catalog/ModelCandidateService.java`
- Modify: `src/test/java/io/github/imzmq/interview/controller/ModelRoutingControllerTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/modelrouting/ModelCandidateServiceTest.java`

- [ ] **Step 1: Write failing backend tests for separate write/read contracts**

Update controller/service tests to require:

```java
assertThat(ModelCandidateResponse.class.getDeclaredFields())
        .extracting(Field::getName)
        .doesNotContain("apiKey");
```

Add update semantics tests:

```java
@Test
void blankKeyPreservesExistingSecretAndExplicitClearRemovesIt() {
    // existing entity has encrypted key; blank request leaves it unchanged
    // a second request with clearApiKey=true sets apiKeyEncrypted to null
}
```

Keep the existing explicit, configuration-gated `copyKey` test because it is a privileged POST action, not an ordinary read. Ordinary GET/list response fields must contain masked status only; `apiKeyCopyAllowed` may advertise whether the explicit action is enabled.

- [ ] **Step 2: Run focused backend tests to verify failure**

```bash
mvn -q -Dtest=ModelRoutingControllerTest,ModelCandidateServiceTest test
```

Expected: FAIL because the DTO used for ordinary reads still exposes the writable `apiKey` field and update has no explicit-clear command.

- [ ] **Step 3: Implement the safe model contract**

`ModelCandidateWriteRequest` contains writable candidate fields plus:

```java
private String apiKey;       // null/blank on update means preserve
private boolean clearApiKey; // explicit destructive action
```

`ModelCandidateResponse` contains candidate fields plus only these key-related fields:

```java
private String apiKeyMasked;
private Boolean apiKeyConfigured;
private Boolean apiKeyReadable;
private Boolean apiKeyCopyAllowed;
```

Change create/update service methods to accept `ModelCandidateWriteRequest`; on update evaluate `clearApiKey` first, otherwise replace only for nonblank `apiKey`. Keep encryption at rest, probe, toggle, masked ordinary reads, and the existing explicit `POST /candidates/{id}/copy-key` gate controlled by `app.security.allow-plaintext-key-export`. The explicit export response remains separate from `ModelCandidateResponse`.

- [ ] **Step 4: Write the failing frontend presentation test**

Create `modelAdminPresentation.test.js`:

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { apiKeyState, routingHealthTone } from '../src/views/admin/models/modelPresentation.js'

test('candidate key state never requires plaintext', () => {
  assert.deepEqual(apiKeyState({ apiKeyConfigured: true, apiKeyReadable: true, apiKeyMasked: 'sk-a****z' }), {
    label: '已配置', detail: 'sk-a****z', canReplace: true
  })
  assert.equal(apiKeyState({ apiKeyConfigured: false }).label, '未配置')
})

test('routing health maps breaker state consistently', () => {
  assert.equal(routingHealthTone('CLOSED'), 'healthy')
  assert.equal(routingHealthTone('OPEN'), 'danger')
  assert.equal(routingHealthTone('HALF_OPEN'), 'warning')
})
```

Run it and expect module-not-found failure.

- [ ] **Step 5: Consolidate frontend state and remove duplicate requests**

Implement the pure functions above, create `modelAdmin.js` from the model functions currently in `admin.js`/`monitoring.js`, and create `useModelsAdmin.js` with one `reload()` that calls `loadModelCandidates()` and `loadModelRoutingStats()` once via `Promise.all`. Candidate actions update/reload this shared state. Both panels receive state/actions through props; neither independently polls the same endpoint.

The edit form displays masked/configured status, uses an empty key field as “keep existing,” and provides a deliberate `clearApiKey` checkbox with confirmation text. Keep the copy action visible only when `apiKeyCopyAllowed === true`; otherwise show no plaintext control. Keep probe/toggle/create/update/delete and one runtime health presentation owner.

- [ ] **Step 6: Run focused and full checks**

```bash
mvn -q -Dtest=ModelRoutingControllerTest,ModelCandidateServiceTest test
cd frontend && node --test test/modelAdminPresentation.test.js
cd frontend && npm test && npm run build
```

Expected: PASS; ordinary `GET /api/model-routing/candidates` response types have no exact `apiKey` field, while the separately gated copy action remains explicit.

- [ ] **Step 7: Commit model consolidation**

```bash
git add frontend/src/api/modelAdmin.js frontend/src/api/admin.js frontend/src/api/monitoring.js \
  frontend/src/views/admin/models frontend/test/modelAdminPresentation.test.js \
  src/main/java/io/github/imzmq/interview/modelrouting/api \
  src/main/java/io/github/imzmq/interview/modelrouting/catalog/ModelCandidateService.java \
  src/test/java/io/github/imzmq/interview/controller/ModelRoutingControllerTest.java \
  src/test/java/io/github/imzmq/interview/modelrouting/ModelCandidateServiceTest.java
git commit -m "refactor(modelrouting): consolidate admin and protect keys"
```

### Task 5: Establish pure RAG presentation contracts before splitting the large page

**Files:**
- Create: `frontend/src/views/admin/rag/ragPresentation.js`
- Create: `frontend/test/ragAdminPresentation.test.js`
- Create: `frontend/src/api/ragAdmin.js`
- Modify: `frontend/src/api/admin.js`

- [ ] **Step 1: Write failing tests for the four RAG responsibilities**

Create `ragAdminPresentation.test.js`:

```js
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildTraceQuery, overviewCards, normalizeEvaluationRuns, auditSummary } from '../src/views/admin/rag/ragPresentation.js'

test('overview has one stable metric owner', () => {
  assert.deepEqual(overviewCards({ currentHour: { traceCount: 3, successRate: '90%', avgLatencyMs: 120 } })
    .map(card => card.key), ['traceCount', 'successRate', 'avgLatencyMs', 'p95LatencyMs', 'fallbackRate', 'satisfactionRate'])
})

test('trace filters map only active values', () => {
  assert.deepEqual(buildTraceQuery({ search: 'rag-1', status: 'ALL', risky: true, slow: false }), {
    search: 'rag-1', risky: true
  })
})

test('evaluation and audit mappings tolerate empty payloads', () => {
  assert.deepEqual(normalizeEvaluationRuns(undefined), [])
  assert.deepEqual(auditSummary({}, []), { idempotencyTotal: 0, auditCount: 0 })
})
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
cd frontend && node --test test/ragAdminPresentation.test.js
```

Expected: FAIL because `ragPresentation.js` does not exist.

- [ ] **Step 3: Implement pure mappings and isolate the HTTP API**

Implement the four named exports with null-safe data mapping. Move all observability, trace, idempotency, audit, skill telemetry, retrieval evaluation, generation-quality evaluation, dashboard/history, and snapshot HTTP functions from `admin.js` into `ragAdmin.js`. Keep paths/methods unchanged. Remove those exports from `admin.js` after all consumers move in Task 6.

- [ ] **Step 4: Run the focused test**

```bash
cd frontend && node --test test/ragAdminPresentation.test.js
```

Expected: PASS.

- [ ] **Step 5: Commit the RAG contracts**

```bash
git add frontend/src/views/admin/rag/ragPresentation.js frontend/test/ragAdminPresentation.test.js \
  frontend/src/api/ragAdmin.js frontend/src/api/admin.js
git commit -m "refactor(rag): define admin presentation contracts"
```

### Task 6: Split and deduplicate RAG overview, traces, evaluation, and audit

**Files:**
- Modify: `frontend/src/views/admin/rag/RagAdminView.vue`
- Modify: `frontend/src/views/admin/rag/RagOverviewPanel.vue`
- Modify: `frontend/src/views/admin/rag/RagTraceDetailView.vue`
- Create: `frontend/src/views/admin/rag/RagTracePanel.vue`
- Create: `frontend/src/views/admin/rag/RagEvaluationPanel.vue`
- Create: `frontend/src/views/admin/rag/RagAuditPanel.vue`
- Create: `frontend/src/views/admin/rag/useRagOverview.js`
- Create: `frontend/src/views/admin/rag/useRagTraces.js`
- Create: `frontend/src/views/admin/rag/useRagEvaluation.js`
- Create: `frontend/src/views/admin/rag/useRagAudit.js`

- [ ] **Step 1: Extract request ownership into four composables**

Each composable accepts an injectable API object for testing and owns only its domain:

```js
export function useRagOverview(api = ragAdminApi) // dashboard, history, snapshot
export function useRagTraces(api = ragAdminApi)   // filters, list, selected trace navigation
export function useRagEvaluation(api = ragAdminApi) // datasets, retrieval/quality runs/details
export function useRagAudit(api = ragAdminApi)    // telemetry, audits, idempotency, clear/replay
```

Return `loading`, `error`, domain state, and named actions. Never make the same HTTP request from two composables.

- [ ] **Step 2: Extract panels from the 1,610-line page**

Move existing template/script sections—not copies—into their owner panels:

- Overview: six summary cards, history chart, alerts, feedback, snapshot.
- Traces: filter form and trace table; detail remains a dedicated route.
- Evaluation: datasets, retrieval metrics/runs, quality engine/runs/details.
- Audit: skill telemetry, recent audit events, idempotency, clear cache, DLQ replay.

`RagAdminView.vue` becomes composition only:

```vue
<template>
  <main class="space-y-8 px-8 py-8">
    <header><h2 class="text-2xl font-extrabold">RAG 运行与评测</h2></header>
    <RagOverviewPanel />
    <RagTracePanel />
    <RagEvaluationPanel />
    <RagAuditPanel />
  </main>
</template>
```

Remove the old duplicated overview cards/requests from `RagAdminView.vue`; `RagOverviewPanel` is the only dashboard/history owner. Remove dead refs, chart instances, and styles after extraction rather than leaving commented blocks.

- [ ] **Step 3: Update trace navigation and error states**

All trace-detail links use named route `admin-rag-trace`; all back links target `/admin/rag`. Every panel renders a visible error message and retry action from its composable instead of logging-only failures or blank data.

- [ ] **Step 4: Run RAG and full frontend checks**

```bash
cd frontend && node --test test/ragAdminPresentation.test.js test/adminRoutes.test.js
cd frontend && npm test
cd frontend && npm run build
```

Expected: PASS. `RagAdminView.vue` should be a small composition page; there must be only one call site owner each for `loadRagDashboard`, `loadMetricsHistory`, `loadOpsTraces`, and evaluation history loading.

- [ ] **Step 5: Commit RAG consolidation**

```bash
git add frontend/src/views/admin/rag frontend/src/api/ragAdmin.js frontend/src/api/admin.js \
  frontend/test/ragAdminPresentation.test.js frontend/test/adminRoutes.test.js
git commit -m "refactor(rag): split operations and evaluation admin"
```

### Task 7: Standardize Agent prompt, intent, and MCP administration

**Files:**
- Create: `frontend/src/api/promptAdmin.js`
- Create: `frontend/src/api/intentAdmin.js`
- Create: `frontend/src/api/mcpAdmin.js`
- Modify: `frontend/src/api/intentTreeAdmin.js`
- Modify: `frontend/src/api/admin.js`
- Modify: `frontend/src/views/admin/agents/AgentAdminLayout.vue`
- Modify: `frontend/src/views/admin/agents/prompts/PromptsAdminView.vue`
- Modify: `frontend/src/views/admin/agents/intents/IntentsAdminView.vue`
- Modify: `frontend/src/views/admin/agents/intents/IntentTreePanel.vue`
- Modify: `frontend/src/views/admin/agents/intents/IntentListPanel.vue`
- Modify: `frontend/src/views/admin/agents/intents/IntentEditAdminView.vue`
- Modify: `frontend/src/views/admin/agents/mcp/McpAdminView.vue`
- Create: `frontend/test/agentAdminNavigation.test.js`

- [ ] **Step 1: Write the Agent administration navigation test**

Export `agentAdminNavigation` from `AgentAdminLayout.vue` via a small adjacent JS file if Vue SFC import is unsuitable for Node. Test exact entries and deep-link ownership:

```js
assert.deepEqual(agentAdminNavigation.map(item => item.path), [
  '/admin/agents/prompts', '/admin/agents/intents', '/admin/agents/mcp'
])
assert.equal(isAgentSectionActive('/admin/agents/intents/3/edit', '/admin/agents/intents'), true)
```

Run and expect FAIL until the helper exists.

- [ ] **Step 2: Split API ownership without changing backend contracts**

Move prompt functions to `promptAdmin.js`, intent config/stats/save functions to `intentAdmin.js`, and MCP discovery/invocation to `mcpAdmin.js`. Update `intentTreeAdmin.js` to import from `intentAdmin.js`. Delete these exports from `admin.js`; if nothing imports `admin.js` after Tasks 4–7, delete it.

- [ ] **Step 3: Normalize page ownership and visible states**

For all three domains:

- Use one title/description owner (the leaf page, not both layout and panel).
- Render loading, empty, failure, and retry states consistently.
- Keep prompts, intents, and MCP on separate routes so switching does not load all three datasets.
- Keep intent tree/list as local modes sharing one loaded intent configuration.
- Ensure create/edit return paths are only `/admin/agents/intents` and deep links use `/admin/agents/intents/:index/edit`.
- Do not add shared business state to `AgentAdminLayout.vue`.

- [ ] **Step 4: Run Agent and full frontend checks**

```bash
cd frontend && node --test test/agentAdminNavigation.test.js test/adminRoutes.test.js
cd frontend && npm test
cd frontend && npm run build
```

Expected: PASS and no import from a removed `api/admin.js` export.

- [ ] **Step 5: Commit Agent cleanup**

```bash
git add frontend/src/api frontend/src/views/admin/agents frontend/test/agentAdminNavigation.test.js \
  frontend/test/adminRoutes.test.js
git commit -m "refactor(agent-ui): organize expert administration tools"
```

### Task 8: Remove unreachable remnants and update architectural documentation

**Files:**
- Modify: `docs/development/frontend-guidelines.md`
- Modify: `docs/development/verification.md`
- Modify: `ARCHITECTURE.md`
- Modify: `PACKAGE_CONVENTIONS.md` only if the deleted `menu` capability is still listed as an active owner
- Delete: any now-unreferenced old frontend API/view/style file discovered by the checks below

- [ ] **Step 1: Prove old route and dynamic-menu strings are absent from active source**

Run:

```bash
grep -RIn --exclude-dir=node_modules --exclude-dir=dist -E \
  "(/monitoring|/model-providers|/ops|/rag-dashboard|/settings|/workspace|/intent-tree|/intent-list|/prompts|/api/settings/menu|/api/settings/agents)" \
  frontend/src src/main/java
```

Expected: no output. Fix real stale links/imports; do not silence the check with compatibility constants.

- [ ] **Step 2: Verify the old pages and APIs are unreachable/deleted**

Run:

```bash
find frontend/src/views -maxdepth 1 -type f | sort
find frontend/src/api -maxdepth 1 -type f | sort
```

Expected: no old administration page at `frontend/src/views/{MonitoringView,ModelProvidersView,OpsView,RagDashboardView,RagTraceDetailView,SettingsView,WorkspaceView,McpView,IntentTreeView,IntentListView,IntentEditView,PromptsView}.vue`; no dynamic menu API; no empty aggregate `admin.js`.

- [ ] **Step 3: Update docs to the actual system**

Document:

- Static four-entry navigation in `frontend/src/shell/navigation.js`.
- Nested management ownership and paths.
- Removal of DB/Redis menu configuration and the disconnected Agent config table/API.
- `modelrouting` as the only runtime model truth source.
- Masked model-key reads and explicit replace/clear semantics.
- Frontend verification now covering navigation/admin pure mappings.

Remove `menu` from the active platform module list in `ARCHITECTURE.md`/`PACKAGE_CONVENTIONS.md`; retain historical migration descriptions only where they explain V1–V9.

- [ ] **Step 4: Check documentation and commit cleanup**

```bash
git diff --check -- ARCHITECTURE.md PACKAGE_CONVENTIONS.md docs/development/frontend-guidelines.md docs/development/verification.md
```

Expected: exit 0.

```bash
git add ARCHITECTURE.md PACKAGE_CONVENTIONS.md
git add -f docs/development/frontend-guidelines.md docs/development/verification.md
git commit -m "docs(ui): document consolidated administration"
```

Any dead source file discovered in Step 2 must be deleted and staged in the earlier task that owns that domain; do not broaden this documentation commit to unrelated source.

### Task 9: Full verification and browser acceptance

**Files:**
- Modify only if a verification failure exposes a defect in files already owned by Tasks 1–8.

- [ ] **Step 1: Check workspace isolation before testing**

```bash
git status --short --branch
git diff --check
```

Expected: unrelated pre-existing edits remain unstaged; no whitespace errors in this feature.

- [ ] **Step 2: Run the full frontend suite and production build**

```bash
cd frontend && npm test && npm run build
```

Expected: all Node tests PASS and Vite build exits 0.

- [ ] **Step 3: Run backend focused, architecture, compile, and verify checks**

```bash
mvn -q -Dtest=FlywayDatabaseMigrationTest,ModelRoutingControllerTest,ModelCandidateServiceTest test
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q verify -DskipTests
```

Expected: all commands exit 0.

- [ ] **Step 4: Run the MySQL migration integration test when an isolated database is available**

Use an empty disposable MySQL 8 schema, never the developer database:

```bash
mvn -q -Dtest=FlywayMySqlMigrationIntegrationTest \
  -Dtest.mysql.jdbc-url="$TEST_MYSQL_JDBC_URL" \
  -Dtest.mysql.username="$TEST_MYSQL_USERNAME" \
  -Dtest.mysql.password="$TEST_MYSQL_PASSWORD" test
```

Expected: V1–V9 apply and `t_menu_config`/`t_agent_config` are absent. If no disposable schema is available, report this check as skipped rather than claiming it passed.

- [ ] **Step 5: Restart and perform browser acceptance**

```bash
bash scripts/dev-stop.sh
bash scripts/dev-start.sh
```

Using `http://localhost:5173`, verify:

1. Sidebar has exactly AI 助手, 知识库, 能力画像, 管理中心.
2. `/admin` lands on `/admin/models`; all `/admin/**` descendants keep 管理中心 active.
3. Model candidate CRUD/probe/toggle and routing health work; GET/list responses never show plaintext keys.
4. RAG overview, trace/detail, evaluation, and audit are reachable with no duplicate dashboard panels.
5. Prompts, intent tree/list/edit, and MCP are independently reachable.
6. Old URLs fall through as unknown and do not redirect.
7. With a stale `menuConfig::allMenus` Redis value present, refreshing the browser still shows only four entries and makes no `/api/settings/menu` request.
8. At 390px viewport width, admin second/third-level navigation remains usable and does not overlap content.

- [ ] **Step 6: Inspect browser/backend errors**

Check browser console/network and:

```bash
tail -n 200 .dev/logs/frontend.log
tail -n 200 .dev/logs/backend.log
```

Expected: no unresolved imports, Vue warnings, 404s from current links, menu API calls, or plaintext-key logging.

- [ ] **Step 7: Commit only verification fixes, if any**

If verification required code changes, rerun the failed command and commit only those files:

```bash
git add frontend/src/shell/navigation.js frontend/src/shell/AppShell.vue frontend/src/router \
  frontend/src/views/admin frontend/src/api/modelAdmin.js frontend/src/api/ragAdmin.js \
  frontend/src/api/promptAdmin.js frontend/src/api/intentAdmin.js frontend/src/api/mcpAdmin.js \
  src/main/java/io/github/imzmq/interview/modelrouting src/main/resources/db/migration/V9__drop_obsolete_navigation_and_agent_config.sql \
  src/test/java/io/github/imzmq/interview/controller/ModelRoutingControllerTest.java \
  src/test/java/io/github/imzmq/interview/modelrouting/ModelCandidateServiceTest.java \
  src/test/java/io/github/imzmq/interview/config/database

git commit -m "fix(ui): resolve admin consolidation verification"
```

If no fixes were necessary, do not create an empty commit.

## Completion checklist

- [ ] Four primary entries come only from `frontend/src/shell/navigation.js`.
- [ ] New nested admin routes exist and every listed old route is absent.
- [ ] No dynamic menu frontend/backend/cache consumer remains.
- [ ] `t_menu_config` and `t_agent_config` are removed by V9, without editing V1–V8.
- [ ] Disconnected Agent settings are deleted; model runtime remains `modelrouting`-owned.
- [ ] Model reads are structurally incapable of returning plaintext keys.
- [ ] Model, RAG, and Agent pages have unique state/request owners and no whole-page compatibility wrappers.
- [ ] Replaced files are deleted at their milestone, not left for a future cleanup.
- [ ] Documentation describes the final implementation, not the migration process.
- [ ] Actual command results and skipped integration checks are reported accurately.
