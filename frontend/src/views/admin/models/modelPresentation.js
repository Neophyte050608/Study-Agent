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

export function createCandidateForm(candidate = {}) {
  return {
    name: candidate.name || '', displayName: candidate.displayName || '',
    provider: candidate.provider || 'openai', model: candidate.model || '', baseUrl: candidate.baseUrl || '',
    apiKey: '', clearApiKey: false, priority: Number(candidate.priority ?? 100),
    routeType: candidate.routeType || 'GENERAL', isPrimary: Boolean(candidate.isPrimary),
    supportsThinking: Boolean(candidate.supportsThinking), enabled: candidate.enabled == null ? true : Boolean(candidate.enabled)
  }
}

export function candidateFormError(form, creating) {
  for (const [field, label] of [['name', '模型唯一标识'], ['displayName', '显示名称'], ['model', '模型标识'], ['baseUrl', 'API 地址']]) {
    if (!String(form[field] || '').trim()) return `${label}不能为空`
  }
  if (form.priority == null || !Number.isFinite(Number(form.priority))) return '优先级必须是数字'
  if (form.clearApiKey && form.apiKey.trim()) return '不能同时清空和替换 API Key'
  if (creating && form.provider !== 'ollama' && !form.apiKey.trim()) return '创建云模型时必须填写 API Key'
  return ''
}

export function candidatePayload(form) {
  const key = form.apiKey.trim()
  return {
    name: form.name.trim(), displayName: form.displayName.trim(), provider: form.provider,
    model: form.model.trim(), baseUrl: form.baseUrl.trim(),
    ...(key ? { apiKey: key } : {}), clearApiKey: Boolean(form.clearApiKey),
    priority: Number(form.priority), routeType: form.routeType,
    isPrimary: Boolean(form.isPrimary), supportsThinking: Boolean(form.supportsThinking), enabled: Boolean(form.enabled)
  }
}

export function healthTagType(state) {
  return { healthy: 'success', danger: 'error', warning: 'warning', neutral: 'default' }[routingHealthTone(state)]
}

export function routingSummary(stats) {
  if (!stats?.runtime) return { label: '暂无运行快照', type: 'default' }
  const states = runtimeRows(stats).map(row => row.state)
  if (states.includes('OPEN')) return { label: '存在熔断模型', type: 'error' }
  if (states.includes('HALF_OPEN')) return { label: '模型恢复观察中', type: 'warning' }
  if (!states.length || states.some(state => state !== 'CLOSED')) return { label: '部分状态未知', type: 'default' }
  return { label: '已观测模型正常', type: 'success' }
}

export function successRate(detail) {
  if (!Number.isFinite(detail?.successCount) || !Number.isFinite(detail?.failureCount)) return '—'
  const total = detail.successCount + detail.failureCount
  return total > 0 ? `${(detail.successCount / total * 100).toFixed(1)}%` : '—'
}

export function runtimeRows(stats) {
  const runtime = stats?.runtime || {}
  const states = runtime.states || {}
  const details = runtime.details || {}
  return [...new Set([...Object.keys(states), ...Object.keys(details)])].sort().map(name => {
    const detail = details[name] || {}
    return {
      name, state: states[name] || detail.state || 'UNKNOWN', requestCount: detail.requestCount ?? null,
      successCount: detail.successCount ?? null, failureCount: detail.failureCount ?? null,
      consecutiveFailureCount: detail.consecutiveFailureCount ?? null,
      lastFailureMessage: detail.lastFailureMessage || '', successRate: successRate(detail)
    }
  })
}
