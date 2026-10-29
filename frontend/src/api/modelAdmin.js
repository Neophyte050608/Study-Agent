import { httpDelete, httpGet, httpPostJson, httpPut } from './http'
export const loadModelCandidates = () => httpGet('/api/model-routing/candidates')
export const loadModelRoutingStats = () => httpGet('/api/model-routing/stats')
export const createModelCandidate = data => httpPostJson('/api/model-routing/candidates', data)
export const updateModelCandidate = (id, data) => httpPut(`/api/model-routing/candidates/${id}`, data)
export const deleteModelCandidate = id => httpDelete(`/api/model-routing/candidates/${id}`)
export const toggleModelCandidate = id => httpPostJson(`/api/model-routing/candidates/${id}/toggle`, {})
export const probeModelCandidate = id => httpPostJson(`/api/model-routing/candidates/${id}/probe`, {})
export const copyModelCandidateKey = id => httpPostJson(`/api/model-routing/candidates/${id}/copy-key`, {})
