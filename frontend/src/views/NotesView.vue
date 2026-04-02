<template>
  <div class="bg-surface text-on-surface min-h-screen">
    <!-- Top Navigation Bar -->
    <header class="fixed top-0 right-0 left-64 h-16 bg-white/80 backdrop-blur-xl border-b border-slate-100 flex justify-between items-center px-8 z-40">
      <div class="flex items-center gap-4">
        <span class="text-xl font-bold font-headline text-indigo-700 dark:text-indigo-400">数字化叙事</span>
        <div class="h-4 w-[1px] bg-slate-300 mx-2"></div>
        <span class="text-sm font-medium text-on-surface-variant">知识库管理</span>
      </div>
    </header>

    <!-- Main Content Canvas -->
    <main class="ml-64 pt-24 px-10 pb-12 min-h-screen">
      <!-- Header Section -->
      <div class="flex justify-between items-end mb-8">
        <section>
          <h1 class="text-3xl font-extrabold tracking-tight text-slate-900 mb-2">知识库管理</h1>
          <p class="text-slate-500 max-w-2xl leading-relaxed">管理您的本地非结构化数据源、文档与分块。</p>
        </section>
        <div class="flex gap-3">
          <button @click="showSyncModal = true" class="bg-white border border-slate-200 text-slate-700 py-2.5 px-5 rounded-lg font-bold flex items-center gap-2 hover:bg-slate-50 transition-all active:scale-95 shadow-sm">
            <span class="material-symbols-outlined text-[18px]">sync</span>
            配置与同步
          </button>
          <button @click="triggerUpload" :disabled="loading" class="bg-indigo-600 py-2.5 px-5 rounded-lg text-white font-bold flex items-center gap-2 hover:bg-indigo-700 transition-all active:scale-95 shadow-sm disabled:opacity-60">
            <span v-if="loading" class="material-symbols-outlined animate-spin text-[18px]">progress_activity</span>
            <span v-else class="material-symbols-outlined text-[18px]">upload_file</span>
            上传文档
          </button>
          <input type="file" ref="fileInput" class="hidden" accept=".pdf" @change="handleUpload" />
        </div>
      </div>

      <!-- Global Message Toast -->
      <div v-if="message" class="mb-6 p-4 rounded-lg flex items-start gap-3 border shadow-sm transition-all" :class="{
        'bg-emerald-50 border-emerald-200 text-emerald-800': messageType === 'success',
        'bg-red-50 border-red-200 text-red-800': messageType === 'error',
        'bg-amber-50 border-amber-200 text-amber-800': messageType === 'warning',
        'bg-blue-50 border-blue-200 text-blue-800': messageType === 'info'
      }">
         <span class="material-symbols-outlined">{{ messageType === 'success' ? 'check_circle' : messageType === 'error' ? 'error' : messageType === 'warning' ? 'warning' : 'info' }}</span>
         <span class="text-sm font-medium">{{ message }}</span>
      </div>

      <!-- Stats Overview -->
      <div class="grid grid-cols-1 md:grid-cols-4 gap-4 mb-8">
        <div class="bg-white p-5 rounded-xl border border-slate-100 shadow-sm flex flex-col">
          <span class="text-slate-500 text-xs font-bold uppercase tracking-wider mb-1">总扫描文件</span>
          <span class="text-2xl font-bold text-slate-900">{{ stats.totalScanned || 0 }}</span>
        </div>
        <div class="bg-white p-5 rounded-xl border border-slate-100 shadow-sm flex flex-col">
          <span class="text-slate-500 text-xs font-bold uppercase tracking-wider mb-1">已索引文档</span>
          <span class="text-2xl font-bold text-indigo-600">{{ stats.totalIndexed || 0 }}</span>
        </div>
        <div class="bg-white p-5 rounded-xl border border-slate-100 shadow-sm flex flex-col">
          <span class="text-slate-500 text-xs font-bold uppercase tracking-wider mb-1">索引成功率</span>
          <span class="text-2xl font-bold text-emerald-600">{{ stats.successRate || '0%' }}</span>
        </div>
        <div class="bg-white p-5 rounded-xl border border-slate-100 shadow-sm flex flex-col">
          <span class="text-slate-500 text-xs font-bold uppercase tracking-wider mb-1">同步失败数</span>
          <span class="text-2xl font-bold text-red-500">{{ stats.failedFiles || 0 }}</span>
        </div>
      </div>

      <!-- Tabs -->
      <div class="border-b border-slate-200 mb-6">
        <nav class="-mb-px flex space-x-8">
          <button v-for="tab in tabs" :key="tab.id" @click="activeTab = tab.id"
            :class="[activeTab === tab.id ? 'border-indigo-500 text-indigo-600' : 'border-transparent text-slate-500 hover:text-slate-700 hover:border-slate-300', 'whitespace-nowrap py-3 px-1 border-b-2 font-bold text-sm transition-colors flex items-center gap-2']">
            <span class="material-symbols-outlined text-[18px]">{{ tab.icon }}</span>
            {{ tab.name }}
          </button>
        </nav>
      </div>

      <!-- Tab Contents -->
      <div class="min-h-[400px]">
        <KnowledgeBaseTab v-if="activeTab === 'kb'" :config="config" />
        <DocumentsTab v-else-if="activeTab === 'docs'" :reports="stats.recentReports" />
        <ChunksTab v-else-if="activeTab === 'chunks'" />
        <SyncTasksTab v-else-if="activeTab === 'tasks'" :reports="stats.recentReports" :lastSyncTime="lastSyncTime" />
      </div>
    </main>

    <!-- Sync Modal -->
    <SyncConfigModal v-if="showSyncModal" @close="showSyncModal = false" @sync="handleSync" :initialConfig="config" :loading="syncLoading" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { knowledgeService } from '../services/knowledgeService'
import KnowledgeBaseTab from './knowledge/KnowledgeBaseTab.vue'
import DocumentsTab from './knowledge/DocumentsTab.vue'
import ChunksTab from './knowledge/ChunksTab.vue'
import SyncTasksTab from './knowledge/SyncTasksTab.vue'
import SyncConfigModal from './knowledge/SyncConfigModal.vue'

const activeTab = ref('kb')
const tabs = [
  { id: 'kb', name: '知识库', icon: 'database' },
  { id: 'docs', name: '文档', icon: 'description' },
  { id: 'chunks', name: '分块', icon: 'view_timeline' },
  { id: 'tasks', name: '同步任务', icon: 'task' }
]

const config = ref({ paths: '', ignoreDirs: '' })
const stats = ref({ recentReports: [] })
const loading = ref(false)
const syncLoading = ref(false)
const showSyncModal = ref(false)
const lastSyncTime = ref('暂无')

const message = ref('')
const messageType = ref('info')
let messageTimer = null

const fileInput = ref(null)

const triggerUpload = () => {
  fileInput.value?.click()
}

const showMessage = (msg, type = 'info') => {
  message.value = msg
  messageType.value = type
  if (messageTimer) clearTimeout(messageTimer)
  messageTimer = setTimeout(() => { message.value = '' }, 5000)
}

const loadData = async () => {
  try {
    config.value = await knowledgeService.getConfig()
    stats.value = await knowledgeService.getStats()
  } catch (err) {
    showMessage(`加载失败: ${err.message || 'unknown'}`, 'error')
  }
}

const handleSync = async ({ pathsText, ignoreDirs }) => {
  const paths = pathsText.split('\n').map((item) => item.trim()).filter(Boolean)
  if (!paths.length) {
    showMessage('请先输入同步路径', 'error')
    return
  }
  syncLoading.value = true
  try {
    await knowledgeService.saveConfig({ paths: pathsText, ignoreDirs })
    config.value = { paths: pathsText, ignoreDirs }

    let successCount = 0
    let failCount = 0
    const failReasons = []
    for (const path of paths) {
      try {
        await knowledgeService.sync({ path, ignoreDirs })
        successCount += 1
      } catch (error) {
        failCount += 1
        failReasons.push(`${path}: ${error.message || 'unknown'}`)
      }
    }
    
    const resultMsg = failCount > 0 
      ? `同步完成：成功 ${successCount}，失败 ${failCount}。${failReasons.join('；')}`
      : `同步完成：成功 ${successCount}，失败 ${failCount}`
      
    showMessage(resultMsg, failCount > 0 ? 'warning' : 'success')
    lastSyncTime.value = new Date().toLocaleString('zh-CN')
    await loadData()
    showSyncModal.value = false
    activeTab.value = 'tasks'
  } catch (error) {
    showMessage(`同步失败: ${error.message || 'unknown'}`, 'error')
  } finally {
    syncLoading.value = false
  }
}

const handleUpload = async (event) => {
  const file = event.target.files?.[0]
  if (!file) return

  if (!file.name.toLowerCase().endsWith('.pdf')) {
    showMessage('上传失败: 仅支持 PDF 简历', 'error')
    event.target.value = ''
    return
  }
  if (file.size > 20 * 1024 * 1024) {
    showMessage('上传失败: 简历文件过大，请上传 20MB 以内 PDF', 'error')
    event.target.value = ''
    return
  }

  loading.value = true
  try {
    const data = await knowledgeService.uploadPdf(file)
    showMessage(`上传成功：${data.fileName || file.name}`, 'success')
    lastSyncTime.value = new Date().toLocaleString('zh-CN')
    await loadData()
    activeTab.value = 'docs'
  } catch (error) {
    showMessage(`上传失败: ${error.message || 'unknown'}`, 'error')
  } finally {
    loading.value = false
    event.target.value = ''
  }
}

onMounted(() => {
  loadData()
})
</script>
