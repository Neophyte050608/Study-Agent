import { httpGet, httpPostJson } from './http.js'

export function loadIntentTreeConfig() {
  return httpGet('/api/intent-tree/config')
}

export function loadIntentTreeStats() {
  return httpGet('/api/intent-tree/stats')
}

export function saveIntentTreeConfig(payload) {
  return httpPostJson('/api/intent-tree/config', payload)
}
