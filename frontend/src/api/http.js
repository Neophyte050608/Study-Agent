export async function httpGet(url) {
  const response = await fetch(url)
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `请求失败: ${response.status}`)
  }
  return response.json()
}

export async function httpPostJson(url, payload) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
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
