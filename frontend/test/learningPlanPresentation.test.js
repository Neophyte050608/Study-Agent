import { test } from 'node:test'
import assert from 'node:assert/strict'
import { statusLabel, codeLabel, degradationLabel } from '../src/views/learning/planPresentation.js'

test('status and degradation labels are explanatory and unknown codes do not leak internals', () => {
  assert.equal(statusLabel('DEGRADED'), '降级完成')
  assert.match(degradationLabel('EVIDENCE_UNAVAILABLE'), /证据/)
  assert.equal(codeLabel('private SQLException'), '执行未完成')
  assert.equal(statusLabel('unknown'), '未知状态')
})
test('backend safe runtime codes are described accurately', () => {
  assert.equal(codeLabel('RUNNING'),'正在执行')
  assert.match(codeLabel('ATTEMPT_BUDGET_EXHAUSTED'),/上限/)
  assert.match(codeLabel('OVERLOADED'),/繁忙/)
  assert.match(codeLabel('DEPENDENCY_FAILED'),/依赖/)
})
