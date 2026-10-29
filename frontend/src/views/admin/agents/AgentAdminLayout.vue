<script setup>
import { useRoute } from 'vue-router'
import { agentAdminNavigation, isAgentSectionActive } from './agentAdminNavigation.js'

defineProps({ sidebarCollapsed: Boolean })

const route = useRoute()
</script>

<template>
  <section>
    <nav class="flex gap-2 overflow-x-auto border-b border-divider bg-surface px-8 py-4" aria-label="Agent 工具与配置">
      <RouterLink
        v-for="item in agentAdminNavigation"
        :key="item.path"
        :to="item.path"
        class="rounded-lg px-4 py-2 text-sm font-bold text-content-secondary transition-colors hover:bg-surface-hover hover:text-brand-text"
        :class="isAgentSectionActive(route.path, item.path) ? 'bg-brand-subtle text-brand-text' : ''"
      >
        {{ item.title }}
      </RouterLink>
    </nav>
    <RouterView v-slot="{ Component }">
      <component :is="Component" :sidebar-collapsed="sidebarCollapsed" />
    </RouterView>
  </section>
</template>
