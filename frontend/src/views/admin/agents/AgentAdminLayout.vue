<script setup>
defineProps({ sidebarCollapsed: Boolean })

const sections = [
  { title: '提示词', to: '/admin/agents/prompts' },
  { title: '意图路由', to: '/admin/agents/intents' },
  { title: 'MCP 能力', to: '/admin/agents/mcp' }
]
</script>

<template>
  <section>
    <nav class="flex gap-2 overflow-x-auto border-b border-divider bg-surface px-8 py-4" aria-label="Agent 工具与配置">
      <RouterLink
        v-for="item in sections"
        :key="item.to"
        :to="item.to"
        class="rounded-lg px-4 py-2 text-sm font-bold text-content-secondary transition-colors hover:bg-surface-hover hover:text-brand-text"
        active-class="bg-brand-subtle text-brand-text"
      >
        {{ item.title }}
      </RouterLink>
    </nav>
    <RouterView v-slot="{ Component }">
      <component :is="Component" :sidebar-collapsed="sidebarCollapsed" />
    </RouterView>
  </section>
</template>
