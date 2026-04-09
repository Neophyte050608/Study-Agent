import { httpGet, httpPostJson } from './http'

export function fetchSuggestions(query, limit = 10) {
  return httpGet(`/api/autocomplete?q=${encodeURIComponent(query)}&limit=${limit}`)
}

export function recordClick(entryId) {
  return httpPostJson('/api/autocomplete/click', { entryId })
}
