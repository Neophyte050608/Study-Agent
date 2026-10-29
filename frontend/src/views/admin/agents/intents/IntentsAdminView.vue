<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import IntentListPanel from './IntentListPanel.vue'
import IntentTreePanel from './IntentTreePanel.vue'

const mode = ref('tree')
const router = useRouter()

const showTree = async (intentCode) => {
  await router.replace({ name: 'admin-agent-intents', query: { intentCode } })
  mode.value = 'tree'
}
</script>

<template>
  <main class="space-y-6 bg-surface-field px-8 py-8">
    <header class="flex flex-wrap items-end justify-between gap-4">
      <div>
        <h2 class="text-2xl font-extrabold text-content">意图路由</h2>
        <p class="mt-1 text-content-secondary">以树形结构或列表管理意图节点与路由规则。</p>
      </div>
      <div class="flex rounded-lg border border-divider bg-surface-raised p-1" role="group" aria-label="意图视图">
        <button type="button" class="rounded-md px-4 py-2 text-sm font-bold" :class="mode === 'tree' ? 'bg-brand-subtle text-brand-text' : 'text-content-secondary'" @click="mode = 'tree'">树形</button>
        <button type="button" class="rounded-md px-4 py-2 text-sm font-bold" :class="mode === 'list' ? 'bg-brand-subtle text-brand-text' : 'text-content-secondary'" @click="mode = 'list'">列表</button>
      </div>
    </header>
    <IntentTreePanel v-if="mode === 'tree'" />
    <IntentListPanel v-else @show-tree="showTree" />
  </main>
</template>
