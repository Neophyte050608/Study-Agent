<template>
  <div class="bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100 antialiased min-h-screen">
    <!-- Header -->
    <header class="fixed top-0 right-0 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-md flex items-center justify-between px-8 z-40 shadow-sm dark:shadow-none border-b border-slate-200 dark:border-slate-800 transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
      <div class="flex items-center gap-4">
        <h1 class="text-xl font-bold tracking-tight text-indigo-700 dark:text-indigo-400">RAG 监控 <span class="text-slate-500 dark:text-slate-400 font-medium text-sm ml-2">/ 检索质量仪表盘</span></h1>
      </div>
    </header>

    <main class="pt-24 px-8 pb-12 min-h-screen relative z-10 bg-[#f9fafb] dark:bg-slate-950 transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
      <!-- Metric Cards Row -->
      <div class="grid grid-cols-6 gap-4 mb-8">
        <div v-for="card in metricCards" :key="card.label" class="bg-white dark:bg-slate-900 p-4 rounded-xl border border-slate-200 dark:border-slate-700 shadow-sm">
          <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">{{ card.label }}</div>
          <div class="text-2xl font-black text-slate-900 dark:text-slate-100">{{ card.value }}</div>
          <div v-if="card.change !== null" class="text-xs mt-1 font-bold" :class="card.change >= 0 ? 'text-emerald-600' : 'text-red-500'">
            {{ card.change >= 0 ? '↑' : '↓' }} {{ Math.abs(card.change) }}% vs 昨日
          </div>
        </div>
      </div>

      <div class="grid grid-cols-3 gap-6 mb-8">
        <!-- Trend Chart (2/3) -->
        <div class="col-span-2 bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-700 p-6 shadow-sm">
          <div class="flex items-center justify-between mb-4">
            <h3 class="text-sm font-bold text-slate-700 dark:text-slate-300 uppercase tracking-wider">趋势分析</h3>
            <div class="flex gap-2">
              <button v-for="range in timeRanges" :key="range.value" @click="selectedRange = range.value; loadHistory()"
                      class="px-3 py-1 rounded-lg text-xs font-bold transition-all"
                      :class="selectedRange === range.value ? 'bg-indigo-600 text-white' : 'bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-400'">
                {{ range.label }}
              </button>
              <button @click="triggerAndReload"
                      :disabled="snapshotLoading"
                      class="px-3 py-1 rounded-lg text-xs font-bold transition-all bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed">
                <span v-if="snapshotLoading" class="inline-flex items-center gap-1">
                  <span class="animate-spin material-symbols-outlined text-sm">progress_activity</span>
                </span>
                <span v-else>生成快照</span>
              </button>
            </div>
          </div>
          <div class="flex flex-wrap gap-3 mb-4">
            <label v-for="metric in metrics" :key="metric.key" class="flex items-center gap-1 text-xs font-bold text-slate-500 cursor-pointer">
              <input type="checkbox" :value="metric.key" v-model="selectedMetrics" @change="renderChart" class="accent-indigo-600" />
              {{ metric.label }}
            </label>
          </div>
          <div v-if="snapshotMessage" class="mb-3 px-3 py-2 rounded-lg text-xs font-bold"
               :class="snapshotMessage.includes('未生成') || snapshotMessage.includes('无数据') || snapshotMessage.includes('失败') ? 'bg-amber-50 text-amber-700 border border-amber-200' : 'bg-emerald-50 text-emerald-700 border border-emerald-200'">
            {{ snapshotMessage }}
          </div>
          <div v-if="historySnapshots.length === 0" class="flex flex-col items-center justify-center py-16 text-center">
            <span class="material-symbols-outlined text-5xl text-slate-300 dark:text-slate-600 mb-3">monitoring</span>
            <p class="text-sm font-bold text-slate-400 mb-2">暂无趋势数据，请先生成快照</p>
            <p class="text-xs text-slate-400 mb-4">快照每小时自动生成一次（整点后 5 分钟），也可手动触发</p>
            <button @click="triggerAndReload"
                    :disabled="snapshotLoading"
                    class="px-4 py-2 bg-indigo-600 text-white rounded-lg text-xs font-bold hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-all">
              <span v-if="snapshotLoading" class="inline-flex items-center gap-1">
                <span class="animate-spin material-symbols-outlined text-sm">progress_activity</span>
                生成中...
              </span>
              <span v-else>生成快照</span>
            </button>
          </div>
          <canvas v-else ref="chartCanvas" height="300"></canvas>
        </div>

        <!-- Alert Panel (1/3) -->
        <div class="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-700 p-6 shadow-sm">
          <h3 class="text-sm font-bold text-slate-700 dark:text-slate-300 uppercase tracking-wider mb-4">告警状态</h3>
          <div class="rounded-xl p-6 mb-4 text-center"
               :class="alertBgClass">
            <div class="text-4xl font-black">{{ alertLevel }}</div>
            <div class="text-xs font-bold mt-2 uppercase tracking-widest">当前级别</div>
          </div>
          <div v-if="alertTags.length > 0" class="space-y-2 mb-4">
            <div v-for="tag in alertTags" :key="tag" class="px-3 py-2 rounded-lg text-xs font-bold"
                 :class="alertTagClass(tag)">
              {{ formatAlertTag(tag) }}
            </div>
          </div>
          <div v-else class="text-xs text-slate-400 mb-4">无活跃告警</div>
          <a href="/ops" class="block w-full py-2 bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 rounded-lg text-xs font-bold hover:bg-slate-200 transition-all text-center no-underline">
            运维中心：查看 Trace 详情 →
          </a>
        </div>
      </div>

      <!-- Feedback Summary -->
      <div class="grid grid-cols-4 gap-4 mb-8">
        <div class="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-700 p-5 shadow-sm text-center">
          <div class="text-3xl mb-1">{{ feedbackSummary.thumbsUp }}</div>
          <div class="text-xs font-bold text-slate-400 uppercase tracking-wider">点赞 👍</div>
        </div>
        <div class="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-700 p-5 shadow-sm text-center">
          <div class="text-3xl mb-1">{{ feedbackSummary.thumbsDown }}</div>
          <div class="text-xs font-bold text-slate-400 uppercase tracking-wider">点踩 👎</div>
        </div>
        <div class="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-700 p-5 shadow-sm text-center">
          <div class="text-3xl mb-1">{{ feedbackSummary.copyCount }}</div>
          <div class="text-xs font-bold text-slate-400 uppercase tracking-wider">复制 📋</div>
        </div>
        <div class="bg-white dark:bg-slate-900 rounded-xl border border-slate-200 dark:border-slate-700 p-5 shadow-sm text-center">
          <div class="text-3xl mb-1" :class="satisfactionColor">{{ feedbackSummary.satisfactionRate }}</div>
          <div class="text-xs font-bold text-slate-400 uppercase tracking-wider">满意度</div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch, nextTick } from 'vue'
import { loadRagDashboard, loadMetricsHistory, triggerSnapshot } from '../api/admin'

defineProps({
  sidebarCollapsed: { type: Boolean, default: false }
})

const dashboard = ref({ currentHour: {}, alertLevel: 'NONE', alertTags: [], riskTagCounts: {} })
const historySnapshots = ref([])
const selectedRange = ref(24)
const selectedMetrics = ref(['avgLatencyMs', 'p95LatencyMs', 'successRate'])
const chartCanvas = ref(null)
const snapshotLoading = ref(false)
const snapshotMessage = ref('')
let chartInstance = null
let refreshTimer = null

const timeRanges = [
  { label: '24h', value: 24 },
  { label: '7d', value: 168 }
]

const metrics = [
  { key: 'avgLatencyMs', label: '平均延迟' },
  { key: 'p95LatencyMs', label: 'P95延迟' },
  { key: 'successRate', label: '成功率' },
  { key: 'satisfactionRate', label: '满意度' },
  { key: 'fallbackRate', label: 'Fallback率' },
  { key: 'emptyRetrievalRate', label: '空召回率' },
  { key: 'traceCount', label: '请求量' }
]

const metricCards = computed(() => {
  const curr = dashboard.value.currentHour || {}
  const feedback = curr.feedback || {}
  return [
    { label: 'Trace 总数', value: curr.traceCount ?? 0 },
    { label: '成功率', value: curr.successRate ?? '0%' },
    { label: '平均延迟', value: (curr.avgLatencyMs ?? 0) + 'ms' },
    { label: 'P95 延迟', value: (curr.p95LatencyMs ?? 0) + 'ms' },
    { label: 'Fallback 率', value: curr.fallbackRate ?? '0%' },
    { label: '满意度', value: feedback.satisfactionRate ?? '0%' }
  ].map(card => ({ ...card, change: null }))
})

const feedbackSummary = computed(() => {
  const fb = dashboard.value.currentHour?.feedback || {}
  return {
    thumbsUp: fb.thumbsUp ?? 0,
    thumbsDown: fb.thumbsDown ?? 0,
    copyCount: fb.copy ?? 0,
    satisfactionRate: fb.satisfactionRate ?? '0%'
  }
})

const satisfactionColor = computed(() => {
  const rate = parseFloat(feedbackSummary.value.satisfactionRate)
  if (isNaN(rate)) return 'text-slate-900 dark:text-slate-100'
  if (rate >= 80) return 'text-emerald-600'
  if (rate >= 50) return 'text-amber-600'
  return 'text-red-500'
})

const alertLevel = computed(() => dashboard.value.alertLevel || 'NONE')
const alertTags = computed(() => dashboard.value.alertTags || [])

const alertBgClass = computed(() => {
  switch (alertLevel.value) {
    case 'HIGH': return 'bg-red-50 dark:bg-red-950/30 text-red-700 dark:text-red-400 border border-red-200'
    case 'MEDIUM': return 'bg-amber-50 dark:bg-amber-950/30 text-amber-700 dark:text-amber-400 border border-amber-200'
    case 'INFO': return 'bg-blue-50 dark:bg-blue-950/30 text-blue-700 dark:text-blue-400 border border-blue-200'
    default: return 'bg-emerald-50 dark:bg-emerald-950/30 text-emerald-700 dark:text-emerald-400 border border-emerald-200'
  }
})

const alertTagClass = (tag) => {
  if (tag.includes('satisfaction') || tag.includes('latency_degrading')) return 'bg-red-50 text-red-700 border border-red-200'
  return 'bg-amber-50 text-amber-700 border border-amber-200'
}

const formatAlertTag = (tag) => {
  const map = {
    failed_traces_elevated: '失败 Trace 增多',
    fallback_rate_elevated: 'Fallback 率过高',
    satisfaction_dropped: '满意度下降',
    slow_traces_elevated: '慢请求增多',
    empty_retrieval_elevated: '空召回增多',
    latency_degrading: '延迟劣化 (vs 昨日)',
    success_rate_degrading: '成功率下降 (vs 昨日)',
    high_active_trace_load: '活跃 Trace 负载高',
    degrading_risky_trend: '风险趋势上升',
    degrading_slow_trend: '慢请求趋势上升',
    degrading_failed_trend: '失败趋势上升'
  }
  return map[tag] || tag
}

const loadDashboard = async () => {
  try {
    dashboard.value = await loadRagDashboard()
  } catch (err) { console.error('Failed to load dashboard:', err) }
}

const loadHistory = async () => {
  try {
    historySnapshots.value = await loadMetricsHistory(selectedRange.value, selectedMetrics.value.join(','))
    await nextTick()
    renderChart()
  } catch (err) { console.error('Failed to load history:', err) }
}

const renderChart = () => {
  const canvas = chartCanvas.value
  if (!canvas || !historySnapshots.value.length) return

  if (chartInstance) chartInstance.destroy()

  const ctx = canvas.getContext('2d')
  const width = canvas.parentElement.clientWidth
  canvas.width = width
  canvas.height = 300

  const data = historySnapshots.value
  const step = Math.max(1, Math.floor(data.length / 12))

  const padding = { top: 20, right: 60, bottom: 40, left: 60 }
  const chartW = width - padding.left - padding.right
  const chartH = 300 - padding.top - padding.bottom

  ctx.clearRect(0, 0, width, 300)

  ctx.strokeStyle = '#e2e8f0'
  ctx.lineWidth = 0.5
  for (let i = 0; i <= 4; i++) {
    const y = padding.top + (chartH / 4) * i
    ctx.beginPath()
    ctx.moveTo(padding.left, y)
    ctx.lineTo(width - padding.right, y)
    ctx.stroke()
  }

  const colors = { avgLatencyMs: '#6366f1', p95LatencyMs: '#f59e0b', successRate: '#10b981', satisfactionRate: '#8b5cf6', fallbackRate: '#ef4444', emptyRetrievalRate: '#f97316', traceCount: '#6b7280' }

  selectedMetrics.value.forEach(metricKey => {
    const color = colors[metricKey] || '#6b7280'
    const values = data.map(d => parseFloat(String(d[metricKey] || '0').replace('%', '')))
    if (values.length < 2) return

    const maxVal = Math.max(...values, 1)
    const minVal = Math.min(...values, 0)

    ctx.strokeStyle = color
    ctx.lineWidth = 2
    ctx.beginPath()
    values.forEach((val, i) => {
      const x = padding.left + (chartW / (values.length - 1)) * i
      const normalized = maxVal === minVal ? 0.5 : (val - minVal) / (maxVal - minVal)
      const y = padding.top + chartH - normalized * chartH
      if (i === 0) ctx.moveTo(x, y)
      else ctx.lineTo(x, y)
    })
    ctx.stroke()
  })

  ctx.fillStyle = '#94a3b8'
  ctx.font = '10px monospace'
  ctx.textAlign = 'center'
  for (let i = 0; i < data.length; i += step) {
    const x = padding.left + (chartW / (data.length - 1)) * i
    const hour = String(data[i].hour).slice(11, 16)
    ctx.fillText(hour, x, 300 - 5)
  }
}

const loadAll = async () => {
  await Promise.all([loadDashboard(), loadHistory()])
}

const triggerAndReload = async () => {
  snapshotLoading.value = true
  snapshotMessage.value = ''
  try {
    const result = await triggerSnapshot()
    snapshotMessage.value = result.message || ''
    await loadAll()
  } catch (err) {
    snapshotMessage.value = '快照生成请求失败'
    console.error('Failed to trigger snapshot:', err)
  } finally {
    snapshotLoading.value = false
  }
}

onMounted(() => {
  loadAll()
  refreshTimer = setInterval(loadDashboard, 30000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  if (chartInstance) chartInstance.destroy()
})

watch(selectedRange, () => loadHistory())
</script>
