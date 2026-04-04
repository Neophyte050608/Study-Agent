<template>
  <div class="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="bg-white dark:bg-slate-900 rounded-2xl shadow-xl w-full max-w-lg overflow-hidden flex flex-col transform transition-all">
      <div class="px-6 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50/80">
        <h3 class="text-lg font-bold text-slate-900 dark:text-slate-100 flex items-center gap-2">
          <span class="material-symbols-outlined text-indigo-600">sync_saved_locally</span>
          配置并同步目录
        </h3>
        <button @click="$emit('close')" class="text-slate-400 hover:text-slate-600 dark:text-slate-400 transition-colors p-1 rounded-md hover:bg-slate-200/50">
          <span class="material-symbols-outlined">close</span>
        </button>
      </div>
      <div class="p-6 space-y-5">
        <div class="space-y-2">
          <label class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider flex items-center gap-1">
             本地绝对路径
             <span class="text-slate-400 font-normal">(支持多行)</span>
          </label>
          <textarea v-model="pathsText" class="w-full bg-slate-50 dark:bg-slate-800/50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all outline-none resize-y" placeholder="/Users/admin/documents/resumes&#10;D:\knowledge\notes" rows="4" :disabled="loading"></textarea>
        </div>
        <div class="space-y-2">
          <label class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider flex items-center gap-1">
             忽略目录
             <span class="text-slate-400 font-normal">(正则或名称)</span>
          </label>
          <input v-model="ignoreDirs" type="text" class="w-full bg-slate-50 dark:bg-slate-800/50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all outline-none" placeholder="node_modules, .git, temp_*" :disabled="loading" />
        </div>
      </div>
      <div class="px-6 py-4 border-t border-slate-100 bg-slate-50/80 flex justify-end gap-3">
        <button @click="$emit('close')" class="px-5 py-2.5 text-sm font-bold text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-slate-100 hover:bg-slate-200/50 rounded-lg transition-colors" :disabled="loading">取消</button>
        <button @click="handleSync" class="px-6 py-2.5 bg-indigo-600 text-white text-sm font-bold rounded-lg hover:bg-indigo-700 transition-all flex items-center gap-2 disabled:opacity-60 shadow-sm" :disabled="loading">
          <span v-if="loading" class="material-symbols-outlined animate-spin text-[18px]">progress_activity</span>
          <span v-else class="material-symbols-outlined text-[18px]">play_arrow</span>
          {{ loading ? '正在同步...' : '开始同步' }}
        </button>
      </div>
    </div>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
const props = defineProps({
  initialConfig: {
    type: Object,
    default: () => ({ paths: '', ignoreDirs: '' })
  },
  loading: {
    type: Boolean,
    default: false
  }
})
const emit = defineEmits(['close', 'sync'])

const pathsText = ref('')
const ignoreDirs = ref('')

onMounted(() => {
  pathsText.value = props.initialConfig?.paths || ''
  ignoreDirs.value = props.initialConfig?.ignoreDirs || ''
})

const handleSync = () => {
  emit('sync', { pathsText: pathsText.value, ignoreDirs: ignoreDirs.value })
}
</script>
