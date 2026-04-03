<template>
  <div class="bg-surface text-on-surface antialiased min-h-screen relative">
    <header class="fixed top-0 right-0 h-16 bg-white/80 backdrop-blur-xl border-b border-slate-100 flex justify-between items-center px-8 z-40 transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
      <div class="flex items-center gap-4">
        <h1 class="text-xl font-bold tracking-tight text-indigo-700 dark:text-indigo-400">提示词管理 <span class="text-slate-500 font-medium text-sm ml-2">/ 集中管理各业务场景下的系统提示词</span></h1>
      </div>
      <div class="flex items-center gap-6">
        <button class="bg-white border border-slate-200 text-slate-700 px-4 py-2 rounded-lg text-sm font-semibold flex items-center gap-2 hover:bg-slate-50 transition-all active:scale-[0.98] disabled:opacity-60" @click="handleReloadCache" :disabled="loading">
          <span class="material-symbols-outlined text-sm">refresh</span>
          刷新缓存
        </button>
        <button class="bg-indigo-600 text-white px-5 py-2 rounded-lg text-sm font-semibold flex items-center gap-2 hover:bg-indigo-700 transition-all active:scale-[0.98] disabled:opacity-60" @click="openCreateModal" :disabled="loading">
          <span class="material-symbols-outlined text-sm">add</span>
          新建模板
        </button>
      </div>
    </header>

    <main class="pt-24 p-8 min-h-screen bg-slate-50 transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
      <div class="max-w-7xl mx-auto">
        <header class="mb-8">
          <h3 class="text-3xl font-extrabold text-slate-900 tracking-tight">提示词管理</h3>
          <p class="text-slate-500 mt-2 max-w-2xl">管理所有 AI 提示词模板，支持分类浏览、在线编辑和渲染预览。</p>
        </header>

        <!-- Filter Bar -->
        <div class="flex flex-col md:flex-row gap-4 mb-8 bg-white p-4 rounded-xl shadow-sm border border-slate-200">
          <div class="flex-1">
            <input v-model="searchQuery" class="w-full bg-slate-50 border-none rounded-lg p-2.5 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" placeholder="搜索名称或标题..." type="text" />
          </div>
          <div class="w-full md:w-48">
            <select v-model="filterCategory" class="w-full bg-slate-50 border-none rounded-lg p-2.5 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none">
              <option value="">全部分类</option>
              <option value="system">system</option>
              <option value="interview">interview</option>
              <option value="coding">coding</option>
              <option value="chat">chat</option>
              <option value="intent">intent</option>
              <option value="general">general</option>
            </select>
          </div>
          <div class="w-full md:w-48">
            <select v-model="filterType" class="w-full bg-slate-50 border-none rounded-lg p-2.5 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none">
              <option value="">全部类型</option>
              <option value="SYSTEM">SYSTEM</option>
              <option value="TASK">TASK</option>
            </select>
          </div>
        </div>

        <!-- List -->
        <div v-if="loading && prompts.length === 0" class="text-center py-12 text-slate-500">
          <span class="material-symbols-outlined animate-spin text-4xl">autorenew</span>
          <p class="mt-4">加载中...</p>
        </div>
        
        <div v-else class="grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-6">
          <div v-for="item in filteredPrompts" :key="item.name" class="bg-white flex flex-col rounded-xl shadow-sm overflow-hidden transition-all duration-300 border border-slate-200 hover:border-indigo-300">
            <div class="p-5 flex-1">
              <div class="flex justify-between items-start mb-3">
                <span :class="getCategoryColor(item.category)" class="px-2.5 py-1 text-xs font-bold rounded-md">{{ item.category }}</span>
                <span :class="item.type === 'SYSTEM' ? 'text-red-500 bg-red-50 border-red-200' : 'text-slate-500 bg-slate-100 border-slate-200'" class="px-2 py-0.5 text-[10px] font-bold rounded border">{{ item.type }}</span>
              </div>
              <h4 class="text-lg font-bold text-slate-900 mb-1 truncate" :title="item.title">{{ item.title || item.name }}</h4>
              <p class="text-xs text-slate-500 font-mono mb-4 truncate">{{ item.name }}</p>
              <p class="text-sm text-slate-600 line-clamp-3 leading-relaxed">{{ item.content }}</p>
            </div>
            <div class="px-5 py-3 border-t border-slate-100 bg-slate-50 flex items-center justify-between">
              <span class="text-xs text-slate-400">{{ formatRelativeTime(item.updatedAt) }}</span>
              <div class="flex gap-2">
                <button class="text-indigo-600 hover:bg-indigo-50 p-1.5 rounded transition-colors" @click="openEditModal(item)" title="编辑">
                  <span class="material-symbols-outlined text-[18px]">edit</span>
                </button>
                <button class="text-teal-600 hover:bg-teal-50 p-1.5 rounded transition-colors" @click="openEditModal(item, true)" title="预览">
                  <span class="material-symbols-outlined text-[18px]">visibility</span>
                </button>
                <button v-if="!item.isBuiltin" class="text-red-500 hover:bg-red-50 p-1.5 rounded transition-colors" @click="handleDelete(item)" title="删除">
                  <span class="material-symbols-outlined text-[18px]">delete</span>
                </button>
                <button v-else class="text-slate-300 cursor-not-allowed p-1.5 rounded" title="内置模板不可删除">
                  <span class="material-symbols-outlined text-[18px]">lock</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- Modal Form -->
    <div v-if="showModal" class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 backdrop-blur-sm p-6">
      <div class="bg-white w-full max-w-7xl h-[90vh] rounded-2xl shadow-2xl flex flex-col overflow-hidden">
        <div class="px-6 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50">
          <h3 class="text-lg font-bold text-slate-900">{{ isNew ? '新建模板' : '编辑模板' }} <span v-if="!isNew" class="text-slate-500 text-sm font-normal ml-2">({{ form.name }})</span></h3>
          <button @click="showModal = false" class="text-slate-400 hover:text-slate-700 transition-colors">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>
        
        <div class="flex-1 overflow-hidden flex flex-col md:flex-row">
          <!-- Left: Form -->
          <div class="w-full md:w-[60%] flex flex-col border-r border-slate-100 p-6 overflow-y-auto bg-white">
            <div class="grid grid-cols-2 gap-4 mb-4">
              <div class="col-span-2 md:col-span-1">
                <label class="block text-xs font-bold text-slate-500 mb-1">模板名称 (唯一标识)</label>
                <input v-model="form.name" :disabled="!isNew" :class="!isNew ? 'bg-slate-100 text-slate-500' : 'bg-slate-50'" class="w-full border-none rounded-lg p-2.5 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" type="text" />
              </div>
              <div class="col-span-2 md:col-span-1">
                <label class="block text-xs font-bold text-slate-500 mb-1">标题</label>
                <input v-model="form.title" class="w-full bg-slate-50 border-none rounded-lg p-2.5 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" type="text" />
              </div>
              <div class="col-span-2 md:col-span-1">
                <label class="block text-xs font-bold text-slate-500 mb-1">分类 (Category)</label>
                <select v-model="form.category" class="w-full bg-slate-50 border-none rounded-lg p-2.5 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none">
                  <option value="system">system</option>
                  <option value="interview">interview</option>
                  <option value="coding">coding</option>
                  <option value="chat">chat</option>
                  <option value="intent">intent</option>
                  <option value="general">general</option>
                </select>
              </div>
              <div class="col-span-2 md:col-span-1">
                <label class="block text-xs font-bold text-slate-500 mb-1">类型 (Type)</label>
                <select v-model="form.type" :disabled="!isNew" :class="!isNew ? 'bg-slate-100 text-slate-500' : 'bg-slate-50'" class="w-full border-none rounded-lg p-2.5 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none">
                  <option value="SYSTEM">SYSTEM</option>
                  <option value="TASK">TASK</option>
                </select>
              </div>
              <div class="col-span-2">
                <label class="block text-xs font-bold text-slate-500 mb-1">描述</label>
                <input v-model="form.description" class="w-full bg-slate-50 border-none rounded-lg p-2.5 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" type="text" />
              </div>
            </div>
            <div class="flex-1 flex flex-col min-h-[300px]">
              <label class="block text-xs font-bold text-slate-500 mb-1">模板内容</label>
              <textarea v-model="form.content" class="flex-1 w-full bg-slate-900 text-slate-50 p-4 rounded-lg font-mono text-sm leading-relaxed focus:ring-2 focus:ring-indigo-500 transition-all outline-none resize-none"></textarea>
            </div>
          </div>
          
          <!-- Right: Preview -->
          <div class="w-full md:w-[40%] flex flex-col p-6 bg-slate-50/50 overflow-y-auto">
            <div class="mb-4">
              <div class="flex justify-between items-center mb-1">
                <label class="block text-xs font-bold text-slate-500">预览变量 (JSON)</label>
                <button @click="handlePreview" class="text-xs bg-white border border-slate-200 text-slate-700 px-3 py-1 rounded hover:bg-slate-50 transition-all flex items-center gap-1 font-medium">
                  <span class="material-symbols-outlined text-[14px]">play_arrow</span> 渲染
                </button>
              </div>
              <textarea v-model="previewVars" class="w-full h-32 bg-white border border-slate-200 rounded-lg p-3 font-mono text-xs focus:ring-2 focus:ring-indigo-500 transition-all outline-none resize-none" placeholder='{"question": "测试"}'></textarea>
            </div>
            <div class="flex-1 flex flex-col min-h-[200px]">
              <label class="block text-xs font-bold text-slate-500 mb-1">渲染结果</label>
              <div class="flex-1 bg-white border border-slate-200 rounded-lg p-4 overflow-auto">
                <div v-if="previewLoading" class="flex justify-center items-center h-full text-slate-400">
                  <span class="material-symbols-outlined animate-spin text-2xl">autorenew</span>
                </div>
                <pre v-else-if="previewResult" class="font-mono text-sm text-slate-800 whitespace-pre-wrap word-break">{{ previewResult }}</pre>
                <div v-else class="flex justify-center items-center h-full text-slate-400 text-sm">
                  点击渲染预览结果
                </div>
              </div>
            </div>
          </div>
        </div>
        
        <div class="px-6 py-4 border-t border-slate-100 bg-slate-50 flex justify-end gap-3">
          <button @click="showModal = false" class="px-5 py-2 rounded-lg text-sm font-semibold text-slate-600 hover:bg-slate-200 transition-all">取消</button>
          <button @click="handleSave" :disabled="saving" class="bg-indigo-600 text-white px-6 py-2 rounded-lg text-sm font-semibold flex items-center gap-2 hover:bg-indigo-700 transition-all active:scale-[0.98] disabled:opacity-60">
            <span v-if="saving" class="material-symbols-outlined animate-spin text-[16px]">autorenew</span>
            保存模板
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { loadPromptTemplates, createPromptTemplate, updatePromptTemplate, deletePromptTemplate, previewPromptTemplate, reloadPromptCache } from '../api/admin'

defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})

const prompts = ref([])
const loading = ref(false)
const saving = ref(false)
const searchQuery = ref('')
const filterCategory = ref('')
const filterType = ref('')

const showModal = ref(false)
const isNew = ref(false)
const form = ref({
  name: '',
  title: '',
  category: 'system',
  type: 'TASK',
  description: '',
  content: ''
})

const previewVars = ref('{}')
const previewResult = ref('')
const previewLoading = ref(false)

const filteredPrompts = computed(() => {
  return prompts.value.filter(p => {
    if (filterCategory.value && p.category !== filterCategory.value) return false
    if (filterType.value && p.type !== filterType.value) return false
    if (searchQuery.value) {
      const q = searchQuery.value.toLowerCase()
      return (p.name && p.name.toLowerCase().includes(q)) || (p.title && p.title.toLowerCase().includes(q))
    }
    return true
  })
})

const getCategoryColor = (category) => {
  const map = {
    system: 'bg-purple-50 text-purple-600 border border-purple-200',
    interview: 'bg-blue-50 text-blue-600 border border-blue-200',
    coding: 'bg-green-50 text-green-600 border border-green-200',
    chat: 'bg-orange-50 text-orange-600 border border-orange-200',
    intent: 'bg-yellow-50 text-yellow-600 border border-yellow-200',
    general: 'bg-slate-100 text-slate-600 border border-slate-200'
  }
  return map[category] || map.general
}

const formatRelativeTime = (dateStr) => {
  if (!dateStr) return '未知时间'
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now - date
  const diffHours = Math.floor(diffMs / (1000 * 60 * 60))
  if (diffHours < 1) return '刚刚'
  if (diffHours < 24) return `${diffHours} 小时前`
  const diffDays = Math.floor(diffHours / 24)
  return `${diffDays} 天前`
}

const loadData = async () => {
  loading.value = true
  try {
    const data = await loadPromptTemplates()
    prompts.value = data || []
  } catch (err) {
    alert(err.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const handleReloadCache = async () => {
  try {
    await reloadPromptCache()
    alert('缓存刷新成功')
  } catch (err) {
    alert(err.message || '刷新失败')
  }
}

const openCreateModal = () => {
  isNew.value = true
  form.value = {
    name: '',
    title: '',
    category: 'system',
    type: 'TASK',
    description: '',
    content: ''
  }
  previewVars.value = '{}'
  previewResult.value = ''
  showModal.value = true
}

const openEditModal = (item, isPreviewMode = false) => {
  isNew.value = false
  form.value = { ...item }
  previewVars.value = '{}'
  previewResult.value = ''
  showModal.value = true
  
  if (isPreviewMode) {
    // maybe auto render?
  }
}

const handleSave = async () => {
  if (!form.value.name || !form.value.content) {
    alert('标识和内容不能为空')
    return
  }
  saving.value = true
  try {
    if (isNew.value) {
      await createPromptTemplate(form.value)
    } else {
      await updatePromptTemplate(form.value.name, {
        title: form.value.title,
        description: form.value.description,
        category: form.value.category,
        content: form.value.content
      })
    }
    showModal.value = false
    await loadData()
  } catch (err) {
    alert(err.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const handleDelete = async (item) => {
  if (confirm(`确定要删除模板 ${item.name} 吗？`)) {
    try {
      await deletePromptTemplate(item.name)
      await loadData()
    } catch (err) {
      alert(err.message || '删除失败')
    }
  }
}

const handlePreview = async () => {
  if (!form.value.name) {
    alert('请先填写/保存模板名称')
    return
  }
  let vars = {}
  try {
    vars = JSON.parse(previewVars.value || '{}')
  } catch (e) {
    alert('JSON 格式错误')
    return
  }
  previewLoading.value = true
  try {
    // If it's a new template, preview API might fail if it's not saved yet, but requirements don't clarify this.
    // Assuming backend preview uses the saved template or we can pass content. The API only accepts variables.
    // So user must save first, or backend just renders the existing one.
    const res = await previewPromptTemplate(form.value.name, vars)
    previewResult.value = res.rendered || res.result || JSON.stringify(res, null, 2)
  } catch (err) {
    previewResult.value = `Error: ${err.message}`
  } finally {
    previewLoading.value = false
  }
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.word-break {
  word-break: break-all;
}
</style>
