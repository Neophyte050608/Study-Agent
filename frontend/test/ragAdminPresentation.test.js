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
