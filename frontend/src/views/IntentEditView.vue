<template>
  <div class="bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100 min-h-screen relative">
    <header class="fixed top-0 right-0 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-md flex items-center justify-between px-8 z-40 shadow-sm border-b border-slate-200 transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
    <div class="flex items-center gap-4">
      <h1 class="text-xl font-bold tracking-tight text-indigo-700">{{ isCreateMode ? '新增意图节点' : '编辑意图节点' }} <span class="text-slate-500 dark:text-slate-400 dark:text-slate-500 font-medium text-sm ml-2">/ {{ currentTitle }}</span></h1>
    </div>
    <div class="flex items-center gap-2">
      <button @click="goBack" class="px-3 py-1.5 text-xs rounded border border-slate-200 text-slate-600 dark:text-slate-400 dark:text-slate-500 hover:bg-slate-50 dark:bg-slate-800/50">返回</button>
      <button @click="save" :disabled="loading || saving" class="px-3 py-1.5 text-xs rounded bg-[#0057c2] text-white hover:opacity-90 disabled:opacity-50">
        {{ saving ? '保存中...' : '保存' }}
      </button>
    </div>
  </header>

  <main class="min-h-screen bg-slate-50 dark:bg-slate-800/50 pt-24 pb-10 px-8 transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
    <section v-if="loading" class="bg-white dark:bg-slate-900 rounded shadow-sm p-10 text-center text-slate-500 dark:text-slate-400 dark:text-slate-500">加载中...</section>
    <section v-else-if="!form" class="bg-white dark:bg-slate-900 rounded shadow-sm p-10 text-center text-slate-500 dark:text-slate-400 dark:text-slate-500">未找到对应意图节点</section>
    <section v-else class="bg-white dark:bg-slate-900 rounded shadow-sm p-6 space-y-4 max-w-4xl">
      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="text-xs text-slate-600 dark:text-slate-400 dark:text-slate-500">Intent ID *</label>
          <input v-model.trim="form.intentId" :disabled="!isCreateMode" class="w-full mt-1 bg-slate-100 dark:bg-slate-800 rounded px-3 py-2 text-sm disabled:opacity-70" placeholder="例如：CODING.PRACTICE.ALGORITHM" />
        </div>
        <div>
          <label class="text-xs text-slate-600 dark:text-slate-400 dark:text-slate-500">名称 *</label>
          <input v-model.trim="form.name" class="w-full mt-1 bg-slate-100 dark:bg-slate-800 rounded px-3 py-2 text-sm" placeholder="例如：算法题训练" />
        </div>
      </div>

      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="text-xs text-slate-600 dark:text-slate-400 dark:text-slate-500">TaskType *</label>
          <input v-model.trim="form.taskType" class="w-full mt-1 bg-slate-100 dark:bg-slate-800 rounded px-3 py-2 text-sm" placeholder="例如：CODING_PRACTICE" />
        </div>
        <div>
          <label class="text-xs text-slate-600 dark:text-slate-400 dark:text-slate-500">Path *</label>
          <input v-model.trim="form.path" class="w-full mt-1 bg-slate-100 dark:bg-slate-800 rounded px-3 py-2 text-sm" placeholder="例如：coding/practice/algorithm" />
        </div>
      </div>

      <div>
        <label class="text-xs text-slate-600 dark:text-slate-400 dark:text-slate-500">描述</label>
        <textarea v-model.trim="form.description" rows="3" class="w-full mt-1 bg-slate-100 dark:bg-slate-800 rounded px-3 py-2 text-sm resize-y" />
      </div>

      <div class="grid grid-cols-2 gap-4">
        <div>
          <label class="text-xs text-slate-600 dark:text-slate-400 dark:text-slate-500">examples（每行一条）</label>
          <textarea v-model="form.examplesText" rows="6" class="w-full mt-1 bg-slate-100 dark:bg-slate-800 rounded px-3 py-2 text-sm resize-y" />
        </div>
        <div>
          <label class="text-xs text-slate-600 dark:text-slate-400 dark:text-slate-500">slotHints（每行一条）</label>
          <textarea v-model="form.slotHintsText" rows="6" class="w-full mt-1 bg-slate-100 dark:bg-slate-800 rounded px-3 py-2 text-sm resize-y" />
        </div>
      </div>

      <div class="flex items-center gap-2">
        <input id="enabled" v-model="form.enabled" type="checkbox" />
        <label for="enabled" class="text-sm text-slate-700 dark:text-slate-300">启用节点</label>
      </div>

      <p class="text-xs" :class="errorText ? 'text-rose-600' : 'text-slate-500 dark:text-slate-400 dark:text-slate-500'">
        {{ errorText || hint }}
      </p>
    </section>
  </main>
  </div>
</template>

<script setup>
// 中文注释：独立编辑页，支持单节点编辑与新增子节点，保存后返回来源页
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { saveIntentTreeConfig } from '../api/admin'
import { useIntentTreeAdmin } from '../composables/useIntentTreeAdmin'
import { editorFormToLeaf } from '../utils/intentTreeTransform'

defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})

const route = useRoute()
const router = useRouter()
const {
  loading,
  saving,
  hint,
  config,
  flatLeafs,
  reload
} = useIntentTreeAdmin()

const form = ref(null)
const errorText = ref('')
const indexParam = computed(() => String(route.params.index || ''))
const isCreateMode = computed(() => indexParam.value === 'new')
const currentTitle = computed(() => {
  if (isCreateMode.value) return '基于父节点预填路径与任务类型'
  const row = flatLeafs.value.find((item) => String(item.index) === indexParam.value)
  return row ? `${row.name || '-'}（${row.intentId}）` : '未找到节点'
})

const returnTo = computed(() => {
  const from = String(route.query.from || '')
  return from.startsWith('/') ? from : '/intent-list'
})

const buildCreateForm = () => {
  const parentIndex = String(route.query.parentIndex || '')
  const parent = flatLeafs.value.find((item) => String(item.index) === parentIndex)
  return {
    intentId: '',
    name: '',
    description: '',
    taskType: parent?.taskType || '',
    path: parent?.path ? `${parent.path}/` : '',
    enabled: true,
    examplesText: '',
    slotHintsText: ''
  }
}

const fillForm = () => {
  if (isCreateMode.value) {
    form.value = buildCreateForm()
    return
  }
  const row = flatLeafs.value.find((item) => String(item.index) === indexParam.value)
  if (!row) {
    form.value = null
    return
  }
  const leaf = (config.value?.leafIntents || [])[row.index]
  form.value = {
    intentId: String(leaf?.intentId || '').trim(),
    name: String(leaf?.name || '').trim(),
    description: String(leaf?.description || '').trim(),
    taskType: String(leaf?.taskType || '').trim(),
    path: String(leaf?.path || '').trim(),
    enabled: leaf?.enabled !== false,
    examplesText: Array.isArray(leaf?.examples) ? leaf.examples.join('\n') : '',
    slotHintsText: Array.isArray(leaf?.slotHints) ? leaf.slotHints.join('\n') : ''
  }
}

const validateForm = () => {
  errorText.value = ''
  if (!form.value) return false
  if (!form.value.intentId) {
    errorText.value = 'intentId 不能为空'
    return false
  }
  if (!form.value.name) {
    errorText.value = '名称不能为空'
    return false
  }
  if (!form.value.taskType) {
    errorText.value = 'taskType 不能为空'
    return false
  }
  if (!form.value.path) {
    errorText.value = 'path 不能为空'
    return false
  }
  const duplicate = flatLeafs.value.find((item) => item.intentId === form.value.intentId && String(item.index) !== indexParam.value)
  if (duplicate) {
    errorText.value = `intentId 已存在：${form.value.intentId}`
    return false
  }
  return true
}

const save = async () => {
  if (!validateForm()) return
  const leafIntents = Array.isArray(config.value?.leafIntents) ? [...config.value.leafIntents] : []
  const nextLeaf = editorFormToLeaf(form.value)
  if (isCreateMode.value) {
    leafIntents.push(nextLeaf)
  } else {
    const index = Number(indexParam.value)
    leafIntents[index] = nextLeaf
  }
  const payload = { ...(config.value || {}), leafIntents }
  saving.value = true
  errorText.value = ''
  try {
    await saveIntentTreeConfig(payload)
    await reload()
    router.push(returnTo.value)
  } catch (error) {
    errorText.value = error.message || '保存失败'
  } finally {
    saving.value = false
  }
}

const goBack = () => {
  router.push(returnTo.value)
}

onMounted(async () => {
  await reload()
  fillForm()
})
</script>
