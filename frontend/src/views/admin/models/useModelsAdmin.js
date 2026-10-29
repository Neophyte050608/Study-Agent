import { onMounted, ref } from 'vue'
import * as api from '../../../api/modelAdmin'

export function useModelsAdmin() {
  const candidates = ref([])
  const stats = ref(null)
  const loading = ref(false)
  const error = ref('')

  async function reload() {
    loading.value = true
    error.value = ''
    try {
      const [items, snapshot] = await Promise.all([
        api.loadModelCandidates(),
        api.loadModelRoutingStats()
      ])
      candidates.value = Array.isArray(items) ? items : []
      stats.value = snapshot || null
      return true
    } catch (cause) {
      error.value = cause.message || '加载失败'
      return false
    } finally {
      loading.value = false
    }
  }

  const mutate = operation => async (...args) => {
    const result = await operation(...args)
    await reload()
    return result
  }
  const actions = {
    reload,
    create: mutate(api.createModelCandidate),
    update: mutate(api.updateModelCandidate),
    remove: mutate(api.deleteModelCandidate),
    toggle: mutate(api.toggleModelCandidate),
    probe: mutate(api.probeModelCandidate),
    copyKey: api.copyModelCandidateKey
  }

  onMounted(() => { void reload() })
  return { candidates, stats, loading, error, actions }
}
