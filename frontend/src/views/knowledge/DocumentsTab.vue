<template>
  <div class="bg-white rounded-xl shadow-sm border border-slate-100 overflow-hidden">
    <div class="p-4 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
      <div class="relative">
        <span class="material-symbols-outlined absolute left-3 top-1/2 -translate-y-1/2 text-slate-400 text-[18px]">search</span>
        <input v-model="searchQuery" @keyup.enter="fetchDocuments" type="text" placeholder="搜索文档名称或状态..." class="pl-9 pr-4 py-2 text-sm border border-slate-200 bg-white rounded-lg shadow-sm focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 w-64 outline-none transition-all" />
      </div>
      <div class="flex items-center gap-4">
        <button @click="fetchDocuments" class="text-sm font-medium text-indigo-600 hover:text-indigo-800">刷新</button>
        <div class="text-sm font-medium text-slate-500">共 <span class="text-indigo-600">{{ total }}</span> 份文档记录</div>
      </div>
    </div>
    <div class="overflow-x-auto min-h-[300px] relative">
      <div v-if="loading" class="absolute inset-0 bg-white/50 backdrop-blur-sm flex items-center justify-center z-10">
        <span class="material-symbols-outlined animate-spin text-indigo-600 text-3xl">progress_activity</span>
      </div>
      <table class="w-full text-left text-sm">
        <thead class="bg-slate-50/80 text-slate-500 font-bold border-b border-slate-100">
          <tr>
            <th class="px-6 py-4 whitespace-nowrap">文档名称</th>
            <th class="px-6 py-4 whitespace-nowrap">来源类型</th>
            <th class="px-6 py-4 whitespace-nowrap">分块数</th>
            <th class="px-6 py-4 whitespace-nowrap">状态</th>
            <th class="px-6 py-4 text-right whitespace-nowrap">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100">
          <tr v-for="(item, idx) in documents" :key="item.docId" class="hover:bg-slate-50/50 transition-colors">
            <td class="px-6 py-4 font-medium text-slate-900 max-w-[200px] truncate" :title="item.docName">{{ item.docName }}</td>
            <td class="px-6 py-4 text-slate-500">{{ item.sourceType }}</td>
            <td class="px-6 py-4 text-slate-500">{{ item.chunkCount }}</td>
            <td class="px-6 py-4">
              <button @click="toggleEnabled(item)" class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-bold transition-colors" :class="item.enabled ? 'bg-emerald-50 text-emerald-700 border border-emerald-100 hover:bg-emerald-100' : 'bg-slate-100 text-slate-500 border border-slate-200 hover:bg-slate-200'">
                <span class="w-1.5 h-1.5 rounded-full" :class="item.enabled ? 'bg-emerald-500' : 'bg-slate-400'"></span>
                {{ item.enabled ? '已启用' : '已禁用' }}
              </button>
            </td>
            <td class="px-6 py-4 text-right space-x-3">
              <button @click="rechunk(item)" class="text-indigo-600 hover:text-indigo-800 text-xs font-bold">重建</button>
              <button @click="$emit('view-chunks', item.docId)" class="text-indigo-600 hover:text-indigo-800 text-xs font-bold">详情</button>
              <button @click="remove(item)" class="text-red-600 hover:text-red-800 text-xs font-bold">删除</button>
            </td>
          </tr>
          <tr v-if="!documents.length && !loading">
            <td colspan="5" class="px-6 py-16 text-center">
               <div class="flex flex-col items-center justify-center text-slate-400">
                 <span class="material-symbols-outlined text-4xl mb-2">description</span>
                 <p>暂无文档记录</p>
               </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    
    <!-- 分页 -->
    <div class="p-4 border-t border-slate-100 flex justify-between items-center bg-slate-50/50 text-sm">
      <button @click="pageNo > 1 && (pageNo--, fetchDocuments())" :disabled="pageNo <= 1" class="px-3 py-1 bg-white border border-slate-200 rounded text-slate-600 disabled:opacity-50 hover:bg-slate-50 transition-colors">上一页</button>
      <div class="flex items-center gap-2 text-slate-500">
        <span>第</span>
        <select v-model="pageNo" @change="fetchDocuments" class="bg-white border border-slate-200 rounded pl-3 pr-8 py-1 text-slate-700 outline-none focus:ring-1 focus:ring-indigo-500 appearance-none bg-[url('data:image/svg+xml;charset=utf-8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%20fill%3D%22none%22%20viewBox%3D%220%200%2020%2020%22%3E%3Cpath%20stroke%3D%22%236b7280%22%20stroke-linecap%3D%22round%22%20stroke-linejoin%3D%22round%22%20stroke-width%3D%221.5%22%20d%3D%22M6%208l4%204%204-4%22%2F%3E%3C%2Fsvg%3E')] bg-[length:1.25rem_1.25rem] bg-[right_0.25rem_center] bg-no-repeat">
          <option v-for="p in Math.max(1, Math.ceil(total / pageSize))" :key="p" :value="p">{{ p }}</option>
        </select>
        <span>页，共 {{ Math.max(1, Math.ceil(total / pageSize)) }} 页</span>
      </div>
      <button @click="documents.length === pageSize && (pageNo++, fetchDocuments())" :disabled="documents.length < pageSize" class="px-3 py-1 bg-white border border-slate-200 rounded text-slate-600 disabled:opacity-50 hover:bg-slate-50 transition-colors">下一页</button>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { knowledgeService } from '../../services/knowledgeService'

defineEmits(['view-chunks'])

const documents = ref([])
const total = ref(0)
const loading = ref(false)
const searchQuery = ref('')
const pageNo = ref(1)
const pageSize = ref(10)

const fetchDocuments = async () => {
  loading.value = true
  try {
    const kbs = await knowledgeService.getKnowledgeBases()
    const kbId = kbs && kbs.length > 0 ? kbs[0].id : 1
    
    const res = await knowledgeService.getDocuments(kbId, {
      pageNo: pageNo.value,
      pageSize: pageSize.value,
      keyword: searchQuery.value
    })
    documents.value = res.records || []
    total.value = res.total || 0
  } catch (e) {
    console.error('Fetch documents failed', e)
  } finally {
    loading.value = false
  }
}

const toggleEnabled = async (item) => {
  try {
    await knowledgeService.setDocumentEnabled(item.docId, !item.enabled)
    item.enabled = !item.enabled
  } catch (e) {
    alert(`切换状态失败: ${e.message}`)
  }
}

const rechunk = async (item) => {
  if (!confirm(`确定要重新切分文档 ${item.docName} 吗？`)) return
  try {
    await knowledgeService.rechunkDocument(item.docId)
    alert('重建任务已提交')
    fetchDocuments()
  } catch (e) {
    alert(`重建失败: ${e.message}`)
  }
}

const remove = async (item) => {
  if (!confirm(`确定要删除文档 ${item.docName} 吗？将同时删除其所有分块和向量索引。`)) return
  try {
    await knowledgeService.deleteDocument(item.docId)
    fetchDocuments()
  } catch (e) {
    alert(`删除失败: ${e.message}`)
  }
}

onMounted(() => {
  fetchDocuments()
})
</script>