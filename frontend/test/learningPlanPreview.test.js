import { test } from 'node:test'
import assert from 'node:assert/strict'
import { useLearningPlanPreview } from '../src/composables/useLearningPlanPreview.js'

const A = '7a1790a2-3cb1-46f2-8a20-8aa8801e42a4'
const B = 'ba1790a2-3cb1-46f2-8a20-8aa8801e42a4'
const snapshot = (status = 'QUEUED', id = A, revision = 0) => ({ taskId: id, status, revision,
  nodes: { profile: { status: 'SUCCEEDED', attempts: 1 }, evidence: { status: 'FAILED', attempts: 1 }, compose: { status: 'SUCCEEDED', attempts: 1 } },
  result: status === 'DEGRADED' ? { plan: 'A safe plan' } : null })
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
const fail = status => Object.assign(new Error('failure'), { status, code: status === 409 ? 'REVISION_CONFLICT' : null })
function clock() {
  let seq=0; const jobs=new Map()
  return { setTimeout(fn,ms) { jobs.set(++seq,{fn,ms}); return seq }, clearTimeout(id) { jobs.delete(id) },
    fire(ms) { const item=[...jobs].find(([,job])=>job.ms===ms); assert.ok(item, `timer ${ms} exists`); jobs.delete(item[0]); item[1].fn() },
    count(ms) { return [...jobs.values()].filter(job=>job.ms===ms).length } }
}
function setup(overrides = {}) {
  const time=clock(), calls=[]; let keys=0
  const api={ submit: async () => snapshot(), query: async () => snapshot(), retry: async () => snapshot(), delete: async () => ({}), ...overrides }
  for (const key of Object.keys(api)) { const fn=api[key]; api[key]=(...args)=> { calls.push({key,args}); return fn(...args) } }
  const vm=useLearningPlanPreview({ api, clock: time, newKey: () => `key-${++keys}` })
  return {vm,time,calls}
}
const flush = async () => { for(let i=0;i<12;i++) await Promise.resolve() }

test('uncertain submission reuses key and freezes changed input until explicit new preview', async () => {
  let attempts=0
  const {vm,calls}=setup({submit:async()=>{ if(!attempts++) throw new TypeError('network'); return snapshot() }})
  await vm.submit(' Java ', ' three days ')
  assert.equal(vm.uncertain.value,true)
  await vm.submit('different', 'input')
  assert.equal(calls.length,1)
  await vm.submit('Java', 'three days')
  assert.equal(calls[0].args[1],calls[1].args[1])
  assert.equal(vm.task.value.taskId,A)
  vm.reset()
  await vm.submit('different','input')
  assert.notEqual(calls[2].args[1],calls[0].args[1])
  vm.dispose()
})
test('poll only starts after query finishes and never overlaps; terminal clears timer', async () => {
  const pending=deferred(); let reads=0
  const {vm,time,calls}=setup({query:async()=>++reads===1?snapshot():pending.promise})
  await vm.load(A)
  assert.equal(time.count(2000),1)
  time.fire(2000); await flush()
  assert.equal(time.count(2000),0)
  await vm.refresh()
  assert.equal(calls.length,2)
  pending.resolve(snapshot('COMPLETED')); await flush()
  assert.equal(vm.task.value.status,'COMPLETED')
  assert.equal(time.count(2000),0)
  vm.dispose()
})
test('switching task ignores late response and aborts old request', async () => {
  const pending=deferred()
  const {vm,calls}=setup({query:async id=>id===A?pending.promise:snapshot('COMPLETED',B)})
  const first=vm.load(A); await flush()
  await vm.load(B)
  pending.resolve(snapshot()); await first
  assert.equal(vm.task.value.taskId,B)
  assert.equal(calls[0].args[1].signal.aborted,true)
  vm.dispose()
})
test('dispose stops timers and ignores in-flight responses', async () => {
  const pending=deferred()
  const {vm,time}=setup({query:async()=>pending.promise})
  const first=vm.load(A); await flush(); vm.dispose(); pending.resolve(snapshot()); await first
  assert.equal(vm.task.value,null)
  assert.equal(time.count(2000),0)
  assert.equal(time.count(15000),0)
})
test('timeout aborts request and pauses without auto retry even if fetch ignores signal', async () => {
  const pending=deferred()
  const {vm,time,calls}=setup({query:async()=>pending.promise})
  const first=vm.load(A); await flush(); time.fire(15000); await first
  assert.equal(vm.busy.value,'')
  assert.equal(vm.paused.value,true)
  assert.match(vm.error.value,/超时/)
  assert.equal(calls[0].args[1].signal.aborted,true)
  pending.resolve(snapshot()); await flush()
  assert.equal(vm.task.value,null)
  assert.equal(time.count(2000),0)
  vm.dispose()
})
test('retry uses current revision and selected roots; conflict only refreshes', async () => {
  let reads=0
  const {vm,calls}=setup({query:async()=>snapshot('DEGRADED',A,++reads),retry:async()=>{throw fail(409)}})
  await vm.load(A)
  await vm.retry(['evidence'])
  const retry=calls.find(call=>call.key==='retry')
  assert.deepEqual(retry.args.slice(0,3),[A,1,['evidence']])
  assert.equal(calls.filter(call=>call.key==='retry').length,1)
  assert.equal(vm.task.value.revision,2)
  assert.match(vm.error.value,/刷新/)
  vm.dispose()
})
test('401 and 404 remove visible private result; 503 preserves snapshot and pauses', async () => {
  for (const status of [401,404,503]) {
    let reads=0
    const {vm,time}=setup({query:async()=>{if(reads++)throw fail(status);return snapshot('DEGRADED')}})
    await vm.load(A); await vm.refresh()
    assert.equal(vm.task.value===null,status!==503)
    assert.equal(vm.paused.value,true)
    assert.equal(time.count(2000),0)
    vm.dispose()
  }
})
test('only terminal delete allowed; successful deletion clears local state', async () => {
  const {vm,calls}=setup({query:async()=>snapshot('RUNNING')})
  await vm.load(A); await vm.remove()
  assert.equal(calls.filter(call=>call.key==='delete').length,0)
  vm.reset()
  const other=setup({query:async()=>snapshot('FAILED')})
  await other.vm.load(A); await other.vm.remove()
  assert.equal(other.vm.task.value,null)
  assert.equal(other.vm.taskId.value,'')
  vm.dispose(); other.vm.dispose()
})
test('delete failure retains terminal snapshot and selected roots must be failed', async () => {
  const {vm,calls}=setup({query:async()=>snapshot('DEGRADED'),delete:async()=>{throw fail(503)}})
  await vm.load(A); await vm.retry(['profile'])
  assert.equal(calls.filter(call=>call.key==='retry').length,0)
  await vm.remove()
  assert.equal(vm.task.value.taskId,A)
  vm.dispose()
})
test('invalid task ID or input performs no requests', async () => {
  const {vm,calls}=setup()
  await vm.load('not-a-uuid'); await vm.submit(' ',''); await vm.submit('a'.repeat(201),''); await vm.submit('a','b'.repeat(2001))
  assert.equal(calls.length,0)
  vm.dispose()
})
test('double submit is single-flight and accepted form cannot create accidental new task', async () => {
  const pending=deferred(); const {vm,calls}=setup({submit:async()=>pending.promise})
  const first=vm.submit('Java',''); await flush(); await vm.submit('Java','')
  pending.resolve(snapshot()); await first; await vm.submit('Java','')
  assert.equal(calls.length,1)
  vm.dispose()
})
test('invalid accepted submission stays uncertain and reuses original key', async () => {
  let submits=0
  const {vm,calls}=setup({submit:async()=>++submits===1?{}:snapshot()})
  await vm.submit('Java')
  assert.equal(vm.taskId.value,'')
  assert.equal(vm.task.value,null)
  assert.equal(vm.uncertain.value,true)
  await vm.submit('Java')
  assert.equal(calls[0].args[1],calls[1].args[1])
  assert.equal(vm.task.value.taskId,A)
  vm.dispose()
})
test('query cannot replace current task with a different ID or malformed revision', async () => {
  for (const response of [snapshot('COMPLETED',B),snapshot('COMPLETED',A,-1)]) {
    const {vm}=setup({query:async()=>response})
    await vm.load(A)
    assert.equal(vm.task.value,null)
    assert.equal(vm.paused.value,true)
    vm.dispose()
  }
})
test('reset aborts pending submit and ignores late acceptance', async () => {
  const pending=deferred(), {vm,calls,time}=setup({submit:async()=>pending.promise})
  const first=vm.submit('Java'); await flush(); vm.reset()
  pending.resolve(snapshot()); await first
  assert.equal(vm.task.value,null)
  assert.equal(vm.taskId.value,'')
  assert.equal(calls[0].args[2].signal.aborted,true)
  assert.equal(time.count(2000),0)
  vm.dispose()
})
test('successful retry schedules query until terminal; delete conflict only refreshes', async () => {
  let reads=0
  const {vm,calls,time}=setup({query:async()=>snapshot(++reads===1?'DEGRADED':'COMPLETED'),delete:async()=>{throw fail(409)}})
  await vm.load(A); await vm.retry(['evidence'])
  assert.equal(vm.task.value.status,'QUEUED')
  time.fire(2000); await flush()
  assert.equal(vm.task.value.status,'COMPLETED')
  assert.equal(time.count(2000),0)
  await vm.remove()
  assert.equal(calls.filter(x=>x.key==='delete').length,1)
  assert.equal(vm.task.value.status,'COMPLETED')
  assert.match(vm.error.value,/刷新/)
  vm.dispose()
})
test('submit conflict never claims a refresh that did not occur', async () => {
  const {vm,calls}=setup({submit:async()=>{throw fail(409)}})
  await vm.submit('Java')
  assert.equal(calls.filter(x=>x.key==='query').length,0)
  assert.doesNotMatch(vm.error.value,/已刷新/)
  vm.dispose()
})
test('reset before request dispatch prevents the abandoned mutation from being sent', async () => {
  const {vm,calls}=setup()
  const abandoned=vm.submit('old input')
  vm.reset()
  await vm.submit('new input')
  await abandoned
  assert.equal(calls.length,1)
  assert.equal(calls[0].args[0].topic,'new input')
  assert.equal(calls[0].args[1],'key-2')
  vm.dispose()
})
