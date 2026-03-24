import { httpGet, httpPostJson } from './http'

export function loadOpsOverview() {
  return httpGet('/api/observability/rag/overview')
}

export function loadOpsTraces() {
  return httpGet('/api/observability/rag-traces')
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
