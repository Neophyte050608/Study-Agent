<template>
  <div class="bg-surface text-content antialiased min-h-screen">
    <!-- TopNavBar Shell -->
    <header class="fixed top-0 right-0 h-16 bg-surface-overlay/80 backdrop-blur-xl border-b border-divider-light flex justify-between items-center px-8 z-40 shadow-sm transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
      <div class="flex items-center gap-4">
        <h1 class="text-xl font-bold tracking-tight text-brand-text">扩展空间 <span class="text-content-secondary font-medium text-sm ml-2">/ 拖拽卡片调整顺序、管理模块位置与启用状态</span></h1>
      </div>
      <div class="flex items-center gap-6">
        <div class="flex items-center bg-surface-hover p-1 rounded-lg">
          <button class="px-4 py-1.5 text-xs font-bold rounded-md transition-all bg-surface-raised text-brand-text shadow-sm">全部模块 (<span id="total-count">{{ menus.length }}</span>)</button>
        </div>
        <button @click="saveLayout" :disabled="loading" class="flex items-center gap-2 bg-brand hover:bg-brand-hover text-white px-5 py-2 rounded-lg text-sm font-bold transition-all active:scale-95 shadow-md disabled:opacity-60">
          <span class="material-symbols-outlined text-lg" data-icon="save" :class="loading ? 'animate-spin' : ''">{{ loading ? 'refresh' : 'save' }}</span>
          <span>{{ loading ? '保存中...' : '保存布局' }}</span>
        </button>
      </div>
    </header>

    <!-- Main Content Canvas -->
    <main class="pt-20 min-h-[calc(100vh)] flex flex-col bg-surface relative z-10 transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">

      <!-- Bento Grid Main Area -->
      <div class="p-8 flex-1 bg-surface">
        <div v-if="hint" class="mb-4 text-sm font-bold text-brand-text">{{ hint }}</div>
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 max-w-7xl mx-auto">
          <div v-for="(menu, index) in menus" :key="menu.id || index"
               class="rounded-2xl p-6 flex flex-col group relative overflow-hidden bg-surface-raised border transition-all duration-300"
               :class="menu.position === 'SIDEBAR' ? 'opacity-100 shadow-sm border-divider-light' : 'opacity-60 shadow-none border-dashed border-divider bg-surface-field/50 dark:bg-surface-field/30'">
            <div class="flex justify-between items-start mb-6 z-20">
              <div class="p-3 rounded-xl" :class="menu.beta ? 'bg-amber-50 dark:bg-amber-950/50 text-amber-600 dark:text-amber-300' : 'bg-brand-subtle/50 text-brand-text'">
                <span class="material-symbols-outlined text-2xl" :data-icon="menu.icon || 'apps'">{{ menu.icon || 'apps' }}</span>
              </div>
              <span class="px-2.5 py-1 rounded-full text-[10px] font-extrabold tracking-wider border"
                    :class="menu.beta ? 'bg-amber-50 dark:bg-amber-950/50 text-amber-700 dark:text-amber-300 border-amber-100 dark:border-amber-800' : 'bg-brand-subtle/50 text-brand-text border-divider-hover'">
                {{ menu.beta ? 'Beta' : '核心' }}
              </span>
            </div>
            <h3 class="text-lg font-bold mb-2 text-content z-20">{{ menu.title }}</h3>
            <p class="text-sm leading-relaxed mb-8 flex-1 text-content-secondary z-20">{{ menu.description || '暂无描述' }}</p>
            <div class="flex items-center gap-3 z-20">
              <button @click="togglePosition(menu)" class="flex-1 px-4 py-2.5 text-xs font-bold rounded-xl border transition-all"
                      :class="menu.position === 'SIDEBAR' ? 'hover:bg-surface-hover text-content-secondary bg-surface-raised border-divider shadow-sm' : 'bg-brand-subtle/50 text-brand-text border-divider-hover shadow-sm'">
                {{ menu.position === 'SIDEBAR' ? '移至扩展' : '移回侧栏' }}
              </button>
              <a :href="toSpaPath(menu.url || menu.path)" class="flex items-center justify-center w-12 h-10 rounded-xl transition-all shadow-sm"
                 :class="menu.position === 'SIDEBAR' ? 'bg-brand hover:bg-brand-hover text-white pointer-events-auto' : 'bg-surface-hover text-content-muted cursor-not-allowed pointer-events-none'">
                <span class="material-symbols-outlined" data-icon="arrow_outward">arrow_outward</span>
              </a>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { loadMenuSettings, saveMenuLayout } from '../api/admin'

defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})

const loading = ref(false)
const hint = ref('')
const menus = ref([])

const toSpaPath = (url) => {
  if (!url) return '#'
  const base = url.replace(/\.html$/, '')
  return base.startsWith('/') ? base : `/${base}`
}

const togglePosition = (menu) => {
  menu.position = menu.position === 'SIDEBAR' ? 'EXTENSION' : 'SIDEBAR'
}

const saveLayout = async () => {
  loading.value = true
  hint.value = '正在保存...'
  try {
    await saveMenuLayout(menus.value)
    hint.value = '保存成功！'
    // Notify AppShell to reload menus
    window.dispatchEvent(new CustomEvent('menu-layout-changed'))
  } catch (error) {
    hint.value = `保存失败: ${error.message || 'unknown'}`
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    const data = await loadMenuSettings()
    menus.value = Array.isArray(data) ? data.map((item) => ({ ...item })) : []
  } catch (error) {
    hint.value = `加载失败: ${error.message || 'unknown'}`
  } finally {
    loading.value = false
  }
})
</script>
