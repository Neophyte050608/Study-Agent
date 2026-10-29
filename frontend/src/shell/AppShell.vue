<template>
  <div class="flex h-screen w-full">
    <aside
      class="fixed left-0 top-0 z-50 flex h-full flex-col border-r border-divider-light bg-surface transition-all duration-300"
      :class="sidebarCollapsed ? 'w-20' : 'w-64'"
    >
      <div class="relative flex items-center gap-3 p-6">
        <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-primary-container text-white">
          <span class="material-symbols-outlined">enterprise</span>
        </div>
        <div class="flex flex-col overflow-hidden whitespace-nowrap transition-all duration-300" :class="sidebarCollapsed ? 'w-0 opacity-0' : 'w-full opacity-100'">
          <h1 class="text-xl font-bold leading-tight tracking-widest text-brand-text">青梧</h1>
          <span class="text-[10px] font-medium tracking-wider text-content-secondary">MIQI学习</span>
        </div>
        <button
          type="button"
          class="absolute -right-3 top-8 z-50 hidden h-6 w-6 items-center justify-center rounded-full border border-divider bg-surface-raised text-content-muted shadow-sm transition-all hover:border-divider-hover hover:text-brand-text md:flex"
          :aria-label="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
          @click="userSidebarCollapsed = !userSidebarCollapsed"
        >
          <span class="material-symbols-outlined text-[14px]">{{ sidebarCollapsed ? 'chevron_right' : 'chevron_left' }}</span>
        </button>
      </div>

      <nav class="no-scrollbar mt-4 flex-1 space-y-1 overflow-y-auto overflow-x-hidden px-4" aria-label="主导航">
        <RouterLink
          v-for="menu in menus"
          :key="menu.id"
          :to="menu.path"
          class="group flex items-center gap-3 rounded-lg py-3 font-sans text-sm font-medium tracking-tight transition-all"
          :class="[
            activeId === menu.id
              ? 'border-l-4 border-brand-text bg-surface-raised text-brand-text shadow-sm dark:bg-brand-subtle/50 dark:text-content'
              : 'text-content-secondary hover:bg-surface-hover',
            sidebarCollapsed ? 'justify-center border-l-0 px-0' : 'px-4'
          ]"
          :title="sidebarCollapsed ? menu.title : ''"
        >
          <span
            class="material-symbols-outlined shrink-0"
            :class="sidebarCollapsed && activeId === menu.id ? 'text-brand-text' : ''"
            :style="activeId === menu.id ? 'font-variation-settings: \'FILL\' 1;' : ''"
          >{{ menu.icon }}</span>
          <span class="overflow-hidden whitespace-nowrap transition-all duration-300" :class="sidebarCollapsed ? 'hidden w-0 opacity-0' : 'block w-full opacity-100'">{{ menu.title }}</span>
        </RouterLink>
      </nav>

      <div class="mt-auto border-t border-divider-light/50 p-4">
        <button
          type="button"
          class="group mt-2 flex w-full items-center gap-3 rounded-lg py-3 font-sans text-sm font-medium tracking-tight text-content-secondary transition-all hover:bg-surface-hover"
          :class="sidebarCollapsed ? 'justify-center px-0' : 'px-4'"
          :title="sidebarCollapsed ? (isDark ? '切换到浅色模式' : '切换到深色模式') : ''"
          @click="toggleTheme"
        >
          <span class="material-symbols-outlined shrink-0 transition-colors group-hover:text-brand-text">{{ isDark ? 'light_mode' : 'dark_mode' }}</span>
          <span class="overflow-hidden whitespace-nowrap text-left transition-all duration-300 group-hover:text-brand-text" :class="sidebarCollapsed ? 'hidden w-0 opacity-0' : 'block w-full opacity-100'">{{ isDark ? '浅色模式' : '深色模式' }}</span>
        </button>
      </div>
    </aside>

    <div class="w-full flex-1">
      <RouterView v-slot="{ Component }">
        <component :is="Component" :sidebar-collapsed="sidebarCollapsed" />
      </RouterView>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useTheme } from '../composables/useTheme'
import { isPrimaryNavigationActive, primaryNavigation, resolveSidebarCollapsed } from './navigation.js'

const route = useRoute()
const menus = primaryNavigation
const userSidebarCollapsed = ref(false)
const narrowViewport = ref(false)
const sidebarCollapsed = computed(() => resolveSidebarCollapsed(userSidebarCollapsed.value, narrowViewport.value))
let narrowViewportQuery

function updateNarrowViewport(event) {
  narrowViewport.value = event.matches
}

onMounted(() => {
  narrowViewportQuery = window.matchMedia('(max-width: 767px)')
  updateNarrowViewport(narrowViewportQuery)
  narrowViewportQuery.addEventListener('change', updateNarrowViewport)
})

onBeforeUnmount(() => {
  narrowViewportQuery?.removeEventListener('change', updateNarrowViewport)
})

const activeId = computed(() => menus.find((item) => isPrimaryNavigationActive(item.id, route.path))?.id)
const { isDark, toggleTheme } = useTheme()
</script>
