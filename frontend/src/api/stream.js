export function createPostEventStream(url, payload, handlers = {}, options = {}) {
  const controller = new AbortController()
  const signal = options.signal || controller.signal
  const headers = {
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
    ...(options.headers || {})
  }
  const retryCount = options.retryCount ?? 0
  const retryDelayMs = options.retryDelayMs ?? 500

  const parseData = (raw) => {
    if (!raw) return ''
    try {
      return JSON.parse(raw)
    } catch {
      return raw
    }
  }

  const dispatch = (eventName, dataLines) => {
    if (!dataLines.length) return
    const payloadData = parseData(dataLines.join('\n'))
    handlers.onEvent?.(eventName, payloadData)
    switch (eventName) {
      case 'meta':
        handlers.onMeta?.(payloadData)
        break
      case 'progress':
        handlers.onProgress?.(payloadData)
        break
      case 'message':
        handlers.onMessage?.(payloadData)
        break
      case 'finish':
        handlers.onFinish?.(payloadData)
        break
      case 'cancel':
        handlers.onCancel?.(payloadData)
        break
      case 'done':
        handlers.onDone?.()
        break
      case 'error':
        handlers.onError?.(new Error(String(payloadData?.message || payloadData || '流式处理失败')))
        break
      default:
        break
    }
  }

  const readStream = async (response) => {
    if (!response.body) {
      throw new Error('流式响应为空')
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let eventName = 'message'
    let dataLines = []
    while (true) {
      if (signal.aborted) {
        await reader.cancel()
        break
      }
      const { value, done } = await reader.read()
      if (done) {
        dispatch(eventName, dataLines)
        break
      }
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() ?? ''
      for (const line of lines) {
        if (!line) {
          dispatch(eventName, dataLines)
          eventName = 'message'
          dataLines = []
          continue
        }
        if (line.startsWith(':')) continue
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
          continue
        }
        if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).trim())
        }
      }
    }
  }

  const start = async () => {
    let attempt = 0
    while (attempt <= retryCount) {
      try {
        const response = await fetch(url, {
          method: 'POST',
          headers,
          body: JSON.stringify(payload || {}),
          signal
        })
        if (!response.ok) {
          const raw = await response.text()
          throw new Error(raw || `流式请求失败: ${response.status}`)
        }
        await readStream(response)
        return
      } catch (error) {
        if (signal.aborted) {
          throw error
        }
        if (attempt >= retryCount) {
          throw error
        }
        await new Promise((resolve) => setTimeout(resolve, retryDelayMs * Math.pow(2, attempt)))
        attempt += 1
      }
    }
  }

  return {
    start,
    cancel: () => controller.abort()
  }
}
