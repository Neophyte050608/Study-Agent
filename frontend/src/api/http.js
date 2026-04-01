function getAuthHeaders() {
  const headers = {}
  const token = localStorage.getItem('auth_token')
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  return headers
}

export async function httpGet(url) {
  const response = await fetch(url, { headers: getAuthHeaders() })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `请求失败: ${response.status}`)
  }
  return response.json()
}

export async function httpPostJson(url, payload) {
  const response = await fetch(url, {
    method: 'POST',
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
  return data
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
  return data
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
  return data
}

export async function httpDelete(url) {
  const response = await fetch(url, { method: 'DELETE', headers: getAuthHeaders() })
  if (response.status === 204 || response.headers.get('content-length') === '0') return {}
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
  return data
}
