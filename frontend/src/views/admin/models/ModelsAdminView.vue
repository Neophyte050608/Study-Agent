<script setup>
import ModelCandidatesPanel from './ModelCandidatesPanel.vue'
import ModelRoutingHealthPanel from './ModelRoutingHealthPanel.vue'
import { useModelsAdmin } from './useModelsAdmin'

const { candidates, stats, loading, error, actions } = useModelsAdmin()
</script>

<template>
  <main class="space-y-8 px-8 py-8">
    <section class="flex flex-wrap items-start justify-between gap-4">
      <div>
        <h2 class="text-2xl font-extrabold">模型与路由</h2>
        <p class="text-content-secondary">管理运行时候选模型并查看路由健康。</p>
        <p v-if="error" class="mt-2 text-sm text-red-600">{{ error }}</p>
      </div>
      <button class="rounded-lg bg-surface-hover px-4 py-2 text-sm font-semibold disabled:opacity-60" :disabled="loading" @click="actions.reload">
        {{ loading ? '刷新中…' : '刷新快照' }}
      </button>
    </section>
    <ModelCandidatesPanel :candidates="candidates" :stats="stats" :loading="loading" :actions="actions" />
    <ModelRoutingHealthPanel :stats="stats" />
  </main>
</template>
