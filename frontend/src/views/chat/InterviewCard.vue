<template>
  <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-sm overflow-hidden my-4">
    <!-- Header: progress + timer -->
    <div class="px-5 py-3 border-b border-slate-100 dark:border-slate-700 bg-slate-50/60 dark:bg-slate-800/60 flex items-center justify-between gap-4">
      <div class="flex items-center gap-2">
        <span class="material-symbols-outlined text-indigo-500">psychology</span>
        <span class="font-bold text-slate-800 dark:text-slate-200">面试</span>
        <span class="text-sm text-slate-500 font-medium">
          第 {{ payload.questionIndex }} / {{ payload.totalQuestions }} 题
        </span>
      </div>
      <div v-if="payload.interviewState === 'asking' || payload.interviewState === 'submitting'" class="flex items-center gap-1.5 text-slate-600 dark:text-slate-300 font-mono text-sm">
        <span class="material-symbols-outlined text-base">timer</span>
        <span>{{ timerDisplay }}</span>
      </div>
    </div>

    <div class="p-6 space-y-5">
      <!-- Question text -->
      <div
        class="markdown-body text-[15px] leading-relaxed text-slate-800 dark:text-slate-200"
        v-html="renderMarkdown(payload.question || '')"
      ></div>

      <!-- asking: answer input -->
      <div v-if="payload.interviewState === 'asking'" class="space-y-4">
        <textarea
          v-model="draftAnswer"
          :disabled="submitting"
          rows="8"
          class="w-full rounded-xl border border-slate-300 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40 px-4 py-3 text-[14px] leading-relaxed text-slate-700 dark:text-slate-200 resize-y focus:outline-none focus:ring-2 focus:ring-indigo-500/20 focus:border-indigo-500"
          placeholder="请输入你的回答（Shift+Enter 换行，Enter 提交）"
          @keydown.enter.exact.prevent="handleSubmit"
        ></textarea>
        <div class="flex items-center justify-between gap-3">
          <button
            v-if="speechSupported"
            @click="toggleRecording"
            :class="recording ? 'text-red-500 border-red-200 bg-red-50 dark:bg-red-950/20' : 'text-slate-500 border-slate-200 hover:bg-slate-50 dark:hover:bg-slate-800'"
            class="px-3 py-2 rounded-xl border text-sm font-medium transition-colors flex items-center gap-1.5"
          >
            <span class="material-symbols-outlined text-base">{{ recording ? 'stop_circle' : 'mic' }}</span>
            {{ recording ? '停止录音' : '语音回答' }}
          </button>
          <div class="flex items-center gap-2 ml-auto">
            <button
              v-if="submitting"
              @click="handleStop"
              class="px-5 py-2.5 rounded-xl bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 text-sm font-semibold transition-colors hover:bg-slate-200 dark:hover:bg-slate-600"
            >
              停止
            </button>
            <button
              @click="handleSubmit"
              :disabled="submitting || !draftAnswer.trim()"
              class="px-5 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-700 disabled:bg-slate-300 disabled:cursor-not-allowed text-white text-sm font-semibold transition-colors"
            >
              {{ submitting ? '提交中...' : '提交回答' }}
            </button>
          </div>
        </div>
      </div>

      <!-- submitting: loading state -->
      <div v-if="payload.interviewState === 'submitting'" class="space-y-4">
        <textarea
          v-model="draftAnswer"
          disabled
          rows="8"
          class="w-full rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-100 dark:bg-slate-800/50 px-4 py-3 text-[14px] leading-relaxed text-slate-500 resize-y"
        ></textarea>
        <div class="flex items-center gap-3 text-sm text-slate-500">
          <span class="flex gap-1">
            <span class="w-1.5 h-1.5 bg-indigo-400 rounded-full animate-bounce"></span>
            <span class="w-1.5 h-1.5 bg-indigo-400 rounded-full animate-bounce" style="animation-delay: 0.2s"></span>
            <span class="w-1.5 h-1.5 bg-indigo-400 rounded-full animate-bounce" style="animation-delay: 0.4s"></span>
          </span>
          AI 正在评估你的回答...
        </div>
        <div class="flex justify-end">
          <button
            @click="handleStop"
            class="px-5 py-2.5 rounded-xl bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 text-sm font-semibold transition-colors hover:bg-slate-200 dark:hover:bg-slate-600"
          >
            停止
          </button>
        </div>
      </div>

      <!-- feedback: score + feedback -->
      <div v-if="payload.interviewState === 'feedback'" class="space-y-4">
        <div class="rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/30 p-4">
          <div class="text-xs font-semibold uppercase tracking-wide text-slate-400 mb-2">你的回答</div>
          <div class="text-[14px] leading-relaxed text-slate-700 dark:text-slate-200 whitespace-pre-wrap">{{ draftAnswer }}</div>
        </div>

        <div class="grid gap-4 md:grid-cols-[140px_minmax(0,1fr)]">
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 p-4 bg-white dark:bg-slate-800/70 text-center">
            <div class="text-xs font-semibold uppercase tracking-wide text-slate-400 mb-2">评分</div>
            <svg class="w-20 h-20 mx-auto -rotate-90" viewBox="0 0 36 36">
              <circle cx="18" cy="18" r="15.5" fill="none" stroke="currentColor" stroke-width="2" class="text-slate-200 dark:text-slate-700" />
              <circle
                cx="18" cy="18" r="15.5" fill="none"
                stroke-width="2.5"
                stroke-linecap="round"
                stroke="currentColor"
                class="text-indigo-600 dark:text-indigo-400"
                :stroke-dasharray="`${(payload.score || 0) * 97.4 / 100} 97.4`"
              />
            </svg>
            <div class="text-xl font-black text-indigo-600 dark:text-indigo-400 -mt-12">{{ payload.score ?? '-' }}</div>
          </div>
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 p-4 bg-white dark:bg-slate-800/70">
            <div class="text-xs font-semibold uppercase tracking-wide text-slate-400 mb-2">点评</div>
            <div
              class="markdown-body text-[14px] leading-relaxed text-slate-700 dark:text-slate-200"
              v-html="renderMarkdown(payload.feedback || '暂无点评')"
            ></div>
          </div>
        </div>

        <div class="flex justify-end">
          <button
            v-if="!payload.isLast"
            @click="handleNext"
            class="px-5 py-2.5 rounded-xl bg-slate-900 hover:bg-slate-700 text-white text-sm font-semibold transition-colors"
          >
            下一题
          </button>
          <button
            v-else
            @click="handleFinish"
            :disabled="finishing"
            class="px-5 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-700 disabled:bg-slate-300 text-white text-sm font-semibold transition-colors"
          >
            {{ finishing ? '生成中...' : '生成报告' }}
          </button>
        </div>
      </div>

      <!-- finished: final report -->
      <div v-if="payload.interviewState === 'finished'" class="space-y-4">
        <div
          class="markdown-body text-[15px] leading-relaxed text-slate-800 dark:text-slate-200"
          v-html="renderMarkdown(payload.report || '报告已生成')"
        ></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  submitInterviewAnswerStream,
  generateInterviewReportStream,
  stopInterviewStream
} from '../../api/interview'

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
const finishing = ref(false)
const recording = ref(false)
const streamTaskId = ref('')
const streamAbort = ref(null)
let streamAbortExpected = false
let isAlive = true

// Timer
const timerSeconds = ref(props.payload.elapsedSeconds || 0)
let timerInterval = null

const timerDisplay = computed(() => {
  const m = String(Math.floor(timerSeconds.value / 60)).padStart(2, '0')
  const s = String(timerSeconds.value % 60).padStart(2, '0')
  return `${m}:${s}`
})

const startTimer = () => {
  stopTimer()
  timerInterval = setInterval(() => {
    timerSeconds.value++
  }, 1000)
}

const stopTimer = () => {
  clearInterval(timerInterval)
  timerInterval = null
}

watch(
  () => props.payload.interviewState,
  (state) => {
    if (state === 'asking' && !submitting.value) {
      startTimer()
    } else {
      stopTimer()
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  isAlive = false
  stopTimer()
  if (streamTaskId.value) {
    stopInterviewStream(streamTaskId.value).catch(() => {})
  }
  streamAbort.value?.()
  recognition?.abort()
})

// Voice recognition
const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition
const speechSupported = !!SpeechRecognition

let recognition = null
if (speechSupported) {
  recognition = new SpeechRecognition()
  recognition.lang = 'zh-CN'
  recognition.continuous = true
  recognition.interimResults = true

  recognition.onstart = () => {
    if (!isAlive) return
    recording.value = true
  }

  recognition.onresult = (event) => {
    if (!isAlive) return
    let final = ''
    for (let i = event.resultIndex; i < event.results.length; i++) {
      if (event.results[i].isFinal) {
        final += event.results[i][0].transcript
      }
    }
    draftAnswer.value += final
  }

  recognition.onend = () => {
    if (!isAlive) return
    recording.value = false
  }

  recognition.onerror = () => {
    if (!isAlive) return
    recording.value = false
  }
}

const toggleRecording = () => {
  if (!recognition) return
  if (recording.value) {
    recognition.stop()
  } else {
    recognition.start()
  }
}

const renderMarkdown = (text) => DOMPurify.sanitize(marked.parse(text || ''))

const isAbortLikeError = (err) => {
  if (!err) return false
  const name = typeof err?.name === 'string' ? err.name : ''
  const msg = typeof err?.message === 'string' ? err.message.toLowerCase() : ''
  return name === 'AbortError' || msg.includes('aborted') || msg.includes('abort')
}

const cancelActiveStream = ({ expected = false } = {}) => {
  if (expected) streamAbortExpected = true
  if (streamAbort.value) {
    streamAbort.value.cancel()
    streamAbort.value = null
  }
}

const randomTraceId = () => `trace-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`

const runStream = async (stream) => {
  submitting.value = true
  streamTaskId.value = ''
  streamAbort.value = stream.cancel
  streamAbortExpected = false
  try {
    await stream.start()
  } finally {
    submitting.value = false
    streamTaskId.value = ''
    streamAbort.value = null
  }
}

const handleSubmit = async () => {
  const answer = draftAnswer.value.trim()
  if (!answer || submitting.value) return

  submitting.value = true
  stopTimer()

  const interviewSessionId = props.payload.interviewSessionId
  if (!interviewSessionId) {
    submitting.value = false
    return
  }

  let streamError = null
  let feedbackBuffer = ''
  let nextQuestionBuffer = ''
  let shouldFinish = false
  let finalScore = props.payload.score || 0
  let isLast = false

  const stream = submitInterviewAnswerStream(
    interviewSessionId,
    answer,
    {
      onMeta: (payload) => {
        if (payload?.streamTaskId) streamTaskId.value = payload.streamTaskId
        else if (typeof payload === 'string') streamTaskId.value = payload
      },
      onMessage: (payload) => {
        const channel = payload?.channel
        const delta = payload?.delta || ''
        if (channel === 'feedback') feedbackBuffer += delta
        if (channel === 'question') nextQuestionBuffer += delta
      },
      onFinish: (payload) => {
        const result = payload?.result || {}
        finalScore = result.averageScore || 0
        isLast = !!result.finished

        if (result.finished) {
          shouldFinish = true
        }

        Object.assign(props.payload, {
          interviewState: 'feedback',
          score: finalScore,
          feedback: feedbackBuffer || result.feedback || '',
          userAnswer: answer,
          isLast,
          question: result.finished
            ? props.payload.question
            : (nextQuestionBuffer || result.nextQuestion || props.payload.question),
          questionIndex: result.finished
            ? props.payload.questionIndex
            : (props.payload.questionIndex + 1),
          elapsedSeconds: timerSeconds.value
        })
      },
      onCancel: () => {},
      onError: (error) => {
        streamError = error
      }
    },
    randomTraceId()
  )

  try {
    await runStream(stream)
    if (streamError) throw streamError
    if (shouldFinish) {
      await doGenerateReport()
    }
  } catch (error) {
    if (streamAbortExpected || isAbortLikeError(error)) {
      streamAbortExpected = false
      return
    }
    Object.assign(props.payload, {
      interviewState: 'feedback',
      score: finalScore,
      feedback: feedbackBuffer + '\n\n**[提交失败]**',
      userAnswer: answer
    })
  }
}

const handleStop = async () => {
  if (streamTaskId.value) {
    try {
      await stopInterviewStream(streamTaskId.value)
    } catch {
      cancelActiveStream({ expected: true })
    }
  } else {
    cancelActiveStream({ expected: true })
  }
}

const handleNext = () => {
  draftAnswer.value = ''
  Object.assign(props.payload, {
    interviewState: 'asking',
    userAnswer: '',
    score: props.payload.score,
    feedback: props.payload.feedback,
    elapsedSeconds: 0
  })
  timerSeconds.value = 0
  startTimer()
}

const handleFinish = async () => {
  await doGenerateReport()
}

const doGenerateReport = async () => {
  const interviewSessionId = props.payload.interviewSessionId
  if (!interviewSessionId) return

  finishing.value = true
  stopTimer()

  let streamError = null
  let reportBuffer = ''

  const stream = generateInterviewReportStream(
    interviewSessionId,
    {
      onMeta: (payload) => {
        if (payload?.streamTaskId) streamTaskId.value = payload.streamTaskId
      },
      onMessage: (payload) => {
        if (payload?.channel === 'report') {
          reportBuffer += payload.delta || ''
        }
      },
      onFinish: (payload) => {
        const result = payload?.result || {}
        Object.assign(props.payload, {
          interviewState: 'finished',
          report: reportBuffer || result.summary || '报告已生成',
          isLast: true
        })
      },
      onCancel: () => {},
      onError: (error) => {
        streamError = error
      }
    },
    randomTraceId()
  )

  try {
    streamAbort.value = stream.cancel
    streamAbortExpected = false
    await stream.start()
    if (streamError) throw streamError
  } catch (error) {
    if (streamAbortExpected || isAbortLikeError(error)) {
      streamAbortExpected = false
      return
    }
    Object.assign(props.payload, {
      interviewState: 'finished',
      report: reportBuffer + '\n\n**[报告生成失败]**'
    })
  } finally {
    finishing.value = false
    streamAbort.value = null
  }
}
</script>
