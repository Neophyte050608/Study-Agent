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
- Create `frontend/src/views/admin/models/ModelCandidateEditor.vue`: actual reusable-in-page editor, not a generic control wrapper.
- Modify `frontend/src/views/admin/models/modelPresentation.js`: editor payload/validation and truthful runtime summary.
- Keep `frontend/src/api/modelAdmin.js`, route configuration, backend, and existing dirty chat files unchanged.
- Create `frontend/test/{theme,modelUiContract}.test.js`, expand `modelAdminPresentation.test.js`.
- Update `docs/development/{frontend-guidelines,verification}.md`.

## Task 1: Single theme owner

- [ ] Write `theme.test.js` with assertions for original light/dark colors, Naive overrides, applying tokens, shared useTheme identity, saved preference vs system preference, toggling/persistence and unavailable storage.

```js
assert.equal(createThemeOverrides(false).common.primaryColor, 'rgb(79, 70, 229)')
assert.equal(createThemeOverrides(true).common.cardColor, 'rgb(15, 23, 42)')
assert.equal(createThemeState(environment).isDark.value, true)
```

- [ ] Run `cd frontend && node --test test/theme.test.js`; expect missing module/export failure.
- [ ] Add `theme.js` with original palette values, `createThemeOverrides(isDark)` and `applyTheme(document, isDark)`; remove duplicate palette block from index.html.
- [ ] Implement `createThemeState(environment)` for initialization, guarded storage, CSS application and toggle; `useTheme()` returns one browser singleton. Initialize before mount in main.js.
- [ ] Run narrow tests; expect all pass. Install only `npm install naive-ui@^2.45.3` and wire NConfigProvider (abstract, preflight-style-disabled), Chinese locales, darkTheme, message/dialog providers. Explicit imports, no NGlobalStyle.
- [ ] Run `npm test && npm run build`; inspect fresh output before scoped commit.

## Task 2: Model form and truthful presentation

- [ ] Test blank edit keys, preserve/replace/clear payloads, conflicting clear+replacement, cloud creation requirements vs Ollama, required identity fields, unknown breaker data, empty counters and actual success ratio.

```js
const form = createCandidateForm({ id: 1, apiKeyMasked: 'masked' })
assert.equal(form.apiKey, '')
assert.equal(Object.hasOwn(candidatePayload(form), 'apiKey'), false)
assert.equal(routingSummary(null).label, '暂无运行快照')
```

- [ ] Run `node --test test/modelAdminPresentation.test.js`; expect missing export failures.
- [ ] Implement pure form and runtime presentation functions in modelPresentation.js. Do not expose plaintext in presentation. Use null/“—” for unavailable numbers and no 100% default reliability.
- [ ] Add source-contract tests requiring library controls and no window.confirm, fake capacity or PING constants. Run and confirm failures against old templates.
- [ ] Implement ModelCandidateEditor with NModal/NForm, associated input IDs, NInput password, explicit key clear, inline validation/save error and a saving guard. Reset secret input on close. Keep cancel and backdrop disabled while saving.
- [ ] Replace candidate panel with real summary, NCard/NDescriptions, NTag, NSwitch, NTooltip, NPopconfirm and NButton. Guard concurrent operations per ID, retain probe/copy-key gated operations, surface failed operations with useMessage.
- [ ] Replace health panel with snapshot-backed statistics, breaker states/details, Ollama result and logs. Missing/unknown data is visibly neutral, not successful.
- [ ] Replace page heading/actions/loading/error. Retain visible previous snapshot on refresh error; controls and sections obey padding/spacing baseline. No bespoke base-component wrappers.
- [ ] Run focused tests, all frontend tests and production build before scoped commit.

## Task 3: Browser acceptance and documentation

- [ ] Open current Vite server (start frontend only if absent); use Playwright with isolated model data. Do not retrieve existing credentials or print real key values.
- [ ] At 1440/390px check sidebar offsets, no page overflow, wrapping controls, modal bounds, light/dark controls and teleported popovers.
- [ ] Test create/edit, immutable name, password initially empty, failed save/retry, preserve/replace/clear request semantics, probe, gated copy and confirmed delete; use dummy keys only.
- [ ] Test keyboard/modal close-focus restoration, cancel, refresh and empty/error/unknown snapshots. Smoke-test existing chat Markdown and profile structure without changing those files.
- [ ] Update frontend guidelines: sole theme owner, Naive explicit imports, no basic wrapper layers, retain business components. Document Node versus browser coverage and remaining page migration stages.
- [ ] Run `cd frontend && npm test && npm run build`, `git diff --check`; record actual build-size change against baseline, not assumed improvement.
- [ ] Review task diff and confirm original dirty files unchanged. Commit only this task's paths; exclude dist/node_modules/.dev. No push.

## Acceptance evidence

Record executed checks and any skipped integration below. This first slice is complete only when both model subpanels and editor use the shared controls; knowledge/profile/RAG/Agent/chat remain later slices.
