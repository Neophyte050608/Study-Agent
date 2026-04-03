import { httpGet, httpPostJson, httpPut, httpDelete } from './http'

export function loadOpsOverview() {
  return httpGet('/api/observability/rag/overview')
}

export function loadOpsTraces() {
  return httpGet('/api/observability/rag-traces')
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
export function runRagQualityEval() {
  return httpGet('/api/observability/rag-quality-eval')
}

export function runRagQualityEvalCustom(cases, options) {
  return httpPostJson('/api/observability/rag-quality-eval/run', { cases, options })
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
