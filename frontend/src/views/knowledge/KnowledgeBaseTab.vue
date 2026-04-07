<template>
  <div class="space-y-6">
    <div class="bg-white dark:bg-slate-900 rounded-xl p-6 shadow-sm border border-slate-100 flex flex-col md:flex-row items-start gap-6">
      <div class="w-16 h-16 rounded-xl bg-indigo-50 flex items-center justify-center text-indigo-600 flex-shrink-0">
        <span class="material-symbols-outlined text-3xl">database</span>
      </div>
      <div class="flex-1">
        <h3 class="text-lg font-bold text-slate-900 dark:text-slate-100 mb-2">默认本地知识库</h3>
        <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">包含所有通过本地目录同步与上传的文档数据。</p>
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4 text-sm">
          <div>
            <span class="text-slate-400 block mb-1 font-medium">同步路径配置</span>
            <div class="font-mono text-xs bg-slate-50 dark:bg-slate-800/50 p-3 rounded-lg border border-slate-100 whitespace-pre-wrap text-slate-600 dark:text-slate-400 min-h-[40px]">
              {{ config.paths || '未配置' }}
            </div>
          </div>
          <div>
            <span class="text-slate-400 block mb-1 font-medium">忽略目录</span>
            <div class="font-mono text-xs bg-slate-50 dark:bg-slate-800/50 p-3 rounded-lg border border-slate-100 text-slate-600 dark:text-slate-400 min-h-[40px]">
              {{ config.ignoreDirs || '无' }}
            </div>
          </div>
        </div>
      </div>
      <div class="flex flex-col items-end gap-2 md:border-l border-slate-100 md:pl-6 w-full md:w-auto mt-4 md:mt-0 pt-4 md:pt-0 border-t md:border-t-0">
         <span class="text-xs text-slate-400 uppercase tracking-widest font-bold">状态</span>
         <span class="px-3 py-1 bg-emerald-50 text-emerald-600 rounded-full text-xs font-bold border border-emerald-100">可用</span>
      </div>
    </div>

    <div class="bg-white dark:bg-slate-900 rounded-xl p-6 shadow-sm border border-slate-100">
      <div class="flex flex-col md:flex-row md:items-start md:justify-between gap-5">
        <div class="flex-1">
          <h3 class="text-lg font-bold text-slate-900 dark:text-slate-100 mb-2">本地图谱索引</h3>
          <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">基于同步目录中的 Markdown 笔记生成 `Local Graph` 检索索引，并可直接激活到当前运行实例。</p>
          <div class="grid grid-cols-1 md:grid-cols-2 gap-4 text-sm">
            <div>
              <span class="text-slate-400 block mb-1 font-medium">当前索引文件</span>
              <div class="font-mono text-xs bg-slate-50 dark:bg-slate-800/50 p-3 rounded-lg border border-slate-100 whitespace-pre-wrap text-slate-600 dark:text-slate-400 min-h-[40px]">
                {{ indexStatus.indexFilePath || '未生成' }}
              </div>
            </div>
            <div>
              <span class="text-slate-400 block mb-1 font-medium">生效目录</span>
              <div class="font-mono text-xs bg-slate-50 dark:bg-slate-800/50 p-3 rounded-lg border border-slate-100 whitespace-pre-wrap text-slate-600 dark:text-slate-400 min-h-[40px]">
                {{ indexStatus.vaultRoot || indexStatus.configuredVaultPath || '未配置' }}
              </div>
            </div>
            <div>
              <span class="text-slate-400 block mb-1 font-medium">节点数量</span>
              <div class="text-sm text-slate-700 dark:text-slate-300 bg-slate-50 dark:bg-slate-800/50 p-3 rounded-lg border border-slate-100 min-h-[40px]">
                {{ indexStatus.nodeCount || 0 }}
              </div>
            </div>
            <div>
              <span class="text-slate-400 block mb-1 font-medium">Ollama 模型</span>
              <div class="font-mono text-xs bg-slate-50 dark:bg-slate-800/50 p-3 rounded-lg border border-slate-100 min-h-[40px] text-slate-600 dark:text-slate-400">
                {{ indexStatus.ollamaModel || '未配置' }}
              </div>
            </div>
          </div>
          <div v-if="indexStatus.error" class="mt-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            {{ indexStatus.error }}
          </div>
        </div>
        <div class="flex flex-col items-stretch gap-3 w-full md:w-auto md:min-w-[220px]">
          <div class="flex items-center gap-2">
            <span class="text-xs text-slate-400 uppercase tracking-widest font-bold">索引状态</span>
            <span
              class="px-3 py-1 rounded-full text-xs font-bold border"
              :class="indexStatus.indexExists ? 'bg-emerald-50 text-emerald-600 border-emerald-100' : 'bg-amber-50 text-amber-700 border-amber-100'"
            >
              {{ indexStatus.indexExists ? '已生成' : '未生成' }}
            </span>
          </div>
          <button
            @click="$emit('build-index')"
            :disabled="indexLoading || !config.paths"
            class="bg-indigo-600 py-2.5 px-5 rounded-lg text-white font-bold flex items-center justify-center gap-2 hover:bg-indigo-700 transition-all active:scale-95 shadow-sm disabled:opacity-60"
          >
            <span v-if="indexLoading" class="material-symbols-outlined animate-spin text-[18px]">progress_activity</span>
            <span v-else class="material-symbols-outlined text-[18px]">account_tree</span>
            {{ indexLoading ? '正在生成索引...' : '生成并激活索引' }}
          </button>
          <button
            @click="$emit('advanced-build-index')"
            :disabled="indexLoading"
            class="bg-white py-2.5 px-5 rounded-lg text-slate-700 font-bold flex items-center justify-center gap-2 border border-slate-200 hover:bg-slate-50 transition-all active:scale-95 shadow-sm disabled:opacity-60"
          >
            <span class="material-symbols-outlined text-[18px]">tune</span>
            高级生成
          </button>
          <div class="text-xs text-slate-400 leading-relaxed">
            默认使用“同步路径配置”的第一条目录，并沿用忽略目录配置。
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup>
defineProps({
  config: {
    type: Object,
    default: () => ({ paths: '', ignoreDirs: '' })
  },
  indexStatus: {
    type: Object,
    default: () => ({
      indexFilePath: '',
      indexExists: false,
      nodeCount: 0,
      vaultRoot: '',
      configuredVaultPath: '',
      ollamaModel: '',
      error: ''
    })
  },
  indexLoading: {
    type: Boolean,
    default: false
  }
})

defineEmits(['build-index', 'advanced-build-index'])
</script>
