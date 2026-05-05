import { httpGet, httpPostJson, httpPut, httpDelete } from './http'

export function loadOpsOverview() {
  return httpGet('/api/observability/rag/overview')
}

export function loadOpsTraces(filters = {}) {
  const params = new URLSearchParams()
  if (filters.limit) params.set('limit', String(filters.limit))
  if (filters.status && filters.status !== 'ALL') params.set('status', filters.status)
  if (filters.riskyOnly) params.set('riskyOnly', 'true')
  if (filters.fallbackOnly) params.set('fallbackOnly', 'true')
  if (filters.emptyRetrievalOnly) params.set('emptyRetrievalOnly', 'true')
  if (filters.slowOnly) params.set('slowOnly', 'true')
  if (filters.q) params.set('q', filters.q)
  if (filters.startedAfter) params.set('startedAfter', filters.startedAfter)
  if (filters.endedBefore) params.set('endedBefore', filters.endedBefore)
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return httpGet(`/api/observability/rag-traces${suffix}`)
}

export function loadSkillTelemetry(filters = {}) {
  const params = new URLSearchParams()
  if (filters.limit) params.set('limit', String(filters.limit))
  if (filters.skillId && filters.skillId !== 'ALL') params.set('skillId', filters.skillId)
  if (filters.status && filters.status !== 'ALL') params.set('status', filters.status)
  if (filters.traceId) params.set('traceId', filters.traceId)
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return httpGet(`/api/observability/skills${suffix}`)
}

export function loadOpsTraceDetail(traceId) {
  return httpGet(`/api/observability/rag-traces/${traceId}`)
}

export function loadOpsIdempotency() {
  return httpGet('/api/observability/a2a/idempotency')
}

export function loadOpsAudits(limit = 5) {
  return httpGet(`/api/observability/audit/ops?limit=${limit}`)
}

export function clearIdempotencyCache() {
  return httpPostJson('/api/observability/a2a/idempotency/clear', {})
}

export function replayDlq() {
  return httpPostJson('/api/observability/a2a/dlq/replay', {})
}

export function runRetrievalEval(dataset) {
  const params = dataset ? `?dataset=${encodeURIComponent(dataset)}` : ''
  return httpGet(`/api/observability/retrieval-eval${params}`)
}

export function loadRetrievalEvalDatasets() {
  return httpGet('/api/observability/retrieval-eval/datasets')
}

export function loadRetrievalEvalRuns(limit = 20) {
  return httpGet(`/api/observability/retrieval-eval/runs?limit=${limit}`)
}

export function loadRetrievalMetrics(params = {}) {
  const query = new URLSearchParams()
  if (params.limit) query.set('limit', String(params.limit))
  if (params.hours) query.set('hours', String(params.hours))
  if (params.dataset) query.set('dataset', params.dataset)
  const suffix = query.toString() ? `?${query.toString()}` : ''
  return httpGet(`/api/observability/retrieval-metrics${suffix}`)
}

export function loadRagQualityEvalDatasets() {
  return httpGet('/api/observability/rag-quality-eval/datasets')
}

export function loadAgentSettings() {
  return httpGet('/api/settings/agents')
}

export function saveAgentSettings(payload) {
  return httpPostJson('/api/settings/agents', payload)
}

export function loadMenuSettings() {
  return httpGet('/api/settings/menu')
}

export function saveMenuLayout(payload) {
  return httpPostJson('/api/settings/menu/layout', payload)
}

export function loadMcpCapabilities() {
  const traceId = `trace-${Date.now()}`
  return httpGet(`/api/mcp/capabilities?traceId=${traceId}`)
}

export function invokeMcpCapability(capability, params) {
  return httpPostJson('/api/mcp/invoke', {
    capability,
    method: capability,
    params,
    traceId: `trace-${Date.now()}`
  })
}

export function loadIntentTreeConfig() {
  return httpGet('/api/intent-tree/config')
}

export function loadIntentTreeStats() {
  return httpGet('/api/intent-tree/stats')
}

export function saveIntentTreeConfig(payload) {
  return httpPostJson('/api/intent-tree/config', payload)
}


// ===== 提示词管理 =====
export function loadPromptTemplates(category, type) {
  let url = '/api/settings/prompts'
  const params = []
  if (category) params.push(`category=${category}`)
  if (type) params.push(`type=${type}`)
  if (params.length) url += '?' + params.join('&')
  return httpGet(url)
}

export function loadPromptTemplate(name) {
  return httpGet(`/api/settings/prompts/${name}`)
}

export function createPromptTemplate(payload) {
  return httpPostJson('/api/settings/prompts', payload)
}

export function updatePromptTemplate(name, payload) {
  return httpPut(`/api/settings/prompts/${name}`, payload)
}

export function deletePromptTemplate(name) {
  return httpDelete(`/api/settings/prompts/${name}`)
}

export function previewPromptTemplate(name, variables) {
  return httpPostJson(`/api/settings/prompts/${name}/preview`, variables || {})
}

export function reloadPromptCache() {
  return httpPostJson('/api/settings/prompts/reload', {})
}

// ===== RAG 生成质量评测 =====
export function runRagQualityEval(engine) {
  const params = engine ? `?engine=${engine}` : ''
  return httpGet(`/api/observability/rag-quality-eval${params}`)
}

export function runRagQualityEvalWithDataset(dataset, engine) {
  const params = new URLSearchParams()
  if (dataset) params.set('dataset', dataset)
  if (engine) params.set('engine', engine)
  const suffix = params.toString() ? `?${params.toString()}` : ''
  return httpGet(`/api/observability/rag-quality-eval${suffix}`)
}

export function runRagQualityEvalCustom(cases, options, engine) {
  return httpPostJson('/api/observability/rag-quality-eval/run', { cases, options, engine })
}

export function loadRagQualityEvalRuns(limit = 20) {
  return httpGet(`/api/observability/rag-quality-eval/runs?limit=${limit}`)
}

export function loadRagQualityEvalDetail(runId) {
  return httpGet(`/api/observability/rag-quality-eval/runs/${runId}`)
}

export function compareRagQualityEvalRuns(baselineRunId, candidateRunId) {
  return httpGet(`/api/observability/rag-quality-eval/compare?baselineRunId=${baselineRunId}&candidateRunId=${candidateRunId}`)
}

export function loadRagQualityEvalTrend(limit = 20) {
  return httpGet(`/api/observability/rag-quality-eval/trend?limit=${limit}`)
}

export function loadRagasEngineStatus() {
  return httpGet('/api/observability/rag-quality-eval/engine-status')
}

// ===== 模型候选管理 =====
export function loadModelCandidates() {
  return httpGet('/api/model-routing/candidates')
}

export function createModelCandidate(data) {
  return httpPostJson('/api/model-routing/candidates', data)
}

export function updateModelCandidate(id, data) {
  return httpPut(`/api/model-routing/candidates/${id}`, data)
}

export function deleteModelCandidate(id) {
  return httpDelete(`/api/model-routing/candidates/${id}`)
}

export function toggleModelCandidate(id) {
  return httpPostJson(`/api/model-routing/candidates/${id}/toggle`, {})
}

export function probeModelCandidate(id) {
  return httpPostJson(`/api/model-routing/candidates/${id}/probe`, {})
}

export function copyModelCandidateKey(id) {
  return httpPostJson(`/api/model-routing/candidates/${id}/copy-key`, {})
}

export function loadModelRoutingStats() {
  return httpGet('/api/model-routing/stats')
}

export async function loadRagDashboard() {
  return httpGet('/api/observability/rag/dashboard')
}

export async function loadMetricsHistory(hours = 168, metric = 'avgLatencyMs,p95LatencyMs,satisfactionRate') {
  return httpGet(`/api/observability/rag/metrics/history?hours=${hours}&metric=${encodeURIComponent(metric)}`)
}

export async function loadMetricsSummary() {
  return httpGet('/api/observability/rag/metrics/summary')
}
