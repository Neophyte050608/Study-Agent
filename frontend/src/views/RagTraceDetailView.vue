<template>
  <div class="bg-slate-50 min-h-screen antialiased relative">
    <!-- Header -->
    <header class="fixed top-0 right-0 h-16 bg-white/80 backdrop-blur-xl border-b border-slate-100 flex justify-between items-center px-8 z-40 transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
      <div class="flex items-center gap-3">
        <router-link to="/ops" class="text-slate-500 hover:text-slate-700 transition-colors">
          <span class="material-symbols-outlined align-middle">arrow_back</span>
        </router-link>
        <div class="h-4 w-px bg-slate-200 mx-1"></div>
        <h1 class="text-lg font-bold tracking-tight text-slate-900">
          链路详情 <span class="text-slate-400 font-mono text-sm ml-2">#{{ traceId.slice(0, 8) }}</span>
        </h1>
        <span v-if="trace" class="px-2 py-0.5 rounded text-[10px] font-black uppercase tracking-widest border"
              :class="statusClass">
          {{ displayTraceStatus }}
        </span>
      </div>
      <div class="flex items-center gap-3">
        <button @click="loadDetail" class="p-2 hover:bg-slate-100 rounded-lg transition-all text-slate-500" title="刷新">
          <span class="material-symbols-outlined" :class="{ 'animate-spin': loading }">refresh</span>
        </button>
        <button @click="copyTraceId" class="p-2 hover:bg-slate-100 rounded-lg transition-all text-slate-500" title="复制 ID">
          <span class="material-symbols-outlined">content_copy</span>
        </button>
      </div>
    </header>

    <main class="pt-24 px-8 pb-12 transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
      <div v-if="loading && !trace" class="flex flex-col items-center justify-center py-32 text-slate-400">
        <span class="material-symbols-outlined animate-spin text-4xl mb-4">refresh</span>
        <p class="text-sm font-medium">正在加载链路时序数据...</p>
      </div>

      <div v-else-if="!trace" class="flex flex-col items-center justify-center py-32 text-slate-400">
        <span class="material-symbols-outlined text-4xl mb-4 text-red-300">error</span>
        <p class="text-sm font-medium">未找到该链路记录或已过期</p>
        <router-link to="/ops" class="mt-4 text-indigo-600 font-bold hover:underline">返回列表</router-link>
      </div>

      <div v-else class="space-y-6">
        <!-- Trace Metadata Cards -->
        <div class="grid grid-cols-6 gap-6">
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">总耗时</div>
            <div class="text-xl font-black text-slate-900">{{ traceSummaryResponse?.businessDurationMs ?? trace.durationMs }} <span class="text-xs font-medium opacity-50">ms</span></div>
          </div>
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">节点总数</div>
            <div class="text-xl font-black text-slate-900">{{ trace.nodes?.length || 0 }}</div>
          </div>
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">检索文档</div>
            <div class="text-xl font-black text-slate-900">{{ retrievedDocumentCount }}</div>
          </div>
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">开始时间</div>
            <div class="text-sm font-bold text-slate-700 mt-1">{{ formatTime(traceSummaryResponse?.startedAt || trace.startTime) }}</div>
          </div>
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">首 Token</div>
            <div class="text-xl font-black text-slate-900">{{ traceSummaryResponse?.firstTokenMs ?? 0 }} <span class="text-xs font-medium opacity-50">ms</span></div>
          </div>
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">风险数</div>
            <div class="text-xl font-black text-slate-900">{{ traceSummaryResponse?.riskCount ?? 0 }}</div>
          </div>
        </div>

        <div class="grid grid-cols-3 gap-6">
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-2">执行路径</div>
            <div class="flex flex-wrap gap-2">
              <span v-if="traceSummary.usesLocalGraph" class="px-2 py-1 rounded-md text-[10px] font-black tracking-widest uppercase bg-cyan-50 text-cyan-700 border border-cyan-200">
                Local Graph
              </span>
              <span v-if="traceSummary.usesRag" class="px-2 py-1 rounded-md text-[10px] font-black tracking-widest uppercase bg-indigo-50 text-indigo-700 border border-indigo-200">
                RAG
              </span>
              <span v-if="!traceSummary.usesLocalGraph && !traceSummary.usesRag" class="px-2 py-1 rounded-md text-[10px] font-black tracking-widest uppercase bg-slate-100 text-slate-500 border border-slate-200">
                Unknown
              </span>
            </div>
          </div>
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-2">Fallback</div>
            <div class="flex flex-wrap gap-2">
              <span v-if="traceSummary.fallbackNodes.length" class="px-2 py-1 rounded-md text-[10px] font-black tracking-widest uppercase bg-amber-50 text-amber-700 border border-amber-200">
                {{ traceSummary.fallbackNodes.length }} triggered
              </span>
              <span v-else class="px-2 py-1 rounded-md text-[10px] font-black tracking-widest uppercase bg-emerald-50 text-emerald-700 border border-emerald-200">
                none
              </span>
            </div>
          </div>
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-2">异常节点</div>
            <div class="flex flex-wrap gap-2">
              <span v-if="traceSummary.failedNodes.length" class="px-2 py-1 rounded-md text-[10px] font-black tracking-widest uppercase bg-red-50 text-red-700 border border-red-200">
                {{ traceSummary.failedNodes.length }} failed
              </span>
              <span v-else class="px-2 py-1 rounded-md text-[10px] font-black tracking-widest uppercase bg-emerald-50 text-emerald-700 border border-emerald-200">
                none
              </span>
            </div>
          </div>
        </div>

        <div v-if="traceSummary.fallbackNodes.length || traceSummary.failedNodes.length || traceRiskTags.length" class="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
          <div class="px-6 py-4 border-b border-slate-100 bg-slate-50/50">
            <h3 class="text-sm font-bold text-slate-700 uppercase tracking-wider flex items-center gap-2">
              <span class="material-symbols-outlined text-amber-600 text-lg">warning</span>
              风险提示
            </h3>
          </div>
          <div class="p-5 space-y-3">
            <div v-if="traceRiskTags.length" class="flex flex-wrap gap-2">
              <span v-for="tag in traceRiskTags" :key="tag" class="px-2 py-1 rounded-md text-[10px] font-black tracking-widest uppercase bg-amber-50 text-amber-700 border border-amber-200">
                {{ formatRiskTag(tag) }}
              </span>
            </div>
            <div v-if="traceSummary.fallbackNodes.length" class="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
              本次链路发生 fallback，节点：
              {{ traceSummary.fallbackNodes.map(node => node.nodeName).join(' / ') }}
            </div>
            <div v-if="traceSummary.failedNodes.length" class="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
              检测到失败节点：
              {{ traceSummary.failedNodes.map(node => node.nodeName).join(' / ') }}
            </div>
          </div>
        </div>

        <!-- Waterfall Chart Card -->
        <div class="bg-white rounded-xl shadow-sm border border-slate-200 overflow-hidden">
          <div class="px-6 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
            <h3 class="text-sm font-bold text-slate-700 uppercase tracking-wider flex items-center gap-2">
              <span class="material-symbols-outlined text-indigo-600 text-lg">waterfall_chart</span>
              执行时序 (Waterfall)
            </h3>
            <span class="text-[10px] font-mono text-slate-400">WINDOW: {{ windowDuration }}ms</span>
          </div>

          <div class="overflow-x-auto">
            <div class="min-w-[800px]">
              <!-- Time Scale Ticks -->
              <div class="relative h-8 border-b border-slate-100 flex items-end px-4 pb-1">
                <div v-for="tick in 5" :key="tick" class="absolute flex flex-col items-center" 
                     :style="{ left: `${(tick-1) * 25}%` }">
                  <div class="w-px h-2 bg-slate-200"></div>
                  <span class="text-[9px] font-mono text-slate-400 mt-0.5">{{ Math.round(windowDuration * (tick-1) * 0.25) }}ms</span>
                </div>
              </div>

              <!-- Waterfall Rows -->
              <div class="divide-y divide-slate-50">
                <div v-for="node in timelineNodes" :key="node.nodeId" 
                     class="group hover:bg-indigo-50/30 transition-all px-4 py-3 flex items-center gap-4 relative cursor-pointer"
                     @click="selectedNode = node">
                  <!-- Node Info -->
                  <div class="w-64 shrink-0 flex items-center gap-2 overflow-hidden" 
                       :style="{ paddingLeft: `${node.depth * 16}px` }">
                    <span class="w-1.5 h-1.5 rounded-full shrink-0" :class="getNodeStatusColor(node.status)"></span>
                    <span class="text-xs font-bold text-slate-700 truncate" :title="node.nodeName">{{ node.nodeName }}</span>
                  </div>

                  <!-- Type Badge -->
                  <div class="w-24 shrink-0">
                    <span class="px-1.5 py-0.5 rounded-[4px] text-[9px] font-black uppercase tracking-tighter border" :class="getNodeTypeBadgeClass(node.nodeType)">
                      {{ formatNodeType(node.nodeType) }}
                    </span>
                  </div>

                  <!-- Bar -->
                  <div class="flex-1 h-6 relative bg-slate-50/50 rounded-sm overflow-hidden border border-slate-100/50">
                    <div class="absolute top-1 bottom-1 rounded-sm transition-all duration-500 shadow-sm"
                         :class="getNodeBarColor(node.status, node.nodeType)"
                         :style="{ left: `${node.leftPercent}%`, width: `${Math.max(node.widthPercent, 0.5)}%` }">
                    </div>
                  </div>

                  <!-- Duration -->
                  <div class="w-20 shrink-0 text-right">
                    <span class="text-xs font-mono font-bold text-slate-600">{{ node.durationMs }}ms</span>
                  </div>

                  <div class="w-28 shrink-0">
                    <span class="px-1.5 py-0.5 rounded-[4px] text-[9px] font-black uppercase tracking-tighter border" :class="getNodePathBadgeClass(node.nodeType)">
                      {{ getNodePathLabel(node.nodeType) }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- Node Detail Sidebar -->
    <transition name="slide">
      <aside v-if="selectedNode" class="fixed top-0 right-0 bottom-0 w-[500px] bg-white shadow-2xl z-50 border-l border-slate-200 flex flex-col">
        <div class="p-6 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
          <div>
            <h3 class="text-lg font-black text-slate-900">{{ selectedNode.nodeName }}</h3>
            <p class="text-[10px] font-bold text-slate-400 uppercase tracking-widest">{{ selectedNode.nodeType }} | {{ selectedNode.nodeId }}</p>
            <div class="mt-2 flex flex-wrap gap-2">
              <span class="px-2 py-0.5 rounded-md text-[10px] font-black uppercase tracking-widest border" :class="getNodeTypeBadgeClass(selectedNode.nodeType)">
                {{ formatNodeType(selectedNode.nodeType) }}
              </span>
              <span class="px-2 py-0.5 rounded-md text-[10px] font-black uppercase tracking-widest border" :class="getNodePathBadgeClass(selectedNode.nodeType)">
                {{ getNodePathLabel(selectedNode.nodeType) }}
              </span>
            </div>
          </div>
          <button @click="selectedNode = null" class="p-2 hover:bg-slate-200 rounded-full transition-all">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>
        
        <div class="flex-1 overflow-y-auto p-6 space-y-6">
          <!-- Status & Time -->
          <div class="grid grid-cols-2 gap-4">
            <div class="p-4 bg-slate-50 rounded-xl border border-slate-100">
              <div class="text-[9px] font-black text-slate-400 uppercase mb-1">Status</div>
              <span class="text-sm font-bold" :class="selectedNode.status === 'FAILED' ? 'text-red-600' : 'text-emerald-600'">
                {{ selectedNode.status }}
              </span>
            </div>
            <div class="p-4 bg-slate-50 rounded-xl border border-slate-100">
              <div class="text-[9px] font-black text-slate-400 uppercase mb-1">Duration</div>
              <span class="text-sm font-mono font-bold text-slate-700">{{ selectedNode.durationMs }} ms</span>
            </div>
          </div>

          <div v-if="isFallbackNode(selectedNode) || selectedNode.errorSummary" class="rounded-xl border px-4 py-3" :class="selectedNode.errorSummary ? 'border-red-200 bg-red-50' : 'border-amber-200 bg-amber-50'">
            <div class="text-[11px] font-black uppercase tracking-widest" :class="selectedNode.errorSummary ? 'text-red-600' : 'text-amber-700'">
              {{ selectedNode.errorSummary ? 'Execution Alert' : 'Fallback Node' }}
            </div>
            <div class="mt-2 text-sm" :class="selectedNode.errorSummary ? 'text-red-700' : 'text-amber-800'">
              {{ selectedNode.errorSummary || '该节点负责本地图谱到 RAG 的降级切换，需重点检查触发条件与输入摘要。'}}
            </div>
          </div>

          <!-- Summaries -->
          <div v-if="selectedNode.inputSummary" class="space-y-2">
            <h4 class="text-xs font-black text-slate-500 uppercase tracking-widest flex items-center gap-2">
              <span class="material-symbols-outlined text-sm">login</span> Input Summary
            </h4>
            <div class="p-4 bg-slate-900 text-slate-300 rounded-xl font-mono text-xs whitespace-pre-wrap leading-relaxed border border-slate-800 shadow-inner">
              {{ selectedNode.inputSummary }}
            </div>
          </div>

          <div v-if="selectedNode.outputSummary" class="space-y-2">
            <h4 class="text-xs font-black text-slate-500 uppercase tracking-widest flex items-center gap-2">
              <span class="material-symbols-outlined text-sm">logout</span> Output Summary
            </h4>
            <div class="p-4 bg-indigo-900/10 text-indigo-900 rounded-xl font-mono text-xs whitespace-pre-wrap leading-relaxed border border-indigo-100 shadow-sm">
              {{ selectedNode.outputSummary }}
            </div>
          </div>

          <div v-if="selectedNodeDetailEntries.length" class="space-y-2">
            <h4 class="text-xs font-black text-slate-500 uppercase tracking-widest flex items-center gap-2">
              <span class="material-symbols-outlined text-sm">data_object</span> Structured Details
            </h4>
            <div class="grid grid-cols-2 gap-3">
              <div v-for="([key, value]) in selectedNodeDetailEntries" :key="`${selectedNode.nodeId}-${key}`" class="p-3 bg-white border border-slate-200 rounded-lg shadow-sm flex justify-between items-start gap-2">
                <span class="text-[10px] font-bold text-slate-400 uppercase">{{ key }}</span>
                <span class="text-xs font-black text-slate-700 break-all text-right">{{ Array.isArray(value) ? value.join(', ') : value }}</span>
              </div>
            </div>
          </div>

          <div v-if="selectedNodeDocumentRefs.length" class="space-y-2">
            <h4 class="text-xs font-black text-slate-500 uppercase tracking-widest flex items-center gap-2">
              <span class="material-symbols-outlined text-sm">description</span> Retrieved Documents
            </h4>
            <div class="space-y-2">
              <div v-for="(ref, index) in selectedNodeDocumentRefs" :key="`${selectedNode.nodeId}-${index}`" class="p-3 bg-slate-50 rounded-xl border border-slate-200 text-xs text-slate-700 leading-relaxed break-all">
                {{ ref }}
              </div>
            </div>
          </div>

          <div v-if="selectedNode.errorSummary" class="space-y-2">
            <h4 class="text-xs font-black text-red-500 uppercase tracking-widest flex items-center gap-2">
              <span class="material-symbols-outlined text-sm">error</span> Error Message
            </h4>
            <div class="p-4 bg-red-50 text-red-700 rounded-xl font-mono text-xs whitespace-pre-wrap border border-red-100">
              {{ selectedNode.errorSummary }}
            </div>
          </div>

          <!-- Metrics -->
          <div v-if="selectedNode.metrics" class="space-y-2">
            <h4 class="text-xs font-black text-slate-500 uppercase tracking-widest flex items-center gap-2">
              <span class="material-symbols-outlined text-sm">bar_chart</span> Metrics
            </h4>
            <div class="grid grid-cols-2 gap-3">
              <div v-for="(val, key) in selectedNode.metrics" :key="key" class="p-3 bg-white border border-slate-200 rounded-lg shadow-sm flex justify-between items-center">
                <span class="text-[10px] font-bold text-slate-400 uppercase">{{ key }}</span>
                <span class="text-xs font-black text-slate-700">{{ val }}</span>
              </div>
            </div>
          </div>
        </div>
      </aside>
    </transition>
    <div v-if="selectedNode" class="fixed inset-0 bg-slate-900/20 backdrop-blur-sm z-40" @click="selectedNode = null"></div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { loadOpsTraceDetail } from '../api/admin'

defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})

const route = useRoute()
const traceId = ref(route.params.traceId)
const loading = ref(false)
const trace = ref(null)
const traceSummaryResponse = ref(null)
const selectedNode = ref(null)
const SLOW_THRESHOLD_MS = Number(import.meta.env.VITE_RAG_SLOW_THRESHOLD_MS || 20000)

const resolveDisplayTraceStatus = (traceStatus, durationMs, nodes) => {
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

const displayTraceStatus = computed(() => {
  if (!trace.value) return 'UNKNOWN'
  const durationMs = typeof traceSummaryResponse.value?.businessDurationMs === 'number'
    ? traceSummaryResponse.value.businessDurationMs
    : (typeof trace.value.durationMs === 'number' ? trace.value.durationMs : 0)
  const traceStatus = typeof traceSummaryResponse.value?.traceStatus === 'string'
    ? traceSummaryResponse.value.traceStatus
    : trace.value.traceStatus
  const nodes = Array.isArray(trace.value.nodes) ? trace.value.nodes : []
  return resolveDisplayTraceStatus(traceStatus, durationMs, nodes)
})

const statusClass = computed(() => {
  if (!trace.value) return ''
  if (displayTraceStatus.value === 'FAILED') return 'bg-red-50 text-red-600 border-red-100'
  if (displayTraceStatus.value === 'SLOW') return 'bg-amber-50 text-amber-600 border-amber-100'
  if (displayTraceStatus.value === 'RUNNING') return 'bg-indigo-50 text-indigo-600 border-indigo-100'
  if (displayTraceStatus.value === 'UNKNOWN') return 'bg-slate-100 text-slate-600 border-slate-200'
  return 'bg-emerald-50 text-emerald-600 border-emerald-100'
})

const LOCAL_NODE_TYPES = new Set([
  'LOCAL_INDEX_LOAD',
  'LOCAL_CANDIDATE_RECALL',
  'LOCAL_OLLAMA_ROUTE',
  'LOCAL_NOTE_GRAPH'
])

const isFallbackNodeType = (nodeType) => {
  return typeof nodeType === 'string' && nodeType.trim().toUpperCase() === 'LOCAL_GRAPH_FALLBACK'
}

const isLocalNodeType = (nodeType) => {
  const normalized = typeof nodeType === 'string' ? nodeType.trim().toUpperCase() : ''
  return LOCAL_NODE_TYPES.has(normalized)
}

const usesRagNodeType = (nodeType) => {
  const normalized = typeof nodeType === 'string' ? nodeType.trim().toUpperCase() : ''
  return !isLocalNodeType(normalized) && !isFallbackNodeType(normalized)
}

const traceSummary = computed(() => {
  const nodes = Array.isArray(trace.value?.nodes) ? trace.value.nodes : []
  return {
    usesLocalGraph: nodes.some(node => isLocalNodeType(node?.nodeType)),
    usesRag: nodes.some(node => usesRagNodeType(node?.nodeType)),
    fallbackNodes: nodes.filter(node => isFallbackNodeType(node?.nodeType)),
    failedNodes: nodes.filter(node => {
      const status = typeof node?.status === 'string' ? node.status.trim().toUpperCase() : ''
      return status === 'FAILED' || status === 'ERROR' || status === 'TIMEOUT'
    })
  }
})

const extractDocCount = (node) => {
  if (!node) return 0
  const detailCount = Number(node.details?.retrievedDocCount)
  if (Number.isFinite(detailCount) && detailCount > 0) return detailCount
  const metricCount = Number(node.metrics?.retrievedDocs)
  if (Number.isFinite(metricCount) && metricCount > 0) return metricCount
  const summary = typeof node.outputSummary === 'string' ? node.outputSummary : ''
  const docCountMatch = summary.match(/docCount=(\d+)/i) || summary.match(/retrievedDocs=(\d+)/i)
  if (docCountMatch) return Number(docCountMatch[1]) || 0
  return 0
}

const extractDocRefs = (node) => {
  if (Array.isArray(node?.details?.retrievedDocumentRefs) && node.details.retrievedDocumentRefs.length) {
    return node.details.retrievedDocumentRefs
  }
  const summary = typeof node?.outputSummary === 'string' ? node.outputSummary : ''
  if (!summary) return []
  const docRefsMatch = summary.match(/docRefs=\[(.*)\]\}?$/s)
  if (!docRefsMatch || !docRefsMatch[1]) return []
  return docRefsMatch[1]
    .split(/,\s*(?=(?:\[[a-z]+]|[A-Za-z]:\\|[A-Za-z0-9_./-]+\s*\|))/i)
    .map(item => item.trim())
    .filter(Boolean)
}

const retrievalNodes = computed(() => {
  const nodes = Array.isArray(trace.value?.nodes) ? trace.value.nodes : []
  return nodes.filter(node => String(node?.nodeType || '').trim().toUpperCase() === 'RETRIEVAL')
})

const retrievedDocumentCount = computed(() => {
  const explicitTotal = Number(traceSummaryResponse.value?.retrievedDocCount ?? trace.value?.totalRetrievedDocs)
  if (Number.isFinite(explicitTotal) && explicitTotal > 0) return explicitTotal
  return retrievalNodes.value.reduce((sum, node) => sum + extractDocCount(node), 0)
})

const selectedNodeDocumentRefs = computed(() => extractDocRefs(selectedNode.value))
const selectedNodeDetailEntries = computed(() => {
  const details = selectedNode.value?.details
  if (!details || typeof details !== 'object') return []
  return Object.entries(details)
    .filter(([_, value]) => value !== null && value !== undefined && value !== '' && (!Array.isArray(value) || value.length))
    .filter(([key]) => key !== 'retrievedDocumentRefs')
})
const traceRiskTags = computed(() => Array.isArray(traceSummaryResponse.value?.riskTags) ? traceSummaryResponse.value.riskTags : [])

const windowDuration = computed(() => {
  if (!trace.value?.nodes?.length) return 0
  return traceSummaryResponse.value?.businessDurationMs || trace.value.durationMs || 1
})

const timelineNodes = computed(() => {
  if (!trace.value?.nodes?.length) return []
  
  const nodes = [...trace.value.nodes]
  // 按照开始时间排序
  nodes.sort((a, b) => new Date(a.startTime) - new Date(b.startTime))

  const baseStartTime = new Date(nodes[0].startTime).getTime()
  
  // 构建树结构来计算深度
  const nodeMap = {}
  nodes.forEach(n => { nodeMap[n.nodeId] = { ...n, children: [] } })
  
  const roots = []
  nodes.forEach(n => {
    if (n.parentNodeId && nodeMap[n.parentNodeId]) {
      nodeMap[n.parentNodeId].children.push(nodeMap[n.nodeId])
    } else {
      roots.push(nodeMap[n.nodeId])
    }
  })

  const flatList = []
  const traverse = (node, depth) => {
    const start = new Date(node.startTime).getTime()
    const offsetMs = start - baseStartTime
    const leftPercent = (offsetMs / windowDuration.value) * 100
    const widthPercent = (node.durationMs / windowDuration.value) * 100
    
    flatList.push({
      ...node,
      depth,
      leftPercent: Math.max(0, Math.min(99.5, leftPercent)),
      widthPercent: Math.max(0.5, Math.min(100 - leftPercent, widthPercent))
    })
    
    node.children.sort((a, b) => new Date(a.startTime) - new Date(b.startTime))
    node.children.forEach(c => traverse(c, depth + 1))
  }

  roots.forEach(r => traverse(r, 0))
  return flatList
})

const loadDetail = async () => {
  loading.value = true
  try {
    const data = await loadOpsTraceDetail(traceId.value)
    if (data) {
      trace.value = data.trace || data
      traceSummaryResponse.value = data.summary || null
    }
  } catch (err) {
    console.error('Failed to load trace detail:', err)
  } finally {
    loading.value = false
  }
}

const formatTime = (ts) => {
  if (!ts) return '-'
  return new Date(ts).toLocaleString()
}

const formatNodeType = (nodeType) => {
  if (!nodeType) return 'UNKNOWN'
  return String(nodeType).replaceAll('_', ' ')
}

const formatRiskTag = (tag) => {
  if (tag === 'slow_trace') return 'slow trace'
  if (tag === 'slow_first_token') return 'slow first token'
  if (tag === 'fallback_triggered') return 'fallback'
  if (tag === 'retrieval_empty') return 'empty retrieval'
  return tag || 'risk'
}

const getNodePathLabel = (nodeType) => {
  if (isFallbackNodeType(nodeType)) return 'fallback'
  if (isLocalNodeType(nodeType)) return 'local graph'
  return 'rag/core'
}

const getNodeTypeBadgeClass = (nodeType) => {
  if (isFallbackNodeType(nodeType)) return 'bg-amber-50 text-amber-700 border-amber-200'
  if (isLocalNodeType(nodeType)) return 'bg-cyan-50 text-cyan-700 border-cyan-200'
  return 'bg-slate-100 text-slate-500 border-slate-200'
}

const getNodePathBadgeClass = (nodeType) => {
  if (isFallbackNodeType(nodeType)) return 'bg-amber-50 text-amber-700 border-amber-200'
  if (isLocalNodeType(nodeType)) return 'bg-cyan-50 text-cyan-700 border-cyan-200'
  return 'bg-indigo-50 text-indigo-700 border-indigo-200'
}

const isFallbackNode = (node) => {
  return isFallbackNodeType(node?.nodeType)
}

const getNodeStatusColor = (status) => {
  const normalized = typeof status === 'string' ? status.trim().toUpperCase() : ''
  if (normalized === 'FAILED' || normalized === 'ERROR' || normalized === 'TIMEOUT') return 'bg-red-500'
  if (normalized === 'RUNNING') return 'bg-amber-500'
  return 'bg-emerald-500'
}

const getNodeBarColor = (status, nodeType) => {
  const normalized = typeof status === 'string' ? status.trim().toUpperCase() : ''
  if (normalized === 'FAILED' || normalized === 'ERROR' || normalized === 'TIMEOUT') return 'bg-red-400'
  if (normalized === 'RUNNING') return 'bg-amber-400'
  if (isFallbackNodeType(nodeType)) return 'bg-amber-400'
  if (isLocalNodeType(nodeType)) return 'bg-cyan-500'
  return 'bg-indigo-500'
}

const copyTraceId = () => {
  navigator.clipboard.writeText(traceId.value)
  // 提示已复制（这里可以用 toast）
}

onMounted(loadDetail)
</script>

<style scoped>
.slide-enter-active, .slide-leave-active {
  transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}
.slide-enter-from, .slide-leave-to {
  transform: translateX(100%);
}

/* 隐藏滚动条但保留功能 */
.overflow-x-auto {
  scrollbar-width: thin;
  scrollbar-color: #e2e8f0 transparent;
}
.overflow-x-auto::-webkit-scrollbar {
  height: 4px;
}
.overflow-x-auto::-webkit-scrollbar-track {
  background: transparent;
}
.overflow-x-auto::-webkit-scrollbar-thumb {
  background-color: #e2e8f0;
  border-radius: 20px;
}
</style>
