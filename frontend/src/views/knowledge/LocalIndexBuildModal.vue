<template>
  <div class="fixed inset-0 bg-slate-900/50 backdrop-blur-sm z-50 flex items-center justify-center p-4">
    <div class="bg-white dark:bg-slate-900 rounded-2xl shadow-xl w-full max-w-2xl overflow-hidden flex flex-col transform transition-all">
      <div class="px-6 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50/80">
        <h3 class="text-lg font-bold text-slate-900 dark:text-slate-100 flex items-center gap-2">
          <span class="material-symbols-outlined text-indigo-600">account_tree</span>
          生成本地图谱索引
        </h3>
        <button @click="$emit('close')" class="text-slate-400 hover:text-slate-600 dark:text-slate-400 transition-colors p-1 rounded-md hover:bg-slate-200/50">
          <span class="material-symbols-outlined">close</span>
        </button>
      </div>
      <div class="p-6 space-y-5">
        <div class="space-y-2">
          <label class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider flex items-center gap-1">
            Scope 配置文件
          </label>
          <input
            v-model="scopeFilePath"
            type="text"
            class="w-full bg-slate-50 dark:bg-slate-800/50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all outline-none"
            placeholder="D:\\Obsidian\\NoteHub\\meta\\local-knowledge-scope.yaml"
            :disabled="loading"
          />
          <p class="text-xs text-slate-400">填写后优先按 scope 文件中的 include/exclude 规则构建索引。</p>
        </div>
        <div class="space-y-2">
          <label class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider flex items-center gap-1">
            Vault 路径
          </label>
          <input
            v-model="vaultPath"
            type="text"
            class="w-full bg-slate-50 dark:bg-slate-800/50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all outline-none"
            placeholder="D:\\knowledge\\notes"
            :disabled="loading"
          />
          <p class="text-xs text-slate-400">为空时默认使用“同步路径配置”的第一条目录。</p>
        </div>
        <div class="space-y-2">
          <label class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider flex items-center gap-1">
            索引输出路径
          </label>
          <input
            v-model="outputPath"
            type="text"
            class="w-full bg-slate-50 dark:bg-slate-800/50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all outline-none"
            placeholder="D:\\knowledge\\notes\\.ai\\local-knowledge-index.json"
            :disabled="loading"
          />
          <p class="text-xs text-slate-400">为空时会写入默认目录，或覆盖当前已配置的索引文件。</p>
        </div>
        <div class="space-y-2">
          <label class="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wider flex items-center gap-1">
            忽略目录
          </label>
          <input
            v-model="ignoreDirs"
            type="text"
            class="w-full bg-slate-50 dark:bg-slate-800/50 border border-slate-200 rounded-xl px-4 py-3 text-sm focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500 transition-all outline-none"
            placeholder=".obsidian, node_modules, .git"
            :disabled="loading"
          />
        </div>
        <label class="flex items-center gap-3 rounded-xl border border-slate-200 bg-slate-50/80 px-4 py-3 cursor-pointer select-none">
          <input v-model="activate" type="checkbox" class="rounded border-slate-300 text-indigo-600 focus:ring-indigo-500" :disabled="loading" />
          <div>
            <div class="text-sm font-semibold text-slate-700 dark:text-slate-200">生成后立即激活</div>
            <div class="text-xs text-slate-400">启用后，当前运行实例会立即切换到新生成的索引文件。</div>
          </div>
        </label>
      </div>
      <div class="px-6 py-4 border-t border-slate-100 bg-slate-50/80 flex justify-end gap-3">
        <button @click="$emit('close')" class="px-5 py-2.5 text-sm font-bold text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:text-slate-100 hover:bg-slate-200/50 rounded-lg transition-colors" :disabled="loading">取消</button>
        <button @click="handleBuild" class="px-6 py-2.5 bg-indigo-600 text-white text-sm font-bold rounded-lg hover:bg-indigo-700 transition-all flex items-center gap-2 disabled:opacity-60 shadow-sm" :disabled="loading">
          <span v-if="loading" class="material-symbols-outlined animate-spin text-[18px]">progress_activity</span>
          <span v-else class="material-symbols-outlined text-[18px]">play_arrow</span>
          {{ loading ? '正在生成...' : '开始生成' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'

const props = defineProps({
  initialConfig: {
    type: Object,
    default: () => ({
      vaultPath: '',
      scopeFilePath: '',
      outputPath: '',
      ignoreDirs: '',
      activate: true
    })
  },
  loading: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['close', 'build'])

const vaultPath = ref('')
const scopeFilePath = ref('')
const outputPath = ref('')
const ignoreDirs = ref('')
const activate = ref(true)

onMounted(() => {
  vaultPath.value = props.initialConfig?.vaultPath || ''
  scopeFilePath.value = props.initialConfig?.scopeFilePath || ''
  outputPath.value = props.initialConfig?.outputPath || ''
  ignoreDirs.value = props.initialConfig?.ignoreDirs || ''
  activate.value = props.initialConfig?.activate !== false
})

const handleBuild = () => {
  emit('build', {
    vaultPath: vaultPath.value,
    scopeFilePath: scopeFilePath.value,
    outputPath: outputPath.value,
    ignoreDirs: ignoreDirs.value,
    activate: activate.value
  })
}
</script>
