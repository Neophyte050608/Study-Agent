import { httpGet, httpPostJson } from './http.js'

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
