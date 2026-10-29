<script setup>
import { NAlert, NButton, NFlex, NSpin } from 'naive-ui'
import ModelCandidatesPanel from './ModelCandidatesPanel.vue'
import ModelRoutingHealthPanel from './ModelRoutingHealthPanel.vue'
import { useModelsAdmin } from './useModelsAdmin'

const { candidates, stats, loading, error, actions } = useModelsAdmin()
</script>

<template>
  <main class="min-w-0 space-y-6 p-4 md:p-6">
    <NFlex align="start" justify="space-between" :size="16">
      <div>
        <h2 class="text-2xl font-semibold">模型与路由</h2>
        <p class="mt-2 text-sm text-content-secondary">管理候选模型、连接配置与运行健康。</p>
      </div>
      <NButton :loading="loading" :disabled="loading" @click="actions.reload">刷新快照</NButton>
    </NFlex>
    <NAlert v-if="error" type="error" title="快照加载失败" role="alert">
      {{ error }}<span v-if="stats"> · 当前保留上次成功加载的数据。</span>
      <div class="mt-3"><NButton size="small" :disabled="loading" @click="actions.reload">重试</NButton></div>
    </NAlert>
    <NSpin v-if="loading && !stats" class="block py-8" aria-label="正在加载模型快照" />
    <template v-else-if="stats || !error">
      <ModelCandidatesPanel :candidates="candidates" :stats="stats" :loading="loading" :actions="actions" />
      <ModelRoutingHealthPanel :stats="stats" />
    </template>
  </main>
</template>
