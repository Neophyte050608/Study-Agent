import { httpPostJson } from './http'
import { createPostEventStream } from './stream'

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

export function startInterviewStream(topic, totalQuestions, handlers = {}, traceId = '') {
  return createPostEventStream('/api/interview/stream/start', {
    topic,
    resumePath: '',
    totalQuestions,
    traceId
  }, handlers)
}

export function submitInterviewAnswerStream(sessionId, answer, handlers = {}, traceId = '') {
  return createPostEventStream('/api/interview/stream/answer', {
    sessionId,
    answer,
    traceId
  }, handlers)
}

export function generateInterviewReportStream(sessionId, handlers = {}, traceId = '') {
  return createPostEventStream('/api/interview/stream/report', {
    sessionId,
    traceId
  }, handlers)
}

export function stopInterviewStream(streamTaskId) {
  return httpPostJson('/api/interview/stream/stop', {
    streamTaskId
  })
}
