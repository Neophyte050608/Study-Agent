import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  createClientMessage,
  messageRenderKey,
  normalizeChatMessages
} from '../src/views/chat/chatMessages.js'

test('normalizes server messages in stable chronological order', () => {
  const result = normalizeChatMessages([
    { id: 12, messageId: 'a-12', role: 'assistant', createdAt: '2026-10-29T20:00:00', content: 'answer' },
    { id: 11, messageId: 'u-11', role: 'user', createdAt: '2026-10-29T20:00:00', content: 'question' }
  ])

  assert.deepEqual(result.map(message => message.id), [11, 12])
})

test('keeps source order when timestamps and numeric ids are equal', () => {
  const result = normalizeChatMessages([
    { id: '7', content: 'first' },
    { id: 7, content: 'second' }
  ])

  assert.deepEqual(result.map(message => message.content), ['first', 'second'])
})

test('deduplicates only persisted messages with the same nonblank messageId', () => {
  const result = normalizeChatMessages([
    { id: 1, messageId: ' duplicate ', content: 'first persisted value' },
    { id: 2, messageId: 'duplicate', content: 'duplicate persisted value' },
    { id: 3, messageId: '', content: 'local one' },
    { id: 4, content: 'local two' }
  ])

  assert.deepEqual(result.map(message => message.content), [
    'first persisted value',
    'local one',
    'local two'
  ])
})

test('parses every supported structured message payload', () => {
  const [scenario, fill, interview, quiz, rich] = normalizeChatMessages([
    { contentType: 'scenario_card', content: '{"title":"scenario"}' },
    { contentType: 'fill_card', content: '{"answer":"fill"}' },
    { contentType: 'interview_card', content: '{"question":"interview"}' },
    { contentType: 'quiz', content: '{"question":"quiz"}' },
    { contentType: 'rich', content: '{"text":"rich text","images":[{"value":{"url":"/image.png"},"position":2}]}' }
  ])

  assert.deepEqual(scenario.scenarioPayload, { title: 'scenario' })
  assert.deepEqual(fill.fillPayload, { answer: 'fill' })
  assert.deepEqual(interview.interviewPayload, { question: 'interview' })
  assert.deepEqual(quiz.quizPayload, { question: 'quiz' })
  assert.equal(rich.content, 'rich text')
  assert.deepEqual(rich.images, [{ url: '/image.png', position: 2 }])
})

test('preserves original content when structured parsing fails', () => {
  const [message] = normalizeChatMessages([
    { contentType: 'quiz', content: '{not-json', metadata: { routeLabel: 'quiz-agent' } }
  ])

  assert.equal(message.content, '{not-json')
  assert.equal(message.routeLabel, 'quiz-agent')
  assert.equal(message.quizPayload, undefined)
})

test('exposes execution metadata on normalized messages', () => {
  const [message] = normalizeChatMessages([{
    id: 1,
    content: 'answer',
    metadata: {
      routeLabel: 'knowledge-route',
      routeSource: 'planner',
      retrievalModeResolved: 'HYBRID_FUSION',
      localGraphUsed: true,
      ragUsed: true,
      fallbackReason: 'timeout'
    }
  }])

  assert.equal(message.routeLabel, 'knowledge-route')
  assert.equal(message.routeSource, 'planner')
  assert.equal(message.retrievalModeResolved, 'HYBRID_FUSION')
  assert.equal(message.localGraphUsed, true)
  assert.equal(message.ragUsed, true)
  assert.equal(message.fallbackReason, 'timeout')
})

test('render keys prefer messageId, then clientKey, then persisted id without an index', () => {
  assert.equal(messageRenderKey({ messageId: ' server-id ', clientKey: 'client-id', id: 9 }), 'message:server-id')
  assert.equal(messageRenderKey({ messageId: ' ', clientKey: 'client-id', id: 9 }), 'client:client-id')
  assert.equal(messageRenderKey({ id: 9 }), 'id:9')
  assert.equal(messageRenderKey({ role: 'assistant', createdAt: 'now', content: 'answer' }), 'message:assistant:now:answer')
})

test('client messages always receive an explicit key without mutating fields', () => {
  const fields = { role: 'assistant', content: 'local response' }
  const message = createClientMessage(fields)

  assert.equal(message.role, fields.role)
  assert.equal(message.content, fields.content)
  assert.equal(typeof message.clientKey, 'string')
  assert.ok(message.clientKey.length > 0)
  assert.equal(fields.clientKey, undefined)
  assert.equal(messageRenderKey(message), `client:${message.clientKey}`)
})
