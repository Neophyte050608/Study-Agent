<template>
  <div class="mt-3 rounded-2xl overflow-hidden border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/60 shadow-sm">
    <button class="block w-full text-left" @click="openPreview = true">
      <img
        :src="image.thumbnailUrl || image.accessUrl"
        :alt="image.imageName || 'image'"
        class="w-full max-h-[320px] object-contain bg-slate-100 dark:bg-slate-950"
        loading="lazy"
      />
    </button>
    <div class="px-4 py-3">
      <div class="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">{{ image.retrieveChannel || 'image' }}</div>
      <div class="mt-1 text-sm font-semibold text-slate-800 dark:text-slate-100">{{ image.imageName || '未命名图片' }}</div>
      <div v-if="image.summaryText" class="mt-2 text-sm leading-6 text-slate-600 dark:text-slate-300">{{ image.summaryText }}</div>
    </div>
  </div>

  <div
    v-if="openPreview"
    ref="overlayRef"
    tabindex="-1"
    class="fixed inset-0 z-[120] bg-slate-950/80 backdrop-blur-sm flex items-center justify-center p-6"
    @click.self="openPreview = false"
    @keydown.esc="openPreview = false"
  >
    <div class="max-w-5xl w-full bg-white dark:bg-slate-900 rounded-3xl overflow-hidden shadow-2xl border border-slate-200 dark:border-slate-700">
      <div class="flex items-center justify-between px-5 py-4 border-b border-slate-200 dark:border-slate-700">
        <div class="text-sm font-semibold text-slate-800 dark:text-slate-100">{{ image.imageName || '图片预览' }}</div>
        <button @click="openPreview = false" class="p-2 rounded-xl text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-800">
          <span class="material-symbols-outlined">close</span>
        </button>
      </div>
      <div class="bg-slate-100 dark:bg-slate-950 p-4">
        <img :src="image.accessUrl" :alt="image.imageName || 'image'" class="w-full max-h-[75vh] object-contain" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'

defineProps({
  image: {
    type: Object,
    required: true
  }
})

const openPreview = ref(false)
const overlayRef = ref(null)

watch(openPreview, async (value) => {
  if (value) {
    await nextTick()
    overlayRef.value?.focus()
  }
})
</script>
