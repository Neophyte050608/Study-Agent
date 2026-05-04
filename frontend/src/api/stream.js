export function createPostEventStream(url, payload, handlers = {}, options = {}) {
  // 该 controller 用于本次请求的主动取消（如“停止生成”按钮）。
  const controller = new AbortController()
  // 如果上层传了 signal，就复用上层；否则使用内部 controller 的 signal。
  const signal = options.signal || controller.signal
  const headers = {
    // 告知后端我们期望 SSE 响应。
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
    ...(options.headers || {})
  }
  // 可配置重试参数：失败时指数退避。
  const retryCount = options.retryCount ?? 0
  const retryDelayMs = options.retryDelayMs ?? 500

  // 尝试把 data 字段解析为 JSON，失败则按文本处理。
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
      // SSE 允许一个事件多行 data，这里合并后再交给解析器。
      const rawText = dataLines.join('\n')
      // [BUG FIX]: JSON 数据跨越多行时，data: 前缀会被剥离，但有些换行符在 JSON 字符串里必须保留为 \n，
      // 如果使用纯 join('\n') 对于非 JSON 会多出换行，但如果是 JSON 字符串，我们需要保证解析正确。
      // 当前 `readStream` 会按行切分 `buffer`，如果是 `data: {...` 然后 `data: }`，合并后是 `{...\n}`，JSON.parse() 可以处理。
      // 问题出在如果有空行（\n\n），会被当做 SSE 事件结束符。这是 SSE 的标准，所以一个事件内的 JSON 必须单行或由后端的 ObjectMapper 生成不带空行的格式。
      const payloadData = parseData(rawText)
      // 先给通用 onEvent 兜底回调。
      handlers.onEvent?.(eventName, payloadData)
    switch (eventName) {
      // 以下是本项目约定的 SSE 事件类型。
      case 'meta':
        handlers.onMeta?.(payloadData)
        break
      case 'progress':
        handlers.onProgress?.(payloadData)
        break
      case 'message':
        handlers.onMessage?.(payloadData)
        break
      case 'quiz':
        handlers.onQuiz?.(payloadData)
        break
      case 'image':
        handlers.onImage?.(payloadData)
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
    // 使用 ReadableStream reader 按块读取响应体。
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    // buffer 用于拼接跨 chunk 的半行内容。
    let buffer = ''
    // SSE 默认事件类型为 message。
    let eventName = 'message'
    let dataLines = []
    while (true) {
      // 前端主动取消时，取消 reader 并退出循环。
      if (signal.aborted) {
        await reader.cancel()
        break
      }
      const { value, done } = await reader.read()
      if (done) {
        // 流结束前把最后一帧未分发数据补发。
        dispatch(eventName, dataLines)
        break
      }
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() ?? ''
      for (const line of lines) {
        // 空行是 SSE 一个事件的结束标志。
        if (!line) {
          dispatch(eventName, dataLines)
          eventName = 'message'
          dataLines = []
          continue
        }
        // ":" 开头是 SSE 注释行，直接忽略。
        if (line.startsWith(':')) continue
        // event: xxx 设置事件名。
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
          continue
        }
        // data: xxx 追加当前事件的数据行。
        if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).trim())
        }
      }
    }
  }

  const start = async () => {
    let attempt = 0
    // 支持失败重试，避免短时网络抖动导致整次会话失败。
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
        // 用户主动取消时，不再重试，直接抛出给上层处理。
        if (signal.aborted) {
          throw error
        }
        // 达到重试上限则失败返回。
        if (attempt >= retryCount) {
          throw error
        }
        // 指数退避：500ms、1000ms、2000ms...
        await new Promise((resolve) => setTimeout(resolve, retryDelayMs * Math.pow(2, attempt)))
        attempt += 1
      }
    }
  }

  return {
    start,
    // 暴露 cancel，供页面“停止生成”或切换会话时调用。
    cancel: () => controller.abort()
  }
}
