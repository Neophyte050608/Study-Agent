# Shared theme and model administration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Use inline execution in the existing main checkout, as already agreed; no subagents or remote push.

**Goal:** Deliver the first complete Naive UI page, with one shared brand/theme definition and unchanged model administration HTTP contracts.

**Architecture:** Root NConfigProvider and shared useTheme supply theme to controls and existing CSS variables. Keep model API composition in useModelsAdmin; separate model editor, candidate presentation, and routing-health presentation. Delete replaced handwritten controls and synthetic monitoring decoration.

**Tech Stack:** Vue 3, Vite, Naive UI 2.45.3, existing Tailwind layout utilities, Node built-in tests.

---

## Scope / file ownership

- Create `frontend/src/theme.js`: existing RGB palettes, library overrides, CSS-variable application.
- Modify `frontend/src/composables/useTheme.js`: shared theme state, first-use initialization and storage fallback.
- Modify `frontend/src/{App.vue,main.js}`, `frontend/index.html`, `frontend/package{,-lock}.json`: provider integration and sole palette owner. No global reset.
- Modify `frontend/src/views/admin/models/{ModelsAdminView,ModelCandidatesPanel,ModelRoutingHealthPanel}.vue`.
- Modify `frontend/src/views/admin/AdminLayout.vue`: narrow-screen padding and non-wrapping horizontal section navigation (browser acceptance follow-up, no route changes).
- Create `frontend/src/views/admin/models/ModelCandidateEditor.vue`: actual reusable-in-page editor, not a generic control wrapper.
- Modify `frontend/src/views/admin/models/modelPresentation.js`: editor payload/validation and truthful runtime summary.
- Keep `frontend/src/api/modelAdmin.js`, route configuration, backend, and existing dirty chat files unchanged.
- Create `frontend/test/{theme,modelUiContract}.test.js`, expand `modelAdminPresentation.test.js`.
- Update `docs/development/{frontend-guidelines,verification}.md`.

## Task 1: Single theme owner

- [x] Write `theme.test.js` with assertions for original light/dark colors, Naive overrides, applying tokens, shared useTheme identity, saved preference vs system preference, toggling/persistence and unavailable storage.

```js
assert.equal(createThemeOverrides(false).common.primaryColor, 'rgb(79, 70, 229)')
assert.equal(createThemeOverrides(true).common.cardColor, 'rgb(15, 23, 42)')
assert.equal(createThemeState(environment).isDark.value, true)
```

- [x] Run `cd frontend && node --test test/theme.test.js`; expect missing module/export failure.
- [x] Add `theme.js` with original palette values, `createThemeOverrides(isDark)` and `applyTheme(document, isDark)`; remove duplicate palette block from index.html.
- [x] Implement `createThemeState(environment)` for initialization, guarded storage, CSS application and toggle; `useTheme()` returns one browser singleton. Initialize before mount in main.js.
- [x] Run narrow tests; expect all pass. Install only `npm install naive-ui@^2.45.3` and wire NConfigProvider (abstract, preflight-style-disabled), Chinese locales, darkTheme, message/dialog providers. Explicit imports, no NGlobalStyle.
- [x] Run `npm test && npm run build`; inspect fresh output before scoped commit.

## Task 2: Model form and truthful presentation

- [x] Test blank edit keys, preserve/replace/clear payloads, conflicting clear+replacement, cloud creation requirements vs Ollama, required identity fields, unknown breaker data, empty counters and actual success ratio.

```js
const form = createCandidateForm({ id: 1, apiKeyMasked: 'masked' })
assert.equal(form.apiKey, '')
assert.equal(Object.hasOwn(candidatePayload(form), 'apiKey'), false)
assert.equal(routingSummary(null).label, '暂无运行快照')
```

- [x] Run `node --test test/modelAdminPresentation.test.js`; expect missing export failures.
- [x] Implement pure form and runtime presentation functions in modelPresentation.js. Do not expose plaintext in presentation. Use null/“—” for unavailable numbers and no 100% default reliability.
- [x] Add source-contract tests requiring library controls and no window.confirm, fake capacity or PING constants. Run and confirm failures against old templates.
- [x] Implement ModelCandidateEditor with NModal/NForm, associated input IDs, NInput password, explicit key clear, inline validation/save error and a saving guard. Reset secret input on close. Keep cancel and backdrop disabled while saving.
- [x] Replace candidate panel with real summary, NCard/NDescriptions, NTag, NSwitch, NTooltip, NPopconfirm and NButton. Guard concurrent operations per ID, retain probe/copy-key gated operations, surface failed operations with useMessage.
- [x] Replace health panel with snapshot-backed statistics, breaker states/details, Ollama result and logs. Missing/unknown data is visibly neutral, not successful.
- [x] Replace page heading/actions/loading/error. Retain visible previous snapshot on refresh error; controls and sections obey padding/spacing baseline. No bespoke base-component wrappers.
- [x] Run focused tests, all frontend tests and production build before scoped commit.

## Task 3: Browser acceptance and documentation

- [x] Open current Vite server (start frontend only if absent); use Playwright with isolated model data. Do not retrieve existing credentials or print real key values.
- [x] At 1440/390px check sidebar offsets, no page overflow, wrapping controls, modal bounds, light/dark controls and teleported popovers.
- [x] Test create/edit, immutable name, password initially empty, failed save/retry, preserve/replace/clear request semantics, probe, gated copy and confirmed delete; use dummy keys only.
- [x] Test keyboard/modal close-focus restoration, cancel, refresh and empty/error/unknown snapshots. Smoke-test existing chat page and profile canvas mounting without changing those files; full chat Markdown interactions are outside this slice.
- [x] Update frontend guidelines: sole theme owner, Naive explicit imports, no basic wrapper layers, retain business components. Document Node versus browser coverage and remaining page migration stages.
- [x] Run `cd frontend && npm test && npm run build`, `git diff --check`; record actual build-size change against baseline, not assumed improvement.
- [x] Review task diff and confirm original dirty files unchanged. Commit only this task's paths; exclude dist/node_modules/.dev. No push.

## Acceptance evidence

Record executed checks and any skipped integration below. This first slice is complete only when both model subpanels and editor use the shared controls; knowledge/profile/RAG/Agent/chat remain later slices.


### Completed: 2026-10-30

- Shared theme and all model subpanels/editor use direct Naive UI controls. Removed old handwritten controls, synthetic capacity/reliability decoration and unsupported runtime `lastCostMs` column (not provided by ModelHealthStore). Actual probe/Ollama detection latency remains.
- Theme tests, form/presentation tests and source contracts were written against missing/old implementations before migration. Final unsupported-latency regression: 2 failures before removal, then 14/14 focused tests passed.
- Fresh `cd frontend && npm test && npm run build`: exit 0; **81 tests passed**, zero failures/skips. Total includes concurrently present chat tests; this slice's focused tests are 14. No backend behavior or HTTP contract changed by this slice.
- Fresh isolated Playwright acceptance against Vite at localhost:5173: exit 0; desktop 1440px and mobile 390px, no page overflow, readable horizontal admin navigation, dark/light controls and modal, persisted preference, label/input association, Esc focus restoration, preserve/replace/clear key payloads, failed-save retry, saving guard, gated dummy-key copy, toggle/probe/delete confirmation, create validation, snapshot failure/retry/empty/unknown cases. Page errors/non-Tailwind warnings: none.
- Chat main and profile radar canvas mount smoke passed. **Not** real model/database integration, complete chat Markdown interaction coverage, or full application startup verification. Browser script/screenshots are temporary outside repository (`/tmp/study-ui-browser`), not a new persistent test framework.
- Baseline build: entry 103.13 kB / gzip 40.10 kB; model chunk 42.07 kB / gzip 12.13 kB. Final build: entry **341.56 kB / gzip 108.84 kB**; model chunk **269.66 kB / gzip 78.14 kB**, model private CSS 0.16 kB. No >500 kB chunk warning. Concurrent unrelated chat changes mean final build is not an isolated benchmark. Component-library adoption increases bundle size; reduction is in handwritten UI, not JavaScript bytes.
- Model Vue sources: 974 → 322 lines including new business editor. Only direct new dependency: naive-ui ^2.45.3; no generic base-control wrappers, compatibility flags, global reset or unrelated dependency upgrades.
- Fresh npm audit: 9 findings (8 high, 1 moderate); counts and affected package names unchanged from previous HEAD lockfile baseline. No new Naive UI finding; existing dependency vulnerabilities remain unresolved and need a separate upgrade task.
- Frontend guidelines and verification coverage updated. Scoped source/docs commit excludes dist/node_modules/.dev, secrets and unrelated dirty/concurrent files. No remote push.
- Remaining slices: knowledge/profile and remaining administration pages, then chat controls. Preserve task-specific layouts rather than building an additional UI abstraction layer.
