<template>
  <div class="flex h-screen w-full">
    <!-- Sidebar matching layout.js -->
    <aside 
      class="fixed left-0 top-0 h-full bg-slate-50 dark:bg-slate-900 border-r border-slate-200/50 flex flex-col z-50 transition-all duration-300"
      :class="sidebarCollapsed ? 'w-20' : 'w-64'"
    >
    <div class="p-6 flex items-center gap-3 relative">
      <div class="w-10 h-10 bg-primary-container rounded-xl flex items-center justify-center text-white shrink-0">
        <span class="material-symbols-outlined">enterprise</span>
      </div>
      <div class="flex flex-col overflow-hidden whitespace-nowrap transition-all duration-300" :class="sidebarCollapsed ? 'w-0 opacity-0' : 'w-full opacity-100'">
        <h1 class="text-xl font-bold text-indigo-700 dark:text-indigo-400 leading-tight tracking-widest">青梧</h1>
        <span class="text-[10px] text-slate-500 font-medium tracking-wider">MIQI学习</span>
      </div>
      <!-- 折叠按钮 -->
      <button 
        @click="sidebarCollapsed = !sidebarCollapsed"
        class="absolute -right-3 top-8 w-6 h-6 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-full flex items-center justify-center text-slate-400 hover:text-indigo-600 hover:border-indigo-300 shadow-sm transition-all z-50"
      >
        <span class="material-symbols-outlined text-[14px]">{{ sidebarCollapsed ? 'chevron_right' : 'chevron_left' }}</span>
      </button>
    </div>
    <nav class="flex-1 px-4 space-y-1 mt-4 overflow-y-auto overflow-x-hidden no-scrollbar">
      <router-link
        v-for="menu in menus"
        :key="menu.id"
        :to="toSpaPath(menu.url)"
        class="flex items-center gap-3 py-3 font-sans text-sm font-medium tracking-tight transition-all rounded-lg group"
        :class="[
          isActive(menu.url)
            ? 'bg-white dark:bg-indigo-950 text-indigo-700 dark:text-indigo-100 shadow-sm border-l-4 border-indigo-600'
            : 'text-slate-600 dark:text-slate-400 hover:bg-slate-200/50 dark:hover:bg-slate-800',
          sidebarCollapsed ? 'px-0 justify-center border-l-0' : 'px-4'
        ]"
        :title="sidebarCollapsed ? menu.title : ''"
      >
        <span class="material-symbols-outlined shrink-0" :class="sidebarCollapsed && isActive(menu.url) ? 'text-indigo-600' : ''" :style="isActive(menu.url) ? 'font-variation-settings: \'FILL\' 1;' : ''">{{ menu.icon || 'apps' }}</span>
        <span class="overflow-hidden whitespace-nowrap transition-all duration-300" :class="sidebarCollapsed ? 'w-0 opacity-0 hidden' : 'w-full opacity-100 block'">{{ menu.title }}</span>
      </router-link>
    </nav>
    <div class="p-4 mt-auto border-t border-slate-200/50 dark:border-slate-800/50">
      <router-link
        :to="toSpaPath('/workspace')"
        class="flex items-center gap-3 py-3 font-sans text-sm font-medium tracking-tight transition-all rounded-lg group"
        :class="[
          isWorkspaceActive
            ? 'bg-white dark:bg-indigo-950 text-indigo-700 dark:text-indigo-100 shadow-sm border-l-4 border-indigo-600'
            : 'text-slate-600 dark:text-slate-400 hover:bg-slate-200/50 dark:hover:bg-slate-800',
          sidebarCollapsed ? 'px-0 justify-center border-l-0' : 'px-4'
        ]"
        :title="sidebarCollapsed ? '扩展空间' : ''"
      >
        <span class="material-symbols-outlined shrink-0" :class="sidebarCollapsed && isWorkspaceActive ? 'text-indigo-600' : ''" :style="isWorkspaceActive ? 'font-variation-settings: \'FILL\' 1;' : ''">dashboard_customize</span>
        <span class="overflow-hidden whitespace-nowrap transition-all duration-300" :class="sidebarCollapsed ? 'w-0 opacity-0 hidden' : 'w-full opacity-100 block'">扩展空间</span>
      </router-link>

      <!-- 主题切换按钮 -->
      <button
        @click="toggleTheme"
        class="w-full flex items-center gap-3 py-3 mt-2 font-sans text-sm font-medium tracking-tight transition-all rounded-lg group text-slate-600 dark:text-slate-400 hover:bg-slate-200/50 dark:hover:bg-slate-800"
        :class="[sidebarCollapsed ? 'px-0 justify-center' : 'px-4']"
        :title="sidebarCollapsed ? (isDark ? '切换到浅色模式' : '切换到深色模式') : ''"
      >
        <span class="material-symbols-outlined shrink-0 group-hover:text-indigo-600 dark:group-hover:text-indigo-400 transition-colors">{{ isDark ? 'light_mode' : 'dark_mode' }}</span>
        <span class="overflow-hidden whitespace-nowrap transition-all duration-300 text-left group-hover:text-indigo-600 dark:group-hover:text-indigo-400" :class="sidebarCollapsed ? 'w-0 opacity-0 hidden' : 'w-full opacity-100 block'">{{ isDark ? '浅色模式' : '深色模式' }}</span>
      </button>
    </div>
  </aside>

  <!-- Each view provides its own <main> to match original HTML -->
    <div class="flex-1 w-full">
      <RouterView v-slot="{ Component }">
        <component :is="Component" :sidebar-collapsed="sidebarCollapsed" />
      </RouterView>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { loadSidebarMenus } from '../api/menu'
import { useTheme } from '../composables/useTheme'

const route = useRoute()
const menus = ref([])
const sidebarCollapsed = ref(false)

// 引入主题管理
const { isDark, toggleTheme } = useTheme()

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
    case '/intent-list':
    case '/intent-list.html':
      return '/intent-list'
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

const currentSpaPath = computed(() => {
  const normalizedRoutePath = normalize(route.path)
  if (normalizedRoutePath.startsWith('/intent-list/')) {
    return '/intent-list'
  }
  return normalize(toSpaPath(route.path))
})

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
