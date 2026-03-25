<template>
  <div class="bg-surface text-on-surface min-h-screen">
    <!-- Top Navigation Bar -->
    <header class="fixed top-0 right-0 left-64 h-16 bg-white/80 backdrop-blur-xl border-b border-slate-100 flex justify-between items-center px-8 z-40">
      <div class="flex items-center gap-4">
        <span class="text-xl font-bold font-headline text-indigo-700 dark:text-indigo-400">数字化叙事</span>
        <div class="h-4 w-[1px] bg-slate-300 mx-2"></div>
        <span class="text-sm font-medium text-on-surface-variant">知识库管理 / 核心数据同步</span>
      </div>
    </header>

    <!-- Main Content Canvas -->
    <main class="ml-64 pt-24 px-10 pb-12 min-h-screen">
      <!-- Header Section -->
      <section class="mb-10">
        <h1 class="text-3xl font-extrabold tracking-tight text-slate-900 mb-2">知识库同步与简历处理</h1>
        <p class="text-slate-500 max-w-2xl leading-relaxed">管理您的本地非结构化数据源。通过目录同步与文件上传，将简历与行业文档转化为系统可识别的语义向量库。</p>
      </section>

      <div class="grid grid-cols-1 lg:grid-cols-12 gap-8 items-start">
        <!-- Left Column: Input Forms -->
        <div class="lg:col-span-7 space-y-6">
          <!-- Local Directory Sync Card -->
          <div class="bg-white rounded-xl p-8 shadow-sm transition-all border border-slate-100">
            <div class="flex items-center gap-3 mb-6">
              <div class="w-10 h-10 rounded-full bg-indigo-50 flex items-center justify-center text-indigo-600">
                <span class="material-symbols-outlined" data-icon="folder_managed">folder_managed</span>
              </div>
              <h2 class="text-lg font-bold text-slate-900">本地目录同步</h2>
            </div>
            <div class="space-y-5">
              <div class="space-y-2">
                <label class="text-xs font-bold text-slate-500 uppercase tracking-wider">本地绝对路径 (支持多行)</label>
                <div class="relative group">
                  <textarea class="w-full bg-slate-50 border-none rounded-lg px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500/20 transition-all outline-none resize-y" placeholder="/Users/admin/documents/resumes&#10;D:\knowledge\notes" rows="3" v-model="pathsText" :disabled="loading"></textarea>
                </div>
              </div>
              <div class="space-y-2">
                <label class="text-xs font-bold text-slate-500 uppercase tracking-wider">忽略目录 (正则或名称)</label>
                <input class="w-full bg-slate-50 border-none rounded-lg px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500/20 transition-all outline-none" placeholder="node_modules, .git, temp_*" type="text" v-model="ignoreDirs" :disabled="loading" />
              </div>
              <div class="pt-4">
                <button class="w-full bg-indigo-600 py-3 px-6 rounded-lg text-white font-bold flex items-center justify-center gap-2 hover:bg-indigo-700 transition-all active:scale-95 disabled:opacity-60" @click="syncAll" :disabled="loading">
                  <span class="material-symbols-outlined" data-icon="sync">sync</span>
                  {{ loading ? '正在同步...' : '开始全量同步' }}
                </button>
                <div class="text-center text-sm mt-2" :class="message.includes('失败') ? 'text-red-500' : 'text-slate-500'">{{ message }}</div>
              </div>
            </div>
          </div>

          <!-- Resume Upload Card -->
          <div class="bg-white rounded-xl p-8 shadow-sm transition-all border-2 border-dashed border-slate-200 hover:border-indigo-300 transition-colors relative">
            <div class="flex items-center justify-between mb-6">
              <div class="flex items-center gap-3">
                <div class="w-10 h-10 rounded-full bg-indigo-50 flex items-center justify-center text-indigo-600">
                  <span class="material-symbols-outlined" data-icon="upload_file">upload_file</span>
                </div>
                <h2 class="text-lg font-bold text-slate-900">简历快速上传</h2>
              </div>
              <span class="text-[10px] font-bold bg-indigo-50 text-indigo-700 px-2 py-1 rounded-full uppercase tracking-tighter">仅支持 .PDF</span>
            </div>
            <div class="flex flex-col items-center justify-center py-10 px-4 bg-slate-50 rounded-xl cursor-pointer hover:bg-slate-100 transition-all group relative overflow-hidden">
              <input type="file" class="absolute inset-0 w-full h-full opacity-0 cursor-pointer" accept=".pdf" @change="onFileChange" :disabled="loading" />
              <div class="w-16 h-16 rounded-full bg-white shadow-sm flex items-center justify-center mb-4 group-hover:scale-110 transition-transform">
                <span class="material-symbols-outlined text-3xl text-indigo-600" data-icon="add_circle">add_circle</span>
              </div>
              <p class="text-sm font-semibold text-slate-900">点击选择文件 或 拖拽至此处</p>
              <p class="text-xs text-slate-500 mt-1">最大支持 20MB 单个文件</p>
            </div>
          </div>
        </div>

        <!-- Right Column: Statistics & Results -->
        <div class="lg:col-span-5 space-y-6">
          <div class="bg-indigo-900 rounded-xl p-8 text-white relative overflow-hidden">
            <!-- Background Accent -->
            <div class="absolute -right-10 -bottom-10 w-40 h-40 bg-white/10 rounded-full blur-3xl"></div>
            <div class="absolute -left-10 -top-10 w-32 h-32 bg-indigo-500/20 rounded-full blur-2xl"></div>
            <div class="relative z-10">
              <h3 class="text-indigo-200 text-xs font-bold uppercase tracking-widest mb-6">实时同步统计</h3>
              <div class="grid grid-cols-2 gap-y-8">
                <div class="space-y-1">
                  <span class="text-indigo-300 text-[10px] font-medium block">总扫描文件数</span>
                  <span class="text-4xl font-bold font-headline">{{ stats.totalScanned || 0 }}</span>
                </div>
                <div class="space-y-1">
                  <span class="text-indigo-300 text-[10px] font-medium block">本次新增</span>
                  <span class="text-4xl font-bold font-headline text-indigo-300">+{{ stats.totalIndexed || 0 }}</span>
                </div>
                <div class="space-y-1">
                  <span class="text-indigo-300 text-[10px] font-medium block">索引成功率</span>
                  <span class="text-4xl font-bold font-headline">{{ stats.successRate || '0%' }}</span>
                </div>
                <div class="space-y-1">
                  <span class="text-indigo-300 text-[10px] font-medium block">同步失败</span>
                  <span class="text-4xl font-bold font-headline text-red-400">{{ stats.failedFiles || 0 }}</span>
                </div>
              </div>
              <div class="mt-8 pt-8 border-t border-white/10 flex items-center justify-between text-xs">
                <span class="text-indigo-200">最后同步时间: {{ lastSyncTime }}</span>
                <span class="flex items-center gap-1 text-emerald-400">
                  <span class="w-2 h-2 bg-emerald-400 rounded-full animate-pulse"></span>
                  运行正常
                </span>
              </div>
            </div>
          </div>

          <!-- Detailed JSON Return Cards -->
          <div class="space-y-4">
            <h3 class="text-xs font-bold text-slate-500 uppercase tracking-widest ml-1">详细解析报告</h3>
            <div v-if="!recentReports.length" class="text-sm text-slate-500 ml-1">暂无报告</div>
            <div v-for="item in recentReports" :key="`${item.fileName}-${item.status}-${item.message}`" class="bg-white border border-slate-100 p-4 rounded-xl shadow-sm flex items-start gap-4 hover:shadow-md transition-shadow">
              <div class="mt-1 w-2 h-2 rounded-full flex-shrink-0" :class="item.status === 'success' ? 'bg-emerald-500' : 'bg-red-500'"></div>
              <div>
                <h4 class="text-sm font-bold text-slate-900 mb-1">{{ item.fileName }}</h4>
                <p class="text-xs text-slate-500 leading-relaxed">{{ item.message }}</p>
                <div class="mt-2 flex items-center gap-2">
                  <span class="text-[9px] font-mono font-bold uppercase tracking-widest" :class="item.status === 'success' ? 'text-emerald-600' : 'text-red-600'">[{{ item.status }}]</span>
                </div>
              </div>
            </div>
          </div>

          <!-- Storage Info Card -->
          <div class="bg-white p-6 rounded-xl shadow-sm border border-slate-100">
            <div class="flex items-center justify-between mb-4">
              <span class="text-sm font-bold text-slate-900">知识库存储空间</span>
              <span class="text-sm font-bold text-indigo-600">{{ storagePercent }}%</span>
            </div>
            <div class="w-full bg-slate-100 h-2 rounded-full overflow-hidden">
              <div class="bg-indigo-600 h-full rounded-full transition-all duration-500" :style="{ width: `${storagePercent}%` }"></div>
            </div>
            <p class="mt-3 text-[10px] text-slate-500 leading-relaxed">系统正在监控您的索引文件容量，建议在超过 90% 后清理忽略目录或扩容数据库。</p>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { loadIngestConfig, loadIngestStats, runIngest, saveIngestConfig, uploadResumePdf } from '../api/notes'

const loading = ref(false)
const pathsText = ref('')
const ignoreDirs = ref('')
const message = ref('')
const stats = ref({})
const lastSyncTime = ref('暂无')

const recentReports = computed(() => stats.value.recentReports || [])

// Simulate storage percentage based on total indexed
const storagePercent = computed(() => {
  const total = stats.value.totalIndexed || 0
  const max = 10000 // Assumed max capacity
  const percent = Math.min(100, Math.round((total / max) * 100))
  return percent
})

const refreshStats = async () => {
  try {
    stats.value = await loadIngestStats()
  } catch (error) {
    message.value = `拉取统计失败: ${error.message || 'unknown'}`
  }
}

const syncAll = async () => {
  const paths = pathsText.value.split('\n').map((item) => item.trim()).filter(Boolean)
  if (!paths.length) {
    message.value = '请先输入同步路径'
    return
  }
  loading.value = true
  message.value = ''
  try {
    await saveIngestConfig({ paths: pathsText.value, ignoreDirs: ignoreDirs.value })
    let successCount = 0
    let failCount = 0
    const failReasons = []
    for (const path of paths) {
      try {
        await runIngest({ path, ignoreDirs: ignoreDirs.value })
        successCount += 1
      } catch (error) {
        failCount += 1
        failReasons.push(`${path}: ${error.message || 'unknown'}`)
      }
    }
    message.value = failCount > 0
      ? `同步完成：成功 ${successCount}，失败 ${failCount}。${failReasons.join('；')}`
      : `同步完成：成功 ${successCount}，失败 ${failCount}`
    lastSyncTime.value = new Date().toLocaleString('zh-CN')
    await refreshStats()
  } finally {
    loading.value = false
  }
}

const onFileChange = async (event) => {
  const file = event.target.files?.[0]
  if (!file) {
    return
  }
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    message.value = '上传失败: 仅支持 PDF 简历'
    event.target.value = ''
    return
  }
  if (file.size > 20 * 1024 * 1024) {
    message.value = '上传失败: 简历文件过大，请上传 20MB 以内 PDF'
    event.target.value = ''
    return
  }
  loading.value = true
  message.value = ''
  try {
    const data = await uploadResumePdf(file)
    message.value = `上传成功：${data.fileName || file.name}`
    lastSyncTime.value = new Date().toLocaleString('zh-CN')
    await refreshStats()
  } catch (error) {
    message.value = `上传失败: ${error.message || 'unknown'}`
  } finally {
    loading.value = false
    event.target.value = ''
  }
}

onMounted(async () => {
  try {
    const config = await loadIngestConfig()
    pathsText.value = config.paths || ''
    ignoreDirs.value = config.ignoreDirs || ''
  } catch (error) {
    message.value = `加载配置失败: ${error.message || 'unknown'}`
  }
  await refreshStats()
})
</script>
