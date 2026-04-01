<template>
  <div class="bg-surface text-on-surface antialiased min-h-screen">
    <!-- TopNavBar Shell -->
    <header class="fixed top-0 right-0 left-64 h-16 bg-white/80 backdrop-blur-xl border-b border-slate-100 flex justify-between items-center px-8 z-40 shadow-sm">
      <div class="flex items-center gap-4">
        <h1 class="text-xl font-bold tracking-tight text-indigo-700 dark:text-indigo-400">数字叙事</h1>
        <div class="h-4 w-[1px] bg-slate-300 mx-1"></div>
        <span class="text-sm font-medium text-slate-500">扩展空间 / Workspace</span>
      </div>
      <div class="flex items-center gap-6">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 rounded-full bg-slate-200 overflow-hidden border border-slate-200">
            <img class="w-full h-full object-cover" data-alt="User avatar" src="https://lh3.googleusercontent.com/aida-public/AB6AXuBctVkb05anLJZh3R4qcdbsAdVpITcVhdVn4cDNWuUtnJ9EEU419nqZQkgNkAcy-ZBxmQBQKT5IS4FtKH3q2eFicbNc8M31gehjKjnDZCPk3L0TQXyxYkhJVrz-Yg3swyMINaHTxBXhnLwcytYoteYsnk6PaiPp8ImQ4oc1I2t6CyNnudaMwwFVrcxAcezRRAYqySlLgMOYHG4FK1qcfu57D1NN5Xb8vWwKUHXKWUoUnlsNMOXUM_637BDXE4KeyhXWdalVY2BvBCI"/>
          </div>
        </div>
      </div>
    </header>

    <!-- Main Content Canvas -->
    <main class="ml-64 pt-24 min-h-[calc(100vh)] flex flex-col bg-slate-50 relative z-10">
      <!-- Header -->
      <header class="sticky top-0 z-40 bg-white/80 backdrop-blur-xl border-b border-slate-100 flex justify-between items-center px-8 py-4 w-full shadow-sm">
        <div class="flex flex-col">
          <div class="flex items-center gap-2">
            <h2 class="text-xl font-bold text-slate-900 font-headline">扩展空间 / Workspace</h2>
            <span class="px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-600 text-[10px] font-bold tracking-widest border border-indigo-100">CONFIG</span>
          </div>
          <p class="text-xs text-slate-500 mt-1">拖拽卡片调整顺序、管理模块位置与启用状态</p>
        </div>
        <div class="flex items-center gap-6">
          <div class="flex items-center bg-slate-100 p-1 rounded-lg">
            <button class="px-4 py-1.5 text-xs font-bold rounded-md transition-all bg-white text-indigo-700 shadow-sm">全部模块 (<span id="total-count">{{ menus.length }}</span>)</button>
          </div>
          <button @click="saveLayout" :disabled="loading" class="flex items-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white px-5 py-2 rounded-lg text-sm font-bold transition-all active:scale-95 shadow-md disabled:opacity-60">
            <span class="material-symbols-outlined text-lg" data-icon="save" :class="loading ? 'animate-spin' : ''">{{ loading ? 'refresh' : 'save' }}</span>
            <span>{{ loading ? '保存中...' : '保存布局' }}</span>
          </button>
        </div>
      </header>

      <!-- Bento Grid Main Area -->
      <div class="p-8 flex-1 bg-slate-50">
        <div v-if="hint" class="mb-4 text-sm font-bold text-indigo-600">{{ hint }}</div>
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 max-w-7xl mx-auto">
          <div v-for="(menu, index) in menus" :key="menu.id || index"
               class="rounded-2xl p-6 flex flex-col group relative overflow-hidden bg-white border transition-all duration-300"
               :class="menu.position === 'SIDEBAR' ? 'opacity-100 shadow-sm border-slate-100' : 'opacity-60 shadow-none border-dashed border-slate-300 bg-slate-50/50'">
            <div class="flex justify-between items-start mb-6 z-20">
              <div class="p-3 rounded-xl" :class="menu.beta ? 'bg-amber-50 text-amber-600' : 'bg-indigo-50 text-indigo-600'">
                <span class="material-symbols-outlined text-2xl" :data-icon="menu.icon || 'apps'">{{ menu.icon || 'apps' }}</span>
              </div>
              <span class="px-2.5 py-1 rounded-full text-[10px] font-extrabold tracking-wider border"
                    :class="menu.beta ? 'bg-amber-50 text-amber-700 border-amber-100' : 'bg-indigo-50 text-indigo-700 border-indigo-100'">
                {{ menu.beta ? 'Beta' : '核心' }}
              </span>
            </div>
            <h3 class="text-lg font-bold mb-2 text-slate-900 z-20">{{ menu.title }}</h3>
            <p class="text-sm leading-relaxed mb-8 flex-1 text-slate-500 z-20">{{ menu.description || '暂无描述' }}</p>
            <div class="flex items-center gap-3 z-20">
              <button @click="togglePosition(menu)" class="flex-1 px-4 py-2.5 text-xs font-bold rounded-xl border transition-all"
                      :class="menu.position === 'SIDEBAR' ? 'hover:bg-slate-100 text-slate-600 bg-white border-slate-200 shadow-sm' : 'bg-indigo-50 text-indigo-700 border-indigo-100 shadow-sm'">
                {{ menu.position === 'SIDEBAR' ? '移至扩展' : '移回侧栏' }}
              </button>
              <a :href="toSpaPath(menu.url || menu.path)" class="flex items-center justify-center w-12 h-10 rounded-xl transition-all shadow-sm"
                 :class="menu.position === 'SIDEBAR' ? 'bg-indigo-600 hover:bg-indigo-700 text-white pointer-events-auto' : 'bg-slate-200 text-slate-400 cursor-not-allowed pointer-events-none'">
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
