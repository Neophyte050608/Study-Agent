import { httpDelete, httpGet, httpPostJson, httpPut } from './http.js'

export function loadPromptTemplates(category, type) {
  let url = '/api/settings/prompts'
  const params = []
  if (category) params.push(`category=${category}`)
  if (type) params.push(`type=${type}`)
  if (params.length) url += `?${params.join('&')}`
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
