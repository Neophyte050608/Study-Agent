<template>
  <div class="bg-surface text-on-surface antialiased h-screen overflow-hidden flex flex-col">
    <!-- 全局错误提示 -->
    <div v-if="errorMsg" class="fixed top-20 right-8 z-50 bg-red-50 text-red-600 border border-red-200 rounded-lg px-4 py-2 shadow-sm flex items-center gap-2 transition-all">
      <span class="material-symbols-outlined text-sm">error</span>
      {{ errorMsg }}
    </div>

    <header class="fixed top-0 right-0 left-64 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-md flex items-center justify-between px-8 z-40 shadow-sm dark:shadow-none border-b border-slate-200 dark:border-slate-800">
      <h1 class="text-xl font-bold text-indigo-700 dark:text-indigo-400">AI 助手</h1>
    </header>

    <main class="ml-64 pt-16 flex-1 flex h-full bg-white dark:bg-slate-900">
      <!-- 左侧会话列表 -->
      <div class="w-72 border-r border-slate-200 dark:border-slate-800 flex flex-col bg-slate-50 dark:bg-slate-900/50 h-full">
        <div class="p-4 border-b border-slate-200 dark:border-slate-800">
          <button
            @click="handleCreateSession"
            class="w-full flex items-center justify-center gap-2 py-2.5 px-4 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl font-medium transition-colors"
          >
            <span class="material-symbols-outlined text-sm">add</span>
            新建对话
          </button>
        </div>
        <div class="flex-1 overflow-y-auto p-3 space-y-1">
          <div v-if="sessionsLoading" class="flex justify-center p-4">
            <span class="material-symbols-outlined animate-spin text-indigo-600">progress_activity</span>
          </div>
          <template v-else>
            <div
              v-for="session in sessions"
              :key="session.sessionId || session.id"
              @click="selectSession(session.sessionId || session.id)"
              class="group flex items-center justify-between px-3 py-2.5 rounded-lg cursor-pointer transition-colors"
              :class="isCurrentSession(session.sessionId || session.id) ? 'bg-white dark:bg-slate-800 shadow-sm text-indigo-700 dark:text-indigo-400' : 'text-slate-700 dark:text-slate-300 hover:bg-slate-200/50 dark:hover:bg-slate-800/50'"
            >
              <div class="flex items-center gap-3 overflow-hidden w-full">
                <span class="material-symbols-outlined text-lg opacity-70">chat_bubble</span>
                <div v-if="editingId === (session.sessionId || session.id)" class="flex-1 min-w-0">
                   <input
                     ref="renameInput"
                     v-model="editTitle"
                     @blur="saveRename(session.sessionId || session.id)"
                     @keyup.enter="saveRename(session.sessionId || session.id)"
                     class="w-full bg-transparent border-b border-indigo-500 focus:outline-none text-sm px-1"
                   />
                </div>
                <span v-else class="text-sm font-medium truncate flex-1">{{ session.title || '新对话' }}</span>
              </div>
              <div class="opacity-0 group-hover:opacity-100 flex items-center gap-1 transition-opacity shrink-0">
                <button @click.stop="startRename(session)" class="p-1 text-slate-400 hover:text-indigo-600 rounded">
                  <span class="material-symbols-outlined text-[16px]">edit</span>
                </button>
                <button @click.stop="handleDeleteSession(session.sessionId || session.id)" class="p-1 text-slate-400 hover:text-red-500 rounded">
                  <span class="material-symbols-outlined text-[16px]">delete</span>
                </button>
              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- 右侧聊天区域 -->
      <div class="flex-1 flex flex-col relative h-full">
        <template v-if="!currentSessionId">
          <!-- 空状态 -->
          <div class="flex-1 flex flex-col items-center justify-center text-slate-500 bg-slate-50/50 dark:bg-slate-900/50">
            <div class="w-16 h-16 bg-white dark:bg-slate-800 rounded-2xl shadow-sm flex items-center justify-center mb-4">
              <span class="material-symbols-outlined text-3xl text-indigo-400">forum</span>
            </div>
            <h3 class="text-lg font-medium text-slate-700 dark:text-slate-200 mb-2">欢迎来到 AI 助手</h3>
            <p class="text-sm text-slate-500">在左侧选择一个会话或新建对话开始</p>
            <button @click="handleCreateSession" class="mt-6 px-6 py-2 bg-indigo-600 text-white rounded-xl hover:bg-indigo-700 transition-colors">
              新建对话
            </button>
          </div>
        </template>
        <template v-else>
          <!-- 消息流 -->
          <div class="flex-1 overflow-y-auto p-6 space-y-6 scroll-smooth bg-slate-50/30 dark:bg-slate-900" ref="messagesContainer">
            <div v-if="messagesLoading" class="h-full flex flex-col items-center justify-center text-slate-400">
              <span class="material-symbols-outlined text-4xl mb-2 animate-spin text-indigo-400">progress_activity</span>
              <p>加载中...</p>
            </div>
            <div v-else-if="messages.length === 0 && !isStreaming" class="h-full flex flex-col items-center justify-center text-slate-400">
              <span class="material-symbols-outlined text-4xl mb-2 opacity-50">waving_hand</span>
              <p>发送消息以开始对话</p>
            </div>

            <template v-if="!messagesLoading">
              <div
                v-for="(msg, idx) in messages"
                :key="idx"
                class="flex"
                :class="msg.role === 'user' ? 'justify-end' : 'justify-start'"
              >
                <div v-if="msg.role !== 'user'" class="w-8 h-8 rounded-full bg-indigo-100 dark:bg-indigo-900 flex items-center justify-center mr-3 flex-shrink-0 mt-1">
                  <span class="material-symbols-outlined text-sm text-indigo-600 dark:text-indigo-400">robot_2</span>
                </div>
                <div
                  class="max-w-[80%] rounded-2xl px-5 py-3.5"
                  :class="msg.role === 'user' ? 'bg-indigo-600 text-white rounded-tr-sm shadow-sm' : 'bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-sm rounded-tl-sm text-slate-800 dark:text-slate-200'"
                >
                  <div v-if="msg.role === 'user'" class="whitespace-pre-wrap leading-relaxed">{{ msg.content }}</div>
                  <div v-else class="markdown-body text-[15px] leading-relaxed" v-html="renderMarkdown(msg.content)"></div>
                </div>
              </div>
              
              <div v-if="isStreaming" class="flex justify-start">
                 <div class="w-8 h-8 rounded-full bg-indigo-100 dark:bg-indigo-900 flex items-center justify-center mr-3 flex-shrink-0 mt-1">
                  <span class="material-symbols-outlined text-sm text-indigo-600 dark:text-indigo-400">robot_2</span>
                </div>
                <div class="max-w-[80%] rounded-2xl px-5 py-3.5 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-sm rounded-tl-sm text-slate-800 dark:text-slate-200">
                   <div class="markdown-body text-[15px] leading-relaxed">
                     <span v-html="renderMarkdown(streamingContent)"></span>
                     <span class="inline-block w-1.5 h-4 ml-1 bg-indigo-500 animate-pulse align-middle"></span>
                   </div>
                </div>
              </div>
            </template>
          </div>

          <!-- 底部输入框 -->
          <div class="p-4 bg-white dark:bg-slate-900 border-t border-slate-200 dark:border-slate-800 z-10">
            <div class="max-w-4xl mx-auto relative flex items-end gap-2 bg-slate-50 dark:bg-slate-800/50 border border-slate-300 dark:border-slate-700 rounded-xl p-2 focus-within:ring-2 focus-within:ring-indigo-500 transition-shadow shadow-sm">
              <textarea
                v-model="inputContent"
                @keydown.enter.ctrl.prevent="handleSend"
                rows="1"
                placeholder="输入消息，Ctrl + Enter 发送..."
                class="w-full max-h-32 bg-transparent border-none focus:ring-0 resize-none px-3 py-2 text-slate-700 dark:text-slate-200 placeholder-slate-400"
                style="min-height: 44px;"
              ></textarea>
              <div class="flex-shrink-0 mb-1 mr-1">
                <button
                  v-if="isStreaming"
                  @click="handleStop"
                  class="h-10 px-4 bg-slate-200 hover:bg-slate-300 dark:bg-slate-700 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 rounded-xl font-medium transition-colors flex items-center gap-1 shadow-sm"
                >
                  <span class="material-symbols-outlined text-sm">stop_circle</span>
                  停止
                </button>
                <button
                  v-else
                  @click="handleSend"
                  :disabled="!inputContent.trim()"
                  class="h-10 px-4 bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded-xl font-medium transition-colors flex items-center gap-1 shadow-sm"
                >
                  <span class="material-symbols-outlined text-sm">send</span>
                  发送
                </button>
              </div>
            </div>
          </div>
        </template>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { marked } from 'marked'
import {
  createChatSession,
  listChatSessions,
  renameChatSession,
  deleteChatSession,
  listChatMessages,
  streamChat,
  stopChatStream
} from '../api/chat'

const sessions = ref([])
const currentSessionId = ref(null)
const messages = ref([])
const inputContent = ref('')
const isStreaming = ref(false)
const streamingContent = ref('')
const streamTaskId = ref(null)
const streamHandle = ref(null)
const messagesContainer = ref(null)

const editingId = ref(null)
const editTitle = ref('')
const renameInput = ref(null)

const sessionsLoading = ref(false)
const messagesLoading = ref(false)
const errorMsg = ref('')

let errorTimeout = null
const showError = (msg) => {
  if (errorTimeout) clearTimeout(errorTimeout)
  errorMsg.value = msg
  errorTimeout = setTimeout(() => {
    errorMsg.value = ''
  }, 3000)
}

// Load sessions on mount
onMounted(async () => {
  await loadSessions()
})

const loadSessions = async () => {
  sessionsLoading.value = true
  try {
    const data = await listChatSessions()
    sessions.value = Array.isArray(data) ? data : []
  } catch (err) {
    console.error('Failed to load sessions', err)
    showError('加载会话列表失败')
  } finally {
    sessionsLoading.value = false
  }
}

const isCurrentSession = (id) => id === currentSessionId.value

const selectSession = async (id) => {
  if (editingId.value) return // Don't switch while editing
  currentSessionId.value = id
  messages.value = []
  isStreaming.value = false
  if (streamHandle.value) {
    streamHandle.value.cancel()
    streamHandle.value = null
  }
  messagesLoading.value = true
  try {
    const data = await listChatMessages(id, 200)
    messages.value = Array.isArray(data) ? data : (data.content || [])
    scrollToBottom()
  } catch (err) {
    console.error('Failed to load messages', err)
    showError('加载消息记录失败')
  } finally {
    messagesLoading.value = false
  }
}

const handleCreateSession = async () => {
  try {
    const session = await createChatSession('新对话')
    await loadSessions()
    if (session && (session.sessionId || session.id)) {
      selectSession(session.sessionId || session.id)
    } else if (sessions.value.length > 0) {
      selectSession(sessions.value[0].sessionId || sessions.value[0].id)
    }
  } catch (err) {
    console.error('Failed to create session', err)
    showError('新建对话失败')
  }
}

const startRename = (session) => {
  editingId.value = session.sessionId || session.id
  editTitle.value = session.title || '新对话'
  nextTick(() => {
    if (renameInput.value) {
      const input = Array.isArray(renameInput.value) ? renameInput.value.find(el => el) : renameInput.value
      input?.focus()
    }
  })
}

const saveRename = async (id) => {
  if (!editingId.value) return
  try {
    if (editTitle.value.trim()) {
      await renameChatSession(id, editTitle.value.trim())
      await loadSessions()
    }
  } catch (err) {
    console.error('Failed to rename session', err)
    showError('重命名失败')
  } finally {
    editingId.value = null
    editTitle.value = ''
  }
}

const handleDeleteSession = async (id) => {
  if (!confirm('确定要删除这个对话吗？')) return
  try {
    await deleteChatSession(id)
    await loadSessions()
    if (currentSessionId.value === id) {
      currentSessionId.value = null
      messages.value = []
    }
  } catch (err) {
    console.error('Failed to delete session', err)
    showError('删除会话失败')
  }
}

const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

function sanitizeHtml(html) {
  return html
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
    .replace(/\bon\w+\s*=\s*"[^"]*"/gi, '')
    .replace(/\bon\w+\s*=\s*'[^']*'/gi, '')
    .replace(/\bon\w+\s*=[^\s>]*/gi, '')
}

// 渲染 markdown，只在内容不为空时渲染
const renderMarkdown = (content) => {
  if (!content) return ''
  try {
    return sanitizeHtml(marked.parse(content, { breaks: true }))
  } catch (e) {
    return content
  }
}

const handleSend = async () => {
  const content = inputContent.value.trim()
  if (!content || isStreaming.value || !currentSessionId.value) return

  // Optimistic update
  messages.value.push({ role: 'user', content })
  inputContent.value = ''
  scrollToBottom()

  isStreaming.value = true
  streamingContent.value = ''
  streamTaskId.value = null

  const targetSessionId = currentSessionId.value

  try {
    streamHandle.value = streamChat(targetSessionId, content, {
      onMeta: (meta) => {
        if (meta && meta.taskId) streamTaskId.value = meta.taskId
        else if (meta && meta.streamTaskId) streamTaskId.value = meta.streamTaskId
        else if (typeof meta === 'string') streamTaskId.value = meta
      },
      onMessage: (msg) => {
        if (typeof msg === 'object' && msg !== null) {
          if (msg.channel === 'answer' && msg.delta) {
             streamingContent.value += msg.delta
          } else if (msg.delta) {
             streamingContent.value += msg.delta
          } else if (msg.content) {
             streamingContent.value += msg.content
          }
        } else if (typeof msg === 'string') {
          streamingContent.value += msg
        }
        scrollToBottom()
      },
      onFinish: () => {
        messages.value.push({ role: 'assistant', content: streamingContent.value })
        streamingContent.value = ''
        isStreaming.value = false
        streamHandle.value = null
        loadSessions() // Title might have been updated
        scrollToBottom()
      },
      onError: (err) => {
        console.error('Stream error:', err)
        messages.value.push({ role: 'assistant', content: streamingContent.value + '\n\n**[生成出错]**' })
        streamingContent.value = ''
        isStreaming.value = false
        streamHandle.value = null
        showError('流式生成出错')
        scrollToBottom()
      },
      onDone: () => {
        if (isStreaming.value) {
          messages.value.push({ role: 'assistant', content: streamingContent.value })
          streamingContent.value = ''
          isStreaming.value = false
          streamHandle.value = null
          loadSessions()
          scrollToBottom()
        }
      }
    })
    
    await streamHandle.value.start()
  } catch (err) {
    console.error('Send error:', err)
    isStreaming.value = false
    streamHandle.value = null
    showError('发送失败')
  }
}

const handleStop = async () => {
  if (streamHandle.value) {
    streamHandle.value.cancel()
    streamHandle.value = null
  }
  if (streamTaskId.value) {
    try {
      await stopChatStream(streamTaskId.value)
    } catch (e) {
      console.error('Stop stream error:', e)
    }
  }
  if (isStreaming.value) {
    messages.value.push({ role: 'assistant', content: streamingContent.value + '\n\n**[已停止]**' })
    streamingContent.value = ''
    isStreaming.value = false
  }
  scrollToBottom()
}

</script>

<style scoped>
/* 调整 markdown 渲染后的基础样式以适应暗色模式 */
:deep(.markdown-body p:last-child) {
  margin-bottom: 0;
}
:deep(.markdown-body pre) {
  background-color: #f8fafc;
  border-radius: 0.5rem;
  padding: 1rem;
  overflow-x: auto;
  margin: 0.5rem 0;
}
:deep(.dark .markdown-body pre) {
  background-color: #0f172a;
}
:deep(.markdown-body code) {
  background-color: #f1f5f9;
  padding: 0.2em 0.4em;
  border-radius: 0.25rem;
  font-size: 0.875em;
}
:deep(.dark .markdown-body code) {
  background-color: #1e293b;
}
:deep(.markdown-body p) {
  margin-top: 0;
  margin-bottom: 0.5rem;
}
:deep(.markdown-body ul), :deep(.markdown-body ol) {
  padding-left: 1.5rem;
  margin-bottom: 0.5rem;
}
:deep(.markdown-body blockquote) {
  border-left: 4px solid #cbd5e1;
  padding-left: 1rem;
  color: #64748b;
  margin: 0.5rem 0;
}
:deep(.dark .markdown-body blockquote) {
  border-left-color: #475569;
  color: #94a3b8;
}
</style>