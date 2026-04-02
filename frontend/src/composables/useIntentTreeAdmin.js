import { computed, ref } from 'vue'
import { batchToggleLeafEnabled, loadIntentTreeAdminData, saveSingleLeaf } from '../api/intentTreeAdmin'
import { editorFormToLeaf, flattenLeafIntents, leafToEditorForm, normalizePathToArray } from '../utils/intentTreeTransform'

export function useIntentTreeAdmin() {
  const loading = ref(false)
  const saving = ref(false)
  const hint = ref('在线管理意图树配置')
  const config = ref({})
  const stats = ref({})
  const keyword = ref('')
  const taskTypeFilter = ref('ALL')
  const statusFilter = ref('ALL')
  const selectedIndexes = ref([])

  const flatLeafs = computed(() => flattenLeafIntents(config.value?.leafIntents || []))

  const taskTypeOptions = computed(() => {
    const set = new Set()
    flatLeafs.value.forEach((item) => {
      if (item.taskType) set.add(item.taskType)
    })
    return Array.from(set).sort()
  })

  const filteredLeafs = computed(() => {
    const q = keyword.value.trim().toLowerCase()
    return flatLeafs.value.filter((item) => {
      if (q) {
        const text = [item.intentId, item.name, item.pathText, item.taskType].join(' ').toLowerCase()
        if (!text.includes(q)) return false
      }
      if (taskTypeFilter.value !== 'ALL' && item.taskType !== taskTypeFilter.value) return false
      if (statusFilter.value === 'ENABLED' && !item.enabled) return false
      if (statusFilter.value === 'DISABLED' && item.enabled) return false
      return true
    })
  })

  const reload = async () => {
    loading.value = true
    hint.value = '正在加载配置...'
    try {
      const data = await loadIntentTreeAdminData()
      config.value = data.config
      stats.value = data.stats
      selectedIndexes.value = selectedIndexes.value.filter((index) => (config.value?.leafIntents || [])[index])
      hint.value = '配置已加载'
    } catch (error) {
      hint.value = `加载失败: ${error.message || 'unknown'}`
      throw error
    } finally {
      loading.value = false
    }
  }

  const saveConfig = async (nextConfig) => {
    saving.value = true
    hint.value = '正在保存配置...'
    try {
      config.value = nextConfig
      hint.value = '配置已保存'
    } finally {
      saving.value = false
    }
  }

  const toggleBatchEnabled = async (enabled, indexes) => {
    const targets = Array.isArray(indexes) && indexes.length > 0 ? indexes : selectedIndexes.value
    if (targets.length === 0) return
    saving.value = true
    hint.value = `正在${enabled ? '启用' : '下线'}选中节点...`
    try {
      const next = await batchToggleLeafEnabled(config.value, targets, enabled)
      await saveConfig(next)
    } finally {
      saving.value = false
    }
  }

  const saveEditorForm = async (index, form) => {
    saving.value = true
    hint.value = '正在保存节点...'
    try {
      const next = await saveSingleLeaf(config.value, index, form)
      await saveConfig(next)
    } finally {
      saving.value = false
    }
  }

  const createChildLeafForm = (parentLeaf) => {
    const parentPath = normalizePathToArray(parentLeaf?.path).join('/')
    return {
      intentId: '',
      name: '',
      description: '',
      taskType: parentLeaf?.taskType || '',
      path: parentPath ? `${parentPath}/` : '',
      enabled: true,
      examplesText: '',
      slotHintsText: ''
    }
  }

  const getLeafEditorForm = (index) => {
    const leaf = (config.value?.leafIntents || [])[index]
    return leafToEditorForm(leaf)
  }

  const replaceLeafWithForm = (index, form) => {
    const leafIntents = Array.isArray(config.value?.leafIntents) ? [...config.value.leafIntents] : []
    leafIntents[index] = editorFormToLeaf(form)
    config.value = { ...(config.value || {}), leafIntents }
  }

  return {
    loading,
    saving,
    hint,
    config,
    stats,
    keyword,
    taskTypeFilter,
    statusFilter,
    selectedIndexes,
    flatLeafs,
    taskTypeOptions,
    filteredLeafs,
    reload,
    toggleBatchEnabled,
    saveEditorForm,
    getLeafEditorForm,
    replaceLeafWithForm,
    createChildLeafForm
  }
}
