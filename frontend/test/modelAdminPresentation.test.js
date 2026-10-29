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
