<template>
  <div class="bg-white rounded-xl shadow-sm border border-slate-100 overflow-hidden">
    <div class="p-4 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
      <div class="relative">
        <span class="material-symbols-outlined absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 text-[18px]">search</span>
        <input v-model="searchQuery" type="text" placeholder="搜索文档名称或状态..." class="pl-9 pr-4 py-2 text-sm border border-slate-200 bg-white rounded-lg shadow-sm focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 w-64 outline-none transition-all" />
      </div>
      <div class="text-sm font-medium text-slate-500">共 <span class="text-indigo-600">{{ filteredReports.length }}</span> 份文档记录</div>
    </div>
    <div class="overflow-x-auto min-h-[300px]">
      <table class="w-full text-left text-sm">
        <thead class="bg-slate-50/80 text-slate-500 font-bold border-b border-slate-100">
          <tr>
            <th class="px-6 py-4 whitespace-nowrap">文档名称</th>
            <th class="px-6 py-4 whitespace-nowrap">状态</th>
            <th class="px-6 py-4 whitespace-nowrap">处理信息</th>
            <th class="px-6 py-4 text-right whitespace-nowrap">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr v-for="(item, idx) in filteredReports" :key="idx" class="hover:bg-slate-50/50 transition-colors">
            <td class="px-6 py-4 font-medium text-slate-900">{{ item.fileName }}</td>
            <td class="px-6 py-4">
              <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-bold" :class="item.status === 'success' ? 'bg-emerald-50 text-emerald-700 border border-emerald-100' : 'bg-red-50 text-red-700 border border-red-100'">
                <span class="w-1.5 h-1.5 rounded-full" :class="item.status === 'success' ? 'bg-emerald-500' : 'bg-red-500'"></span>
                {{ item.status === 'success' ? '已处理' : '失败' }}
              </span>
            </td>
            <td class="px-6 py-4 text-slate-500 max-w-md truncate" :title="item.message">{{ item.message }}</td>
            <td class="px-6 py-4 text-right">
              <button class="text-indigo-600 hover:text-indigo-800 text-xs font-bold opacity-50 cursor-not-allowed" title="待后端接入">详情</button>
            </td>
          </tr>
          <tr v-if="!filteredReports.length">
            <td colspan="4" class="px-6 py-16 text-center">
               <div class="flex flex-col items-center justify-center text-slate-400">
                 <span class="material-symbols-outlined text-4xl mb-2">description</span>
                 <p>暂无文档记录</p>
               </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
<script setup>
import { ref, computed } from 'vue'
const props = defineProps({
  reports: {
    type: Array,
    default: () => []
  }
})
const searchQuery = ref('')
const filteredReports = computed(() => {
  if (!searchQuery.value) return props.reports || []
  const q = searchQuery.value.toLowerCase()
  return (props.reports || []).filter(r => r.fileName?.toLowerCase().includes(q) || r.message?.toLowerCase().includes(q))
})
</script>
