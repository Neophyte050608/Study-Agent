import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
const source = name => readFile(new URL(`../src/views/admin/models/${name}.vue`, import.meta.url), 'utf8')

test('model controls use the shared library, without old controls or fake telemetry', async () => {
  const candidates = await source('ModelCandidatesPanel')
  const health = await source('ModelRoutingHealthPanel')
  const page = await source('ModelsAdminView')
  assert.match(candidates, /NCard/)
  assert.match(candidates, /NPopconfirm/)
  assert.match(candidates, /NSwitch/)
  assert.doesNotMatch(candidates, /window\.confirm|<button|inputClass|getCircuitBadgeClass/)
  assert.match(health, /NDescriptions/)
  assert.doesNotMatch(health, /lastCostMs|最近耗时|capacityPercent|PING:|internal-cluster|View_Full_Logs|<button/)
  assert.match(page, /NAlert/)
  assert.match(page, /NSpin/)
})

test('editor owns a library form and modal, not a generic wrapper layer', async () => {
  const editor = await source('ModelCandidateEditor')
  assert.match(editor, /NModal/)
  assert.match(editor, /NFormItem/)
  assert.match(editor, /type="password"/)
  assert.match(editor, /candidatePayload/)
  assert.doesNotMatch(editor, /candidate\.apiKey|<input|<select|window\.confirm/)
})

test('snapshot retry is rendered in the supported alert content slot', async () => {
  const page = await source('ModelsAdminView')
  assert.match(page, />重试<\/NButton>/)
  assert.doesNotMatch(page, /#action/)
})

test('admin section links stay legible in the narrow shell', async () => {
  const layout = await readFile(new URL('../src/views/admin/AdminLayout.vue', import.meta.url), 'utf8')
  assert.match(layout, /shrink-0 whitespace-nowrap/)
  assert.match(layout, /px-4.*md:px-6/)
})
