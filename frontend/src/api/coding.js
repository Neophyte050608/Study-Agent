import { httpPostJson } from './http'

export function dispatchCodingChat(sessionId, message) {
  return httpPostJson('/api/task/dispatch', {
    taskType: 'CODING_PRACTICE',
    payload: {
      action: 'chat',
      sessionId,
      message
    }
  })
}
