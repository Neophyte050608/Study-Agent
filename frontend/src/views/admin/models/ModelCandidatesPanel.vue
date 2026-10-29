<script setup>
import { computed, ref } from 'vue'
import { NCard, NDescriptions, NDescriptionsItem, NButton, NSwitch, NTag, NEmpty, NAlert, NFlex, NPopconfirm, NTooltip, useMessage } from 'naive-ui'
import ModelCandidateEditor from './ModelCandidateEditor.vue'
import { apiKeyState } from './modelPresentation.js'

const props = defineProps({ candidates: { type: Array, default: () => [] }, stats: { type: Object, default: null }, loading: Boolean, actions: { type: Object, required: true } })
const message = useMessage()
const showEditor = ref(false)
const editingCandidate = ref(null)
const busy = ref({})
const probeResults = ref({})
const sortedCandidates = computed(() => [...props.candidates].sort((a, b) => (a.priority ?? 100) - (b.priority ?? 100) || String(a.name).localeCompare(String(b.name))))
const routes = computed(() => [
  ['默认模型', props.stats?.defaultModel], ['深度推理模型', props.stats?.deepThinkingModel],
  ['召回模型', props.stats?.retrievalModel]
])
function edit(candidate = null) {
  editingCandidate.value = candidate
  showEditor.value = true
}
function canCopy(candidate) {
  return candidate.apiKeyConfigured && candidate.apiKeyReadable && candidate.apiKeyCopyAllowed
}
function copyHint(candidate) {
  if (!candidate.apiKeyConfigured) return '当前未配置 API Key'
  if (!candidate.apiKeyReadable) return '密钥不可读，请重新保存或检查加密配置'
  return candidate.apiKeyCopyAllowed ? '复制明文密钥到剪贴板' : '服务端已禁用明文密钥导出'
}
async function run(candidate, operation) {
  if (busy.value[candidate.id] || props.loading) return false
  busy.value = { ...busy.value, [candidate.id]: operation }
  try {
    if (operation === 'copy') {
      const data = await props.actions.copyKey(candidate.id)
      if (!data.apiKey) throw new Error('服务端未返回可复制的密钥')
      await navigator.clipboard.writeText(data.apiKey)
      message.success('密钥已复制到剪贴板')
    } else if (operation === 'probe') {
      const result = await props.actions.probe(candidate.id)
      probeResults.value = { ...probeResults.value, [candidate.id]: result }
      if (result.status === 'healthy') message.success('探测完成，模型状态健康')
      else message.error('探测完成，模型状态异常')
    } else if (operation === 'toggle') {
      await props.actions.toggle(candidate.id)
      message.success(`模型已${candidate.enabled ? '停用' : '启用'}`)
    } else {
      await props.actions.remove(candidate.id)
      delete probeResults.value[candidate.id]
      message.success('模型候选已删除')
    }
    return true
  } catch (cause) {
    const detail = cause.message || '请重试'
    if (operation === 'probe') probeResults.value = { ...probeResults.value, [candidate.id]: { status: 'unhealthy', error: detail } }
    message.error(`操作失败：${detail}`)
    return false
  } finally {
    const next = { ...busy.value }
    delete next[candidate.id]
    busy.value = next
  }
}
</script>

<template>
  <section class="space-y-6 min-w-0" aria-label="候选模型管理">
    <NCard title="路由配置快照" size="small">
      <NDescriptions label-placement="left" :column="1" size="small">
        <NDescriptionsItem v-for="[label, value] in routes" :key="label" :label="label"><span class="break-all">{{ value || '—' }}</span></NDescriptionsItem>
      </NDescriptions>
    </NCard>
    <NFlex align="center" justify="space-between">
      <h3 class="text-lg font-semibold">候选模型（{{ candidates.length }}）</h3>
      <NButton type="primary" :disabled="loading" @click="edit()">新增模型</NButton>
    </NFlex>
    <NCard v-if="!candidates.length"><NEmpty description="暂无候选模型，新增后可配置路由并探测连接。" /></NCard>
    <div class="grid grid-cols-1 gap-4 xl:grid-cols-2">
      <NCard v-for="candidate in sortedCandidates" :key="candidate.id" :title="candidate.displayName || candidate.name" size="small">
        <NFlex class="mb-4" align="center">
          <NTag size="small">{{ candidate.provider }}</NTag>
          <NTag size="small" type="info">{{ candidate.routeType || 'GENERAL' }}</NTag>
          <NTag v-if="candidate.isPrimary" size="small" type="primary">主模型</NTag>
          <NTag v-if="candidate.supportsThinking" size="small">支持推理</NTag>
          <NFlex align="center">
            <NSwitch :value="!!candidate.enabled" :loading="busy[candidate.id] === 'toggle'" :disabled="loading || !!busy[candidate.id]" :aria-label="`启用 ${candidate.displayName || candidate.name}`" @update:value="run(candidate, 'toggle')" />
            <span class="text-sm text-content-secondary">{{ candidate.enabled ? '已启用' : '已停用' }}</span>
          </NFlex>
        </NFlex>
        <NDescriptions :column="1" label-placement="left" bordered size="small" :label-style="{ width: '88px', whiteSpace: 'nowrap' }">
          <NDescriptionsItem label="唯一标识"><span class="break-all">{{ candidate.name }}</span></NDescriptionsItem>
          <NDescriptionsItem label="模型"><span class="break-all">{{ candidate.model }}</span></NDescriptionsItem>
          <NDescriptionsItem label="API 地址"><span class="break-all">{{ candidate.baseUrl }}</span></NDescriptionsItem>
          <NDescriptionsItem label="优先级">{{ candidate.priority ?? 100 }}</NDescriptionsItem>
          <NDescriptionsItem label="API Key">
            <NFlex align="center">
              <span>{{ apiKeyState(candidate).label }} · {{ apiKeyState(candidate).detail }}</span>
              <NTooltip>
                <template #trigger><span tabindex="0"><NButton size="small" tertiary :disabled="!canCopy(candidate) || loading || !!busy[candidate.id]" :loading="busy[candidate.id] === 'copy'" @click="run(candidate, 'copy')">复制密钥</NButton></span></template>
                {{ copyHint(candidate) }}
              </NTooltip>
            </NFlex>
          </NDescriptionsItem>
        </NDescriptions>
        <NAlert v-if="probeResults[candidate.id]" class="mt-4" :type="probeResults[candidate.id].status === 'healthy' ? 'success' : 'error'" :show-icon="false">
          {{ probeResults[candidate.id].status === 'healthy' ? `探测成功 · ${probeResults[candidate.id].latencyMs ?? '—'} ms` : `探测失败：${probeResults[candidate.id].error || '未知错误'}` }}
        </NAlert>
        <template #footer>
          <NFlex>
            <NButton :disabled="loading || !!busy[candidate.id]" :loading="busy[candidate.id] === 'probe'" @click="run(candidate, 'probe')">探测</NButton>
            <NButton :disabled="loading || !!busy[candidate.id]" @click="edit(candidate)">编辑</NButton>
            <NPopconfirm :disabled="loading || !!busy[candidate.id]" :on-positive-click="() => run(candidate, 'remove')">
              <template #trigger><NButton type="error" secondary :disabled="loading || !!busy[candidate.id]" :loading="busy[candidate.id] === 'remove'">删除</NButton></template>
              确定删除「{{ candidate.displayName || candidate.name }}」？此操作不可恢复。
            </NPopconfirm>
          </NFlex>
        </template>
      </NCard>
    </div>
    <ModelCandidateEditor v-model:show="showEditor" :candidate="editingCandidate" :actions="actions" @saved="message.success" />
  </section>
</template>
