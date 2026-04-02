<template>
  <div class="space-y-6">
    <div class="bg-indigo-900 rounded-xl p-8 text-white relative overflow-hidden flex flex-col md:flex-row justify-between items-start md:items-center gap-6">
      <div class="absolute -right-10 -bottom-10 w-48 h-48 bg-white/10 rounded-full blur-3xl"></div>
      <div class="absolute -left-10 -top-10 w-32 h-32 bg-indigo-500/20 rounded-full blur-2xl"></div>
      <div class="relative z-10">
        <h3 class="text-indigo-200 text-xs font-bold uppercase tracking-widest mb-2 flex items-center gap-2">
           <span class="material-symbols-outlined text-[16px]">history</span>
           最近一次同步任务
        </h3>
        <div class="text-2xl md:text-3xl font-bold font-headline mt-1">{{ lastSyncTime !== '暂无' ? lastSyncTime : '暂无同步记录' }}</div>
      </div>
      <div class="relative z-10 flex gap-8 bg-black/20 p-4 rounded-xl border border-white/10">
        <div class="text-center">
           <div class="text-indigo-200 text-xs font-medium mb-1 uppercase tracking-wider">成功文件</div>
           <div class="text-2xl font-bold text-emerald-400">{{ successCount }}</div>
        </div>
        <div class="w-px bg-white/10"></div>
        <div class="text-center">
           <div class="text-indigo-200 text-xs font-medium mb-1 uppercase tracking-wider">失败文件</div>
           <div class="text-2xl font-bold text-red-400">{{ failCount }}</div>
        </div>
      </div>
    </div>

    <div>
      <h4 class="text-sm font-bold text-slate-900 mb-4 flex items-center gap-2">
         <span class="material-symbols-outlined text-slate-400">receipt_long</span>
         同步日志详情
      </h4>
      <div class="bg-white border border-slate-100 rounded-xl shadow-sm overflow-hidden">
         <div v-for="(item, idx) in reports" :key="idx" class="p-4 border-b border-slate-50 flex items-start gap-4 hover:bg-slate-50 transition-colors last:border-0">
           <div class="mt-1.5 w-2 h-2 rounded-full flex-shrink-0" :class="item.status === 'success' ? 'bg-emerald-500' : 'bg-red-500'"></div>
           <div class="flex-1 min-w-0">
             <h5 class="text-sm font-bold text-slate-900 mb-1 truncate">{{ item.fileName }}</h5>
             <p class="text-xs text-slate-500 leading-relaxed">{{ item.message }}</p>
           </div>
           <div class="text-[10px] font-mono font-bold uppercase tracking-widest px-2.5 py-1 rounded-full flex-shrink-0" :class="item.status === 'success' ? 'bg-emerald-50 text-emerald-600 border border-emerald-100' : 'bg-red-50 text-red-600 border border-red-100'">
             {{ item.status }}
           </div>
         </div>
         <div v-if="!reports?.length" class="p-12 text-center text-slate-400">
           <span class="material-symbols-outlined text-4xl mb-2">inbox</span>
           <p class="text-sm">暂无任务日志</p>
         </div>
      </div>
    </div>
  </div>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({
  reports: {
    type: Array,
    default: () => []
  },
  lastSyncTime: {
    type: String,
    default: '暂无'
  }
})
const successCount = computed(() => (props.reports || []).filter(r => r.status === 'success').length)
const failCount = computed(() => (props.reports || []).filter(r => r.status !== 'success').length)
</script>
