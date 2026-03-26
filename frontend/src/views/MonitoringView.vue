<template>
  <main class="ml-64 pt-24 px-8 pb-12 w-full min-h-screen relative z-10 bg-[#f9fafb]">
    <!-- Dashboard Header -->
    <div class="flex flex-col md:flex-row justify-between items-start md:items-end gap-6 mb-12 w-[80%]">
      <div>
        <h1 class="font-headline text-4xl font-bold tracking-tighter text-[#111827] mb-2">模型路由与健康分析</h1>
        <p class="text-[#4b5563] text-sm flex items-center gap-2">
          <span class="material-symbols-outlined text-sm">update</span>
          数据刷新于: <span class="text-[#059669] font-mono font-bold">{{ lastUpdate }}</span> | 下次更新 <span class="text-[#059669] font-mono underline decoration-dotted underline-offset-4">{{ countdown }}s</span>
        </p>
      </div>
      <div class="grid grid-cols-2 sm:grid-cols-4 gap-4 w-full md:w-auto">
        <div class="bg-white p-4 border border-[#e5e7eb] shadow-sm rounded-lg border-l-4 border-l-[#10b981]">
          <div class="text-[10px] font-bold text-[#4b5563] uppercase mb-1 tracking-widest">Total Throughput</div>
          <div class="text-xl font-black text-[#111827]">{{ (runtimeHealth?.successCount || 0) + (runtimeHealth?.failureCount || 0) }} <span class="text-xs text-[#4b5563] font-normal">REQ/M</span></div>
        </div>
        <div class="bg-white p-4 border border-[#e5e7eb] shadow-sm rounded-lg border-l-4 border-l-[#f59e0b]">
          <div class="text-[10px] font-bold text-[#4b5563] uppercase mb-1 tracking-widest">Route Fallback</div>
          <div class="text-xl font-black text-[#111827]">{{ fallbackCount || 0 }} <span class="text-xs text-[#4b5563] font-normal">TIMES</span></div>
        </div>
        <div class="bg-white p-4 border border-[#e5e7eb] shadow-sm rounded-lg border-l-4 border-l-[#ef4444]">
          <div class="text-[10px] font-bold text-[#4b5563] uppercase mb-1 tracking-widest">Total Failures</div>
          <div class="text-xl font-black text-[#dc2626]">{{ runtimeHealth?.failureCount || 0 }}</div>
        </div>
        <div class="bg-white p-4 border border-[#e5e7eb] shadow-sm rounded-lg border-l-4 border-l-[#10b981]">
          <div class="text-[10px] font-bold text-[#4b5563] uppercase mb-1 tracking-widest">Circuit Status</div>
          <div class="text-xl font-black" :class="systemHealthLabel === '系统降级中' ? 'text-[#d97706]' : 'text-[#059669]'">{{ systemHealthLabel === '系统降级中' ? 'DEGRADED' : 'OPTIMAL' }}</div>
        </div>
      </div>
    </div>

    <!-- Model Routing Grid -->
    <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 pb-20">
      <div v-for="item in detailCards" :key="item.name" 
           class="bg-white border p-6 rounded-xl relative group transition-all duration-300 hover:shadow-lg"
           :class="item.statusClass === 'safe' ? 'border-[#e5e7eb] shadow-[0_4px_20px_-2px_rgba(5,150,105,0.1)]' : 'border-[#fee2e2] shadow-[0_4px_20px_-2px_rgba(220,38,38,0.1)]'">
        <div class="flex justify-between items-start mb-8">
          <div class="flex items-center gap-4">
            <div class="relative">
              <div class="w-3 h-3" :class="item.statusClass === 'safe' ? 'bg-[#059669]' : 'bg-[#dc2626] animate-pulse'"></div>
              <div class="absolute -inset-1 border opacity-20 scale-150" :class="item.statusClass === 'safe' ? 'border-[#059669]' : 'border-[#dc2626]'"></div>
            </div>
            <div>
              <h3 class="font-headline text-lg font-bold tracking-tight text-[#111827]">{{ item.name }}</h3>
              <div class="font-bold text-[10px] text-[#4b5563] uppercase tracking-widest mt-1">STATE: {{ item.state }}</div>
            </div>
          </div>
          <div class="flex flex-col items-end">
            <div class="font-mono text-xs font-bold px-2 py-0.5 border uppercase rounded"
                 :class="item.statusClass === 'safe' ? 'text-[#047857] border-[#a7f3d0] bg-[#ecfdf5]' : 'text-[#b91c1c] border-[#fecaca] bg-[#fef2f2]'">
              Status: {{ item.statusClass === 'safe' ? 'Closed' : 'Open' }}
            </div>
          </div>
        </div>
        <div class="grid grid-cols-3 gap-4 mb-8">
          <div>
            <div class="text-[9px] font-bold text-[#4b5563] uppercase mb-1 tracking-widest">Success</div>
            <div class="text-xl font-black text-[#111827]">{{ item.successCount }}</div>
          </div>
          <div>
            <div class="text-[9px] font-bold uppercase mb-1 tracking-widest" :class="item.failureCount > 0 ? 'text-[#dc2626]' : 'text-[#4b5563]'">Failures</div>
            <div class="text-xl font-black" :class="item.failureCount > 0 ? 'text-[#dc2626]' : 'text-[#111827]'">{{ item.failureCount }}</div>
          </div>
          <div>
            <div class="text-[9px] font-bold text-[#4b5563] uppercase mb-1 tracking-widest">Reliability</div>
            <div class="text-xl font-black" :class="item.statusClass === 'safe' ? 'text-[#059669]' : 'text-[#dc2626]'">
              {{ item.reliabilityPercent }}%
            </div>
          </div>
        </div>

        <!-- Progress Bar: Traffic Flow / Capacity (Restored Module) -->
        <div class="space-y-2 mb-6">
          <div class="flex justify-between text-[10px] font-mono text-[#4b5563] uppercase font-bold">
            <span>Traffic Flow</span>
            <span>Capacity {{ item.capacityPercent }}%</span>
          </div>
          <div class="h-2 bg-[#f3f4f6] rounded-full overflow-hidden">
            <div class="h-full rounded-full transition-all duration-500" 
                 :class="item.statusClass === 'safe' ? 'bg-[#10b981]' : 'bg-[#ef4444]'"
                 :style="{ width: item.capacityPercent + '%' }"></div>
          </div>
        </div>
        
        <!-- Last Error Block -->
        <div v-if="item.lastFailureMessage" class="bg-[#fef2f2] p-4 mb-6 border-l-4 border-[#dc2626] rounded">
          <div class="text-[9px] font-bold text-[#b91c1c] uppercase mb-1 tracking-widest">Critical Alert: Last Error Message</div>
          <div class="text-xs font-mono text-[#991b1b] font-medium">{{ item.lastFailureMessage }}</div>
        </div>
        
        <div class="flex justify-between items-center pt-4 border-t border-[#e5e7eb]">
          <span class="text-[9px] font-mono font-bold" :class="item.statusClass === 'safe' ? 'text-[#6b7280]' : 'text-[#dc2626] animate-pulse tracking-tighter'">
            {{ item.statusClass === 'safe' ? '_MODEL_STABLE' : '// CIRCUIT_BREAKER_TRIPPED' }}
          </span>
          <button v-if="item.statusClass === 'safe'" class="text-[10px] font-bold text-[#111827] uppercase hover:text-[#059669] transition-colors flex items-center gap-1 group/btn">
            <span>_ DETAILS</span>
            <span class="material-symbols-outlined text-xs group-hover/btn:translate-x-1 transition-transform">chevron_right</span>
          </button>
          <button v-else class="text-[10px] font-bold text-[#dc2626] uppercase border border-[#fecaca] px-3 py-1 rounded bg-white hover:bg-[#dc2626] hover:text-white transition-all active:scale-95 shadow-sm">
            _ FORCE_RESET
          </button>
        </div>
      </div>

      <!-- Bento Card: Traffic Distribution Map -->
      <div class="lg:col-span-2 bg-white border border-[#e5e7eb] p-6 rounded-xl flex flex-col md:flex-row gap-8 shadow-sm w-[60%]">
        <div class="flex-1">
          <h4 class="font-headline text-sm font-bold tracking-tight text-[#111827] mb-6 uppercase">实时流量路由拓扑</h4>
          <div class="relative w-full h-48 bg-[#f8fafc] border border-[#e5e7eb] rounded-lg flex items-center justify-center overflow-hidden">
            <img class="absolute inset-0 w-full h-full object-cover opacity-5 grayscale" src="https://lh3.googleusercontent.com/aida-public/AB6AXuA3XZAfKOSqa_iRlzaQkfeVWMm1ChoeidnmF2SgmUQ-UWgaHNt2ZERlbCVctKX5U2i3WDNCc9_snAl4jGSSrb4zWhFFZBsilhj3exD8mNTWURtPG0eM6qKlE2w36NXEAO_bAqFpcGlvf87ETlJ4httolgcHFzIGO0YL3ygEPiC_kHIm22kJRE3rNpzP4WZx_CAv06KCXuVNmGGka9htocU4zfe9-jA57LvvqClkng6k25DykaxEkx-kNpgCWbvnR8WeUlEWN35IEmxr"/>
            <div class="relative z-10 w-full h-full p-4 flex flex-col justify-between">
              <div class="flex justify-between items-start">
                <div class="bg-[#059669] text-white text-[10px] font-bold px-2 py-1 uppercase tracking-widest rounded shadow-sm">Source: Edge_Shanghai_01</div>
                <div class="bg-white border border-[#e5e7eb] text-[#6b7280] text-[10px] px-2 py-1 font-mono font-bold rounded">LAT: 31.2304 / LON: 121.4737</div>
              </div>
              <div class="flex justify-center items-center h-full py-4">
                <div class="relative w-full max-w-lg h-1 bg-[#e2e8f0] rounded-full overflow-hidden">
                  <div class="absolute inset-0 bg-gradient-to-r from-transparent via-[#34d399] to-transparent opacity-50"></div>
                  <div class="absolute top-0 left-1/4 w-3 h-3 -mt-1 bg-[#10b981] rounded-full animate-ping"></div>
                  <div class="absolute top-0 left-2/3 w-3 h-3 -mt-1 bg-[#10b981] rounded-full shadow-sm"></div>
                </div>
              </div>
              <div class="flex justify-between items-end">
                <div class="flex flex-col gap-1">
                  <div class="text-[8px] text-[#6b7280] font-mono font-bold uppercase">ROUTING_TO_INSTANCE:</div>
                  <div class="text-xs font-bold text-[#059669] font-mono tracking-tight">internal-cluster-0x02ff</div>
                </div>
                <div class="text-[8px] text-[#6b7280] font-mono font-bold">PING: 24ms</div>
              </div>
            </div>
          </div>
        </div>
        <div class="md:w-64 flex flex-col gap-4">
          <h4 class="font-headline text-sm font-bold tracking-tight text-[#111827] uppercase">系统健康日志</h4>
          <div class="flex-1 space-y-3 font-mono text-[10px]">
            <div v-for="(line, index) in healthLogs" :key="index" class="flex gap-2 font-bold" :class="line.includes('OPEN') || line.includes('fail=') ? 'text-[#dc2626]' : 'text-[#047857]'">
              <span class="opacity-50">[{{ lastUpdate }}]</span>
              <span>{{ line.replace(`[${lastUpdate}]`, '') }}</span>
            </div>
            <div v-if="healthLogs.length === 0" class="flex gap-2 text-[#6b7280] font-medium">
              <span class="opacity-50">[{{ lastUpdate }}]</span>
              <span>WAITING_FOR_LOGS...</span>
            </div>
          </div>
          <button class="w-full bg-[#f1f5f9] text-[#4b5563] py-3 rounded font-bold text-[10px] tracking-widest border border-[#e2e8f0] hover:text-[#111827] hover:bg-[#e2e8f0] transition-all uppercase shadow-sm">
            View_Full_Logs
          </button>
        </div>
      </div>
    </div>
  </main>
</template>

<script setup>
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { loadModelRoutingStats } from '../api/monitoring'

const runtimeHealth = ref({ successCount: 0, failureCount: 0 })
const rawDetails = ref({})
const rawStates = ref({})
const healthLogs = ref([])

const lastUpdate = ref('00:00:00')
const countdown = ref(5)

const fallbackCount = computed(() => {
  if (!rawDetails.value) return 0
  return Object.values(rawDetails.value).reduce((sum, item) => sum + (item.failureCount || 0), 0)
})

const systemHealthLabel = computed(() => {
  if (!rawStates.value) return '全系统健康'
  const hasOpen = Object.values(rawStates.value).some(s => s === 'OPEN')
  return hasOpen ? '系统降级中' : '全系统健康'
})

const detailCards = computed(() => {
  if (!rawDetails.value) return []
  return Object.entries(rawDetails.value).map(([name, detail]) => {
    const state = rawStates.value?.[name] || 'UNKNOWN'
    const isHealthy = state === 'CLOSED' || state === 'HALF_OPEN'
    const successCount = detail.successCount || 0
    const failureCount = detail.failureCount || 0
    const total = successCount + failureCount
    const reliabilityPercent = total === 0 ? 100 : ((successCount / total) * 100).toFixed(1)

    return {
      name,
      state,
      statusClass: isHealthy ? 'safe' : 'danger',
      successCount,
      failureCount,
      lastCostMs: detail.lastCostMs || 0,
      reliabilityPercent,
      capacityPercent: Math.min(100, Math.round((total / 500) * 100) + (isHealthy ? 20 : 80)), // Mocked dynamic capacity for visualization
      lastFailureMessage: !isHealthy ? (detail.lastFailureMessage || '未知降级原因') : null
    }
  })
})

let timer = null
let countdownTimer = null

const updateStats = async () => {
  try {
    const data = await loadModelRoutingStats()
    if (data.runtime) {
      runtimeHealth.value = data.runtime.health || { successCount: 0, failureCount: 0 }
      rawDetails.value = data.runtime.details || {}
      rawStates.value = data.runtime.states || {}
      healthLogs.value = data.healthLogs || []
    }
    const now = new Date()
    lastUpdate.value = now.toLocaleTimeString('en-US', { hour12: false })
    countdown.value = 5
  } catch (err) {
    console.error('Failed to fetch stats:', err)
  }
}

onMounted(() => {
  updateStats()
  timer = setInterval(updateStats, 5000)
  countdownTimer = setInterval(() => {
    if (countdown.value > 0) countdown.value--
  }, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (countdownTimer) clearInterval(countdownTimer)
})
</script>
