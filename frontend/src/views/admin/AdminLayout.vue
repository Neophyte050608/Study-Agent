<script setup>
import { useRoute } from 'vue-router'

defineProps({ sidebarCollapsed: Boolean })

const sections = [
  { title: '模型与路由', to: '/admin/models' },
  { title: 'RAG 运行与评测', to: '/admin/rag' },
  { title: 'Agent 工具与配置', to: '/admin/agents/prompts' }
]

const route = useRoute()
const isSectionActive = (target) => target.startsWith('/admin/agents')
  ? route.path.startsWith('/admin/agents/')
  : route.path === target || route.path.startsWith(`${target}/`)
</script>

<template>
  <div class="min-h-screen bg-surface transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
    <header class="border-b border-divider bg-surface-overlay px-8 py-5">
      <h1 class="text-xl font-bold text-brand-text">管理中心</h1>
      <nav class="mt-4 flex gap-2 overflow-x-auto" aria-label="管理中心">
        <RouterLink
          v-for="item in sections"
          :key="item.to"
          :to="item.to"
          class="rounded-lg px-4 py-2 text-sm font-bold text-content-secondary transition-colors hover:bg-surface-hover hover:text-brand-text"
          :class="isSectionActive(item.to) ? 'bg-brand-subtle text-brand-text' : ''"
        >
          {{ item.title }}
        </RouterLink>
      </nav>
    </header>
    <RouterView v-slot="{ Component }">
      <component :is="Component" :sidebar-collapsed="sidebarCollapsed" />
    </RouterView>
  </div>
</template>
