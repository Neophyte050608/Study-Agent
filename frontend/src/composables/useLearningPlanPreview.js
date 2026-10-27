import { computed, ref } from 'vue'
import { submitLearningPlan, queryLearningPlan, retryLearningPlan, deleteLearningPlan } from '../api/learningPlans.js'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const ACTIVE = new Set(['QUEUED', 'RUNNING'])
const TERMINAL = new Set(['COMPLETED', 'DEGRADED', 'FAILED', 'TIMED_OUT'])
const RETRYABLE = new Set(['DEGRADED', 'FAILED', 'TIMED_OUT'])
const FAILED = new Set(['FAILED', 'TIMED_OUT'])

/** Request-local state only. Aborting an HTTP request never cancels a durable task. */
export function useLearningPlanPreview({ api = {
  submit: submitLearningPlan, query: queryLearningPlan, retry: retryLearningPlan, delete: deleteLearningPlan
}, clock = globalThis, newKey = () => globalThis.crypto.randomUUID() } = {}) {
  const task = ref(null), taskId = ref(''), busy = ref(''), error = ref(''), paused = ref(false), uncertain = ref(false)
  const canRetry = computed(() => RETRYABLE.has(task.value?.status))
  const canDelete = computed(() => TERMINAL.has(task.value?.status))
  const failedNodes = computed(() => Object.entries(task.value?.nodes || {})
    .filter(([, node]) => FAILED.has(node.status)).map(([id]) => id))
  let generation = 0, stopped = false, timer = null, active = null, submission = null

  function stopTimer() {
    if (timer !== null) clock.clearTimeout(timer)
    timer = null
  }
  function invalidate() {
    generation++
    stopTimer()
    active?.controller.abort()
    active?.cancel()
    active = null
    busy.value = ''
  }
  function schedule() {
    stopTimer()
    if (!stopped && !paused.value && ACTIVE.has(task.value?.status)) {
      timer = clock.setTimeout(() => { timer = null; void refresh() }, 2000)
    }
  }
  function failure(e) {
    paused.value = true
    if (e.status === 401 || e.status === 404) task.value = null
    error.value = e.code === 'INVALID_RESPONSE' ? '任务响应格式不合法，查询已暂停。请手动重试；未确认的提交会复用原幂等键。'
      : e.code === 'CLIENT_TIMEOUT' ? '请求超时，查询已暂停。可手动刷新；后台任务不会因此取消。'
      : e.status === 401 ? '认证已失效，请配置有效凭证后重新查询。'
      : e.status === 404 ? '任务不存在或当前身份无权访问。'
      : e.status === 503 ? '持久任务暂不可用，请检查后台执行开关、MySQL 或稍后手动重试。'
      : e.status === 409 ? '请求与已有输入、任务状态或执行预算冲突；未重复执行操作。'
      : e.status === 400 ? '请求参数不合法，请检查输入。'
      : e.status >= 500 ? '服务端处理失败，查询已暂停。请稍后手动重试。'
      : '网络请求失败，查询已暂停。请手动重试。'
  }
  async function run(kind, action, accept) {
    if (stopped || busy.value) return false
    stopTimer()
    const epoch = generation, controller = new AbortController()
    let deadline, cancel, conflict = false, ok = false
    const interruption = new Promise((_, reject) => {
      cancel = () => reject(Object.assign(new Error('superseded'), { code: 'CLIENT_CANCELLED' }))
      deadline = clock.setTimeout(() => {
        controller.abort()
        reject(Object.assign(new Error('timeout'), { code: 'CLIENT_TIMEOUT' }))
      }, 15000)
    })
    active = { controller, cancel }
    busy.value = kind
    error.value = ''
    paused.value = false
    try {
      const result = await Promise.race([Promise.resolve().then(() => {
        if (stopped || epoch !== generation || controller.signal.aborted) {
          throw Object.assign(new Error('superseded'), { code: 'CLIENT_CANCELLED' })
        }
        return action(controller.signal)
      }), interruption])
      if (stopped || epoch !== generation) return false
      if (kind !== 'delete' && (!result || typeof result.taskId !== 'string' || !UUID.test(result.taskId)
        || (kind !== 'submit' && result.taskId.toLowerCase() !== taskId.value.toLowerCase())
        || !Number.isSafeInteger(result.revision) || result.revision < 0
        || (!ACTIVE.has(result.status) && !TERMINAL.has(result.status)))) {
        throw Object.assign(new Error('invalid task response'), { code: 'INVALID_RESPONSE' })
      }
      accept(result)
      uncertain.value = false
      ok = true
    } catch (e) {
      if (stopped || epoch !== generation) return false
      if (kind === 'submit') uncertain.value = !e.status || e.status >= 500
      failure(e)
      conflict = e.status === 409 && (kind === 'retry' || kind === 'delete')
    } finally {
      clock.clearTimeout(deadline)
      if (epoch === generation) {
        active = null
        busy.value = ''
        if (ok) schedule()
      }
    }
    if (conflict && epoch === generation && !stopped) {
      await refresh()
      if (epoch === generation && !stopped && !error.value) {
        error.value = '任务状态或执行预算冲突，已刷新最新状态；未重复执行操作。'
      }
    }
    return ok
  }
  async function load(id) {
    if (stopped || (id === taskId.value && task.value)) return
    invalidate()
    task.value = null
    taskId.value = id || ''
    submission = null
    uncertain.value = false
    error.value = ''
    paused.value = false
    if (!id) return
    if (typeof id !== 'string' || !UUID.test(id)) {
      error.value = '任务 ID 格式不合法，请检查页面地址。'
      paused.value = true
      return
    }
    await refresh()
  }
  async function refresh() {
    if (!UUID.test(taskId.value)) return
    return run('query', signal => api.query(taskId.value, { signal }), result => { task.value = result })
  }
  async function submit(topic, request = '') {
    if (stopped || busy.value || taskId.value) return
    const input = { topic: typeof topic === 'string' ? topic.trim() : '', request: typeof request === 'string' ? request.trim() : '' }
    if (!input.topic || input.topic.length > 200 || input.request.length > 2000) {
      error.value = '主题需为 1–200 字符，学习要求最多 2000 字符。'
      return
    }
    const fingerprint = JSON.stringify(input)
    if (uncertain.value && submission?.fingerprint !== fingerprint) {
      error.value = '上次提交结果尚未确认。请重发原输入，或明确新建预览。'
      return
    }
    if (submission?.fingerprint !== fingerprint) submission = { fingerprint, key: newKey() }
    const key = submission.key
    return run('submit', signal => api.submit(input, key, { signal }), result => {
      taskId.value = result.taskId
      task.value = result
    })
  }
  async function retry(nodes = []) {
    if (!canRetry.value || nodes.some(id => !failedNodes.value.includes(id))) {
      error.value = '只能重试失败或超时节点，最终状态与执行预算由服务端校验。'
      return
    }
    return run('retry', signal => api.retry(taskId.value, task.value.revision, nodes, { signal }), result => { task.value = result })
  }
  async function remove() {
    if (!canDelete.value) { error.value = '只允许删除终态任务，删除不是取消执行。'; return }
    return run('delete', signal => api.delete(taskId.value, { signal }), () => {
      task.value = null
      taskId.value = ''
      submission = null
    })
  }
  function reset() {
    invalidate()
    task.value = null
    taskId.value = ''
    error.value = ''
    paused.value = false
    uncertain.value = false
    submission = null
  }
  function dispose() { stopped = true; invalidate() }
  return { task, taskId, busy, error, paused, uncertain, canRetry, canDelete, failedNodes,
    load, submit, refresh, retry, remove, reset, dispose }
}
