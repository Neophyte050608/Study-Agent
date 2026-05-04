import { httpGet, httpPostJson, httpPut, httpDelete } from './http'
import { createPostEventStream } from './stream'

// 创建聊天会话。
// title 为空时由后端兜底为“新对话”。
export function createChatSession(title = '') {
  return httpPostJson('/api/chat/sessions', title ? { title } : {})
}

// 拉取当前用户的会话列表（按后端排序返回）。
export function listChatSessions() {
  return httpGet('/api/chat/sessions')
}

// 重命名指定会话。
export function renameChatSession(sessionId, title) {
  return httpPut(`/api/chat/sessions/${sessionId}`, { title })
}

// 删除指定会话（通常是软删/逻辑删或直接删，取决于后端实现）。
export function deleteChatSession(sessionId) {
  return httpDelete(`/api/chat/sessions/${sessionId}`)
}

// 分页拉取消息记录。
// limit: 最大条数；beforeId: 游标（取更早消息）。
export function listChatMessages(sessionId, limit = 50, beforeId = null) {
  let url = `/api/chat/sessions/${sessionId}/messages?limit=${limit}`
  if (beforeId) url += `&beforeId=${beforeId}`
  return httpGet(url)
}

// 以 SSE 方式发起聊天，返回可 start/cancel 的句柄对象。
export function streamChat(sessionId, content, handlers = {}, options = {}) {
  // Web 端最小请求体：用户输入 content。
  const payload = { content }
  // 可选带上检索模式，用于控制后端 RAG 策略。
  if (options?.retrievalMode) {
    payload.retrievalMode = options.retrievalMode
  }
  // 由 stream.js 统一处理 SSE 连接、事件分发、重试。
  return createPostEventStream(
    `/api/chat/sessions/${sessionId}/stream`,
    payload,
    handlers
  )
}

// 主动停止正在进行的流式任务（对应后端 taskId）。
export function stopChatStream(streamTaskId) {
  return httpPostJson('/api/chat/stream/stop', { streamTaskId })
}
