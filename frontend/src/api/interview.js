import { httpPostJson } from './http'

export function startInterview(topic, totalQuestions) {
  return httpPostJson('/api/start', {
    topic,
    resumePath: '',
    totalQuestions
  })
}

export function submitInterviewAnswer(sessionId, answer) {
  return httpPostJson('/api/answer', {
    sessionId,
    answer
  })
}

export function generateInterviewReport(sessionId) {
  return httpPostJson('/api/report', {
    sessionId
  })
}
