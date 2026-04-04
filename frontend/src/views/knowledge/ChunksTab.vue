<template>
  <div class="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-100 overflow-hidden">
    <div class="p-4 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
      <div class="flex items-center gap-3">
        <button @click="$emit('back')" class="text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:text-slate-200 transition-colors flex items-center">
          <span class="material-symbols-outlined text-[20px]">arrow_back</span>
        </button>
        <h3 class="font-bold text-slate-800 dark:text-slate-200">文档分块明细 <span class="text-sm font-normal text-slate-500 dark:text-slate-400 ml-2" v-if="docId">{{ docId }}</span></h3>
      </div>
      <div class="text-sm font-medium text-slate-500 dark:text-slate-400">共 <span class="text-indigo-600">{{ total }}</span> 个分块</div>
    </div>

    <div v-if="!docId" class="flex flex-col items-center justify-center py-24 bg-slate-50/50">
      <div class="w-16 h-16 rounded-full bg-white dark:bg-slate-900 shadow-sm flex items-center justify-center text-slate-400 mb-5">
        <span class="material-symbols-outlined text-3xl">ads_click</span>
      </div>
      <h3 class="text-lg font-bold text-slate-900 dark:text-slate-100 mb-2">未选择文档</h3>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-6">请先在文档列表中点击“详情”以查看具体分块内容。</p>
    </div>

    <div v-else class="overflow-x-auto min-h-[300px] relative">
      <div v-if="loading" class="absolute inset-0 bg-white/50 backdrop-blur-sm flex items-center justify-center z-10">
        <span class="material-symbols-outlined animate-spin text-indigo-600 text-3xl">progress_activity</span>
      </div>
      <table class="w-full text-left text-sm">
        <thead class="bg-slate-50/80 text-slate-500 dark:text-slate-400 font-bold border-b border-slate-100">
          <tr>
            <th class="px-6 py-4 whitespace-nowrap w-24">序号</th>
            <th class="px-6 py-4">分块预览 (Snippet)</th>
            <th class="px-6 py-4 whitespace-nowrap w-32">状态</th>
            <th class="px-6 py-4 text-right whitespace-nowrap w-32">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr v-for="(item, idx) in chunks" :key="item.chunkId" class="hover:bg-slate-50/50 transition-colors">
            <td class="px-6 py-4 font-mono text-slate-500 dark:text-slate-400">#{{ item.childIndex }}</td>
            <td class="px-6 py-4 text-slate-700 dark:text-slate-300">
              <div class="line-clamp-3 text-xs leading-relaxed">{{ item.snippet }}</div>
            </td>
            <td class="px-6 py-4">
              <span class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-bold" :class="item.enabled ? 'bg-emerald-50 text-emerald-700 border border-emerald-100' : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 border border-slate-200'">
                <span class="w-1.5 h-1.5 rounded-full" :class="item.enabled ? 'bg-emerald-500' : 'bg-slate-400'"></span>
                {{ item.enabled ? '已启用' : '已禁用' }}
              </span>
            </td>
            <td class="px-6 py-4 text-right">
              <button @click="toggleEnabled(item)" class="text-indigo-600 hover:text-indigo-800 text-xs font-bold">
                {{ item.enabled ? '禁用' : '启用' }}
              </button>
            </td>
          </tr>
          <tr v-if="!chunks.length && !loading">
            <td colspan="4" class="px-6 py-16 text-center text-slate-400">该文档暂无分块数据</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 分页 -->
    <div v-if="docId" class="p-4 border-t border-slate-100 flex justify-between items-center bg-slate-50/50 text-sm">
      <button @click="pageNo > 1 && (pageNo--, fetchChunks())" :disabled="pageNo <= 1" class="px-3 py-1 bg-white dark:bg-slate-900 border border-slate-200 rounded text-slate-600 dark:text-slate-400 disabled:opacity-50 hover:bg-slate-50 dark:bg-slate-800/50 transition-colors">上一页</button>
      <div class="flex items-center gap-2 text-slate-500 dark:text-slate-400">
        <span>第</span>
        <select v-model="pageNo" @change="fetchChunks" class="bg-white dark:bg-slate-900 border border-slate-200 rounded pl-3 pr-8 py-1 text-slate-700 dark:text-slate-300 outline-none focus:ring-1 focus:ring-indigo-500 appearance-none bg-[url('data:image/svg+xml;charset=utf-8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20fill%3D%22none%22%20viewBox%3D%220%200%2020%2020%22%3E%3Cpath%20stroke%3D%22%236b7280%22%20stroke-linecap%3D%22round%22%20stroke-linejoin%3D%22round%22%20stroke-width%3D%221.5%22%20d%3D%22M6%208l4%204%204-4%22%2F%3E%3C%2Fsvg%3E')] bg-[length:1.25rem_1.25rem] bg-[right_0.25rem_center] bg-no-repeat">
          <option v-for="p in Math.max(1, Math.ceil(total / pageSize))" :key="p" :value="p">{{ p }}</option>
        </select>
        <span>页，共 {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</span>
      </div>
      <button @click="chunks.length === pageSize && (pageNo++, fetchChunks())" :disabled="chunks.length < pageSize" class="px-3 py-1 bg-white dark:bg-slate-900 border border-slate-200 rounded text-slate-600 dark:text-slate-400 disabled:opacity-50 hover:bg-slate-50 dark:bg-slate-800/50 transition-colors">下一页</button>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted, watch } from 'vue'
import { knowledgeService } from '../../services/knowledgeService'

const props = defineProps({
  docId: {
    type: String,
    default: null
  }
})

defineEmits(['back'])

const chunks = ref([])
const total = ref(0)
const loading = ref(false)
const pageNo = ref(1)
const pageSize = ref(10)

const fetchChunks = async () => {
  if (!props.docId) return
  loading.value = true
  try {
    const res = await knowledgeService.getChunks(props.docId, {
      current: pageNo.value,
      size: pageSize.value
    })
    chunks.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    console.error('Fetch chunks failed', e)
  } finally {
    loading.value = false
  }
}

const toggleEnabled = async (item) => {
  try {
    await knowledgeService.setChunkEnabled(props.docId, item.chunkId, !item.enabled)
    item.enabled = !item.enabled
  } catch (e) {
    alert(`切换状态失败: ${e.message}`)
  }
}

watch(() => props.docId, (newVal) => {
  if (newVal) {
    pageNo.value = 1
    fetchChunks()
  } else {
    chunks.value = []
    total.value = 0
  }
})

onMounted(() => {
  if (props.docId) {
    fetchChunks()
  }
})
</script>