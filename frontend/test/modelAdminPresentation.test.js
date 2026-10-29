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

const { createCandidateForm, candidatePayload, candidateFormError, routingSummary, runtimeRows, successRate } = await import('../src/views/admin/models/modelPresentation.js')

test('editor never prefills an existing key and payload preserves blank keys', () => {
  const form = createCandidateForm({ id: 3, name: 'cloud', apiKeyMasked: 'masked', apiKey: 'must-not-prefill' })
  assert.equal(form.apiKey, '')
  assert.equal(form.clearApiKey, false)
  assert.equal(Object.hasOwn(candidatePayload(form), 'apiKey'), false)
  form.apiKey = ' dummy-key '
  assert.equal(candidatePayload(form).apiKey, 'dummy-key')
  form.apiKey = ''
  form.clearApiKey = true
  assert.equal(candidatePayload(form).clearApiKey, true)
  assert.equal(Object.hasOwn(candidatePayload(form), 'apiKey'), false)
})

test('form validation retains required fields, cloud create rules and explicit key intent', () => {
  const form = { ...createCandidateForm(), name: 'a', displayName: 'A', model: 'm', baseUrl: 'https://example.invalid' }
  assert.match(candidateFormError(form, true), /API Key/)
  assert.equal(candidateFormError(form, false), '')
  form.provider = 'ollama'
  assert.equal(candidateFormError(form, true), '')
  form.apiKey = 'dummy'
  form.clearApiKey = true
  assert.match(candidateFormError(form, false), /清空.*替换/)
  form.clearApiKey = false
  form.name = ' '
  assert.match(candidateFormError(form, false), /唯一标识/)
  form.name = 'a'
  form.priority = null
  assert.match(candidateFormError(form, false), /优先级/)
})

test('unknown and incomplete health cannot be presented as healthy', () => {
  assert.equal(routingSummary(null).label, '暂无运行快照')
  assert.equal(routingSummary({ runtime: { states: {} } }).type, 'default')
  assert.equal(routingSummary({ runtime: { states: { a: 'UNKNOWN' } } }).type, 'default')
  assert.equal(routingSummary({ runtime: { states: { a: 'CLOSED', b: 'UNKNOWN' } } }).type, 'default')
  assert.equal(routingSummary({ runtime: { states: { a: 'CLOSED' } } }).type, 'success')
  assert.equal(routingSummary({ runtime: { states: { a: 'HALF_OPEN' } } }).type, 'warning')
  assert.equal(routingSummary({ runtime: { states: { a: 'OPEN' } } }).type, 'error')
})

test('runtime presentation uses actual counters and leaves missing observations empty', () => {
  assert.equal(successRate({ successCount: 0, failureCount: 0 }), '—')
  assert.equal(successRate({ successCount: 9, failureCount: 1 }), '90.0%')
  assert.equal(successRate({ successCount: 9 }), '—')
  assert.deepEqual(runtimeRows(null), [])
  const rows = runtimeRows({ runtime: { states: { a: 'CLOSED', b: 'OPEN' }, details: { a: { requestCount: 5 }, c: { successCount: 1 } } } })
  assert.deepEqual(rows.map(row => row.name), ['a', 'b', 'c'])
  assert.equal(rows[0].requestCount, 5)
  assert.equal(Object.hasOwn(rows[0], 'lastCostMs'), false)
  assert.equal(rows[1].requestCount, null)
  assert.equal(rows[2].state, 'UNKNOWN')
})
