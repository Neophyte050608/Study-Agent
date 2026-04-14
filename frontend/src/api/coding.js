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

export function submitScenarioCard({ messageId, chatSessionId, sessionId, cardId, answer }) {
  return httpPostJson('/api/coding/scenario-card/submit', {
    messageId,
    chatSessionId,
    sessionId,
    cardId,
    answer
  })
}

export function nextScenarioCard({ messageId, chatSessionId, sessionId, cardId }) {
  return httpPostJson('/api/coding/scenario-card/next', {
    messageId,
    chatSessionId,
    sessionId,
    cardId
  })
}

export function submitFillCard({ messageId, chatSessionId, sessionId, cardId, answer }) {
  return httpPostJson('/api/coding/fill-card/submit', {
    messageId,
    chatSessionId,
    sessionId,
    cardId,
    answer
  })
}

export function nextFillCard({ messageId, chatSessionId, sessionId, cardId }) {
  return httpPostJson('/api/coding/fill-card/next', {
    messageId,
    chatSessionId,
    sessionId,
    cardId
  })
}
