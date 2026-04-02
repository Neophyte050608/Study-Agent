<template>
  <div class="bg-slate-50 min-h-screen antialiased">
    <!-- Header -->
    <header class="fixed top-0 right-0 left-64 h-16 bg-white/80 backdrop-blur-xl border-b border-slate-100 flex justify-between items-center px-8 z-40">
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

    <main class="ml-64 pt-24 px-8 pb-12">
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
        <div class="grid grid-cols-4 gap-6">
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">总耗时</div>
            <div class="text-xl font-black text-slate-900">{{ trace.durationMs }} <span class="text-xs font-medium opacity-50">ms</span></div>
          </div>
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">节点总数</div>
            <div class="text-xl font-black text-slate-900">{{ trace.nodes?.length || 0 }}</div>
          </div>
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">检索文档</div>
            <div class="text-xl font-black text-slate-900">{{ trace.totalRetrievedDocs || 0 }}</div>
          </div>
          <div class="bg-white p-5 rounded-xl border border-slate-200 shadow-sm">
            <div class="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-1">开始时间</div>
            <div class="text-sm font-bold text-slate-700 mt-1">{{ formatTime(trace.startTime) }}</div>
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
                    <span class="px-1.5 py-0.5 rounded-[4px] text-[9px] font-black uppercase tracking-tighter bg-slate-100 text-slate-500 border border-slate-200">
                      {{ node.nodeType }}
                    </span>
                  </div>

                  <!-- Bar -->
                  <div class="flex-1 h-6 relative bg-slate-50/50 rounded-sm overflow-hidden border border-slate-100/50">
                    <div class="absolute top-1 bottom-1 rounded-sm transition-all duration-500 shadow-sm"
                         :class="getNodeBarColor(node.status)"
                         :style="{ left: `${node.leftPercent}%`, width: `${Math.max(node.widthPercent, 0.5)}%` }">
                    </div>
                  </div>

                  <!-- Duration -->
                  <div class="w-20 shrink-0 text-right">
                    <span class="text-xs font-mono font-bold text-slate-600">{{ node.durationMs }}ms</span>
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

const route = useRoute()
const traceId = ref(route.params.traceId)
const loading = ref(false)
const trace = ref(null)
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
  const durationMs = typeof trace.value.durationMs === 'number' ? trace.value.durationMs : 0
  const nodes = Array.isArray(trace.value.nodes) ? trace.value.nodes : []
  return resolveDisplayTraceStatus(trace.value.traceStatus, durationMs, nodes)
})

const statusClass = computed(() => {
  if (!trace.value) return ''
  if (displayTraceStatus.value === 'FAILED') return 'bg-red-50 text-red-600 border-red-100'
  if (displayTraceStatus.value === 'SLOW') return 'bg-amber-50 text-amber-600 border-amber-100'
  if (displayTraceStatus.value === 'RUNNING') return 'bg-indigo-50 text-indigo-600 border-indigo-100'
  if (displayTraceStatus.value === 'UNKNOWN') return 'bg-slate-100 text-slate-600 border-slate-200'
  return 'bg-emerald-50 text-emerald-600 border-emerald-100'
})

const windowDuration = computed(() => {
  if (!trace.value?.nodes?.length) return 0
  return trace.value.durationMs || 1
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
      trace.value = data
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

const getNodeStatusColor = (status) => {
  if (status === 'FAILED') return 'bg-red-500'
  if (status === 'RUNNING') return 'bg-amber-500'
  return 'bg-emerald-500'
}

const getNodeBarColor = (status) => {
  if (status === 'FAILED') return 'bg-red-400'
  if (status === 'RUNNING') return 'bg-amber-400'
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
