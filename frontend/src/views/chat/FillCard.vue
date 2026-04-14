<template>
  <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-sm overflow-hidden my-4">
    <div class="px-5 py-3 border-b border-slate-100 dark:border-slate-700 bg-slate-50/60 dark:bg-slate-800/60 flex items-center justify-between gap-4">
      <div class="flex items-center gap-2">
        <span class="material-symbols-outlined text-sky-500">format_list_bulleted_add</span>
        <span class="font-bold text-slate-800 dark:text-slate-200">{{ payload.topic }} 填空题</span>
        <span class="text-xs text-slate-400 font-normal px-2 py-0.5 bg-slate-200 dark:bg-slate-700 rounded-full">{{ payload.difficulty }}</span>
      </div>
      <div class="text-sm font-medium text-slate-500">
        {{ payload.progress }}
      </div>
    </div>

    <div class="p-6 space-y-5">
      <div class="markdown-body text-[15px] leading-relaxed text-slate-800 dark:text-slate-200" v-html="renderMarkdown(payload.stem)"></div>

      <div v-if="!payload.submitted" class="space-y-4">
        <textarea
          v-model="draftAnswer"
          :disabled="submitting"
          rows="6"
          class="w-full rounded-xl border border-slate-300 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40 px-4 py-3 text-[14px] leading-relaxed text-slate-700 dark:text-slate-200 resize-y focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500"
          placeholder="输入你补全后的答案"
        />
        <div class="flex items-center justify-between gap-3">
          <div class="text-xs text-slate-400">提交后会在卡片内展示评分、点评和参考答案。</div>
          <button
            @click="handleSubmit"
            :disabled="submitting || !draftAnswer.trim() || !messageId"
            class="px-5 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-700 disabled:bg-slate-300 disabled:cursor-not-allowed text-white text-sm font-semibold transition-colors"
          >
            {{ submitting ? '提交中...' : '提交答案' }}
          </button>
        </div>
      </div>

      <div v-else class="space-y-4">
        <div class="rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/30 p-4">
          <div class="text-xs font-semibold uppercase tracking-wide text-slate-400 mb-2">你的答案</div>
          <div class="text-[14px] leading-relaxed text-slate-700 dark:text-slate-200 whitespace-pre-wrap">{{ payload.userAnswer }}</div>
        </div>

        <div class="grid gap-4 md:grid-cols-[140px_minmax(0,1fr)]">
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 p-4 bg-white dark:bg-slate-800/70">
            <div class="text-xs font-semibold uppercase tracking-wide text-slate-400 mb-2">评分</div>
            <div class="flex items-end gap-1">
              <span class="text-2xl font-black text-indigo-600 dark:text-indigo-400">{{ payload.score ?? '-' }}</span>
              <span class="text-xs font-semibold text-slate-400 pb-1">/100</span>
            </div>
          </div>
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 p-4 bg-white dark:bg-slate-800/70">
            <div class="text-xs font-semibold uppercase tracking-wide text-slate-400 mb-2">点评</div>
            <div class="markdown-body text-[14px] leading-relaxed text-slate-700 dark:text-slate-200" v-html="renderMarkdown(payload.feedback || '暂无点评')"></div>
          </div>
        </div>

        <div class="rounded-xl border border-emerald-200 dark:border-emerald-900 bg-emerald-50/70 dark:bg-emerald-950/20 p-4">
          <div class="text-xs font-semibold uppercase tracking-wide text-emerald-600 dark:text-emerald-300 mb-2">参考答案</div>
          <div class="markdown-body text-[14px] leading-relaxed text-slate-700 dark:text-slate-200" v-html="renderMarkdown(payload.referenceAnswer || '暂无参考答案')"></div>
        </div>

        <div v-if="payload.nextHint" class="rounded-xl border border-amber-200 dark:border-amber-900 bg-amber-50/70 dark:bg-amber-950/20 p-4">
          <div class="text-xs font-semibold uppercase tracking-wide text-amber-600 dark:text-amber-300 mb-2">补充建议</div>
          <div class="markdown-body text-[14px] leading-relaxed text-slate-700 dark:text-slate-200" v-html="renderMarkdown(payload.nextHint)"></div>
        </div>

        <div class="flex justify-end">
          <button
            v-if="!payload.isLast && payload.canContinue"
            @click="handleNext"
            :disabled="loadingNext"
            class="px-5 py-2.5 rounded-xl bg-slate-900 hover:bg-slate-700 disabled:bg-slate-300 disabled:cursor-not-allowed text-white text-sm font-semibold transition-colors"
          >
            {{ loadingNext ? '生成中...' : '下一题' }}
          </button>
          <div v-else-if="payload.isLast" class="text-sm text-emerald-600 dark:text-emerald-300 font-semibold">
            本轮填空题已完成
          </div>
          <div v-else class="text-sm text-slate-500 dark:text-slate-400 font-semibold">
            已进入下一题
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { nextFillCard, submitFillCard } from '../../api/coding'

const props = defineProps({
  payload: {
    type: Object,
    required: true
  },
  messageId: {
    type: String,
    required: true
  },
  chatSessionId: {
    type: String,
    required: true
  }
})

const emit = defineEmits(['updated'])

const draftAnswer = ref(props.payload.userAnswer || '')
const submitting = ref(false)
const loadingNext = ref(false)

const renderMarkdown = (text) => DOMPurify.sanitize(marked.parse(text || ''))

watch(
  () => props.payload.userAnswer,
  (value) => {
    if (props.payload.submitted) {
      draftAnswer.value = value || ''
    }
  }
)

const handleSubmit = async () => {
  const answer = draftAnswer.value.trim()
  if (!answer || submitting.value) return
  submitting.value = true
  try {
    await submitFillCard({
      messageId: props.messageId,
      chatSessionId: props.chatSessionId,
      sessionId: props.payload.sessionId,
      cardId: props.payload.cardId,
      answer
    })
    emit('updated')
  } finally {
    submitting.value = false
  }
}

const handleNext = async () => {
  if (loadingNext.value) return
  loadingNext.value = true
  try {
    await nextFillCard({
      messageId: props.messageId,
      chatSessionId: props.chatSessionId,
      sessionId: props.payload.sessionId,
      cardId: props.payload.cardId
    })
    emit('updated')
  } finally {
    loadingNext.value = false
  }
}
</script>
