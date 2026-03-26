<template>
  <div class="bg-surface text-on-surface antialiased min-h-screen">
    <!-- Top Navigation Bar -->
    <header class="fixed top-0 right-0 left-64 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-md flex items-center justify-between px-8 z-40 shadow-sm dark:shadow-none">
      <h1 class="text-xl font-bold text-indigo-700 dark:text-indigo-400">面试练习看板</h1>
    </header>

    <!-- Main Content Canvas -->
    <main class="ml-64 pt-24 pb-12 px-8 min-h-screen relative z-10">
      <!-- Case 1: Initial State (Configuration) -->
      <section v-if="!sessionId" class="max-w-4xl mx-auto mb-12">
        <div class="mb-8">
          <h2 class="text-3xl font-extrabold tracking-tight text-on-surface mb-2">开启新的叙事篇章</h2>
          <p class="text-on-surface-variant max-w-2xl">通过 AI 模拟真实面试场景，基于您的简历与目标职位生成针对性问题。通过深度反馈提升职业竞争力。</p>
        </div>
        <div class="bg-white rounded-xl p-8 shadow-sm">
          <div class="grid grid-cols-1 md:grid-cols-2 gap-8">
            <!-- Form Fields -->
            <div class="space-y-6">
              <div>
                <label class="block text-sm font-semibold mb-2 text-on-surface-variant">面试主题 (Topic)</label>
                <input class="w-full px-4 py-3 bg-slate-50 border-none rounded-lg focus:ring-2 focus:ring-primary transition-all text-sm" placeholder="例如：高级架构师, 产品经理" type="text" v-model="topic" :disabled="loading" />
              </div>
              <div>
                <label class="block text-sm font-semibold mb-2 text-on-surface-variant">题目数量 (Total Questions)</label>
                <select class="w-full px-4 py-3 bg-slate-50 border-none rounded-lg focus:ring-2 focus:ring-primary transition-all text-sm" v-model.number="totalQuestions" :disabled="loading">
                  <option :value="5">5 题 (快速模式)</option>
                  <option :value="10">10 题 (标准模式)</option>
                  <option :value="20">20 题 (深度挑战)</option>
                </select>
              </div>
            </div>
            <div class="space-y-6">
              <div>
                <label class="block text-sm font-semibold mb-2 text-on-surface-variant">简历路径 (Read Only)</label>
                <div class="flex items-center px-4 py-3 bg-slate-50 text-on-surface-variant rounded-lg cursor-not-allowed italic text-sm">
                  <span class="material-symbols-outlined text-sm mr-2" data-icon="attachment">attachment</span>
                  /user/documents/resumes/2024_backend_lead.pdf
                </div>
              </div>
              <div class="pt-2">
                <div class="p-4 bg-indigo-50 dark:bg-indigo-950/30 rounded-lg border-none">
                  <p class="text-[12px] text-indigo-700 dark:text-indigo-300 leading-relaxed">
                    <span class="font-bold">提示：</span> 系统将结合知识库中的“分布式架构”与“系统设计”模块生成高匹配度题目。
                  </p>
                </div>
              </div>
            </div>
          </div>
          <div class="mt-10 flex justify-center">
            <button @click="isStreaming ? stopStreaming() : start()" :disabled="loading && !isStreaming" class="px-10 py-4 bg-gradient-to-br from-indigo-600 to-indigo-800 text-white font-bold rounded-lg shadow-lg hover:opacity-90 active:scale-95 transition-all text-lg flex items-center space-x-3 disabled:opacity-60">
              <span>{{ isStreaming ? '停止生成' : (loading ? '启动中...' : '开始面试') }}</span>
              <span class="material-symbols-outlined" data-icon="rocket_launch">rocket_launch</span>
            </button>
          </div>
        </div>
      </section>

      <!-- Case 2: Interview State (Active Task) -->
      <section v-else class="max-w-5xl mx-auto mb-12">
        <div class="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <!-- Question Column -->
          <div class="lg:col-span-2 space-y-6">
            <div class="bg-white rounded-xl p-8 shadow-sm">
              <div class="flex items-center justify-between mb-6">
                <span class="px-3 py-1 bg-indigo-100 text-indigo-800 text-[11px] font-bold rounded-full uppercase tracking-wider">Question {{ String(currentQuestionIndex).padStart(2, '0') }} / {{ totalQuestions }}</span>
                <div class="flex items-center text-error font-mono font-bold text-xl">
                  <span class="material-symbols-outlined mr-2" data-icon="timer">timer</span>
                  <span>{{ timerDisplay }}</span>
                </div>
              </div>
              <h3 class="text-xl font-bold text-on-surface leading-snug mb-8 whitespace-pre-wrap">{{ currentQuestion }}</h3>
              <div class="space-y-4">
                <label class="block text-sm font-semibold text-on-surface-variant">手动输入回答</label>
                <textarea class="w-full p-6 bg-slate-50 border-none rounded-xl focus:ring-2 focus:ring-primary transition-all text-sm leading-relaxed" placeholder="请输入您的详细回答，建议包含核心逻辑与边界处理..." rows="10" v-model="answer" :disabled="loading || finished || isStreaming"></textarea>
              </div>
              <div class="mt-6 flex justify-end">
                <button type="button" class="mr-4 px-6 py-3 bg-slate-100 text-on-surface font-bold rounded-lg hover:bg-slate-200 transition-colors flex items-center space-x-2" :disabled="loading || finished || isStreaming" @click="toggleRecording">
                  <span>{{ isRecording ? '停止录音' : '语音回答' }}</span>
                  <span class="material-symbols-outlined" :class="isRecording ? 'text-error' : ''" data-icon="mic">{{ isRecording ? 'stop_circle' : 'mic' }}</span>
                </button>
                <button @click="isStreaming ? stopStreaming() : submit()" :disabled="(loading && !isStreaming) || finished || (!answer.trim() && !isStreaming)" class="px-6 py-3 bg-indigo-600 text-white font-bold rounded-lg hover:bg-indigo-700 transition-colors flex items-center space-x-2 disabled:opacity-60">
                  <span>{{ isStreaming ? '停止生成' : '提交回答' }}</span>
                  <span class="material-symbols-outlined" data-icon="send">send</span>
                </button>
              </div>
            </div>
          </div>

          <!-- Feedback Column -->
          <div class="space-y-6">
            <div class="bg-white/80 backdrop-blur-md rounded-xl p-6 border border-slate-100 shadow-sm h-full">
              <h4 class="text-sm font-bold text-indigo-700 mb-6 flex items-center">
                <span class="material-symbols-outlined mr-2" data-icon="insights">insights</span>
                实时反馈分析
              </h4>
              <!-- Score Display -->
              <div class="text-center mb-8">
                <div class="inline-flex items-center justify-center w-24 h-24 rounded-full border-4 border-indigo-600">
                  <span class="text-3xl font-extrabold text-indigo-700">{{ averageScoreDisplay }}</span>
                </div>
                <p class="mt-2 text-xs font-medium text-on-surface-variant uppercase tracking-widest">综合评分</p>
              </div>
              <!-- Feedback Points -->
              <div class="space-y-6">
                <div>
                  <h5 class="text-xs font-bold text-on-surface mb-2 flex items-center">
                    <span class="w-1.5 h-1.5 rounded-full bg-indigo-600 mr-2"></span>
                    核心反馈
                  </h5>
                  <p class="text-xs text-on-surface-variant leading-relaxed">
                    {{ feedbackText }}
                  </p>
                </div>
                <div v-if="finished">
                  <button @click="resetInterview" class="w-full mt-4 px-4 py-2 bg-slate-100 text-slate-700 font-bold rounded-lg hover:bg-slate-200 transition-colors text-sm">
                    返回配置页
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </main>
    
    <!-- Background Decoration Elements -->
    <div class="fixed top-0 right-0 z-0 w-1/2 h-full bg-gradient-to-l from-indigo-50/50 to-transparent pointer-events-none"></div>
    <div class="fixed bottom-0 left-64 z-0 w-96 h-96 bg-indigo-100/20 blur-[120px] rounded-full pointer-events-none"></div>
  </div>
</template>

<script setup>
import { computed, ref, onUnmounted } from 'vue'
import { generateInterviewReportStream, startInterviewStream, stopInterviewStream, submitInterviewAnswerStream } from '../api/interview'

const loading = ref(false)
const topic = ref('高级后端开发工程师')
const totalQuestions = ref(10)
const sessionId = ref('')
const currentQuestion = ref('')
const currentQuestionIndex = ref(0)
const answer = ref('')
const averageScore = ref(0)
const feedbackText = ref('等待开始面试')
const hint = ref('配置好方向后可立即开始')
const finished = ref(false)
const isStreaming = ref(false)
const streamTaskId = ref('')
const streamAbort = ref(null)

const averageScoreDisplay = computed(() => averageScore.value ? averageScore.value.toFixed(1) : '-')

const timerSeconds = ref(0)
let timerInterval = null

const timerDisplay = computed(() => {
  const m = String(Math.floor(timerSeconds.value / 60)).padStart(2, '0')
  const s = String(timerSeconds.value % 60).padStart(2, '0')
  return `${m}:${s}`
})

const startTimer = () => {
  clearInterval(timerInterval)
  timerSeconds.value = 0
  timerInterval = setInterval(() => {
    timerSeconds.value++
  }, 1000)
}

const stopTimer = () => {
  clearInterval(timerInterval)
}

onUnmounted(() => {
  if (streamTaskId.value) {
    stopInterviewStream(streamTaskId.value).catch(() => null)
  }
  streamAbort.value?.()
  stopTimer()
})

const isRecording = ref(false)
let recognition = null
const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition

if (SpeechRecognition) {
  recognition = new SpeechRecognition()
  recognition.lang = 'zh-CN'
  recognition.continuous = true
  recognition.interimResults = true

  recognition.onstart = () => {
    isRecording.value = true
  }

  recognition.onresult = (event) => {
    let currentFinal = ''
    for (let i = event.resultIndex; i < event.results.length; ++i) {
      if (event.results[i].isFinal) {
        currentFinal += event.results[i][0].transcript
      }
    }
    answer.value += currentFinal
  }

  recognition.onend = () => {
    isRecording.value = false
  }
}

const toggleRecording = () => {
  if (!recognition) {
    alert('您的浏览器不支持语音识别')
    return
  }
  if (isRecording.value) {
    recognition.stop()
  } else {
    recognition.start()
  }
}

const randomTraceId = () => `trace-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`

const runStream = async (stream) => {
  loading.value = true
  isStreaming.value = true
  streamTaskId.value = ''
  streamAbort.value = stream.cancel
  try {
    await stream.start()
  } finally {
    loading.value = false
    isStreaming.value = false
    streamTaskId.value = ''
    streamAbort.value = null
  }
}

const stopStreaming = async () => {
  if (!isStreaming.value) {
    return
  }
  if (streamTaskId.value) {
    try {
      await stopInterviewStream(streamTaskId.value)
    } catch {
      streamAbort.value?.()
    }
  } else {
    streamAbort.value?.()
  }
}

const start = async () => {
  hint.value = '正在启动面试...'
  currentQuestion.value = ''
  feedbackText.value = '请回答当前问题'
  let streamError = null
  const stream = startInterviewStream(
    topic.value || '高级后端开发工程师',
    totalQuestions.value || 10,
    {
      onMeta: (payload) => {
        if (payload?.streamTaskId) {
          streamTaskId.value = payload.streamTaskId
        }
      },
      onProgress: (payload) => {
        if (payload?.label) {
          hint.value = payload.label
        }
      },
      onMessage: (payload) => {
        if (payload?.channel === 'question') {
          currentQuestion.value += payload.delta || ''
        }
      },
      onFinish: (payload) => {
        const result = payload?.result || {}
        sessionId.value = result.id || sessionId.value
        currentQuestion.value = result.currentQuestion || currentQuestion.value
        currentQuestionIndex.value = result.currentQuestionIndex || 1
        answer.value = ''
        averageScore.value = result.averageScore || 0
        finished.value = false
        hint.value = '面试已开始'
        startTimer()
      },
      onCancel: () => {
        hint.value = '已停止生成'
      },
      onError: (error) => {
        streamError = error
      }
    },
    randomTraceId()
  )
  try {
    await runStream(stream)
    if (streamError) {
      throw streamError
    }
  } catch (error) {
    hint.value = `启动失败: ${error.message || 'unknown'}`
  }
}

const submit = async () => {
  if (!sessionId.value || !answer.value.trim()) {
    return
  }
  hint.value = '正在提交回答...'
  feedbackText.value = ''
  let streamError = null
  let nextQuestionBuffer = ''
  let shouldGenerateReport = false
  const stream = submitInterviewAnswerStream(
    sessionId.value,
    answer.value.trim(),
    {
      onMeta: (payload) => {
        if (payload?.streamTaskId) {
          streamTaskId.value = payload.streamTaskId
        }
      },
      onProgress: (payload) => {
        if (payload?.label) {
          hint.value = payload.label
        }
      },
      onMessage: (payload) => {
        const channel = payload?.channel
        const delta = payload?.delta || ''
        if (channel === 'feedback') {
          feedbackText.value += delta
        }
        if (channel === 'question') {
          nextQuestionBuffer += delta
        }
      },
      onFinish: (payload) => {
        const result = payload?.result || {}
        averageScore.value = result.averageScore || 0
        answer.value = ''
        if (result.finished) {
          shouldGenerateReport = true
          finished.value = true
          hint.value = '答题已完成，正在生成报告...'
          return
        }
        currentQuestionIndex.value += 1
        currentQuestion.value = nextQuestionBuffer || result.nextQuestion || ''
        hint.value = '已进入下一题'
        startTimer()
      },
      onCancel: () => {
        hint.value = '已停止生成'
      },
      onError: (error) => {
        streamError = error
      }
    },
    randomTraceId()
  )
  try {
    await runStream(stream)
    if (streamError) {
      throw streamError
    }
    if (shouldGenerateReport) {
      await finishNow()
    }
  } catch (error) {
    hint.value = `提交失败: ${error.message || 'unknown'}`
  }
}

const finishNow = async () => {
  if (!sessionId.value) {
    return
  }
  stopTimer()
  hint.value = '正在生成报告...'
  currentQuestion.value = '面试完成\n\n'
  let streamError = null
  const stream = generateInterviewReportStream(
    sessionId.value,
    {
      onMeta: (payload) => {
        if (payload?.streamTaskId) {
          streamTaskId.value = payload.streamTaskId
        }
      },
      onProgress: (payload) => {
        if (payload?.label) {
          hint.value = payload.label
        }
      },
      onMessage: (payload) => {
        if (payload?.channel === 'report') {
          currentQuestion.value += payload.delta || ''
        }
      },
      onFinish: (payload) => {
        const result = payload?.result || {}
        if (!currentQuestion.value.trim() || currentQuestion.value.trim() === '面试完成') {
          currentQuestion.value = `面试完成\n\n${result.summary || '报告已生成'}`
        }
        finished.value = true
        hint.value = '报告已生成'
      },
      onCancel: () => {
        hint.value = '已停止生成'
      },
      onError: (error) => {
        streamError = error
      }
    },
    randomTraceId()
  )
  try {
    await runStream(stream)
    if (streamError) {
      throw streamError
    }
  } catch (error) {
    hint.value = `报告生成失败: ${error.message || 'unknown'}`
  }
}

const resetInterview = () => {
  sessionId.value = ''
  currentQuestionIndex.value = 0
  answer.value = ''
  averageScore.value = 0
  finished.value = false
  isStreaming.value = false
  streamTaskId.value = ''
  streamAbort.value = null
  stopTimer()
  timerSeconds.value = 0
  feedbackText.value = '等待开始面试'
  hint.value = '配置好方向后可立即开始'
}
</script>
