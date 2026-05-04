# Chat 统一：合并面试刷题子页面到主聊天 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 删除 InterviewView.vue 和 CodingView.vue，将计时器/分数环/语音输入/快捷入口统一到 ChatView，净减少 ~500 行代码

**Architecture:** 新增 InterviewCard 组件嵌入 ChatView 消息流，遵循现有 ScenarioCard/FillCard 模式（组件自管 API 调用，emit `updated` 触发消息刷新）。路由清理并做旧 URL 重定向兼容。

**Tech Stack:** Vue 3 SFC + marked + DOMPurify + Web Speech API + SSE streaming

---

### Task 1: 创建 InterviewCard.vue 组件

**Files:**
- Create: `frontend/src/views/chat/InterviewCard.vue`

- [ ] **Step 1: 写出完整组件文件**

```vue
<template>
  <div class="bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-sm overflow-hidden my-4">
    <!-- 头部：进度 + 计时器 -->
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
      <!-- 题目 -->
      <div
        class="markdown-body text-[15px] leading-relaxed text-slate-800 dark:text-slate-200"
        v-html="renderMarkdown(payload.question || '')"
      ></div>

      <!-- asking: 答题输入 -->
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

      <!-- submitting: 加载态（保留题目，禁用输入） -->
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

      <!-- feedback: 评分 + 反馈 -->
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

      <!-- finished: 最终报告 -->
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

// 计时器
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

// 根据 payload 状态恢复/启停计时器
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
  stopTimer()
  if (streamTaskId.value) {
    stopInterviewStream(streamTaskId.value).catch(() => {})
  }
  streamAbort.value?.()
})

// 语音识别
const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition
const speechSupported = !!SpeechRecognition

let recognition = null
if (speechSupported) {
  recognition = new SpeechRecognition()
  recognition.lang = 'zh-CN'
  recognition.continuous = true
  recognition.interimResults = true

  recognition.onstart = () => {
    recording.value = true
  }

  recognition.onresult = (event) => {
    let final = ''
    for (let i = event.resultIndex; i < event.results.length; i++) {
      if (event.results[i].isFinal) {
        final += event.results[i][0].transcript
      }
    }
    draftAnswer.value += final
  }

  recognition.onend = () => {
    recording.value = false
  }

  recognition.onerror = () => {
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
          interviewState: result.finished ? 'feedback' : 'feedback',
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
    emit('updated')
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
    emit('updated')
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
    const handle = {
      start: stream.start,
      cancel: stream.cancel
    }
    streamAbort.value = stream.cancel
    streamAbortExpected = false
    await stream.start()
    if (streamError) throw streamError
    emit('updated')
  } catch (error) {
    if (streamAbortExpected || isAbortLikeError(error)) {
      streamAbortExpected = false
      return
    }
    Object.assign(props.payload, {
      interviewState: 'finished',
      report: reportBuffer + '\n\n**[报告生成失败]**'
    })
    emit('updated')
  } finally {
    finishing.value = false
    streamAbort.value = null
  }
}
</script>
```

- [ ] **Step 2: 验证编译**

Run: `cd frontend && npm run build:spring`

Expected: Build succeeds (component has no consumers yet, won't cause errors)

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/chat/InterviewCard.vue
git commit -m "feat: 新增 InterviewCard 组件 — 嵌入式面试卡片"
```

---

### Task 2: ChatView — normalizeMessages 增加 interview_card 解析

**Files:**
- Modify: `frontend/src/views/ChatView.vue` (in `normalizeMessages` function)

- [ ] **Step 1: 在 normalizeMessages 中添加 interview_card 处理**

Find the existing `if (msg.contentType === 'fill_card' ...)` block in `normalizeMessages` (around line 515), and add after it:

```js
if (msg.contentType === 'interview_card' && msg.content) {
  try {
    const interviewPayload = JSON.parse(msg.content)
    return { ...msg, ...metadata, interviewPayload, content: '' }
  } catch (e) {
    return { ...msg, ...metadata }
  }
}
```

**Exact edit:** In `normalizeMessages`, after the `fill_card` block closing `}` and before the `quiz` block:

Old:
```
    if (msg.contentType === 'quiz' && msg.content) {
```

New:
```
    if (msg.contentType === 'interview_card' && msg.content) {
      try {
        const interviewPayload = JSON.parse(msg.content)
        return { ...msg, ...metadata, interviewPayload, content: '' }
      } catch (e) {
        return { ...msg, ...metadata }
      }
    }
    if (msg.contentType === 'quiz' && msg.content) {
```

- [ ] **Step 2: 验证编译**

Run: `cd frontend && npm run build:spring`

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/ChatView.vue
git commit -m "feat: ChatView normalizeMessages 支持 interview_card 解析"
```

---

### Task 3: ChatView — 模板添加 InterviewCard 渲染

**Files:**
- Modify: `frontend/src/views/ChatView.vue` (template section)

- [ ] **Step 1: 在消息流中渲染 InterviewCard**

Find the existing `FillCard` usage in `<template>` (line 210-215), and add InterviewCard after it:

Old:
```
                    <FillCard
                      v-if="msg.fillPayload"
                      :payload="msg.fillPayload"
                      :message-id="msg.messageId"
                      :chat-session-id="currentSessionId"
                      @updated="handleStructuredMessageUpdated"
                    />
```

New:
```
                    <FillCard
                      v-if="msg.fillPayload"
                      :payload="msg.fillPayload"
                      :message-id="msg.messageId"
                      :chat-session-id="currentSessionId"
                      @updated="handleStructuredMessageUpdated"
                    />
                    <InterviewCard
                      v-if="msg.interviewPayload"
                      :payload="msg.interviewPayload"
                      :message-id="msg.messageId"
                      :chat-session-id="currentSessionId"
                      @updated="handleStructuredMessageUpdated"
                    />
```

- [ ] **Step 2: 在 script setup 中添加 import**

Add import after existing card imports:

```
import InterviewCard from './chat/InterviewCard.vue'
```

- [ ] **Step 3: 验证编译**

Run: `cd frontend && npm run build:spring`

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/ChatView.vue
git commit -m "feat: ChatView 消息流渲染 InterviewCard"
```

---

### Task 4: ChatView — 空状态添加快捷入口 pills

**Files:**
- Modify: `frontend/src/views/ChatView.vue` (template, empty state section)

- [ ] **Step 1: 在空状态标题下方添加 pills**

Find the empty state template (line 136):
```html
<h3 class="text-3xl font-bold text-slate-800 dark:text-slate-200 mb-8">有什么我能帮你的吗？</h3>
```

Replace the line `<h3 ...>有什么我能帮你的吗？</h3>` with:

```html
            <h3 class="text-3xl font-bold text-slate-800 dark:text-slate-200 mb-6">有什么我能帮你的吗？</h3>

            <!-- 快捷入口 -->
            <div class="flex gap-2 mb-8 flex-wrap justify-center">
              <button
                @click="inputContent = '我要刷3道Spring Boot的场景题'; nextTick(() => $refs.emptyInput?.focus())"
                class="whitespace-nowrap px-4 py-2.5 bg-indigo-50 dark:bg-indigo-950/50 text-indigo-700 dark:text-indigo-300 text-xs font-bold rounded-full border border-indigo-100 dark:border-indigo-900/50 hover:bg-indigo-100 dark:hover:bg-indigo-900 transition-colors flex items-center gap-2"
              >
                <span class="material-symbols-outlined text-sm">rocket_launch</span>
                3道 Spring Boot 场景题
              </button>
              <button
                @click="inputContent = '来一道中等难度的Redis选择题'"
                class="whitespace-nowrap px-4 py-2.5 bg-indigo-50 dark:bg-indigo-950/50 text-indigo-700 dark:text-indigo-300 text-xs font-bold rounded-full border border-indigo-100 dark:border-indigo-900/50 hover:bg-indigo-100 dark:hover:bg-indigo-900 transition-colors flex items-center gap-2"
              >
                <span class="material-symbols-outlined text-sm">database</span>
                Redis 选择题
              </button>
              <button
                @click="inputContent = '开始一场高级后端工程师面试'"
                class="whitespace-nowrap px-4 py-2.5 bg-indigo-600 text-white text-xs font-bold rounded-full hover:bg-indigo-700 transition-colors flex items-center gap-2 shadow-lg shadow-indigo-100 dark:shadow-indigo-900/30"
              >
                <span class="material-symbols-outlined text-sm">psychology</span>
                开始面试
              </button>
              <button
                @click="inputContent = '直接开始刷题'"
                class="whitespace-nowrap px-4 py-2.5 bg-indigo-50 dark:bg-indigo-950/50 text-indigo-700 dark:text-indigo-300 text-xs font-bold rounded-full border border-indigo-100 dark:border-indigo-900/50 hover:bg-indigo-100 dark:hover:bg-indigo-900 transition-colors flex items-center gap-2"
              >
                <span class="material-symbols-outlined text-sm">play_arrow</span>
                直接开始刷题
              </button>
            </div>
```

- [ ] **Step 2: 验证编译**

Run: `cd frontend && npm run build:spring`

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/ChatView.vue
git commit -m "feat: ChatView 空状态添加刷题/面试快捷入口 pills"
```

---

### Task 5: ChatView — onFinish 处理 interview 结构化消息

**Files:**
- Modify: `frontend/src/views/ChatView.vue` (onFinish handler in handleSend)

- [ ] **Step 1: 在 handleSend 的 onFinish 中处理 interviewPayload**

Find `onFinish` in `handleSend` (around line 916-930). In the `messages.value.push(...)` call, the spread of `streamingMeta.value` already pushes known metadata. The backend will include `interviewPayload` in the finish result if the message is interview type.

Add interview payload extraction before the push. Find:
```js
        messages.value.push({ 
          role: 'assistant', 
          messageId: result?.assistantMessageId || '',
          content: finalContent,
          images: finalImages,
          quizPayload: finalQuizPayload,
          scenarioPayload: finalScenarioPayload,
          fillPayload: finalFillPayload,
          ...streamingMeta.value
        })
```

Replace with:
```js
        const finalInterviewPayload = result?.interviewPayload || null
        const finalContentWithFallback = (finalQuizPayload || finalScenarioPayload || finalFillPayload || finalInterviewPayload)
          ? ''
          : (streamingContent.value || result?.content || '')
        messages.value.push({ 
          role: 'assistant', 
          messageId: result?.assistantMessageId || '',
          content: finalContentWithFallback,
          images: finalImages,
          quizPayload: finalQuizPayload,
          scenarioPayload: finalScenarioPayload,
          fillPayload: finalFillPayload,
          interviewPayload: finalInterviewPayload,
          ...streamingMeta.value
        })
```

Wait — this should use the already-computed `finalContent` variable. Let me redo this more surgically.

The existing code computes `finalContent` as:
```js
const finalContent = (finalQuizPayload || finalScenarioPayload || finalFillPayload) ? '' : (streamingContent.value || result?.content || '')
```

Change this line to also include `finalInterviewPayload`:
```js
const finalInterviewPayload = result?.interviewPayload || null
const finalContent = (finalQuizPayload || finalScenarioPayload || finalFillPayload || finalInterviewPayload) ? '' : (streamingContent.value || result?.content || '')
```

And add `interviewPayload: finalInterviewPayload` to the push object.

- [ ] **Step 2: 验证编译**

Run: `cd frontend && npm run build:spring`

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/ChatView.vue
git commit -m "feat: ChatView onFinish 支持 interview 结构化消息"
```

---

### Task 6: 路由 — 清理旧路由并做重定向兼容

**Files:**
- Modify: `frontend/src/router/index.js`

- [ ] **Step 1: 删除路由定义和 import**

Remove these import lines:
```js
import InterviewView from '../views/InterviewView.vue'
import CodingView from '../views/CodingView.vue'
```

Remove these route definitions:
```js
      {
        path: 'interview',
        name: 'interview',
        component: InterviewView,
        alias: ['interview.html']
      },
```
and
```js
      {
        path: 'coding',
        name: 'coding',
        component: CodingView,
        alias: ['practice', 'practice.html', 'coding.html']
      },
```

- [ ] **Step 2: 添加重定向路由（兼容旧 URL）**

In the same position in the routes array, add redirects:

```js
      {
        path: 'interview',
        redirect: '/chat'
      },
      {
        path: 'interview.html',
        redirect: '/chat'
      },
      {
        path: 'coding',
        redirect: '/chat'
      },
      {
        path: 'practice',
        redirect: '/chat'
      },
      {
        path: 'practice.html',
        redirect: '/chat'
      },
      {
        path: 'coding.html',
        redirect: '/chat'
      },
```

- [ ] **Step 3: 验证编译**

Run: `cd frontend && npm run build:spring`

- [ ] **Step 4: Commit**

```bash
git add frontend/src/router/index.js
git commit -m "refactor: 移除 Interview/Coding 路由，重定向到 /chat"
```

---

### Task 7: AppShell — sidebar 菜单映射更新

**Files:**
- Modify: `frontend/src/shell/AppShell.vue` (in `toSpaPath` function)

- [ ] **Step 1: 更新 toSpaPath 映射**

Find the switch cases for interview and coding in `toSpaPath()` (line 106-121):

Old:
```js
      case '/interview':
      case '/interview.html':
        return '/interview'
      ...
      case '/practice':
      case '/practice.html':
      case '/coding':
      case '/coding.html':
        return '/coding'
```

Replace with:
```js
      case '/interview':
      case '/interview.html':
        return '/chat'
      ...
      case '/practice':
      case '/practice.html':
      case '/coding':
      case '/coding.html':
        return '/chat'
```

- [ ] **Step 2: 验证编译**

Run: `cd frontend && npm run build:spring`

- [ ] **Step 3: Commit**

```bash
git add frontend/src/shell/AppShell.vue
git commit -m "refactor: AppShell sidebar 面试/刷题菜单项指向 /chat"
```

---

### Task 8: 删除旧 View 文件

**Files:**
- Delete: `frontend/src/views/InterviewView.vue`
- Delete: `frontend/src/views/CodingView.vue`

- [ ] **Step 1: 删除文件**

```bash
git rm frontend/src/views/InterviewView.vue frontend/src/views/CodingView.vue
```

- [ ] **Step 2: 验证编译**

Run: `cd frontend && npm run build:spring`

Expected: Build succeeds (imports already removed in Task 6)

- [ ] **Step 3: Commit**

```bash
git commit -m "refactor: 删除 InterviewView.vue 和 CodingView.vue"
```

---

### Task 9: 全量验证

- [ ] **Step 1: 前端构建**

Run: `cd frontend && npm run build:spring`
Expected: Build succeeds, no errors

- [ ] **Step 2: 后端编译**

Run: `./mvnw -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: 运行全部测试**

Run: `./mvnw test`
Expected: All 132 tests pass

- [ ] **Step 4: 确认无残留引用**

```bash
grep -r "InterviewView\|CodingView" frontend/src/ --include="*.vue" --include="*.js" --include="*.ts"
```
Expected: No output

- [ ] **Step 5: 最终 commit（如有遗漏修复）**
