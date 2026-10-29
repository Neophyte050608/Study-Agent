const OVERVIEW_METRICS = [
  ['traceCount', '调用量', 0],
  ['successRate', '成功率', '0%'],
  ['avgLatencyMs', '平均延迟', 0],
  ['p95LatencyMs', 'P95 延迟', 0],
  ['fallbackRate', '回退率', '0%'],
  ['satisfactionRate', '满意度', '0%']
]

export function overviewCards(payload = {}) {
  const currentHour = payload?.currentHour && typeof payload.currentHour === 'object'
    ? payload.currentHour
    : {}
  const feedback = currentHour.feedback && typeof currentHour.feedback === 'object'
    ? currentHour.feedback
    : {}

  return OVERVIEW_METRICS.map(([key, label, fallback]) => ({
    key,
    label,
    value: key === 'satisfactionRate'
      ? (currentHour[key] ?? feedback[key] ?? fallback)
      : (currentHour[key] ?? fallback)
  }))
}

export function buildTraceQuery(filters = {}) {
  const query = {}
  const search = String(filters.search || '').trim()
  const status = String(filters.status || '').trim()
  if (search) query.search = search
  if (status && status !== 'ALL') query.status = status
  if (filters.risky === true) query.risky = true
  if (filters.fallback === true) query.fallback = true
  if (filters.emptyRetrieval === true) query.emptyRetrieval = true
  if (filters.slow === true) query.slow = true
  return query
}

export function normalizeEvaluationRuns(payload) {
  if (Array.isArray(payload)) return payload
  return Array.isArray(payload?.runs) ? payload.runs : []
}

export function auditSummary(idempotency = {}, audits = []) {
  const inMemorySize = Number(idempotency?.inMemorySize) || 0
  const redisSize = Number(idempotency?.redisSize) || 0
  return {
    idempotencyTotal: inMemorySize + redisSize,
    auditCount: Array.isArray(audits) ? audits.length : 0
  }
}
