<template>
  <div class="bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100 min-h-screen relative">
    <header class="fixed top-0 right-0 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-md flex items-center justify-between px-8 z-40 shadow-sm border-b border-slate-200 transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
    <div class="flex items-center gap-4">
      <h1 class="text-xl font-bold tracking-tight text-indigo-700">意图列表 <span class="text-slate-500 dark:text-slate-400 dark:text-slate-500 font-medium text-sm ml-2">/ 筛选、分页与批量下线意图节点</span></h1>
    </div>
    <div class="flex items-center gap-2">
      <RouterLink to="/intent-tree" class="px-3 py-1.5 text-xs rounded border border-slate-200 text-slate-600 dark:text-slate-400 dark:text-slate-500 hover:bg-slate-50 dark:bg-slate-800/50">
        回到树页
      </RouterLink>
      <button @click="reload" :disabled="loading" class="px-3 py-1.5 text-xs rounded border border-slate-200 text-slate-600 dark:text-slate-400 dark:text-slate-500 hover:bg-slate-50 dark:bg-slate-800/50 disabled:opacity-50">
        刷新
      </button>
    </div>
  </header>

  <main class="min-h-screen bg-slate-50 dark:bg-slate-800/50 pt-24 pb-10 px-8 space-y-6 transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
    <section class="rounded-xl border border-slate-200 bg-white dark:bg-slate-900 p-4">
      <div class="flex flex-wrap items-center gap-2">
        <div class="relative w-full md:w-[420px]">
          <input v-model="keyword" placeholder="搜索意图名称/ID/路径..." class="h-10 w-full border-slate-200 pl-9 text-sm rounded focus-visible:ring-0 focus-visible:border-slate-200" />
          <span class="material-symbols-outlined absolute left-2 top-[10px] text-slate-400 dark:text-slate-500 text-base">search</span>
        </div>
        <select v-model="taskTypeFilter" class="h-10 rounded border-slate-200 text-sm px-2">
          <option value="ALL">全部任务类型</option>
          <option v-for="t in taskTypeOptions" :key="t" :value="t">{{ t }}</option>
        </select>
        <select v-model="statusFilter" class="h-10 rounded border-slate-200 text-sm px-2">
          <option value="ALL">全部状态</option>
          <option value="ENABLED">仅启用</option>
          <option value="DISABLED">仅下线</option>
        </select>
        <button class="h-10 rounded border-slate-200 text-sm px-3 border" @click="resetFilters">清空筛选</button>
      </div>
    </section>

    <section class="bg-white dark:bg-slate-900 rounded shadow-sm p-4">
      <div v-if="selectedIndexes.length > 0" class="flex items-center justify-between border-y border-slate-200 bg-slate-50 dark:bg-slate-800/50 px-4 py-2 -mx-4 mb-3">
        <span class="text-sm text-slate-700 dark:text-slate-300">已选 {{ selectedIndexes.length }} 项</span>
        <div class="flex items-center gap-2">
          <button class="h-8 rounded border text-xs px-3" @click="runBatchOffline" :disabled="loading">批量下线</button>
        </div>
      </div>

      <div v-if="loading" class="py-10 text-center text-slate-500 dark:text-slate-400 dark:text-slate-500">加载中...</div>
      <div v-else-if="pageRows.length === 0" class="py-10 text-center text-slate-500 dark:text-slate-400 dark:text-slate-500">
        {{ filteredLeafs.length === 0 ? '暂无意图节点，请在树页新增' : '没有匹配结果，请调整筛选条件' }}
      </div>

      <table v-else class="min-w-[1024px] w-full text-sm">
        <thead>
          <tr class="text-left">
            <th class="w-[48px]">
              <input type="checkbox" :checked="allPageSelected" @change="togglePageSelect($event.target.checked)" />
            </th>
            <th class="w-[300px]">意图节点</th>
            <th class="w-[320px]">路径</th>
            <th class="w-[120px]">任务类型</th>
            <th class="w-[90px]">示例数</th>
            <th class="w-[120px]">状态</th>
            <th class="w-[200px]">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in pageRows" :key="row.index" class="border-t">
            <td>
              <input type="checkbox" :checked="selectedIndexes.includes(row.index)" @change="toggleRowSelect(row.index, $event.target.checked)" />
            </td>
            <td>
              <div class="space-y-0.5">
                <div class="flex items-center gap-2">
                  <span class="font-semibold text-slate-900 dark:text-slate-100">{{ row.name || '-' }}</span>
                  <span class="rounded-full border border-slate-200 bg-slate-50 dark:bg-slate-800/50 px-2 py-0.5 font-mono text-xs text-slate-600 dark:text-slate-400 dark:text-slate-500">
                    {{ row.intentId }}
                  </span>
                </div>
              </div>
            </td>
            <td>
              <div class="flex flex-wrap items-center gap-1">
                <span v-for="(seg, i) in row.pathSegments" :key="`${row.index}-${seg}-${i}`" class="inline-flex items-center gap-1">
                  <span v-if="i > 0" class="text-slate-300">/</span>
                  <button type="button" class="rounded px-1.5 py-0.5 text-xs transition-colors" @click="navigateToTree(row)">
                    {{ seg }}
                  </button>
                </span>
              </div>
            </td>
            <td>{{ row.taskType || '-' }}</td>
            <td>{{ row.exampleCount }}</td>
            <td>
              <span :class="row.enabled ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-200 text-slate-600 dark:text-slate-400 dark:text-slate-500'" class="px-2 py-0.5 rounded text-[12px]">
                {{ row.enabled ? '启用' : '下线' }}
              </span>
            </td>
            <td>
              <div class="flex items-center gap-2">
                <button class="h-8 rounded border text-xs px-2.5" @click="navigateToEdit(row)">编辑</button>
                <button class="h-8 rounded border text-xs px-2.5" @click="offlineSingle(row)">下线</button>
                <button class="h-8 rounded border text-xs px-2.5" @click="navigateToTree(row)">定位树</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>

      <div v-if="showPagination" class="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500 dark:text-slate-400 dark:text-slate-500">
        <span>共 {{ total }} 条，显示 {{ rangeStart }}-{{ rangeEnd }}</span>
        <div class="flex flex-wrap items-center gap-2">
          <span>每页</span>
          <select v-model.number="pageSize" class="h-8 rounded border-slate-200 text-sm px-2" @change="setPage(1)">
            <option v-for="size in pageSizeOptions" :key="size" :value="size">{{ size }} 条</option>
          </select>
          <button class="h-8 rounded border px-2.5" @click="setPage(1)" :disabled="currentPage <= 1">首页</button>
          <button class="h-8 rounded border px-2.5" @click="setPage(Math.max(1, currentPage - 1))" :disabled="currentPage <= 1">上一页</button>
          <span>{{ currentPage }} / {{ totalPages }}</span>
          <button class="h-8 rounded border px-2.5" @click="setPage(Math.min(totalPages, currentPage + 1))" :disabled="currentPage >= totalPages">下一页</button>
          <button class="h-8 rounded border px-2.5" @click="setPage(totalPages)" :disabled="currentPage >= totalPages">末页</button>
        </div>
      </div>
    </section>
  </main>
  </div>
</template>

<script setup>
// 中文注释：列表页实现，支持筛选、分页与批量下线，并与树页/编辑页双向导航
import { computed, onMounted, ref } from 'vue'
import { useRouter, useRoute, RouterLink } from 'vue-router'
import { useIntentTreeAdmin } from '../composables/useIntentTreeAdmin'

const router = useRouter()
const route = useRoute()
defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})
const {
  loading,
  hint,
  config,
  filteredLeafs,
  taskTypeOptions,
  keyword,
  taskTypeFilter,
  statusFilter,
  selectedIndexes,
  reload,
  toggleBatchEnabled
} = useIntentTreeAdmin()

// 中文注释：分页状态
const pageSizeOptions = [10, 20, 50]
const pageNo = ref(1)
const pageSize = ref(pageSizeOptions[0])

// 中文注释：分页计算
const total = computed(() => filteredLeafs.value.length)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const currentPage = computed(() => Math.min(pageNo.value, totalPages.value))
const startIndex = computed(() => (currentPage.value - 1) * pageSize.value)
const pageRows = computed(() => filteredLeafs.value.slice(startIndex.value, startIndex.value + pageSize.value))
const rangeStart = computed(() => (total.value === 0 ? 0 : startIndex.value + 1))
const rangeEnd = computed(() => (total.value === 0 ? 0 : Math.min(startIndex.value + pageRows.value.length, total.value)))
const showPagination = computed(() => !loading.value && total.value > 0)

// 中文注释：选择行为
const allPageSelected = computed(() => {
  const ids = pageRows.value.map((row) => row.index)
  return ids.length > 0 && ids.every((id) => selectedIndexes.value.includes(id))
})
const toggleRowSelect = (index, checked) => {
  const set = new Set(selectedIndexes.value)
  if (checked) set.add(index)
  else set.delete(index)
  selectedIndexes.value = Array.from(set)
}
const togglePageSelect = (checked) => {
  const set = new Set(selectedIndexes.value)
  const ids = pageRows.value.map((row) => row.index)
  if (checked) ids.forEach((id) => set.add(id))
  else ids.forEach((id) => set.delete(id))
  selectedIndexes.value = Array.from(set)
}

// 中文注释：导航行为
const navigateToTree = (row) => {
  router.push(`/intent-tree?intentCode=${encodeURIComponent(row.intentId)}`)
}
const navigateToEdit = (row) => {
  const from = route.fullPath
  router.push(`/intent-list/${row.index}/edit?from=${encodeURIComponent(from)}`)
}

// 中文注释：批量与单项操作
const runBatchOffline = async () => {
  if (selectedIndexes.value.length === 0) return
  const ok = window.confirm(`确认下线已选中的 ${selectedIndexes.value.length} 个意图节点吗？`)
  if (!ok) return
  await toggleBatchEnabled(false)
  await reload()
  selectedIndexes.value = []
}
const offlineSingle = async (row) => {
  const ok = window.confirm(`确认下线节点 [${row.intentId || row.name}] 吗？`)
  if (!ok) return
  selectedIndexes.value = [row.index]
  await toggleBatchEnabled(false)
  await reload()
  selectedIndexes.value = []
}

const resetFilters = () => {
  keyword.value = ''
  taskTypeFilter.value = 'ALL'
  statusFilter.value = 'ALL'
  pageNo.value = 1
}

const setPage = (no) => {
  pageNo.value = no
}

onMounted(async () => {
  await reload()
})
</script>
