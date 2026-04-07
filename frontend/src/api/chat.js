import { httpGet, httpPostJson, httpPut, httpDelete } from './http'
import { createPostEventStream } from './stream'

export function createChatSession(title = '') {
  return httpPostJson('/api/chat/sessions', title ? { title } : {})
}

export function listChatSessions() {
  return httpGet('/api/chat/sessions')
}

export function renameChatSession(sessionId, title) {
  return httpPut(`/api/chat/sessions/${sessionId}`, { title })
}

export function deleteChatSession(sessionId) {
  return httpDelete(`/api/chat/sessions/${sessionId}`)
}

export function listChatMessages(sessionId, limit = 50, beforeId = null) {
  let url = `/api/chat/sessions/${sessionId}/messages?limit=${limit}`
  if (beforeId) url += `&beforeId=${beforeId}`
  return httpGet(url)
}

export function streamChat(sessionId, content, handlers = {}, options = {}) {
  const payload = { content }
  if (options?.retrievalMode) {
    payload.retrievalMode = options.retrievalMode
  }
  return createPostEventStream(
    `/api/chat/sessions/${sessionId}/stream`,
    payload,
    handlers
  )
}

export function stopChatStream(streamTaskId) {
  return httpPostJson('/api/chat/stream/stop', { streamTaskId })
}
