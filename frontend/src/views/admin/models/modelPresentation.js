export function apiKeyState(candidate = {}) {
  if (!candidate.apiKeyConfigured) return { label: '未配置', detail: '未配置 API Key', canReplace: true }
  if (!candidate.apiKeyReadable) return { label: '不可读', detail: candidate.apiKeyMasked || '密钥解密失败', canReplace: true }
  return { label: '已配置', detail: candidate.apiKeyMasked || '已安全保存', canReplace: true }
}
export function routingHealthTone(state) {
  if (state === 'CLOSED') return 'healthy'
  if (state === 'OPEN') return 'danger'
  if (state === 'HALF_OPEN') return 'warning'
  return 'neutral'
}
