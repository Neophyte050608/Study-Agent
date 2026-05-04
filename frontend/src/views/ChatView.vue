<template>
  <div class="bg-surface text-on-surface antialiased h-screen overflow-hidden flex flex-col">
    <!-- 全局错误提示 -->
    <div v-if="errorMsg" class="fixed top-20 right-8 z-50 bg-red-50 text-red-600 border border-red-200 rounded-lg px-4 py-2 shadow-sm flex items-center gap-2 transition-all">
      <span class="material-symbols-outlined text-sm">error</span>
      {{ errorMsg }}
    </div>

    <!-- 自定义删除确认弹窗 -->
    <div v-if="sessionToDelete" class="fixed inset-0 z-[100] flex items-center justify-center bg-slate-900/40 backdrop-blur-sm transition-all">
      <div class="bg-white dark:bg-slate-900 w-full max-w-sm rounded-3xl shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-200 border border-slate-100 dark:border-slate-800">
        <div class="p-7 relative">
          <button @click="cancelDelete" class="absolute top-4 right-4 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors">
            <span class="material-symbols-outlined text-xl">close</span>
          </button>
          
          <div class="flex items-center gap-3 mb-3">
            <span class="material-symbols-outlined text-3xl text-orange-500 drop-shadow-sm" style="font-variation-settings: 'FILL' 1;">warning</span>
            <h3 class="text-[21px] font-extrabold text-slate-800 dark:text-slate-100 tracking-tight">确认删除吗？</h3>
          </div>
          <p class="text-[19px] text-slate-500 dark:text-slate-400 font-medium leading-relaxed">删除后不可以恢复哦 🥺</p>
        </div>
        <div class="px-6 py-5 flex justify-end gap-3 mt-2">
          <button @click="cancelDelete" class="px-6 py-2 rounded-xl text-sm font-bold text-slate-600 dark:text-slate-300 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700 transition-all shadow-sm">
            取消
          </button>
          <button @click="confirmDelete" class="px-6 py-2 rounded-xl text-sm font-bold text-white bg-[#ff4d4f] hover:bg-[#ef4444] transition-all shadow-md shadow-red-500/20 active:scale-[0.97]">
            删除
          </button>
        </div>
      </div>
    </div>

    <header class="fixed top-0 right-0 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-md flex items-center justify-between px-8 z-40 shadow-sm dark:shadow-none border-b border-slate-200 dark:border-slate-800 transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
      <h1 class="text-xl font-bold tracking-tight text-indigo-700 dark:text-indigo-400">AI 助手 <span class="text-slate-500 font-medium text-sm ml-2">/ 随时随地为您提供智能对话与解答</span></h1>
    </header>

    <main class="pt-16 flex-1 flex h-full bg-white dark:bg-slate-900 transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
      <!-- 左侧会话列表 -->
      <div 
        class="border-r border-slate-200 dark:border-slate-800 flex flex-col bg-slate-50 dark:bg-slate-900/50 h-full transition-all duration-300"
        :class="historyCollapsed ? 'w-0 overflow-hidden border-none' : 'w-72'"
      >
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
      <div class="flex-1 flex flex-col relative h-full min-w-0 transition-all duration-300">
        <!-- 侧边栏控制和新建对话按钮 -->
        <div class="absolute top-4 left-4 z-20 flex items-center gap-2">
          <button 
            @click="historyCollapsed = !historyCollapsed"
            class="p-2 bg-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200 rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800 hover:shadow-sm transition-all"
            title="切换侧边栏"
          >
            <span class="material-symbols-outlined text-[36px]">{{ historyCollapsed ? 'dock_to_right' : 'dock_to_left' }}</span>
          </button>
          <button 
            @click="handleCreateSession"
            class="p-2 bg-transparent text-slate-500 hover:text-slate-800 dark:hover:text-slate-200 rounded-xl hover:bg-slate-100 dark:hover:bg-slate-800 hover:shadow-sm transition-all"
            title="新建对话"
          >
            <span class="material-symbols-outlined text-[36px]">edit_square</span>
          </button>
        </div>

        <!-- 顶部居中标题 -->
        <div v-if="currentSessionId && currentSession" class="absolute top-4 left-1/2 -translate-x-1/2 z-20 flex flex-col items-center">
          <div 
            v-if="editingId !== currentSessionId"
            class="group flex items-center gap-2 px-3 py-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 hover:shadow-sm transition-all cursor-pointer"
            @click="startRename(currentSession)"
          >
            <span class="text-[15px] font-bold text-slate-800 dark:text-slate-200">{{ currentSession.title || '新对话' }}</span>
            <span class="material-symbols-outlined text-[16px] text-slate-400 opacity-0 group-hover:opacity-100 transition-opacity">edit</span>
          </div>
          <div v-else class="flex items-center gap-2 px-3 py-1.5">
             <input
               ref="renameInput"
               v-model="editTitle"
               @blur="saveRename(currentSessionId)"
               @keyup.enter="saveRename(currentSessionId)"
               class="bg-transparent border-b border-indigo-500 focus:outline-none text-[15px] font-bold text-slate-800 dark:text-slate-200 text-center w-48"
             />
          </div>
          <span class="text-[11px] text-slate-400 mt-0.5">内容由 AI 生成</span>
        </div>

        <template v-if="!currentSessionId">
          <!-- 空状态 -->
          <div class="flex-1 flex flex-col items-center justify-center text-slate-500 bg-slate-50/50 dark:bg-slate-900/50 pb-20">
            <h3 class="text-3xl font-bold text-slate-800 dark:text-slate-200 mb-6">有什么我能帮你的吗？</h3>

            <!-- 快捷入口 -->
            <div class="flex gap-2 mb-8 flex-wrap justify-center">
              <button
                @click="inputContent = '我要刷3道Spring Boot的场景题'"
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
            
            <!-- 底部输入框 (空状态下) -->
            <div class="w-full max-w-4xl mx-auto px-6 relative">
              <AutocompleteDropdown
                :suggestions="acSuggestions"
                :visible="acVisible"
                :highlight-index="acHighlightIndex"
                :query="inputContent.trim()"
                @select="handleAcSelect"
                @hover="handleAcHover"
              />
              <div class="relative flex flex-col bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-700 rounded-2xl shadow-[0_8px_30px_rgb(0,0,0,0.08)] dark:shadow-[0_8px_30px_rgb(0,0,0,0.3)] focus-within:border-indigo-500/50 focus-within:ring-4 focus-within:ring-indigo-500/10 transition-all overflow-hidden">
                <textarea
                  v-model="inputContent"
                  @keydown="handleAcKeydown"
                  @keydown.enter.exact.prevent="handleSendFromEmpty"
                  rows="1"
                  placeholder="发送消息，Enter 发送，Shift + Enter 换行..."
                  class="w-full max-h-[200px] bg-transparent border-none focus:ring-0 resize-none px-5 pt-4 pb-12 text-[15px] text-slate-700 dark:text-slate-200 placeholder-slate-400"
                  style="min-height: 56px;"
                ></textarea>
                <div class="absolute bottom-3 right-3 flex items-center gap-2">
                  <button
                    @click="handleSendFromEmpty"
                    :disabled="!inputContent.trim()"
                    class="w-9 h-9 flex items-center justify-center bg-indigo-600 hover:bg-indigo-700 disabled:opacity-40 disabled:hover:bg-indigo-600 text-white rounded-xl transition-colors"
                  >
                    <span class="material-symbols-outlined text-[18px]">arrow_upward</span>
                  </button>
                </div>
              </div>
            </div>
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
                  class="max-w-[85%] rounded-2xl px-5 py-3.5"
                  :class="msg.role === 'user' ? 'bg-indigo-600 text-white rounded-tr-sm shadow-sm' : 'bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-sm rounded-tl-sm text-slate-800 dark:text-slate-200'"
                >
                  <div v-if="msg.role === 'user'" class="whitespace-pre-wrap leading-relaxed">{{ msg.content }}</div>
                  <div v-else class="markdown-body text-[15px] leading-relaxed">
                    <div v-html="renderMarkdown(msg.content)"></div>
                    <ImageCard v-for="(image, imageIdx) in msg.images || []" :key="`${idx}-${imageIdx}`" :image="image" />
                    <QuizCard v-if="msg.quizPayload" :payload="msg.quizPayload" />
                    <ScenarioCard
                      v-if="msg.scenarioPayload"
                      :payload="msg.scenarioPayload"
                      :message-id="msg.messageId"
                      :chat-session-id="currentSessionId"
                      @updated="handleStructuredMessageUpdated"
                    />
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
                    <div v-if="msg.generationStatus || msg.retrievalModeResolved || msg.localGraphUsed || msg.ragUsed || msg.fallbackReason || msg.routeLabel || msg.routeSource" class="mt-3 flex flex-wrap gap-2">
                      <span v-if="msg.generationStatus === 'RUNNING'" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-emerald-50 text-emerald-700 border border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-200 dark:border-emerald-900">
                        generating
                      </span>
                      <span v-else-if="msg.generationStatus === 'CANCELLED'" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-amber-50 text-amber-700 border border-amber-200 dark:bg-amber-950/40 dark:text-amber-200 dark:border-amber-900">
                        cancelled
                      </span>
                      <span v-else-if="msg.generationStatus === 'FAILED'" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-rose-50 text-rose-700 border border-rose-200 dark:bg-rose-950/40 dark:text-rose-200 dark:border-rose-900">
                        failed
                      </span>
                      <span v-if="msg.retrievalModeResolved" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-200">
                        mode: {{ formatRetrievalMode(msg.retrievalModeResolved) }}
                      </span>
                      <span v-if="msg.localGraphUsed" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-cyan-50 text-cyan-700 border border-cyan-200 dark:bg-cyan-950/40 dark:text-cyan-200 dark:border-cyan-900">
                        local graph
                      </span>
                      <span v-if="msg.ragUsed" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-indigo-50 text-indigo-700 border border-indigo-200 dark:bg-indigo-950/40 dark:text-indigo-200 dark:border-indigo-900">
                        rag
                      </span>
                      <span v-if="msg.fallbackReason" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-amber-50 text-amber-700 border border-amber-200 dark:bg-amber-950/40 dark:text-amber-200 dark:border-amber-900">
                        fallback: {{ msg.fallbackReason }}
                      </span>
                      <span v-if="msg.routeLabel" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-violet-50 text-violet-700 border border-violet-200 dark:bg-violet-950/40 dark:text-violet-200 dark:border-violet-900">
                        route: {{ formatRouteLabel(msg.routeLabel) }}
                      </span>
                      <span v-if="msg.routeSource" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-fuchsia-50 text-fuchsia-700 border border-fuchsia-200 dark:bg-fuchsia-950/40 dark:text-fuchsia-200 dark:border-fuchsia-900">
                        via: {{ formatRouteLabel(msg.routeSource) }}
                      </span>
                    </div>
                    <div class="mt-3 flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                      <button
                        class="inline-flex items-center gap-1 px-2 py-1 rounded-md border transition-colors"
                        :class="getReaction(msg, idx) === 'like'
                          ? 'border-emerald-300 text-emerald-700 bg-emerald-50 dark:border-emerald-700 dark:text-emerald-300 dark:bg-emerald-950/30'
                          : 'border-transparent hover:border-slate-200 hover:bg-slate-100 dark:hover:border-slate-700 dark:hover:bg-slate-700/60'"
                        @click="toggleReaction(msg, idx, 'like')"
                      >
                        <span class="material-symbols-outlined text-[15px]" :style="getReaction(msg, idx) === 'like' ? `font-variation-settings: 'FILL' 1` : ''">thumb_up</span>
                        点赞
                      </button>
                      <button
                        class="inline-flex items-center gap-1 px-2 py-1 rounded-md border transition-colors"
                        :class="getReaction(msg, idx) === 'dislike'
                          ? 'border-rose-300 text-rose-700 bg-rose-50 dark:border-rose-700 dark:text-rose-300 dark:bg-rose-950/30'
                          : 'border-transparent hover:border-slate-200 hover:bg-slate-100 dark:hover:border-slate-700 dark:hover:bg-slate-700/60'"
                        @click="toggleReaction(msg, idx, 'dislike')"
                      >
                        <span class="material-symbols-outlined text-[15px]" :style="getReaction(msg, idx) === 'dislike' ? `font-variation-settings: 'FILL' 1` : ''">thumb_down</span>
                        点踩
                      </button>
                      <button
                        class="inline-flex items-center gap-1 px-2 py-1 rounded-md border border-transparent hover:border-slate-200 hover:bg-slate-100 dark:hover:border-slate-700 dark:hover:bg-slate-700/60 transition-colors"
                        @click="copyMessage(msg, idx)"
                      >
                        <span class="material-symbols-outlined text-[15px]">{{ copiedState[getMessageActionKey(msg, idx)] ? 'check' : 'content_copy' }}</span>
                        {{ copiedState[getMessageActionKey(msg, idx)] ? '已复制' : '复制' }}
                      </button>
                    </div>
                  </div>
                </div>
              </div>
              
              <div v-if="isStreaming" class="flex justify-start">
                 <div class="w-8 h-8 rounded-full bg-indigo-100 dark:bg-indigo-900 flex items-center justify-center mr-3 flex-shrink-0 mt-1">
                  <span class="material-symbols-outlined text-sm text-indigo-600 dark:text-indigo-400">robot_2</span>
                </div>
                <div class="max-w-[85%] rounded-2xl px-5 py-3.5 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-sm rounded-tl-sm text-slate-800 dark:text-slate-200 min-h-[52px] flex items-center">
                   <div v-if="!streamingContent" class="flex items-center gap-1.5 px-2">
                     <div class="w-2.5 h-2.5 rounded-full bg-indigo-400 animate-[bounce_1s_infinite_-0.3s]"></div>
                     <div class="w-2.5 h-2.5 rounded-full bg-indigo-500 animate-[bounce_1s_infinite_-0.15s]"></div>
                     <div class="w-2.5 h-2.5 rounded-full bg-indigo-600 animate-[bounce_1s_infinite]"></div>
                   </div>
                   <div v-else class="markdown-body text-[15px] leading-relaxed">
                     <span v-html="renderMarkdown(streamingContent)"></span>
                     <ImageCard v-for="(image, imageIdx) in streamingImages" :key="`stream-${imageIdx}`" :image="image" />
                     <div v-if="streamingMeta.retrievalModeResolved || streamingMeta.localGraphUsed || streamingMeta.ragUsed || streamingMeta.fallbackReason || streamingMeta.routeLabel || streamingMeta.routeSource" class="mt-3 flex flex-wrap gap-2">
                       <span v-if="streamingMeta.retrievalModeResolved" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-200">
                         mode: {{ formatRetrievalMode(streamingMeta.retrievalModeResolved) }}
                       </span>
                       <span v-if="streamingMeta.localGraphUsed" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-cyan-50 text-cyan-700 border border-cyan-200 dark:bg-cyan-950/40 dark:text-cyan-200 dark:border-cyan-900">
                         local graph
                       </span>
                       <span v-if="streamingMeta.ragUsed" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-indigo-50 text-indigo-700 border border-indigo-200 dark:bg-indigo-950/40 dark:text-indigo-200 dark:border-indigo-900">
                         rag
                       </span>
                       <span v-if="streamingMeta.fallbackReason" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-amber-50 text-amber-700 border border-amber-200 dark:bg-amber-950/40 dark:text-amber-200 dark:border-amber-900">
                         fallback: {{ streamingMeta.fallbackReason }}
                       </span>
                       <span v-if="streamingMeta.routeLabel" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-violet-50 text-violet-700 border border-violet-200 dark:bg-violet-950/40 dark:text-violet-200 dark:border-violet-900">
                         route: {{ formatRouteLabel(streamingMeta.routeLabel) }}
                       </span>
                       <span v-if="streamingMeta.routeSource" class="px-2 py-0.5 rounded-md text-[10px] font-bold tracking-wide bg-fuchsia-50 text-fuchsia-700 border border-fuchsia-200 dark:bg-fuchsia-950/40 dark:text-fuchsia-200 dark:border-fuchsia-900">
                         via: {{ formatRouteLabel(streamingMeta.routeSource) }}
                       </span>
                     </div>
                     <span class="inline-block w-1.5 h-4 ml-1 bg-indigo-500 animate-pulse align-middle"></span>
                   </div>
                </div>
              </div>
            </template>
          </div>

          <!-- 底部输入框 -->
          <div class="p-6 bg-transparent z-10 w-full max-w-6xl mx-auto">
            <div class="mb-3 flex items-center justify-between">
              <div class="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                <span class="font-semibold">检索模式</span>
                <select v-model="selectedRetrievalMode" class="px-2 py-1 rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200">
                  <option value="RAG_ONLY">RAG_ONLY</option>
                  <option value="LOCAL_GRAPH_ONLY">LOCAL_GRAPH_ONLY</option>
                  <option value="HYBRID_FUSION">HYBRID_FUSION</option>
                </select>
              </div>
              <div class="text-[11px] text-slate-400 dark:text-slate-500">
                当前请求级模式
              </div>
            </div>
            <div class="relative">
              <AutocompleteDropdown
                :suggestions="acSuggestions"
                :visible="acVisible"
                :highlight-index="acHighlightIndex"
                :query="inputContent.trim()"
                @select="handleAcSelect"
                @hover="handleAcHover"
              />
              <div class="relative flex flex-col bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-700 rounded-2xl shadow-[0_8px_30px_rgb(0,0,0,0.08)] dark:shadow-[0_8px_30px_rgb(0,0,0,0.3)] focus-within:border-indigo-500/50 focus-within:ring-4 focus-within:ring-indigo-500/10 transition-all overflow-hidden">
                <textarea
                  v-model="inputContent"
                  @keydown="handleAcKeydown"
                  @keydown.enter.exact.prevent="handleSend"
                  rows="1"
                  placeholder="发送消息，Enter 发送，Shift + Enter 换行..."
                  class="w-full max-h-[200px] bg-transparent border-none focus:ring-0 resize-none px-5 pt-4 pb-12 text-[15px] text-slate-700 dark:text-slate-200 placeholder-slate-400"
                  style="min-height: 56px;"
                ></textarea>
                <div class="absolute bottom-3 right-3 flex items-center gap-2">
                  <button
                    v-if="isStreaming"
                    @click="handleStop"
                    class="h-9 px-4 bg-slate-100 hover:bg-slate-200 dark:bg-slate-700 dark:hover:bg-slate-600 text-slate-700 dark:text-slate-200 rounded-xl text-sm font-medium transition-colors flex items-center gap-1"
                  >
                    <span class="material-symbols-outlined text-[18px]">stop_circle</span>
                    停止
                  </button>
                  <button
                    v-else
                    @click="handleSend"
                    :disabled="!inputContent.trim()"
                    class="w-9 h-9 flex items-center justify-center bg-indigo-600 hover:bg-indigo-700 disabled:opacity-40 disabled:hover:bg-indigo-600 text-white rounded-xl transition-colors"
                  >
                    <span class="material-symbols-outlined text-[18px]">arrow_upward</span>
                  </button>
                </div>
              </div>
            </div>
            <div class="text-center mt-3 text-xs text-slate-400 dark:text-slate-500">
              AI 可能会犯错。请核查重要信息。
            </div>
          </div>
        </template>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import QuizCard from './chat/QuizCard.vue'
import ScenarioCard from './chat/ScenarioCard.vue'
import FillCard from './chat/FillCard.vue'
import InterviewCard from './chat/InterviewCard.vue'
import ImageCard from './chat/ImageCard.vue'
import AutocompleteDropdown from './chat/AutocompleteDropdown.vue'
import { fetchSuggestions, recordClick } from '../api/autocomplete'
import {
  createChatSession,
  listChatSessions,
  renameChatSession,
  deleteChatSession,
  listChatMessages,
  streamChat,
  stopChatStream
} from '../api/chat'

const props = defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})

const RETRIEVAL_MODE_STORAGE_KEY = 'chat.selectedRetrievalMode'
const RETRIEVAL_MODE_OPTIONS = ['RAG_ONLY', 'LOCAL_GRAPH_ONLY', 'HYBRID_FUSION']

const sessions = ref([])
const currentSessionId = ref(null)
const currentSession = computed(() => sessions.value.find(s => (s.sessionId || s.id) === currentSessionId.value))

// 内部会话历史侧边栏的折叠状态
const historyCollapsed = ref(false)

const messages = ref([])
const inputContent = ref('')
const isStreaming = ref(false)
const streamingContent = ref('')
const streamingImages = ref([])
const currentQuizPayload = ref(null)
const streamTaskId = ref(null)
const streamHandle = ref(null)
const messagesContainer = ref(null)
const selectedRetrievalMode = ref('RAG_ONLY')
const acSuggestions = ref([])
const acVisible = ref(false)
const acHighlightIndex = ref(-1)
const reactionState = ref({})
const copiedState = ref({})
let acDebounceTimer = null
let acAbortController = null
let acSuppressEnterOnce = false
let acSkipNextQuery = false
const streamingMeta = ref({
  retrievalModeRequested: '',
  retrievalModeResolved: '',
  fallbackReason: '',
  localGraphUsed: false,
  ragUsed: false,
  routeLabel: '',
  routeSource: ''
})

const editingId = ref(null)
const editTitle = ref('')
const renameInput = ref(null)

const sessionsLoading = ref(false)
const messagesLoading = ref(false)
const errorMsg = ref('')

const sessionToDelete = ref(null)
const streamAbortExpected = ref(false)
let runningMessagePollTimer = null

const isAbortLikeError = (err) => {
  if (!err) return false
  const name = typeof err?.name === 'string' ? err.name : ''
  const message = typeof err?.message === 'string' ? err.message.toLowerCase() : ''
  return name === 'AbortError' || message.includes('aborted') || message.includes('abort')
}

const cleanupStreamingState = () => {
  streamingContent.value = ''
  streamingImages.value = []
  currentQuizPayload.value = null
  streamingMeta.value = {
    retrievalModeRequested: '',
    retrievalModeResolved: '',
    fallbackReason: '',
    localGraphUsed: false,
    ragUsed: false,
    routeLabel: '',
    routeSource: ''
  }
  isStreaming.value = false
  streamHandle.value = null
}

const stopRunningMessagePolling = () => {
  if (runningMessagePollTimer) {
    clearInterval(runningMessagePollTimer)
    runningMessagePollTimer = null
  }
}

const hasRunningMessages = (items) => {
  return Array.isArray(items) && items.some(msg => msg?.generationStatus === 'RUNNING')
}

const normalizeImageItem = (item) => {
  if (!item || typeof item !== 'object') return null
  const source = item.value && typeof item.value === 'object' ? item.value : item
  return {
    ...source,
    position: item.position ?? source.position
  }
}

const normalizeMessages = (rawMessages) => {
  return rawMessages.map(msg => {
    const metadata = msg?.metadata && typeof msg.metadata === 'object' ? msg.metadata : {}
    if (msg.contentType === 'scenario_card' && msg.content) {
      try {
        const scenarioPayload = JSON.parse(msg.content)
        return { ...msg, ...metadata, scenarioPayload, content: '' }
      } catch (e) {
        return { ...msg, ...metadata }
      }
    }
    if (msg.contentType === 'fill_card' && msg.content) {
      try {
        const fillPayload = JSON.parse(msg.content)
        return { ...msg, ...metadata, fillPayload, content: '' }
      } catch (e) {
        return { ...msg, ...metadata }
      }
    }
    if (msg.contentType === 'interview_card' && msg.content) {
      try {
        const interviewPayload = JSON.parse(msg.content)
        return { ...msg, ...metadata, interviewPayload, content: '' }
      } catch (e) {
        return { ...msg, ...metadata }
      }
    }
    if (msg.contentType === 'quiz' && msg.content) {
      try {
        const quizPayload = JSON.parse(msg.content)
        return { ...msg, ...metadata, quizPayload, content: '' }
      } catch (e) {
        return { ...msg, ...metadata }
      }
    }
    if (msg.contentType === 'rich' && msg.content) {
      try {
        const richPayload = JSON.parse(msg.content)
        return {
          ...msg,
          ...metadata,
          content: richPayload?.text || '',
          images: Array.isArray(richPayload?.images)
            ? richPayload.images.map(normalizeImageItem).filter(Boolean)
            : []
        }
      } catch (e) {
        return { ...msg, ...metadata }
      }
    }
    return { ...msg, ...metadata }
  })
}

const handleStructuredMessageUpdated = async () => {
  await refreshCurrentMessages({ silent: true })
}

const refreshCurrentMessages = async ({ silent = false } = {}) => {
  if (!currentSessionId.value) return
  if (!silent) {
    messagesLoading.value = true
  }
  try {
    const data = await listChatMessages(currentSessionId.value, 200)
    const rawMessages = Array.isArray(data) ? data : (data.content || [])
    messages.value = normalizeMessages(rawMessages)
    if (!hasRunningMessages(messages.value)) {
      stopRunningMessagePolling()
    }
    scrollToBottom()
  } catch (err) {
    console.error('Failed to refresh messages', err)
    if (!silent) {
      showError('加载消息记录失败')
    }
  } finally {
    if (!silent) {
      messagesLoading.value = false
    }
  }
}

const ensureRunningMessagePolling = () => {
  if (!currentSessionId.value || isStreaming.value || !hasRunningMessages(messages.value) || runningMessagePollTimer) {
    return
  }
  runningMessagePollTimer = setInterval(async () => {
    if (!currentSessionId.value || isStreaming.value) {
      stopRunningMessagePolling()
      return
    }
    await refreshCurrentMessages({ silent: true })
  }, 2000)
}

const cancelActiveStream = ({ expected = false } = {}) => {
  if (expected) {
    streamAbortExpected.value = true
  }
  if (streamHandle.value) {
    streamHandle.value.cancel()
    streamHandle.value = null
  }
}

const handlePageLeave = () => {
  cancelActiveStream({ expected: true })
}

const handleDeleteSession = (id) => {
  sessionToDelete.value = id
}

const cancelDelete = () => {
  sessionToDelete.value = null
}

const confirmDelete = async () => {
  const id = sessionToDelete.value
  if (!id) return
  
  sessionToDelete.value = null
  
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

let errorTimeout = null
const showError = (msg) => {
  if (errorTimeout) clearTimeout(errorTimeout)
  errorMsg.value = msg
  errorTimeout = setTimeout(() => {
    errorMsg.value = ''
  }, 3000)
}

const normalizeRetrievalMode = (value) => {
  return RETRIEVAL_MODE_OPTIONS.includes(value) ? value : 'RAG_ONLY'
}

// Load sessions on mount
onMounted(async () => {
  document.addEventListener('click', handleClickOutside)
  window.addEventListener('pagehide', handlePageLeave)
  window.addEventListener('beforeunload', handlePageLeave)
  try {
    selectedRetrievalMode.value = normalizeRetrievalMode(localStorage.getItem(RETRIEVAL_MODE_STORAGE_KEY))
  } catch (err) {
    console.warn('Failed to restore retrieval mode from localStorage', err)
  }
  await loadSessions()
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside)
  window.removeEventListener('pagehide', handlePageLeave)
  window.removeEventListener('beforeunload', handlePageLeave)
  if (acDebounceTimer) clearTimeout(acDebounceTimer)
  cancelActiveStream({ expected: true })
  stopRunningMessagePolling()
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
  stopRunningMessagePolling()
  currentSessionId.value = id
  messages.value = []
  isStreaming.value = false
  cancelActiveStream({ expected: true })
  await refreshCurrentMessages()
  ensureRunningMessagePolling()
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

const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

// 渲染 markdown，只在内容不为空时渲染
const renderMarkdown = (content) => {
  if (!content) return ''
  try {
    return DOMPurify.sanitize(marked.parse(content, { breaks: true }))
  } catch (e) {
    return content
  }
}

const formatRetrievalMode = (mode) => {
  const normalized = typeof mode === 'string' ? mode.trim().toUpperCase() : ''
  if (!normalized) return 'UNKNOWN'
  return normalized.replaceAll('_', ' ')
}

const formatRouteLabel = (label) => {
  const normalized = typeof label === 'string' ? label.trim() : ''
  if (!normalized) return 'unknown'
  return normalized.replaceAll('-', ' ')
}

const getMessageActionKey = (msg, idx) => {
  if (msg?.messageId) return String(msg.messageId)
  if (msg?.id) return String(msg.id)
  return `msg-${idx}`
}

const getReaction = (msg, idx) => {
  return reactionState.value[getMessageActionKey(msg, idx)] || ''
}

const toggleReaction = (msg, idx, reaction) => {
  const key = getMessageActionKey(msg, idx)
  const prev = reactionState.value[key] || ''
  reactionState.value[key] = prev === reaction ? '' : reaction
}

const copyMessage = async (msg, idx) => {
  const text = typeof msg?.content === 'string' ? msg.content : ''
  if (!text.trim()) {
    showError('当前消息没有可复制文本')
    return
  }

  const key = getMessageActionKey(msg, idx)
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.focus()
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
    }
    copiedState.value[key] = true
    setTimeout(() => {
      copiedState.value[key] = false
    }, 1500)
  } catch (err) {
    console.error('Copy message failed', err)
    showError('复制失败')
  }
}

const handleSendFromEmpty = async () => {
  if (acSuppressEnterOnce) {
    acSuppressEnterOnce = false
    return
  }
  const content = inputContent.value.trim()
  if (!content || isStreaming.value) return
  acVisible.value = false
  acHighlightIndex.value = -1

  // Create new session first
  try {
    const session = await createChatSession(content.slice(0, 20) + (content.length > 20 ? '...' : ''))
    await loadSessions()
    
    if (session && (session.sessionId || session.id)) {
      currentSessionId.value = session.sessionId || session.id
      messages.value = []
      
      // We don't reset inputContent here because handleSend needs it
      // Delay handleSend slightly to allow Vue to update DOM with new session state
      nextTick(() => {
        handleSend()
      })
    }
  } catch (err) {
    console.error('Failed to create session from empty state', err)
    showError('新建对话失败')
  }
}

const handleSend = async () => {
  if (acSuppressEnterOnce) {
    acSuppressEnterOnce = false
    return
  }
  const content = inputContent.value.trim()
  if (!content || isStreaming.value || !currentSessionId.value) return
  acVisible.value = false
  acHighlightIndex.value = -1

  // 乐观更新：用户消息先入本地列表，提升输入反馈速度。
  messages.value.push({ role: 'user', content })
  inputContent.value = ''
  scrollToBottom()

  // 初始化本次流式状态容器。
  isStreaming.value = true
  streamAbortExpected.value = false
  streamingContent.value = ''
  streamingImages.value = []
  currentQuizPayload.value = null
  streamTaskId.value = null
  streamingMeta.value = {
    retrievalModeRequested: selectedRetrievalMode.value,
    retrievalModeResolved: '',
    fallbackReason: '',
    localGraphUsed: false,
    ragUsed: false,
    routeLabel: '',
    routeSource: ''
  }

  const targetSessionId = currentSessionId.value

  try {
    // 发起 SSE 请求；后端会持续推送 meta/progress/message/finish 等事件。
    streamHandle.value = streamChat(targetSessionId, content, {
      // meta 阶段下发 streamTaskId，供“停止生成”接口使用。
      onMeta: (meta) => {
        if (meta && meta.taskId) streamTaskId.value = meta.taskId
        else if (meta && meta.streamTaskId) streamTaskId.value = meta.streamTaskId
        else if (typeof meta === 'string') streamTaskId.value = meta
      },
      // 结构化卡片：测验题。
      onQuiz: (payload) => {
        currentQuizPayload.value = payload
      },
      // 图片事件：边收边渲染。
      onImage: (payload) => {
        if (payload) {
          streamingImages.value.push(payload)
          scrollToBottom()
        }
      },
      // 文本增量事件：不断拼接 delta。
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
      onFinish: (payload) => {
        // finish 时将缓存中的流式数据组装为最终 assistant 消息。
        const result = payload?.result || {}
        const finalQuizPayload = currentQuizPayload.value || result?.quizPayload || null
        const finalScenarioPayload = result?.scenarioPayload || null
        const finalFillPayload = result?.fillPayload || null
        const finalImages = streamingImages.value.length ? [...streamingImages.value] : (Array.isArray(result?.images) ? result.images : [])
        const finalInterviewPayload = result?.interviewPayload || null
        const finalContent = (finalQuizPayload || finalScenarioPayload || finalFillPayload || finalInterviewPayload) ? '' : (streamingContent.value || result?.content || '')
        streamingMeta.value = {
          retrievalModeRequested: result?.retrievalModeRequested || selectedRetrievalMode.value,
          retrievalModeResolved: result?.retrievalModeResolved || '',
          fallbackReason: result?.fallbackReason || '',
          localGraphUsed: !!result?.localGraphUsed,
          ragUsed: !!result?.ragUsed,
          routeLabel: result?.routeLabel || '',
          routeSource: result?.routeSource || ''
        }
        messages.value.push({
          role: 'assistant',
          messageId: result?.assistantMessageId || '',
          content: finalContent,
          images: finalImages,
          quizPayload: finalQuizPayload,
          scenarioPayload: finalScenarioPayload,
          fillPayload: finalFillPayload,
          interviewPayload: finalInterviewPayload,
          ...streamingMeta.value
        })
        streamingContent.value = ''
        streamingImages.value = []
        currentQuizPayload.value = null
        isStreaming.value = false
        streamHandle.value = null
        loadSessions() // Title might have been updated
        refreshCurrentMessages({ silent: true })
        scrollToBottom()
      },
      onError: (err) => {
        // 主动取消场景不提示错误；非预期错误展示失败消息。
        if (streamAbortExpected.value || isAbortLikeError(err)) {
          cleanupStreamingState()
          streamAbortExpected.value = false
          return
        }
        console.error('Stream error:', err)
        messages.value.push({ role: 'assistant', content: streamingContent.value + '\n\n**[生成出错]**' })
        cleanupStreamingState()
        showError('流式生成出错')
        scrollToBottom()
      },
      onDone: () => {
        // done 是协议层“流结束”兜底事件。
        if (isStreaming.value) {
          isStreaming.value = false
          streamHandle.value = null
        }
      }
    }, {
      retrievalMode: selectedRetrievalMode.value
    })
    
    await streamHandle.value.start()
  } catch (err) {
    if (streamAbortExpected.value || isAbortLikeError(err)) {
      cleanupStreamingState()
      streamAbortExpected.value = false
      return
    }
    console.error('Send error:', err)
    isStreaming.value = false
    streamHandle.value = null
    showError('发送失败')
  }
}

const handleStop = async () => {
  // 1) 先取消前端流读取。
  cancelActiveStream({ expected: true })
  // 2) 再请求后端停止任务，确保后端计算也终止。
  if (streamTaskId.value) {
    try {
      await stopChatStream(streamTaskId.value)
    } catch (e) {
      console.error('Stop stream error:', e)
    }
  }
  if (isStreaming.value) {
    // 3) 补一条“已停止”消息，避免界面停在半句。
    messages.value.push({ role: 'assistant', content: streamingContent.value + '\n\n**[已停止]**' })
    cleanupStreamingState()
  }
  scrollToBottom()
}

function handleAcSelect(item) {
  if (!item) return
  acSkipNextQuery = true
  inputContent.value = item.phrase || ''
  acSuggestions.value = []
  acVisible.value = false
  acHighlightIndex.value = -1
  if (item.id) recordClick(item.id).catch(() => {})
}

function handleAcHover(index) {
  acHighlightIndex.value = index
}

function handleAcKeydown(e) {
  if (!acVisible.value) return

  if (e.key === 'ArrowDown') {
    e.preventDefault()
    acHighlightIndex.value = Math.min(acHighlightIndex.value + 1, acSuggestions.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    acHighlightIndex.value = Math.max(acHighlightIndex.value - 1, -1)
  } else if (e.key === 'Enter' && acHighlightIndex.value >= 0) {
    e.preventDefault()
    acSuppressEnterOnce = true
    handleAcSelect(acSuggestions.value[acHighlightIndex.value])
  } else if (e.key === 'Escape') {
    acVisible.value = false
    acHighlightIndex.value = -1
  }
}

function handleClickOutside(e) {
  const target = e.target
  if (!(target instanceof Element)) {
    acVisible.value = false
    return
  }
  if (!target.closest('.autocomplete-dropdown') && target.tagName !== 'TEXTAREA') {
    acVisible.value = false
  }
}

watch(() => messages.value.length, () => {
  scrollToBottom()
})

watch(
  () => [currentSessionId.value, isStreaming.value, hasRunningMessages(messages.value)],
  ([sessionId, streaming, running]) => {
    if (!sessionId || streaming || !running) {
      stopRunningMessagePolling()
      return
    }
    ensureRunningMessagePolling()
  }
)

watch(selectedRetrievalMode, (value) => {
  const normalized = normalizeRetrievalMode(value)
  if (normalized !== value) {
    selectedRetrievalMode.value = normalized
    return
  }
  try {
    localStorage.setItem(RETRIEVAL_MODE_STORAGE_KEY, normalized)
  } catch (err) {
    console.warn('Failed to persist retrieval mode to localStorage', err)
  }
})

watch(inputContent, (val) => {
  if (acDebounceTimer) clearTimeout(acDebounceTimer)

  if (acSkipNextQuery) {
    acSkipNextQuery = false
    return
  }

  const trimmed = val.trim()
  if (trimmed.length < 2) {
    acSuggestions.value = []
    acVisible.value = false
    acHighlightIndex.value = -1
    return
  }

  if (acAbortController) {
    acAbortController.abort()
  }
  acAbortController = new AbortController()
  const currentController = acAbortController

  acDebounceTimer = setTimeout(async () => {
    try {
      const data = await fetchSuggestions(trimmed, 10)
      if (currentController !== acAbortController || currentController.signal.aborted) {
        return
      }
      acSuggestions.value = Array.isArray(data) ? data : []
      acVisible.value = acSuggestions.value.length > 0
      acHighlightIndex.value = -1
    } catch (e) {
      if (currentController !== acAbortController || currentController.signal.aborted) {
        return
      }
      acSuggestions.value = []
      acVisible.value = false
    }
  }, 300)
})

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
