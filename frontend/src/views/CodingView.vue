<template>
  <div class="bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100 min-h-screen relative">
    <!-- Header -->
    <header class="fixed top-0 right-0 z-40 bg-white/80 dark:bg-slate-950/80 backdrop-blur-xl shadow-sm border-b border-slate-100 flex justify-between items-center px-8 h-16 transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
        <div class="flex items-center gap-4">
            <h1 class="text-xl font-bold tracking-tight text-indigo-700 dark:text-indigo-400">算法刷题 <span class="text-slate-500 dark:text-slate-400 dark:text-slate-500 font-medium text-sm ml-2">/ 针对性生成并评测各类算法及场景题目</span></h1>
        </div>
    </header>

    <main class="flex-1 flex flex-col pt-16 transition-all duration-300 h-screen overflow-hidden" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
      <!-- Status Drawer -->
    <div v-show="sessionId" class="absolute top-24 right-8 z-30">
        <div class="glass-effect p-5 rounded-2xl shadow-xl border border-white/50 w-72">
            <div class="flex justify-between items-center mb-5">
                <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-widest">刷题状态</span>
                <div class="flex items-center gap-1.5">
                    <span class="text-[10px] font-bold text-emerald-500 uppercase">LIVE</span>
                    <span class="flex h-2 w-2 rounded-full bg-emerald-500 animate-pulse"></span>
                </div>
            </div>
            <div class="grid grid-cols-2 gap-3 mb-5">
                <div class="bg-indigo-50/50 dark:bg-indigo-950/30 p-4 rounded-xl border border-indigo-100/50 dark:border-indigo-900/50">
                    <p class="text-[10px] text-indigo-400 font-bold uppercase mb-1">当前进度</p>
                    <p class="text-xl font-black text-indigo-900 dark:text-indigo-100">{{ currentProgress || '-' }}</p>
                </div>
                <div class="bg-indigo-50/50 dark:bg-indigo-950/30 p-4 rounded-xl border border-indigo-100/50 dark:border-indigo-900/50">
                    <p class="text-[10px] text-indigo-400 font-bold uppercase mb-1">最后得分</p>
                    <p class="text-xl font-black text-indigo-900 dark:text-indigo-100">{{ currentScore || '-' }}</p>
                </div>
            </div>
            <button @click="endSession" class="w-full py-3 bg-error text-white rounded-xl text-sm font-bold flex items-center justify-center gap-2 hover:opacity-90 active:scale-[0.98] transition-all shadow-lg shadow-red-200 dark:shadow-red-900/30">
                <span class="material-symbols-outlined text-lg" data-icon="logout">logout</span>
                <span>结束本次刷题</span>
            </button>
        </div>
    </div>

    <!-- Chat Container -->
    <section class="flex-1 overflow-y-auto chat-scroll px-8 py-12 pb-10 flex flex-col gap-10" ref="chatContainer">
        <!-- AI Initial Message -->
        <div class="flex gap-4 max-w-3xl">
            <div class="flex-shrink-0 w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center shadow-lg shadow-indigo-100 dark:shadow-indigo-900/30">
                <span class="material-symbols-outlined text-white text-xl" data-icon="smart_toy">smart_toy</span>
            </div>
            <div class="space-y-2">
                <div class="bg-white/80 dark:bg-slate-950/80 backdrop-blur-sm p-6 rounded-2xl rounded-tl-none shadow-sm border border-slate-200/50 markdown-content">
                    <p class="text-slate-700 dark:text-slate-300 leading-relaxed">
                        你好！我是你的 AI 面试教练。我可以为你生成各种题型（算法、场景、选择、填空）的题目。
                        <br><br>
                        你可以直接告诉我你的需求，例如：<br>
                        - "我要刷3道Spring Boot的场景题"<br>
                        - "来一道中等难度的Redis选择题"<br>
                        - "直接开始刷题"（我会根据你的历史画像为你出题）
                    </p>
                </div>
            </div>
        </div>

        <template v-for="item in messages" :key="item.id">
            <!-- User Message -->
            <div v-if="item.role === 'user'" class="flex flex-row-reverse gap-4 max-w-3xl ml-auto">
                <div class="flex-shrink-0 w-10 h-10 rounded-xl bg-slate-200 dark:bg-slate-700 flex items-center justify-center">
                    <span class="material-symbols-outlined text-slate-500 dark:text-slate-400 dark:text-slate-500 text-xl" data-icon="person">person</span>
                </div>
                <div class="space-y-2 flex flex-col items-end">
                    <div class="bg-indigo-600 text-white p-6 rounded-2xl rounded-tr-none shadow-xl shadow-indigo-100 dark:shadow-indigo-900/30">
                        <p class="leading-relaxed font-medium whitespace-pre-wrap">{{ item.content }}</p>
                    </div>
                    <div class="flex items-center gap-2 px-1">
                        <span class="text-[10px] text-slate-400 dark:text-slate-500 font-medium">刚刚</span>
                        <span class="text-[10px] text-slate-300">•</span>
                        <span class="text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase tracking-wider">你</span>
                    </div>
                </div>
            </div>

            <!-- AI Message -->
            <div v-if="item.role === 'ai'" class="flex gap-4 max-w-3xl">
                <div class="flex-shrink-0 w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center shadow-lg shadow-indigo-100 dark:shadow-indigo-900/30">
                    <span class="material-symbols-outlined text-white text-xl" data-icon="smart_toy">smart_toy</span>
                </div>
                <div class="space-y-2 max-w-[calc(100%-3rem)]">
                    <div class="bg-white/80 dark:bg-slate-950/80 backdrop-blur-sm p-6 rounded-2xl rounded-tl-none shadow-sm border border-slate-200/50 markdown-content overflow-hidden" v-html="renderMarkdown(item.content)">
                    </div>
                    <div class="flex items-center gap-2 px-1">
                        <span class="text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase tracking-wider">AI 教练</span>
                        <span class="text-[10px] text-slate-300">•</span>
                        <span class="text-[10px] text-slate-400 dark:text-slate-500 font-medium">刚刚</span>
                    </div>
                </div>
            </div>
        </template>

        <!-- Thinking -->
        <div v-if="loading" class="flex gap-4 max-w-3xl mb-8">
            <div class="flex-shrink-0 w-10 h-10 rounded-xl bg-indigo-600/40 flex items-center justify-center">
                <span class="material-symbols-outlined text-white text-xl" data-icon="smart_toy">smart_toy</span>
            </div>
            <div class="bg-white/80 dark:bg-slate-950/80 backdrop-blur-sm px-5 py-3 rounded-2xl shadow-sm border border-slate-200/50 flex items-center gap-3">
                <div class="flex gap-1">
                    <div class="w-1.5 h-1.5 bg-indigo-400 rounded-full animate-bounce"></div>
                    <div class="w-1.5 h-1.5 bg-indigo-400 rounded-full animate-bounce" style="animation-delay: 0.2s"></div>
                    <div class="w-1.5 h-1.5 bg-indigo-400 rounded-full animate-bounce" style="animation-delay: 0.4s"></div>
                </div>
                <span class="text-xs font-bold text-slate-400 dark:text-slate-500 uppercase tracking-widest">AI 正在思考中...</span>
            </div>
        </div>
    </section>

    <!-- Bottom Input Area -->
    <section class="w-full px-8 pb-6 pt-4 bg-gradient-to-t from-slate-50 via-slate-50 to-transparent shrink-0">
        <div class="max-w-7xl mx-auto relative">
            <!-- Quick Action Pills -->
            <div v-show="!sessionId" class="flex gap-2 mb-4 overflow-x-auto no-scrollbar pb-1">
                <button class="whitespace-nowrap px-4 py-2.5 bg-indigo-50 dark:bg-indigo-950/50 text-indigo-700 dark:text-indigo-300 text-xs font-bold rounded-full border border-indigo-100 dark:border-indigo-900/50 hover:bg-indigo-100 dark:hover:bg-indigo-900 transition-colors flex items-center gap-2" @click="applyQuickAction('我要刷3道Spring Boot的场景题')">
                    <span class="material-symbols-outlined text-sm" data-icon="rocket_launch">rocket_launch</span>
                    3道 Spring Boot 场景题
                </button>
                <button class="whitespace-nowrap px-4 py-2.5 bg-indigo-50 dark:bg-indigo-950/50 text-indigo-700 dark:text-indigo-300 text-xs font-bold rounded-full border border-indigo-100 dark:border-indigo-900/50 hover:bg-indigo-100 dark:hover:bg-indigo-900 transition-colors flex items-center gap-2" @click="applyQuickAction('来一道简单的Redis选择题')">
                    <span class="material-symbols-outlined text-sm" data-icon="database">database</span>
                    Redis 选择题
                </button>
                <button class="whitespace-nowrap px-4 py-2.5 bg-indigo-600 text-white text-xs font-bold rounded-full hover:bg-indigo-700 transition-colors flex items-center gap-2 shadow-lg shadow-indigo-100 dark:shadow-indigo-900/30" @click="applyQuickAction('直接开始刷题')">
                    <span class="material-symbols-outlined text-sm" data-icon="play_arrow">play_arrow</span>
                    直接开始刷题
                </button>
            </div>
            
            <!-- Input Shell -->
            <div class="bg-white dark:bg-slate-900 rounded-2xl shadow-xl border border-slate-200/60 p-2 flex items-end gap-2 focus-within:ring-4 focus-within:ring-indigo-500/10 focus-within:border-indigo-500/30 transition-all group">
                <div class="flex-1 min-h-[48px] px-4 py-3">
                    <textarea class="w-full bg-transparent border-none focus:ring-0 outline-none p-0 text-slate-700 dark:text-slate-300 leading-relaxed resize-none max-h-48 scrollbar-hide" v-model="inputText" @keydown.enter.exact.prevent="sendMessage" placeholder="请输入你的回答... (Shift+Enter 换行)" rows="1" style="height: 24px" ref="textareaRef" @input="adjustTextareaHeight"></textarea>
                </div>
                <div class="flex items-center gap-1 pr-2 pb-1.5">
                    <button class="p-2.5 text-slate-400 dark:text-slate-500 hover:text-indigo-600 hover:bg-indigo-50 dark:hover:bg-indigo-950/50 dark:bg-indigo-950/50 transition-all rounded-xl">
                        <span class="material-symbols-outlined" data-icon="mic">mic</span>
                    </button>
                    <button class="w-10 h-10 rounded-xl bg-indigo-600 text-white flex items-center justify-center hover:bg-indigo-700 transition-all active:scale-95 shadow-lg shadow-indigo-200 dark:shadow-indigo-900/30" @click="sendMessage" :disabled="loading">
                        <span class="material-symbols-outlined text-xl" data-icon="send">send</span>
                    </button>
                </div>
            </div>
            <p class="text-center text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase tracking-widest mt-4">
                面试练习工作区 v2.4 • 企业级 LLM 核心驱动
            </p>
        </div>
    </section>
    </main>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { dispatchCodingChat } from '../api/coding'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})

let serial = 0
const loading = ref(false)
const sessionId = ref('')
const inputText = ref('')
const hint = ref('')
const currentProgress = ref('')
const currentScore = ref('')
const chatContainer = ref(null)
const textareaRef = ref(null)

const messages = ref([])

const renderMarkdown = (text) => {
  return DOMPurify.sanitize(marked.parse(text || ''))
}

const adjustTextareaHeight = () => {
  if (textareaRef.value) {
    textareaRef.value.style.height = '24px'
    textareaRef.value.style.height = Math.min(textareaRef.value.scrollHeight, 192) + 'px'
  }
}

const scrollToBottom = async () => {
  await nextTick()
  if (chatContainer.value) {
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight
  }
}

const appendMessage = (role, content) => {
  messages.value.push({ id: ++serial, role, content })
  scrollToBottom()
}

const applyQuickAction = (text) => {
  inputText.value = text
  adjustTextareaHeight()
}

const endSession = () => {
  sessionId.value = ''
  currentProgress.value = ''
  currentScore.value = ''
  appendMessage('ai', '已结束本次刷题会话。你可以随时输入新需求开启下一轮。')
}

const sendMessage = async () => {
  const text = inputText.value.trim()
  if (!text) return
  
  appendMessage('user', text)
  inputText.value = ''
  adjustTextareaHeight()
  loading.value = true
  scrollToBottom()
  
  try {
    const result = await dispatchCodingChat(sessionId.value, text)
    if (!result.success) {
      appendMessage('ai', `请求失败: ${result.message || 'unknown error'}`)
      return
    }
    const data = result.data || {}
    sessionId.value = data.sessionId || sessionId.value
    
    if (data.status === 'question_generated') {
      appendMessage('ai', data.question)
      currentProgress.value = data.progress || '-'
    } else if (data.status === 'evaluated') {
      let evalMsg = `**得分: ${data.score}/100**\n\n**评语:** ${data.feedback}`
      appendMessage('ai', evalMsg)
      currentScore.value = data.score
      
      if (data.isLast) {
        appendMessage('ai', "本次刷题已全部完成！辛苦了！你可以随时输入新需求开启下一轮。")
        sessionId.value = ""
      }
    } else if (data.status === 'completed') {
      appendMessage('ai', data.message)
      sessionId.value = ""
    } else {
      appendMessage('ai', data.message || "未知状态")
    }
  } catch (error) {
    appendMessage('ai', `发生错误: ${error.message || 'unknown error'}`)
  } finally {
    loading.value = false
  }
}
</script>
