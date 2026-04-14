<template>
  <div class="bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100 antialiased min-h-screen">
    <header
      class="fixed top-0 right-0 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-xl border-b border-slate-100 dark:border-slate-800 flex justify-between items-center px-8 z-40 transition-all duration-300"
      :class="sidebarCollapsed ? 'left-20' : 'left-64'"
    >
      <div class="flex items-center gap-4">
        <h1 class="text-xl font-bold tracking-tight text-indigo-700 dark:text-indigo-400">
          模型提供商管理
          <span class="text-slate-500 dark:text-slate-400 font-medium text-sm ml-2">/ 多模型候补与健康监控</span>
        </h1>
      </div>
      <button
        class="bg-indigo-600 text-white px-5 py-2 rounded-lg text-sm font-semibold flex items-center gap-2 hover:bg-indigo-700 transition-all active:scale-[0.98] disabled:opacity-60"
        @click="openCreateModal"
        :disabled="loading"
      >
        <span class="material-symbols-outlined text-sm">add</span>
        新增模型
      </button>
    </header>

    <main
      class="pt-24 p-8 min-h-screen bg-slate-50 dark:bg-slate-950 transition-all duration-300"
      :class="sidebarCollapsed ? 'ml-20' : 'ml-64'"
    >
      <div class="max-w-7xl mx-auto space-y-6">
        <section class="grid grid-cols-1 xl:grid-cols-5 gap-4">
          <div class="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-800 p-5">
            <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">候选模型数</p>
            <p class="mt-3 text-3xl font-bold text-slate-900 dark:text-slate-100">{{ candidates.length }}</p>
            <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">当前已配置的模型候选总数</p>
          </div>
          <div class="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-800 p-5">
            <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">默认模型</p>
            <p class="mt-3 text-lg font-bold text-slate-900 dark:text-slate-100 break-all">{{ stats?.defaultModel || '-' }}</p>
            <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">通用路由默认优先模型</p>
          </div>
          <div class="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-800 p-5">
            <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">深度推理模型</p>
            <p class="mt-3 text-lg font-bold text-slate-900 dark:text-slate-100 break-all">{{ stats?.deepThinkingModel || '-' }}</p>
            <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">用于 THINKING 路由的首选模型</p>
          </div>
          <div class="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-800 p-5">
            <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">召回模型</p>
            <p class="mt-3 text-lg font-bold text-slate-900 dark:text-slate-100 break-all">{{ stats?.retrievalModel || '-' }}</p>
            <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">用于 RETRIEVAL 路由的首选模型</p>
          </div>
          <div class="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-800 p-5">
            <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">回退次数</p>
            <p class="mt-3 text-3xl font-bold text-slate-900 dark:text-slate-100">{{ stats?.runtime?.routeFallbackCount ?? 0 }}</p>
            <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">运行时累计路由回退计数</p>
          </div>
        </section>

        <p v-if="hint" class="text-sm font-medium" :class="hintTypeClass">{{ hint }}</p>

        <div v-if="loading && candidates.length === 0" class="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-slate-200 dark:border-slate-800 p-10 text-center text-slate-500 dark:text-slate-400">
          <span class="material-symbols-outlined animate-spin text-4xl">autorenew</span>
          <p class="mt-4">正在加载模型配置...</p>
        </div>

        <div v-else-if="sortedCandidates.length === 0" class="bg-white dark:bg-slate-900 rounded-xl shadow-sm border border-dashed border-slate-300 dark:border-slate-700 p-10 text-center">
          <span class="material-symbols-outlined text-5xl text-slate-300 dark:text-slate-600">hub</span>
          <p class="mt-4 text-lg font-semibold text-slate-900 dark:text-slate-100">尚未配置模型候选</p>
          <p class="mt-2 text-sm text-slate-500 dark:text-slate-400">创建第一个模型提供商后，即可在这里查看健康状态与运行时统计。</p>
        </div>

        <div v-else class="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <article
            v-for="candidate in sortedCandidates"
            :key="candidate.id"
            class="bg-white dark:bg-slate-900 rounded-xl shadow-sm border transition-all hover:border-indigo-300 dark:hover:border-indigo-500 overflow-hidden"
            :class="getCardBorderClass(candidate.name)"
          >
            <div class="p-6 border-b border-slate-100 dark:border-slate-800 flex items-start justify-between gap-4">
              <div class="min-w-0">
                <div class="flex items-center gap-3 flex-wrap">
                  <span class="w-2.5 h-2.5 rounded-full shrink-0" :class="getCircuitDotClass(candidate.name)"></span>
                  <h2 class="text-lg font-bold text-slate-900 dark:text-slate-100 truncate">{{ candidate.displayName || candidate.name }}</h2>
                  <span class="text-xs px-2 py-0.5 rounded-full font-medium" :class="getRouteTypeClass(candidate.routeType)">
                    {{ candidate.routeType || 'GENERAL' }}
                  </span>
                  <span class="text-xs px-2 py-0.5 rounded-full font-medium" :class="getCircuitBadgeClass(candidate.name)">
                    {{ getCircuitState(candidate.name) || 'UNKNOWN' }}
                  </span>
                </div>
                <p class="mt-2 text-sm text-slate-500 dark:text-slate-400 break-all">{{ candidate.name }}</p>
              </div>

              <label class="relative inline-flex items-center cursor-pointer shrink-0">
                <input
                  type="checkbox"
                  class="sr-only peer"
                  :checked="Boolean(candidate.enabled)"
                  :disabled="toggleLoadingIds.has(candidate.id)"
                  @change="handleToggle(candidate)"
                />
                <div class="w-11 h-6 bg-slate-200 dark:bg-slate-700 rounded-full peer-focus:ring-2 peer-focus:ring-indigo-500 transition-all peer-checked:bg-indigo-600"></div>
                <div class="absolute left-1 top-1 bg-white dark:bg-slate-900 w-4 h-4 rounded-full transition-all peer-checked:translate-x-5"></div>
              </label>
            </div>

            <div class="p-6 space-y-6">
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div>
                  <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">提供商</p>
                  <p class="mt-1 text-sm font-medium text-slate-900 dark:text-slate-100">{{ candidate.provider || '-' }}</p>
                </div>
                <div>
                  <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">模型</p>
                  <p class="mt-1 text-sm font-medium text-slate-900 dark:text-slate-100 break-all">{{ candidate.model || '-' }}</p>
                </div>
                <div class="sm:col-span-2">
                  <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">地址</p>
                  <p class="mt-1 text-sm font-medium text-slate-900 dark:text-slate-100 break-all">{{ candidate.baseUrl || '-' }}</p>
                </div>
                <div class="sm:col-span-2">
                  <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">密钥</p>
                  <div class="mt-1 flex items-center gap-3 flex-wrap">
                    <span class="text-sm font-medium text-slate-900 dark:text-slate-100">{{ candidate.apiKeyMasked || '未配置' }}</span>
                    <button
                      class="bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 px-3 py-1.5 rounded-lg text-sm font-medium hover:bg-slate-200 dark:hover:bg-slate-700 transition-all disabled:opacity-60"
                      @click="handleCopyKey(candidate.id)"
                      :disabled="copyingId === candidate.id || !canCopyKey(candidate)"
                      :title="getCopyKeyHint(candidate)"
                    >
                      {{ copiedId === candidate.id ? '已复制' : (copyingId === candidate.id ? '复制中...' : '复制') }}
                    </button>
                  </div>
                </div>
                <div>
                  <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">优先级</p>
                  <p class="mt-1 text-sm font-medium text-slate-900 dark:text-slate-100">{{ candidate.priority ?? 100 }}</p>
                </div>
                <div>
                  <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">能力标记</p>
                  <div class="mt-1 flex items-center gap-2 flex-wrap">
                    <span class="text-xs px-2 py-0.5 rounded-full font-medium" :class="candidate.isPrimary ? 'bg-indigo-50 text-indigo-700 dark:bg-indigo-500/15 dark:text-indigo-300' : 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'">
                      {{ candidate.isPrimary ? '主模型' : '候补模型' }}
                    </span>
                    <span class="text-xs px-2 py-0.5 rounded-full font-medium" :class="candidate.supportsThinking ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300' : 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'">
                      {{ candidate.supportsThinking ? '支持推理' : '通用模型' }}
                    </span>
                  </div>
                </div>
              </div>

              <section class="rounded-xl bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-800 p-4">
                <div class="flex items-center justify-between gap-3">
                  <div>
                    <p class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">运行时统计</p>
                    <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">展示当前熔断器与请求结果汇总。</p>
                  </div>
                  <span class="text-xs px-2 py-0.5 rounded-full font-medium" :class="getCircuitBadgeClass(candidate.name)">
                    {{ getDetail(candidate.name)?.state || getCircuitState(candidate.name) || 'UNKNOWN' }}
                  </span>
                </div>

                <div class="mt-4 grid grid-cols-2 sm:grid-cols-4 gap-3">
                  <div class="rounded-lg bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 p-3">
                    <p class="text-xs text-slate-500 dark:text-slate-400">请求</p>
                    <p class="mt-1 text-lg font-bold text-slate-900 dark:text-slate-100">{{ getDetail(candidate.name)?.requestCount ?? 0 }}</p>
                  </div>
                  <div class="rounded-lg bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 p-3">
                    <p class="text-xs text-slate-500 dark:text-slate-400">成功</p>
                    <p class="mt-1 text-lg font-bold text-emerald-600 dark:text-emerald-400">{{ getDetail(candidate.name)?.successCount ?? 0 }}</p>
                  </div>
                  <div class="rounded-lg bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 p-3">
                    <p class="text-xs text-slate-500 dark:text-slate-400">失败</p>
                    <p class="mt-1 text-lg font-bold text-red-600 dark:text-red-400">{{ getDetail(candidate.name)?.failureCount ?? 0 }}</p>
                  </div>
                  <div class="rounded-lg bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 p-3">
                    <p class="text-xs text-slate-500 dark:text-slate-400">连续失败</p>
                    <p class="mt-1 text-lg font-bold text-amber-600 dark:text-amber-400">{{ getDetail(candidate.name)?.consecutiveFailureCount ?? 0 }}</p>
                  </div>
                </div>

                <p
                  v-if="getDetail(candidate.name)?.lastFailureMessage"
                  class="mt-3 text-xs text-red-600 dark:text-red-400 break-all"
                >
                  最近失败: {{ getDetail(candidate.name)?.lastFailureMessage }}
                </p>
              </section>
            </div>

            <footer class="px-6 py-4 bg-slate-50/70 dark:bg-slate-800/50 border-t border-slate-100 dark:border-slate-800">
              <div class="flex flex-col gap-3">
                <div class="flex flex-wrap items-center gap-3">
                  <button
                    class="bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm font-semibold hover:bg-indigo-700 transition-all disabled:opacity-60 inline-flex items-center gap-2"
                    @click="handleProbe(candidate.id)"
                    :disabled="probingIds.has(candidate.id)"
                  >
                    <span v-if="probingIds.has(candidate.id)" class="material-symbols-outlined animate-spin text-[16px]">autorenew</span>
                    <span v-else class="material-symbols-outlined text-[16px]">network_ping</span>
                    探测
                  </button>
                  <button
                    class="bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 px-4 py-2 rounded-lg text-sm font-medium hover:bg-slate-200 dark:hover:bg-slate-700 transition-all"
                    @click="openEditModal(candidate)"
                  >
                    编辑
                  </button>
                  <button
                    class="bg-red-50 dark:bg-red-500/10 text-red-600 dark:text-red-300 px-4 py-2 rounded-lg text-sm font-medium hover:bg-red-100 dark:hover:bg-red-500/20 transition-all"
                    @click="handleDelete(candidate)"
                  >
                    删除
                  </button>
                </div>

                <p
                  v-if="probeResults[candidate.id]"
                  class="text-sm font-medium break-all"
                  :class="probeResults[candidate.id].status === 'healthy' ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'"
                >
                  {{ formatProbeResult(probeResults[candidate.id]) }}
                </p>
              </div>
            </footer>
          </article>
        </div>
      </div>
    </main>

    <div v-if="showModal" class="fixed inset-0 z-50 bg-slate-950/55 backdrop-blur-sm flex items-center justify-center p-4">
      <div class="w-full max-w-3xl bg-white dark:bg-slate-900 rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-800 overflow-hidden">
        <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-800/50 flex items-center justify-between">
          <div>
            <h3 class="text-lg font-bold text-slate-900 dark:text-slate-100">{{ editingId === null ? '新增模型候选' : '编辑模型候选' }}</h3>
            <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">
              {{ editingId === null ? '创建新的模型候补配置并纳入路由管理。' : '保留唯一标识不变，更新模型参数与优先级。' }}
            </p>
          </div>
          <button class="text-slate-400 dark:text-slate-500 hover:text-slate-700 dark:hover:text-slate-300 transition-colors" @click="closeModal">
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>

        <div class="p-6 grid grid-cols-1 md:grid-cols-2 gap-5 max-h-[75vh] overflow-y-auto">
          <div class="space-y-2">
            <label class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">唯一标识</label>
            <input
              v-model.trim="form.name"
              :disabled="editingId !== null"
              :class="editingId !== null ? 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 cursor-not-allowed' : inputClass"
              class="w-full border-none rounded-lg p-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none"
              type="text"
              placeholder="deepseek-chat"
            />
          </div>

          <div class="space-y-2">
            <label class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">显示名称</label>
            <input v-model.trim="form.displayName" :class="inputClass" class="w-full border-none rounded-lg p-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" type="text" placeholder="DeepSeek Chat" />
          </div>

          <div class="space-y-2">
            <label class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">提供商</label>
            <select v-model="form.provider" :class="inputClass" class="w-full border-none rounded-lg p-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none">
              <option value="openai">openai</option>
              <option value="zhipuai">zhipuai</option>
              <option value="ollama">ollama</option>
            </select>
          </div>

          <div class="space-y-2">
            <label class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">模型标识</label>
            <input v-model.trim="form.model" :class="inputClass" class="w-full border-none rounded-lg p-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" type="text" placeholder="gpt-4o" />
          </div>

          <div class="space-y-2 md:col-span-2">
            <label class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">API 地址</label>
            <input v-model.trim="form.baseUrl" :class="inputClass" class="w-full border-none rounded-lg p-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" type="text" placeholder="https://api.openai.com" />
          </div>

          <div class="space-y-2 md:col-span-2">
            <label class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">API Key</label>
            <div class="relative">
              <input
                v-model.trim="form.apiKey"
                :class="inputClass"
                class="w-full border-none rounded-lg p-3 pr-12 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none"
                :type="showApiKey ? 'text' : 'password'"
                :placeholder="apiKeyPlaceholder"
              />
              <button class="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 hover:text-slate-700 dark:hover:text-slate-300" @click="showApiKey = !showApiKey" type="button">
                <span class="material-symbols-outlined text-[18px]">{{ showApiKey ? 'visibility_off' : 'visibility' }}</span>
              </button>
            </div>
            <p class="text-xs text-slate-500 dark:text-slate-400">
              {{ apiKeyHelperText }}
            </p>
          </div>

          <div class="space-y-2">
            <label class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">优先级</label>
            <input v-model.number="form.priority" :class="inputClass" class="w-full border-none rounded-lg p-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" type="number" min="0" step="1" />
          </div>

          <div class="space-y-2">
            <label class="text-xs font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400">路由类型</label>
            <select v-model="form.routeType" :class="inputClass" class="w-full border-none rounded-lg p-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none">
              <option value="GENERAL">GENERAL</option>
              <option value="THINKING">THINKING</option>
              <option value="RETRIEVAL">RETRIEVAL</option>
              <option value="ALL">ALL</option>
            </select>
          </div>

          <label class="flex items-center gap-3 rounded-xl border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-800/50 p-4 cursor-pointer">
            <input v-model="form.isPrimary" class="w-4 h-4 accent-indigo-600" type="checkbox" />
            <div>
              <p class="text-sm font-semibold text-slate-900 dark:text-slate-100">主模型</p>
              <p class="text-xs text-slate-500 dark:text-slate-400">启用主动健康监控，并作为优先级策略的重要候选。</p>
            </div>
          </label>

          <label class="flex items-center gap-3 rounded-xl border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-800/50 p-4 cursor-pointer">
            <input v-model="form.supportsThinking" class="w-4 h-4 accent-indigo-600" type="checkbox" />
            <div>
              <p class="text-sm font-semibold text-slate-900 dark:text-slate-100">支持深度推理</p>
              <p class="text-xs text-slate-500 dark:text-slate-400">允许该模型参与 THINKING 路由。</p>
            </div>
          </label>

          <label class="flex items-center gap-3 rounded-xl border border-slate-200 dark:border-slate-800 bg-slate-50 dark:bg-slate-800/50 p-4 cursor-pointer md:col-span-2">
            <input v-model="form.enabled" class="w-4 h-4 accent-indigo-600" type="checkbox" />
            <div>
              <p class="text-sm font-semibold text-slate-900 dark:text-slate-100">启用候选</p>
              <p class="text-xs text-slate-500 dark:text-slate-400">关闭后该模型不会参与路由，但配置会保留。</p>
            </div>
          </label>
        </div>

        <div class="px-6 py-4 border-t border-slate-100 dark:border-slate-800 bg-slate-50 dark:bg-slate-800/50 flex justify-end gap-3">
          <button class="bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 px-4 py-2 rounded-lg text-sm font-medium hover:bg-slate-200 dark:hover:bg-slate-700 transition-all" @click="closeModal">
            取消
          </button>
          <button
            class="bg-indigo-600 text-white px-5 py-2 rounded-lg text-sm font-semibold hover:bg-indigo-700 transition-all disabled:opacity-60 inline-flex items-center gap-2"
            @click="saveCandidate"
            :disabled="saving"
          >
            <span v-if="saving" class="material-symbols-outlined animate-spin text-[16px]">autorenew</span>
            保存
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import {
  loadModelCandidates,
  createModelCandidate,
  updateModelCandidate,
  deleteModelCandidate,
  toggleModelCandidate,
  probeModelCandidate,
  copyModelCandidateKey,
  loadModelRoutingStats
} from '../api/admin'

defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})

const inputClass = 'bg-slate-50 dark:bg-slate-800/50'

const createEmptyForm = () => ({
  name: '',
  displayName: '',
  provider: 'openai',
  model: '',
  baseUrl: '',
  apiKey: '',
  priority: 100,
  routeType: 'GENERAL',
  isPrimary: false,
  supportsThinking: false,
  enabled: true
})

const candidates = ref([])
const stats = ref(null)
const hint = ref('')
const hintTone = ref('info')
const loading = ref(false)
const saving = ref(false)
const showModal = ref(false)
const editingId = ref(null)
const showApiKey = ref(false)
const form = ref(createEmptyForm())
const probeResults = ref({})
const probingIds = ref(new Set())
const toggleLoadingIds = ref(new Set())
const copyingId = ref(null)
const copiedId = ref(null)
let copiedTimer = null

const hintTypeClass = computed(() => {
  if (hintTone.value === 'error') return 'text-red-600 dark:text-red-400'
  if (hintTone.value === 'success') return 'text-emerald-600 dark:text-emerald-400'
  return 'text-indigo-600 dark:text-indigo-400'
})

const isOllamaProvider = computed(() => String(form.value.provider || '').trim().toLowerCase() === 'ollama')

const apiKeyPlaceholder = computed(() => {
  if (editingId.value === null) {
    return isOllamaProvider.value ? '本地 Ollama 可留空' : '创建时必填'
  }
  return isOllamaProvider.value ? '本地 Ollama 可留空，保留为空不修改' : '留空表示不修改'
})

const apiKeyHelperText = computed(() => {
  if (isOllamaProvider.value) {
    return '本地 Ollama 一般无需 API Key；使用 openai/zhipuai 等云提供商时再填写。'
  }
  return '云提供商通常要求 API Key；编辑已有模型时留空表示不修改原密钥。'
})

const sortedCandidates = computed(() => {
  return [...candidates.value].sort((a, b) => {
    const priorityDiff = (a.priority ?? 100) - (b.priority ?? 100)
    if (priorityDiff !== 0) return priorityDiff
    return String(a.name || '').localeCompare(String(b.name || ''))
  })
})

const setHint = (message, tone = 'info') => {
  hint.value = message
  hintTone.value = tone
}

const reload = async (options = {}) => {
  const silent = Boolean(options.silent)
  if (!silent) {
    loading.value = true
    setHint('正在加载模型候选与路由状态...')
  }
  try {
    const [candidateData, statsData] = await Promise.all([
      loadModelCandidates(),
      loadModelRoutingStats()
    ])
    candidates.value = Array.isArray(candidateData) ? candidateData : []
    stats.value = statsData || null
    if (!silent) setHint('模型候选已同步', 'success')
  } catch (error) {
    setHint(`加载失败: ${error.message || 'unknown'}`, 'error')
  } finally {
    loading.value = false
  }
}

const closeModal = () => {
  showModal.value = false
  editingId.value = null
  showApiKey.value = false
  form.value = createEmptyForm()
}

const openCreateModal = () => {
  editingId.value = null
  form.value = createEmptyForm()
  showApiKey.value = false
  showModal.value = true
}

const openEditModal = (candidate) => {
  editingId.value = candidate.id
  form.value = {
    name: candidate.name || '',
    displayName: candidate.displayName || '',
    provider: candidate.provider || 'openai',
    model: candidate.model || '',
    baseUrl: candidate.baseUrl || '',
    apiKey: '',
    priority: Number(candidate.priority ?? 100),
    routeType: candidate.routeType || 'GENERAL',
    isPrimary: Boolean(candidate.isPrimary),
    supportsThinking: Boolean(candidate.supportsThinking),
    enabled: Boolean(candidate.enabled)
  }
  showApiKey.value = false
  showModal.value = true
}

const validateForm = () => {
  if (!form.value.name.trim()) {
    setHint('模型唯一标识不能为空', 'error')
    return false
  }
  if (!form.value.displayName.trim()) {
    setHint('显示名称不能为空', 'error')
    return false
  }
  if (!form.value.model.trim()) {
    setHint('模型标识不能为空', 'error')
    return false
  }
  if (!form.value.baseUrl.trim()) {
    setHint('API 地址不能为空', 'error')
    return false
  }
  if (editingId.value === null && !isOllamaProvider.value && !form.value.apiKey.trim()) {
    setHint('创建模型时必须填写 API Key', 'error')
    return false
  }
  return true
}

const buildPayload = () => ({
  name: form.value.name.trim(),
  displayName: form.value.displayName.trim(),
  provider: form.value.provider,
  model: form.value.model.trim(),
  baseUrl: form.value.baseUrl.trim(),
  apiKey: form.value.apiKey.trim(),
  priority: Number.isFinite(Number(form.value.priority)) ? Number(form.value.priority) : 100,
  routeType: form.value.routeType,
  isPrimary: Boolean(form.value.isPrimary),
  supportsThinking: Boolean(form.value.supportsThinking),
  enabled: Boolean(form.value.enabled)
})

const saveCandidate = async () => {
  if (!validateForm()) return

  saving.value = true
  setHint(editingId.value === null ? '正在创建模型候选...' : '正在更新模型候选...')
  try {
    const payload = buildPayload()
    if (editingId.value === null) {
      await createModelCandidate(payload)
      setHint('模型候选已创建', 'success')
    } else {
      await updateModelCandidate(editingId.value, payload)
      setHint('模型候选已更新', 'success')
    }
    closeModal()
    await reload({ silent: true })
  } catch (error) {
    setHint(`保存失败: ${error.message || 'unknown'}`, 'error')
  } finally {
    saving.value = false
  }
}

const handleDelete = async (candidate) => {
  const confirmed = window.confirm(`确定删除模型候选「${candidate.displayName || candidate.name}」吗？`)
  if (!confirmed) return

  setHint(`正在删除 ${candidate.name}...`)
  try {
    await deleteModelCandidate(candidate.id)
    delete probeResults.value[candidate.id]
    await reload({ silent: true })
    setHint('模型候选已删除', 'success')
  } catch (error) {
    setHint(`删除失败: ${error.message || 'unknown'}`, 'error')
  }
}

const withUpdatedSet = (sourceSet, id, active) => {
  const next = new Set(sourceSet.value)
  if (active) next.add(id)
  else next.delete(id)
  sourceSet.value = next
}

const handleToggle = async (candidate) => {
  withUpdatedSet(toggleLoadingIds, candidate.id, true)
  try {
    await toggleModelCandidate(candidate.id)
    candidate.enabled = !candidate.enabled
    setHint(`模型 ${candidate.displayName || candidate.name} 已${candidate.enabled ? '启用' : '停用'}`, 'success')
  } catch (error) {
    setHint(`切换失败: ${error.message || 'unknown'}`, 'error')
  } finally {
    withUpdatedSet(toggleLoadingIds, candidate.id, false)
  }
}

const handleProbe = async (id) => {
  withUpdatedSet(probingIds, id, true)
  try {
    const result = await probeModelCandidate(id)
    probeResults.value = {
      ...probeResults.value,
      [id]: result
    }
    await reload({ silent: true })
    setHint(result.status === 'healthy' ? '探测完成，模型状态健康' : '探测完成，模型状态异常', result.status === 'healthy' ? 'success' : 'error')
  } catch (error) {
    probeResults.value = {
      ...probeResults.value,
      [id]: {
        status: 'unhealthy',
        error: error.message || 'unknown'
      }
    }
    setHint(`探测失败: ${error.message || 'unknown'}`, 'error')
  } finally {
    withUpdatedSet(probingIds, id, false)
  }
}

const writeClipboard = async (text) => {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }

  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', 'readonly')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  const succeeded = document.execCommand('copy')
  document.body.removeChild(textarea)
  if (!succeeded) throw new Error('当前环境不支持剪贴板写入')
}

const handleCopyKey = async (id) => {
  copyingId.value = id
  try {
    const data = await copyModelCandidateKey(id)
    await writeClipboard(data.apiKey || '')
    copiedId.value = id
    setHint('密钥已复制到剪贴板', 'success')
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = window.setTimeout(() => {
      copiedId.value = null
    }, 2000)
  } catch (error) {
    setHint(`复制失败: ${error.message || 'unknown'}`, 'error')
  } finally {
    copyingId.value = null
  }
}

const canCopyKey = (candidate) => {
  return Boolean(candidate?.apiKeyConfigured) && Boolean(candidate?.apiKeyReadable) && Boolean(candidate?.apiKeyCopyAllowed)
}

const getCopyKeyHint = (candidate) => {
  if (!candidate?.apiKeyConfigured) return '当前未配置 API Key'
  if (!candidate?.apiKeyReadable) return '当前密钥不可读，请检查加密密钥配置或重新保存'
  if (!candidate?.apiKeyCopyAllowed) return '服务端已禁用明文 API Key 导出'
  return '复制明文 API Key 到剪贴板'
}

const getCircuitState = (name) => stats.value?.runtime?.states?.[name] || null
const getDetail = (name) => stats.value?.runtime?.details?.[name] || null

const getCircuitDotClass = (name) => {
  const state = getCircuitState(name)
  if (state === 'CLOSED') return 'bg-emerald-500'
  if (state === 'HALF_OPEN') return 'bg-amber-500'
  if (state === 'OPEN') return 'bg-red-500'
  return 'bg-slate-300 dark:bg-slate-600'
}

const getCardBorderClass = (name) => {
  const state = getCircuitState(name)
  if (state === 'CLOSED') return 'border-emerald-200 dark:border-emerald-500/30'
  if (state === 'HALF_OPEN') return 'border-amber-200 dark:border-amber-500/30'
  if (state === 'OPEN') return 'border-red-200 dark:border-red-500/30'
  return 'border-slate-200 dark:border-slate-800'
}

const getCircuitBadgeClass = (name) => {
  const state = getCircuitState(name)
  if (state === 'CLOSED') return 'bg-emerald-50 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300'
  if (state === 'HALF_OPEN') return 'bg-amber-50 text-amber-700 dark:bg-amber-500/15 dark:text-amber-300'
  if (state === 'OPEN') return 'bg-red-50 text-red-700 dark:bg-red-500/15 dark:text-red-300'
  return 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'
}

const getRouteTypeClass = (routeType) => {
  if (routeType === 'THINKING') return 'bg-violet-50 text-violet-700 dark:bg-violet-500/15 dark:text-violet-300'
  if (routeType === 'RETRIEVAL') return 'bg-emerald-50 text-emerald-700 dark:bg-emerald-500/15 dark:text-emerald-300'
  if (routeType === 'ALL') return 'bg-indigo-50 text-indigo-700 dark:bg-indigo-500/15 dark:text-indigo-300'
  return 'bg-sky-50 text-sky-700 dark:bg-sky-500/15 dark:text-sky-300'
}

const formatProbeResult = (result) => {
  if (!result) return ''
  if (result.status === 'healthy') return `✓ healthy ${result.latencyMs ?? '-'}ms`
  return `✗ unhealthy: ${result.error || '未知错误'}`
}

onMounted(() => {
  reload()
})
</script>
