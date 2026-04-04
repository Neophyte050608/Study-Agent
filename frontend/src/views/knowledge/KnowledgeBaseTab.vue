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
  </div>
</template>
<script setup>
defineProps({
  config: {
    type: Object,
    default: () => ({ paths: '', ignoreDirs: '' })
  }
})
</script>
