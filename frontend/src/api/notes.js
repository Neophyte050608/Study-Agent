import { httpGet, httpPostFormData, httpPostJson } from './http'

export function loadIngestConfig() {
  return httpGet('/api/ingest/config')
}

export function saveIngestConfig(payload) {
  return httpPostJson('/api/ingest/config', payload)
}

export function runIngest(payload) {
  return httpPostJson('/api/ingest', payload)
}

export function uploadResumePdf(file) {
  const formData = new FormData()
  formData.append('file', file)
  return httpPostFormData('/api/resume/upload', formData)
}

export function loadIngestStats() {
  return httpGet('/api/observability/ingest/stats')
}
