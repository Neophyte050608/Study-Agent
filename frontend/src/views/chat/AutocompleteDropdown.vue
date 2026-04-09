<template>
  <Transition name="fade">
    <div
      v-if="visible && suggestions.length > 0"
      class="autocomplete-dropdown absolute bottom-full left-0 right-0 mb-1 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg overflow-hidden z-50 max-h-[320px] overflow-y-auto"
    >
      <div
        v-for="(item, index) in suggestions"
        :key="item.id || index"
        :ref="el => { if (el) itemRefs[index] = el }"
        @click="$emit('select', item)"
        @mouseenter="$emit('hover', index)"
        :class="[
          'px-4 py-2.5 cursor-pointer flex items-center justify-between transition-colors',
          index === highlightIndex
            ? 'bg-indigo-50 dark:bg-indigo-900/30'
            : 'hover:bg-slate-50 dark:hover:bg-slate-700/50'
        ]"
      >
        <span class="text-sm text-slate-700 dark:text-slate-200" v-html="highlightMatch(item.phrase)"></span>
        <span
          v-if="item.category"
          class="ml-2 text-[11px] px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-slate-400 dark:text-slate-500 shrink-0"
        >
          {{ item.category }}
        </span>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { ref as vueRef, watch } from 'vue'
import DOMPurify from 'dompurify'

const props = defineProps({
  suggestions: { type: Array, default: () => [] },
  visible: { type: Boolean, default: false },
  highlightIndex: { type: Number, default: -1 },
  query: { type: String, default: '' }
})

defineEmits(['select', 'hover'])

const itemRefs = vueRef({})

watch(() => props.suggestions, () => {
  itemRefs.value = {}
})

watch(() => props.highlightIndex, (idx) => {
  if (idx >= 0 && itemRefs.value[idx]) {
    itemRefs.value[idx].scrollIntoView({ block: 'nearest' })
  }
})

function highlightMatch(phrase) {
  const safePhrase = phrase || ''
  if (!props.query) return DOMPurify.sanitize(safePhrase)
  const idx = safePhrase.toLowerCase().indexOf(props.query.toLowerCase())
  if (idx === -1) return DOMPurify.sanitize(safePhrase)
  const before = safePhrase.slice(0, idx)
  const match = safePhrase.slice(idx, idx + props.query.length)
  const after = safePhrase.slice(idx + props.query.length)
  return DOMPurify.sanitize(
    `${before}<strong class="text-indigo-600 dark:text-indigo-400">${match}</strong>${after}`
  )
}
</script>

<style scoped>
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.15s ease;
}

.fade-enter-from, .fade-leave-to {
  opacity: 0;
}
</style>
