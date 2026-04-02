<template>
  <div class="bg-surface text-on-surface antialiased min-h-screen">
    <!-- TopNavBar Shell -->
    <header class="fixed top-0 right-0 left-64 h-16 bg-white/80 backdrop-blur-xl border-b border-slate-100 flex justify-between items-center px-8 z-40">
      <div class="flex items-center gap-4">
        <h1 class="text-xl font-bold tracking-tight text-indigo-700 dark:text-indigo-400">数字叙事 <span class="text-on-surface-variant font-medium">/ 观测与运维</span></h1>
      </div>
    </header>

    <!-- Main Content Canvas -->
    <main class="ml-64 pt-24 px-8 pb-12 min-h-screen bg-slate-50">
      <!-- Header & Tabs -->
      <div class="mb-8">
        <div class="flex items-end justify-between">
          <div>
            <h2 class="text-3xl font-extrabold tracking-tight text-slate-900 mb-2">系统全链路观测</h2>
            <p class="text-slate-500 max-w-2xl leading-relaxed">监控 RAG 检索链路性能、A2A 通信状态及核心运维审计日志，确保数字化叙事引擎的高可用运行。</p>
          </div>
          <div class="flex bg-white border border-slate-200 p-1 rounded-xl shadow-sm">
            <button class="px-6 py-2 rounded-lg text-sm font-bold bg-slate-100 text-indigo-700 shadow-sm transition-all" @click="reload" :disabled="loading">
              <span class="material-symbols-outlined align-middle mr-1 text-[18px]" :class="loading ? 'animate-spin' : ''">refresh</span>
              刷新数据
            </button>
          </div>
        </div>
        <p v-if="hint && hint !== '可执行清理与重放操作' && hint !== '数据已刷新'" class="mt-2 text-sm text-indigo-600">{{ hint }}</p>
      </div>

      <!-- Content Grid: Bento Style -->
      <div class="grid grid-cols-12 gap-6">
        <!-- Key Metrics Summary -->
        <div class="col-span-12 grid grid-cols-4 gap-6">
          <div class="bg-white p-6 rounded-xl border border-slate-200 shadow-sm hover:border-indigo-300 transition-all">
            <div class="flex items-center gap-3 mb-4">
              <span class="material-symbols-outlined text-indigo-600 bg-indigo-50 p-2 rounded-lg" data-icon="speed">speed</span>
              <span class="text-xs font-bold text-slate-500 uppercase tracking-wider">平均耗时</span>
            </div>
            <div class="text-2xl font-black text-slate-900">{{ overview.avgLatencyMs ?? 0 }}<span class="text-sm font-medium ml-1 opacity-60">ms</span></div>
            <div class="mt-2 flex items-center gap-1 text-xs text-indigo-500 font-medium">
              <span class="material-symbols-outlined text-xs">analytics</span> 统计中
            </div>
          </div>
          <div class="bg-white p-6 rounded-xl border border-slate-200 shadow-sm hover:border-indigo-300 transition-all">
            <div class="flex items-center gap-3 mb-4">
              <span class="material-symbols-outlined text-indigo-600 bg-indigo-50 p-2 rounded-lg">timer</span>
              <span class="text-xs font-bold text-slate-500 uppercase tracking-wider">P95 耗时</span>
            </div>
            <div class="text-2xl font-black text-slate-900">{{ p95Latency }}<span class="text-sm font-medium ml-1 opacity-60">ms</span></div>
            <div class="mt-2 flex items-center gap-1 text-xs text-amber-500 font-medium">
              <span class="material-symbols-outlined text-xs">bolt</span> 性能基准
            </div>
          </div>
          <div class="bg-white p-6 rounded-xl border border-slate-200 shadow-sm hover:border-indigo-300 transition-all">
            <div class="flex items-center gap-3 mb-4">
              <span class="material-symbols-outlined text-indigo-600 bg-indigo-50 p-2 rounded-lg" data-icon="find_in_page">find_in_page</span>
              <span class="text-xs font-bold text-slate-500 uppercase tracking-wider">平均召回文档</span>
            </div>
            <div class="text-2xl font-black text-slate-900">{{ overview.avgRetrievedDocs ?? 0 }}<span class="text-sm font-medium ml-1 opacity-60">docs</span></div>
            <div class="mt-2 flex items-center gap-1 text-xs text-emerald-600 font-medium">
              <span class="material-symbols-outlined text-xs" data-icon="stable">video_stable</span> 稳定运行中
            </div>
          </div>
          <div class="bg-white p-6 rounded-xl border border-slate-200 shadow-sm hover:border-indigo-300 transition-all">
            <div class="flex items-center gap-3 mb-4">
              <span class="material-symbols-outlined text-indigo-600 bg-indigo-50 p-2 rounded-lg">check_circle</span>
              <span class="text-xs font-bold text-slate-500 uppercase tracking-wider">成功率</span>
            </div>
            <div class="text-2xl font-black text-slate-900">{{ successRate }}</div>
            <div class="mt-2 flex items-center gap-1 text-xs text-indigo-600 font-medium">
              <span class="material-symbols-outlined text-xs">verified</span> 高可用指标
            </div>
          </div>
        </div>

        <!-- RAG Trace Table -->
        <div id="section-rag" class="col-span-8 bg-white rounded-xl p-8 shadow-sm border border-slate-200">
          <div class="flex items-center justify-between mb-8">
            <h3 class="text-xl font-bold flex items-center gap-2 text-slate-900">
              <span class="material-symbols-outlined text-indigo-700" data-icon="route">route</span>
              RAG 链路实时追踪
            </h3>
            <div class="flex items-center gap-2">
              <span class="w-3 h-3 rounded-full bg-emerald-500 animate-pulse"></span>
              <span class="text-xs text-slate-500 font-medium">实时监听中...</span>
            </div>
          </div>
          <div class="overflow-hidden">
            <table class="w-full text-left">
              <thead>
                <tr class="text-slate-500 text-xs uppercase tracking-widest font-bold border-b border-slate-100">
                  <th class="pb-4 font-bold">Trace ID</th>
                  <th class="pb-4 font-bold">耗时 (Latency)</th>
                  <th class="pb-4 font-bold">召回数量</th>
                  <th class="pb-4 font-bold">状态</th>
                  <th class="pb-4 text-right font-bold">操作</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                <tr v-for="item in traces" :key="item.traceId" class="group hover:bg-slate-50 transition-all">
                  <td class="py-4 text-sm font-mono text-indigo-600">{{ item.traceId || '-' }}</td>
                  <td class="py-4 text-sm font-semibold text-slate-700">{{ item.latencyMs ?? 0 }} ms</td>
                  <td class="py-4 text-sm text-slate-700">{{ item.retrievedCount ?? 0 }}</td>
                  <td class="py-4">
                    <span class="px-2 py-1 rounded text-[10px] font-bold uppercase tracking-tighter"
                          :class="getTraceStatusClass(item.status)">
                      {{ item.status }}
                    </span>
                  </td>
                  <td class="py-4 text-right">
                    <button @click="viewDetail(item.traceId)" class="text-indigo-600 hover:text-indigo-800 text-xs font-bold flex items-center gap-1 ml-auto">
                      查看详情 <span class="material-symbols-outlined text-xs">arrow_forward</span>
                    </button>
                  </td>
                </tr>
                <tr v-if="!traces.length">
                  <td colspan="5" class="py-8 text-center text-sm text-slate-500">暂无轨迹数据</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- A2A Idempotency Status -->
        <div id="section-a2a" class="col-span-4 space-y-6">
          <div class="bg-white rounded-xl p-6 shadow-sm border border-slate-200">
            <div class="flex items-center justify-between mb-4">
              <h3 class="text-sm font-bold text-slate-500 uppercase tracking-widest flex items-center gap-2">
                <span class="material-symbols-outlined text-indigo-700 text-lg" data-icon="rebase_edit">rebase_edit</span>
                高级运维工具
              </h3>
              <button class="text-xs font-bold text-indigo-600 hover:text-indigo-800 transition-colors" @click="showAdvancedOps = !showAdvancedOps">
                {{ showAdvancedOps ? '收起' : '展开' }}
              </button>
            </div>
            <p v-if="!showAdvancedOps" class="text-xs text-slate-500 leading-relaxed">
              这块主要用于 A2A 消息幂等排障与死信重放，日常看 RAG 链路时可保持收起。
            </p>
            <div v-else class="space-y-6">
              <div>
                <div class="flex justify-between items-end mb-2">
                  <span class="text-xs font-bold text-slate-900">L1 Memory Cache</span>
                  <span class="text-xs text-indigo-600 font-mono">{{ idempotency.inMemorySize || 0 }} keys</span>
                </div>
                <div class="h-2 w-full bg-slate-100 rounded-full overflow-hidden">
                  <div class="h-full bg-indigo-500 rounded-full" :style="{ width: Math.min(100, ((idempotency.inMemorySize || 0) / 1000) * 100) + '%' }"></div>
                </div>
                <p class="text-[10px] mt-2 text-slate-500">清理策略：LRU (Least Recently Used)</p>
              </div>
              <div>
                <div class="flex justify-between items-end mb-2">
                  <span class="text-xs font-bold text-slate-900">L2 Redis Dist. Cache</span>
                  <span class="text-xs text-indigo-600 font-mono">{{ idempotency.redisSize || 0 }} keys</span>
                </div>
                <div class="h-2 w-full bg-slate-100 rounded-full overflow-hidden">
                  <div class="h-full bg-indigo-400 rounded-full" :style="{ width: Math.min(100, ((idempotency.redisSize || 0) / 10000) * 100) + '%' }"></div>
                </div>
                <p class="text-[10px] mt-2 text-slate-500">持久化：AOF + RDB Enabled</p>
              </div>
            </div>
          </div>

          <!-- Danger Zone -->
          <div v-if="showAdvancedOps" class="rounded-xl border-2 border-dashed border-red-300 p-6 bg-red-50/50 group hover:border-red-400 transition-all">
            <div class="flex items-center gap-3 mb-4">
              <span class="material-symbols-outlined text-red-600" data-icon="dangerous">dangerous</span>
              <h3 class="text-sm font-bold text-red-600 uppercase tracking-widest">危险操作区 (Danger Zone)</h3>
            </div>
            <p class="text-xs text-red-800/80 mb-6 leading-relaxed">
              执行以下操作可能会导致部分正在进行的业务中断或数据不一致，请在确保备份的情况下谨慎操作。
            </p>
            <div class="grid grid-cols-1 gap-3">
              <button class="w-full flex items-center justify-center gap-2 py-3 bg-white text-red-600 border border-red-200 rounded-lg text-xs font-bold hover:bg-red-600 hover:text-white transition-all active:scale-95 disabled:opacity-50" @click="purge" :disabled="loading">
                <span class="material-symbols-outlined text-sm" data-icon="delete_sweep">delete_sweep</span>
                清理幂等缓存 (Force Purge)
              </button>
              <button class="w-full flex items-center justify-center gap-2 py-3 bg-white text-slate-700 border border-slate-200 rounded-lg text-xs font-bold hover:bg-slate-100 transition-all active:scale-95 disabled:opacity-50" @click="replay" :disabled="loading">
                <span class="material-symbols-outlined text-sm" data-icon="replay">replay</span>
                死信队列重放 (DLQ Replay)
              </button>
            </div>
          </div>
        </div>

        <!-- Recent Audit Log (Asymmetric Section) -->
        <div id="section-audit" class="col-span-12 bg-white rounded-xl p-8 border border-slate-200 shadow-sm">
          <div class="flex items-center justify-between mb-6">
            <div>
              <h3 class="text-lg font-bold text-slate-900">最近运维审计</h3>
              <p class="text-xs text-slate-500">记录所有特权用户的写操作指令</p>
            </div>
            <button class="text-indigo-600 text-xs font-bold flex items-center gap-1 hover:underline">
              查看完整日志 <span class="material-symbols-outlined text-xs" data-icon="arrow_forward">arrow_forward</span>
            </button>
          </div>
          <div class="space-y-4">
            <div v-for="item in audits" :key="`${item.timestamp}-${item.operator}-${item.action}`" class="flex items-center justify-between bg-slate-50 px-4 py-3 rounded-lg border-l-4 border-indigo-500 hover:bg-slate-100 transition-colors">
              <div class="flex items-center gap-4">
                <span class="text-xs font-mono text-slate-500 w-36">{{ formatTime(item.timestamp) }}</span>
                <span class="px-2 py-0.5 bg-indigo-100 text-indigo-800 text-[10px] font-black rounded-full uppercase w-16 text-center">{{ item.action || 'OP' }}</span>
                <span class="text-sm font-medium text-slate-700">用户 <span class="font-bold text-slate-900">{{ item.operator || 'system' }}</span> {{ item.message || '-' }}</span>
              </div>
              <span class="text-xs text-slate-400 font-mono">System</span>
            </div>
            <div v-if="!audits.length" class="py-4 text-sm text-slate-500 text-center">暂无审计日志</div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { clearIdempotencyCache, loadOpsAudits, loadOpsIdempotency, loadOpsOverview, loadOpsTraces, replayDlq } from '../api/admin'

const router = useRouter()
const loading = ref(false)
const hint = ref('可执行清理与重放操作')
const overview = ref({})
const traces = ref([])
const audits = ref([])
const idempotency = ref({})
const showAdvancedOps = ref(false)
const SLOW_THRESHOLD_MS = Number(import.meta.env.VITE_RAG_SLOW_THRESHOLD_MS || 20000)

const p95Latency = computed(() => {
  if (typeof overview.value?.p95LatencyMs === 'number') {
    return overview.value.p95LatencyMs
  }
  if (!traces.value.length) return 0
  const latencies = traces.value.map(t => t.latencyMs).sort((a, b) => a - b)
  const index = Math.ceil(latencies.length * 0.95) - 1
  return latencies[index]
})

const successRate = computed(() => {
  if (typeof overview.value?.successRate === 'string' && overview.value.successRate.trim()) {
    return overview.value.successRate
  }
  if (!traces.value.length) return '0%'
  const successCount = traces.value.filter(t => t.status === 'SUCCESS' || t.status === 'SLOW').length
  return ((successCount / traces.value.length) * 100).toFixed(1) + '%'
})

const idempotencyTotal = computed(() => {
  const inMemory = idempotency.value.inMemorySize || 0
  const redisSize = idempotency.value.redisSize || 0
  return inMemory + redisSize
})

const formatTime = (value) => {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString()
}

const viewDetail = (traceId) => {
  router.push({ name: 'rag-trace-detail', params: { traceId } })
}

const resolveStatus = (traceStatus, durationMs, nodes) => {
  const normalizedTraceStatus = typeof traceStatus === 'string' ? traceStatus.trim().toUpperCase() : ''
  if (normalizedTraceStatus === 'FAILED' || normalizedTraceStatus === 'ERROR' || normalizedTraceStatus === 'TIMEOUT') {
    return 'FAILED'
  }
  if (normalizedTraceStatus === 'COMPLETED' || normalizedTraceStatus === 'SUCCESS') {
    return durationMs >= SLOW_THRESHOLD_MS ? 'SLOW' : 'SUCCESS'
  }
  if (normalizedTraceStatus === 'RUNNING') {
    return 'RUNNING'
  }
  const safeNodes = Array.isArray(nodes) ? nodes : []
  const nodeStatuses = safeNodes
    .map(node => (typeof node?.status === 'string' ? node.status.trim().toUpperCase() : ''))
    .filter(Boolean)
  if (nodeStatuses.includes('FAILED') || nodeStatuses.includes('ERROR') || nodeStatuses.includes('TIMEOUT')) {
    return 'FAILED'
  }
  if (nodeStatuses.includes('RUNNING')) {
    return 'RUNNING'
  }
  if (safeNodes.length > 0) {
    return durationMs >= SLOW_THRESHOLD_MS ? 'SLOW' : 'SUCCESS'
  }
  return 'UNKNOWN'
}

const getTraceStatusClass = (status) => {
  if (status === 'FAILED') {
    return 'bg-red-100 text-red-700'
  }
  if (status === 'SLOW') {
    return 'bg-amber-100 text-amber-700'
  }
  if (status === 'RUNNING') {
    return 'bg-indigo-100 text-indigo-700'
  }
  if (status === 'UNKNOWN') {
    return 'bg-slate-100 text-slate-700'
  }
  return 'bg-emerald-100 text-emerald-700'
}

const reload = async () => {
  loading.value = true
  hint.value = '正在刷新运维数据...'
  try {
    const [overviewData, tracesData, idempotencyData, auditsData] = await Promise.all([
      loadOpsOverview(),
      loadOpsTraces(),
      loadOpsIdempotency(),
      loadOpsAudits(5)
    ])
    overview.value = overviewData || {}
    traces.value = Array.isArray(tracesData)
      ? tracesData.map(item => {
          const nodes = Array.isArray(item?.nodes) ? item.nodes : []
          const retrievedCount = nodes
            .filter(n => n?.nodeType === 'RETRIEVAL')
            .reduce((sum, n) => {
              const docs = n?.metrics?.retrievedDocs
              return sum + (typeof docs === 'number' ? docs : 0)
            }, 0)
          return {
            traceId: item?.traceId ?? '-',
            latencyMs: typeof item?.durationMs === 'number' ? item.durationMs : 0,
            retrievedCount,
            status: resolveStatus(item?.traceStatus, typeof item?.durationMs === 'number' ? item.durationMs : 0, nodes)
          }
        })
      : []
    idempotency.value = idempotencyData || {}
    audits.value = Array.isArray(auditsData) ? auditsData : []
    hint.value = '数据已刷新'
  } catch (error) {
    hint.value = `刷新失败: ${error.message || 'unknown'}`
  } finally {
    loading.value = false
  }
}

const purge = async () => {
  loading.value = true
  hint.value = '正在清理幂等缓存...'
  try {
    await clearIdempotencyCache()
    await reload()
    hint.value = '幂等缓存已清理'
  } catch (error) {
    hint.value = `清理失败: ${error.message || 'unknown'}`
    loading.value = false
  }
}

const replay = async () => {
  loading.value = true
  hint.value = '正在重放死信队列...'
  try {
    await replayDlq()
    await reload()
    hint.value = '死信重放指令已发送'
  } catch (error) {
    hint.value = `重放失败: ${error.message || 'unknown'}`
    loading.value = false
  }
}

onMounted(reload)
</script>
