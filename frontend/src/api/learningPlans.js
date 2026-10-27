import { httpGet, httpPostJson, httpDelete } from './http.js'

const BASE = '/api/learning/plan-previews'
const path = id => `${BASE}/${encodeURIComponent(id)}`

export const submitLearningPlan = (input, key, options = {}) =>
  httpPostJson(BASE, { topic: input.topic, request: input.request },
    { ...options, headers: { 'Idempotency-Key': key } })
export const queryLearningPlan = (id, options = {}) => httpGet(path(id), options)
export const retryLearningPlan = (id, revision, nodes = [], options = {}) =>
  httpPostJson(`${path(id)}/retry`, { expectedRevision: revision, nodes }, options)
export const deleteLearningPlan = (id, options = {}) => httpDelete(path(id), options)
