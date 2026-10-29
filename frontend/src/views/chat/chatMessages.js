let clientMessageSequence = 0

const nonblankString = (value) => typeof value === 'string' ? value.trim() : ''

const normalizeImageItem = (item) => {
  if (!item || typeof item !== 'object') return null
  const source = item.value && typeof item.value === 'object' ? item.value : item
  return {
    ...source,
    position: item.position ?? source.position
  }
}

const parseStructuredMessage = (message) => {
  const metadata = message?.metadata && typeof message.metadata === 'object'
    ? message.metadata
    : {}
  const normalized = { ...message, ...metadata }

  if (!message?.content || typeof message.content !== 'string') return normalized

  const payloadFieldByType = {
    scenario_card: 'scenarioPayload',
    fill_card: 'fillPayload',
    interview_card: 'interviewPayload',
    quiz: 'quizPayload'
  }
  const payloadField = payloadFieldByType[message.contentType]

  try {
    const payload = JSON.parse(message.content)
    if (payloadField) {
      return { ...normalized, [payloadField]: payload, content: '' }
    }
    if (message.contentType === 'rich') {
      return {
        ...normalized,
        content: typeof payload?.text === 'string' ? payload.text : '',
        images: Array.isArray(payload?.images)
          ? payload.images.map(normalizeImageItem).filter(Boolean)
          : []
      }
    }
  } catch {
    // Keep the original message visible when a structured payload is malformed.
  }

  return normalized
}

const numericId = (message) => {
  if (message?.id === null || message?.id === undefined || message.id === '') return null
  const value = Number(message.id)
  return Number.isFinite(value) ? value : null
}

const timestamp = (message) => {
  const value = Date.parse(message?.createdAt)
  return Number.isFinite(value) ? value : null
}

export function normalizeChatMessages(rawMessages = []) {
  if (!Array.isArray(rawMessages)) return []

  const seenMessageIds = new Set()
  return rawMessages
    .map((message, sourceIndex) => ({
      message: parseStructuredMessage(message && typeof message === 'object' ? message : {}),
      sourceIndex
    }))
    .filter(({ message }) => {
      const messageId = nonblankString(message.messageId)
      if (!messageId) return true
      if (seenMessageIds.has(messageId)) return false
      seenMessageIds.add(messageId)
      return true
    })
    .sort((left, right) => {
      const leftTimestamp = timestamp(left.message)
      const rightTimestamp = timestamp(right.message)
      if (leftTimestamp !== rightTimestamp) {
        if (leftTimestamp === null) return 1
        if (rightTimestamp === null) return -1
        return leftTimestamp - rightTimestamp
      }

      const leftId = numericId(left.message)
      const rightId = numericId(right.message)
      if (leftId !== rightId) {
        if (leftId === null) return 1
        if (rightId === null) return -1
        return leftId - rightId
      }

      return left.sourceIndex - right.sourceIndex
    })
    .map(({ message }) => message)
}

export function messageRenderKey(message = {}) {
  const messageId = nonblankString(message.messageId)
  if (messageId) return `message:${messageId}`

  const clientKey = nonblankString(message.clientKey)
  if (clientKey) return `client:${clientKey}`

  if (message.id !== null && message.id !== undefined && String(message.id).trim()) {
    return `id:${String(message.id).trim()}`
  }

  return `message:${message.role || 'unknown'}:${message.createdAt || ''}:${message.content || ''}`
}

export function createClientMessage(fields = {}) {
  let clientKey = nonblankString(fields.clientKey)
  if (!clientKey) {
    const randomUUID = globalThis.crypto?.randomUUID
    clientKey = typeof randomUUID === 'function'
      ? randomUUID.call(globalThis.crypto)
      : `local-${++clientMessageSequence}`
  }
  return { ...fields, clientKey }
}
