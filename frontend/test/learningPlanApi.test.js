import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { submitLearningPlan, queryLearningPlan, retryLearningPlan, deleteLearningPlan } from '../src/api/learningPlans.js'
import { httpGet, httpPostJson, httpDelete } from '../src/api/http.js'

const ID = '7a1790a2-3cb1-46f2-8a20-8aa8801e42a4'
let calls, originalFetch, originalStorage
beforeEach(() => {
  calls = []
  originalFetch = globalThis.fetch
  originalStorage = globalThis.localStorage
  globalThis.localStorage = { getItem: () => 'test-token' }
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options })
    return new Response(JSON.stringify({ taskId: ID, status: 'QUEUED', revision: 0 }), { status: 202 })
  }
})
afterEach(() => { globalThis.fetch = originalFetch; globalThis.localStorage = originalStorage })

test('submit carries idempotency key and trusted auth, never payload owner', async () => {
  const controller = new AbortController()
  const task = await submitLearningPlan({ topic: 'Java', request: 'three days' }, 'same-key', { signal: controller.signal })
  assert.equal(task.taskId, ID)
  assert.equal(calls[0].url, '/api/learning/plan-previews')
  assert.equal(calls[0].options.headers['Idempotency-Key'], 'same-key')
  assert.equal(calls[0].options.headers.Authorization, 'Bearer test-token')
  assert.equal(calls[0].options.signal, controller.signal)
  assert.deepEqual(JSON.parse(calls[0].options.body), { topic: 'Java', request: 'three days' })
})
test('query/retry/delete use existing paths and exact revision and roots', async () => {
  await queryLearningPlan(ID)
  await retryLearningPlan(ID, 4, ['evidence'])
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response(null, { status: 204 }) }
  assert.deepEqual(await deleteLearningPlan(ID), {})
  assert.equal(calls[0].url, `/api/learning/plan-previews/${ID}`)
  assert.equal(calls[1].url, `/api/learning/plan-previews/${ID}/retry`)
  assert.deepEqual(JSON.parse(calls[1].options.body), { expectedRevision: 4, nodes: ['evidence'] })
  assert.equal(calls[2].options.method, 'DELETE')
})
test('all helpers preserve status/code for structured errors', async () => {
  for (const action of [() => httpGet('/test'), () => httpPostJson('/test', {}), () => httpDelete('/test')]) {
    globalThis.fetch = async () => new Response(JSON.stringify({ code: 'REVISION_CONFLICT', message: '任务状态或请求冲突' }), { status: 409 })
    await assert.rejects(action(), e => e.status === 409 && e.code === 'REVISION_CONFLICT' && e.message === '任务状态或请求冲突')
  }
})
test('empty error DELETE is not mistaken for successful deletion', async () => {
  globalThis.fetch = async () => new Response(null, { status: 401, headers: { 'content-length': '0' } })
  await assert.rejects(deleteLearningPlan(ID), e => e.status === 401)
})
test('non JSON failures do not expose raw server content', async () => {
  globalThis.fetch = async () => new Response('<html>private SQL exception</html>', { status: 503 })
  await assert.rejects(queryLearningPlan(ID), e => e.status === 503 && !e.message.includes('SQL'))
})
test('legacy calls still unwrap success data and send no extra custom headers', async () => {
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response('{"code":0,"data":{"ok":true}}') }
  assert.deepEqual(await httpPostJson('/legacy', { a: 1 }), { ok: true })
  assert.equal(calls[0].options.headers['Idempotency-Key'], undefined)
})
test('malformed successful JSON is rejected safely rather than accepted as an empty task', async () => {
  globalThis.fetch = async () => new Response('<html>private response</html>', { status: 200 })
  for (const action of [() => httpGet('/test'), () => httpPostJson('/test', {}), () => httpDelete('/test')]) {
    await assert.rejects(action(), e => e.code === 'INVALID_RESPONSE' && !e.message.includes('private'))
  }
})
