<template>
  <!-- Sidebar matching layout.js -->
  <aside class="fixed left-0 top-0 h-full w-64 bg-slate-50 dark:bg-slate-900 border-r border-slate-200/50 flex flex-col z-50">
    <div class="p-6 flex items-center gap-3">
      <div class="w-10 h-10 bg-primary-container rounded-xl flex items-center justify-center text-white">
        <span class="material-symbols-outlined">enterprise</span>
      </div>
      <div>
        <h1 class="text-xl font-bold text-indigo-700 dark:text-indigo-400 leading-tight">数字叙事</h1>
        <p class="text-[10px] text-slate-500 font-medium">高级管理后台</p>
      </div>
    </div>
    <nav class="flex-1 px-4 space-y-1 mt-4">
      <router-link
        v-for="menu in menus"
        :key="menu.id"
        :to="toSpaPath(menu.url)"
        class="flex items-center gap-3 px-4 py-3 font-sans text-sm font-medium tracking-tight transition-colors rounded-lg"
        :class="isActive(menu.url)
          ? 'bg-white dark:bg-indigo-950 text-indigo-700 dark:text-indigo-100 shadow-sm border-l-4 border-indigo-600'
          : 'text-slate-600 dark:text-slate-400 hover:bg-slate-200/50 dark:hover:bg-slate-800'"
      >
        <span class="material-symbols-outlined" :style="isActive(menu.url) ? 'font-variation-settings: \'FILL\' 1;' : ''">{{ menu.icon || 'apps' }}</span>
        <span>{{ menu.title }}</span>
      </router-link>
    </nav>
    <div class="p-4 mt-auto border-t border-slate-200/50 dark:border-slate-800/50">
      <router-link
        :to="toSpaPath('/workspace')"
        class="flex items-center gap-3 px-4 py-3 font-sans text-sm font-medium tracking-tight transition-colors rounded-lg"
        :class="isWorkspaceActive
          ? 'bg-white dark:bg-indigo-950 text-indigo-700 dark:text-indigo-100 shadow-sm border-l-4 border-indigo-600'
          : 'text-slate-600 dark:text-slate-400 hover:bg-slate-200/50 dark:hover:bg-slate-800'"
      >
        <span class="material-symbols-outlined" :style="isWorkspaceActive ? 'font-variation-settings: \'FILL\' 1;' : ''">dashboard_customize</span>
        <span>扩展空间</span>
      </router-link>
    </div>
  </aside>

  <!-- Each view provides its own <main> to match original HTML -->
  <RouterView />
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { loadSidebarMenus } from '../api/menu'

const route = useRoute()
const menus = ref([])

const normalize = (path) => {
  if (!path) {
    return '/'
  }
  const normalized = path.endsWith('/') && path !== '/' ? path.slice(0, -1) : path
  return normalized.startsWith('/') ? normalized : `/${normalized}`
}

const toSpaPath = (rawUrl) => {
  const normalized = normalize(rawUrl)
  switch (normalized) {
    case '/':
      return '/interview'
    case '/interview':
    case '/interview.html':
      return '/interview'
    case '/monitoring':
    case '/monitoring.html':
      return '/monitoring'
    case '/knowledge':
    case '/knowledge.html':
    case '/notes':
    case '/notes.html':
      return '/notes'
    case '/practice':
    case '/practice.html':
    case '/coding':
    case '/coding.html':
      return '/coding'
    case '/profile':
    case '/profile.html':
      return '/profile'
    case '/ops':
    case '/ops.html':
      return '/ops'
    case '/settings':
    case '/settings.html':
      return '/settings'
    case '/workspace':
    case '/workspace.html':
      return '/workspace'
    case '/mcp':
    case '/mcp.html':
      return '/mcp'
    case '/intent-tree':
    case '/intent-tree.html':
      return '/intent-tree'
    case '/prompts':
    case '/prompts.html':
      return '/prompts'
    case '/chat':
    case '/chat.html':
      return '/chat'
    default:
      return normalized
  }
}

const currentSpaPath = computed(() => normalize(toSpaPath(route.path)))

const isActive = (url) => normalize(toSpaPath(url)) === currentSpaPath.value
const isWorkspaceActive = computed(() => currentSpaPath.value === '/workspace')

const loadMenus = async () => {
  try {
    const data = await loadSidebarMenus()
    console.log('Loaded menus from API:', data)
    if (data && data.length > 0) {
      menus.value = data
    } else {
      throw new Error('Empty menu data')
    }
  } catch (error) {
    console.error('Failed to load menus, using fallback:', error)
    menus.value = [
      { id: 'DASHBOARD', title: '面试控制台', icon: 'dashboard', url: '/interview' },
      { id: 'NOTES', title: '知识库管理', icon: 'description', url: '/notes' },
      { id: 'CODING', title: '算法刷题', icon: 'code', url: '/coding' },
      { id: 'PROFILE', title: '能力画像', icon: 'analytics', url: '/profile' },
      { id: 'MONITORING', title: '系统监控', icon: 'monitoring', url: '/monitoring' },
      { id: 'CHAT', title: 'AI 助手', icon: 'chat_bubble', url: '/chat' }
    ]
  }
}

onMounted(() => {
  loadMenus()
  window.addEventListener('menu-layout-changed', loadMenus)
})

onBeforeUnmount(() => {
  window.removeEventListener('menu-layout-changed', loadMenus)
})
</script>
