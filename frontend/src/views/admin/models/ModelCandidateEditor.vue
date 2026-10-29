<script setup>
import { computed, ref, watch } from 'vue'
import { NModal, NForm, NFormItem, NInput, NInputNumber, NSelect, NSwitch, NButton, NAlert, NFlex } from 'naive-ui'
import { createCandidateForm, candidateFormError, candidatePayload } from './modelPresentation.js'

const props = defineProps({ show: Boolean, candidate: { type: Object, default: null }, actions: { type: Object, required: true } })
const emit = defineEmits(['update:show', 'saved'])
const form = ref(createCandidateForm())
const saving = ref(false)
const error = ref('')
const creating = computed(() => props.candidate == null)
const providers = ['openai', 'zhipuai', 'ollama'].map(value => ({ label: value, value }))
const routes = ['GENERAL', 'THINKING', 'RETRIEVAL', 'ALL'].map(value => ({ label: value, value }))
watch(() => props.show, show => {
  form.value = createCandidateForm(show ? props.candidate || {} : {})
  error.value = ''
})
function close() {
  if (!saving.value) emit('update:show', false)
}
async function save() {
  if (saving.value) return
  error.value = candidateFormError(form.value, creating.value)
  if (error.value) return
  saving.value = true
  try {
    const payload = candidatePayload(form.value)
    if (creating.value) await props.actions.create(payload)
    else await props.actions.update(props.candidate.id, payload)
    emit('saved', creating.value ? '模型候选已创建' : '模型候选已更新')
    emit('update:show', false)
  } catch (cause) {
    error.value = `保存失败：${cause.message || '请重试'}`
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <NModal :show="show" preset="card" :title="creating ? '新增模型候选' : '编辑模型候选'" class="model-editor" :mask-closable="!saving" :close-on-esc="!saving" :closable="!saving" @update:show="close">
    <NForm :model="form" label-placement="top" :disabled="saving" @submit.prevent="save">
      <NAlert v-if="error" type="error" class="mb-4" role="alert">{{ error }}</NAlert>
      <div class="grid grid-cols-1 gap-x-4 md:grid-cols-2">
        <NFormItem label="唯一标识" :label-props="{ for: 'model-name' }" required>
          <NInput :input-props="{ id: 'model-name' }" v-model:value="form.name" :disabled="!creating" placeholder="deepseek-chat" />
        </NFormItem>
        <NFormItem label="显示名称" :label-props="{ for: 'model-display-name' }" required>
          <NInput :input-props="{ id: 'model-display-name' }" v-model:value="form.displayName" placeholder="DeepSeek Chat" />
        </NFormItem>
        <NFormItem label="提供商" :label-props="{ for: 'model-provider' }">
          <NSelect filterable v-model:value="form.provider" :options="providers" :input-props="{ id: 'model-provider' }" />
        </NFormItem>
        <NFormItem label="模型标识" :label-props="{ for: 'model-identifier' }" required>
          <NInput :input-props="{ id: 'model-identifier' }" v-model:value="form.model" placeholder="gpt-4o" />
        </NFormItem>
        <NFormItem label="API 地址" :label-props="{ for: 'model-url' }" required class="md:col-span-2">
          <NInput :input-props="{ id: 'model-url' }" v-model:value="form.baseUrl" placeholder="https://api.openai.com" />
        </NFormItem>
        <NFormItem label="API Key" :label-props="{ for: 'model-key' }" class="md:col-span-2">
          <div class="w-full space-y-3">
            <NInput :input-props="{ id: 'model-key' }" v-model:value="form.apiKey" type="password" show-password-on="click" autocomplete="new-password" :disabled="form.clearApiKey || saving" :placeholder="creating ? (form.provider === 'ollama' ? '本地 Ollama 可留空' : '创建云模型时必填') : '留空表示保留原密钥'" />
            <p class="text-sm text-content-secondary">{{ creating ? '本地 Ollama 通常不需要密钥。' : '不会读取或预填已有密钥；填写新值替换，留空保留。' }}</p>
            <NFlex v-if="!creating" align="center">
              <NSwitch v-model:value="form.clearApiKey" :disabled="saving || !!form.apiKey.trim()" aria-label="清空已保存的 API Key" />
              <span>清空已保存的 API Key</span>
            </NFlex>
          </div>
        </NFormItem>
        <NFormItem label="优先级" :label-props="{ for: 'model-priority' }">
          <NInputNumber v-model:value="form.priority" :precision="0" :input-props="{ id: 'model-priority' }" class="w-full" />
        </NFormItem>
        <NFormItem label="路由类型" :label-props="{ for: 'model-route' }">
          <NSelect filterable v-model:value="form.routeType" :options="routes" :input-props="{ id: 'model-route' }" />
        </NFormItem>
      </div>
      <NFlex :size="16" class="mb-6">
        <NFlex align="center"><NSwitch v-model:value="form.isPrimary" aria-label="主模型" /><span>主模型</span></NFlex>
        <NFlex align="center"><NSwitch v-model:value="form.supportsThinking" aria-label="支持推理" /><span>支持推理</span></NFlex>
        <NFlex align="center"><NSwitch v-model:value="form.enabled" aria-label="启用模型" /><span>启用</span></NFlex>
      </NFlex>
      <NFlex justify="end">
        <NButton :disabled="saving" @click="close">取消</NButton>
        <NButton type="primary" attr-type="submit" :loading="saving">保存模型</NButton>
      </NFlex>
    </NForm>
  </NModal>
</template>

<style scoped>
.model-editor { width: min(680px, calc(100vw - 32px)); max-height: calc(100dvh - 48px); overflow-y: auto; }
</style>
