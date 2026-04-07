import { httpGet, httpPostFormData, httpPostJson, httpPatch, httpDelete } from './http'

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

export function getKnowledgeBases() {
  return httpGet('/api/knowledge-bases')
}

export function getDocuments(kbId, params) {
  const query = new URLSearchParams()
  if (params.pageNo) query.append('pageNo', params.pageNo)
  if (params.pageSize) query.append('pageSize', params.pageSize)
  if (params.status) query.append('status', params.status)
  if (params.keyword) query.append('keyword', params.keyword)
  return httpGet(`/api/knowledge-bases/${kbId}/documents?${query.toString()}`)
}

export function getChunks(docId, params) {
  const query = new URLSearchParams()
  if (params.current) query.append('current', params.current)
  if (params.size) query.append('size', params.size)
  if (params.enabled !== undefined) query.append('enabled', params.enabled)
  return httpGet(`/api/knowledge-documents/${docId}/chunks?${query.toString()}`)
}

export function setDocumentEnabled(docId, enabled) {
  return httpPatch(`/api/knowledge-documents/${docId}/enabled?value=${enabled}`, {})
}

export function removeDocument(docId) {
  return httpDelete(`/api/knowledge-documents/${docId}`)
}

export function rechunkDocument(docId) {
  return httpPostJson(`/api/knowledge-documents/${docId}/rechunk`, {})
}

export function setChunkEnabled(docId, chunkId, enabled) {
  return httpPatch(`/api/knowledge-documents/${docId}/chunks/${chunkId}/enabled?value=${enabled}`, {})
}

export function loadLocalKnowledgeIndexStatus() {
  return httpGet('/api/knowledge-retrieval/index/status')
}

export function buildLocalKnowledgeIndex(payload) {
  return httpPostJson('/api/knowledge-retrieval/index/build', payload || {})
}
