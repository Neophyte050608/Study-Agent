function unwrap(json) {
  if (json && json.code === 0) {
    return json.data
  }
  return json
}

function getAuthHeaders() {
  const headers = {}
  const token = localStorage.getItem('auth_token')
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  return headers
}

async function readResponse(response) {
  const raw = await response.text()
  let data = {}
  try { if (raw) data = JSON.parse(raw) } catch {
    if (response.ok) throw Object.assign(new Error('响应格式不合法'), { code: 'INVALID_RESPONSE' })
    // Never expose raw server pages, including malformed error responses.
  }
  if (!response.ok) {
    const error = new Error(typeof data?.message === 'string' ? data.message : `请求失败: ${response.status}`)
    error.status = response.status
    error.code = typeof data?.code === 'string' ? data.code : null
    throw error
  }
  return unwrap(data)
}

export async function httpGet(url, options = {}) {
  return readResponse(await fetch(url, { headers: { ...options.headers, ...getAuthHeaders() }, signal: options.signal }))
}

export async function httpPostJson(url, payload, options = {}) {
  return readResponse(await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...options.headers, ...getAuthHeaders() },
    body: JSON.stringify(payload), signal: options.signal
  }))
}

export async function httpPostFormData(url, formData) {
  const response = await fetch(url, {
    method: 'POST',
    headers: getAuthHeaders(),
    body: formData
  })
  const raw = await response.text()
  let data = {}
  if (raw) {
    try {
      data = JSON.parse(raw)
    } catch {
      data = { message: raw }
    }
  }
  if (!response.ok) {
    throw new Error(data.message || `请求失败: ${response.status}`)
  }
  return unwrap(data)
}

export async function httpPut(url, payload = {}) {
  const response = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...getAuthHeaders() },
    body: JSON.stringify(payload)
  })
  const raw = await response.text()
  let data = {}
  if (raw) {
    try {
      data = JSON.parse(raw)
    } catch {
      data = { message: raw }
    }
  }
  if (!response.ok) {
    throw new Error(data.message || `请求失败: ${response.status}`)
  }
  return unwrap(data)
}

export async function httpPatch(url, payload = {}) {
  const response = await fetch(url, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json', ...getAuthHeaders() },
    body: JSON.stringify(payload)
  })
  const raw = await response.text()
  let data = {}
  if (raw) {
    try {
      data = JSON.parse(raw)
    } catch {
      data = { message: raw }
    }
  }
  if (!response.ok) {
    throw new Error(data.message || `请求失败: ${response.status}`)
  }
  return unwrap(data)
}

export async function httpDelete(url, options = {}) {
  return readResponse(await fetch(url, { method: 'DELETE', headers: getAuthHeaders(), signal: options.signal }))
}
