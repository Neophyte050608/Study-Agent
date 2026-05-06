<template>
  <div class="bg-surface text-content antialiased min-h-screen">
    <!-- Top Navigation Bar -->
    <header class="fixed top-0 right-0 h-16 bg-surface-overlay/80 backdrop-blur-xl border-b border-slate-100 flex justify-between items-center px-8 z-40 transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
      <div class="flex items-center gap-4">
        <h1 class="text-xl font-bold tracking-tight text-brand-text">模型配置 <span class="text-content-secondary font-medium text-sm ml-2">/ 针对不同业务场景配置专属的 AI 智能代理</span></h1>
      </div>
      <div class="flex items-center gap-6">
        <button class="bg-brand text-white px-5 py-2 rounded-lg text-sm font-semibold flex items-center gap-2 hover:bg-brand-hover transition-all active:scale-[0.98] disabled:opacity-60" @click="saveAll" :disabled="loading">
          <span class="material-symbols-outlined text-sm" data-icon="save">save</span>
          全局保存
        </button>
      </div>
    </header>

    <!-- Content Area -->
    <main class="pt-24 p-8 min-h-screen bg-surface-field transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
      <div class="max-w-7xl mx-auto">
        <header class="mb-10">
          <h3 class="text-3xl font-extrabold text-content tracking-tight">动态 Agent 设置</h3>
          <p class="text-content-secondary mt-2 max-w-2xl">针对不同业务场景配置专属的 AI 智能代理，优化响应逻辑、模型深度与生成策略。</p>
          <p class="text-indigo-600 text-sm mt-2 font-medium" v-if="hint && hint !== '在此统一管理各 Agent 模型参数'">{{ hint }}</p>
        </header>
        
        <!-- Bento Grid -->
        <div class="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
          
          <template v-for="(config, key) in configs" :key="key">
            <div class="bg-surface-raised rounded-xl shadow-sm overflow-hidden transition-all duration-300 border border-slate-200 hover:border-indigo-300"
                 :class="key === 'DecisionLayerAgent' ? 'lg:col-span-4 flex flex-col h-full' : (key === 'EvaluationAgent' ? 'lg:col-span-8' : 'lg:col-span-12')">
              
              <div class="p-6 border-b border-slate-100 flex justify-between items-center bg-surface-raised">
                <div class="flex items-center gap-4">
                  <div class="p-3 rounded-lg" :class="key === 'DecisionLayerAgent' ? 'bg-amber-50 text-amber-600' : (key === 'LearningProfileAgent' ? 'bg-emerald-50 text-emerald-600' : 'bg-indigo-50 text-indigo-700')">
                    <span class="material-symbols-outlined" data-icon="smart_toy">
                      {{ key === 'DecisionLayerAgent' ? 'alt_route' : (key === 'LearningProfileAgent' ? 'psychology' : 'fact_check') }}
                    </span>
                  </div>
                  <div>
                    <h4 class="font-bold text-lg text-content">{{ key }}</h4>
                    <p class="text-sm text-content-secondary" v-if="key === 'EvaluationAgent'">核心职责：深度分析候选人回答质量及多维度打分。</p>
                    <p class="text-sm text-content-secondary" v-if="key === 'DecisionLayerAgent'">核心职责：管理逻辑流与分支路由。</p>
                    <p class="text-sm text-content-secondary" v-if="key === 'LearningProfileAgent'">核心职责：通过数据洞察生成个性化成长路径与学习建议。</p>
                  </div>
                </div>
                <label class="relative inline-flex items-center cursor-pointer">
                  <input type="checkbox" class="sr-only peer" v-model="config.enabled" />
                  <div class="w-11 h-6 bg-slate-200 rounded-full peer-focus:ring-2 peer-focus:ring-indigo-500 transition-all peer-checked:bg-indigo-600"></div>
                  <div class="absolute left-1 top-1 bg-surface-raised w-4 h-4 rounded-full transition-all peer-checked:translate-x-5"></div>
                </label>
              </div>
              
              <div :class="key === 'DecisionLayerAgent' ? 'p-6 flex-1 space-y-6' : 'p-8'">
                <div :class="key === 'DecisionLayerAgent' ? 'space-y-4' : (key === 'EvaluationAgent' ? 'grid grid-cols-1 md:grid-cols-2 gap-8' : 'grid grid-cols-1 md:grid-cols-4 gap-8')">
                  
                  <div class="space-y-2">
                    <label class="text-xs font-bold uppercase tracking-wider text-content-secondary">Provider 服务商</label>
                    <select class="w-full bg-surface-field border-none rounded-lg p-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" v-model="config.provider">
                      <option value="OPENAI">OpenAI</option>
                      <option value="ZHIPUAI">ZhipuAI</option>
                      <option value="OLLAMA">Ollama</option>
                    </select>
                  </div>
                  
                  <div class="space-y-2">
                    <label class="text-xs font-bold uppercase tracking-wider text-content-secondary">Model Name 模型名称</label>
                    <input class="w-full bg-surface-field border-none rounded-lg p-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" type="text" v-model="config.modelName" />
                  </div>
                  
                  <div class="space-y-2" :class="key === 'EvaluationAgent' ? 'md:col-span-2' : ''">
                    <label class="text-xs font-bold uppercase tracking-wider text-content-secondary">API Key 密钥</label>
                    <input class="w-full bg-surface-field border-none rounded-lg p-3 text-sm focus:ring-2 focus:ring-indigo-500 transition-all outline-none" placeholder="留空则使用系统全局默认 Key" type="password" v-model="config.apiKey" />
                  </div>
                  
                  <div class="space-y-4" :class="key === 'EvaluationAgent' ? 'md:col-span-2' : ''">
                    <div class="flex justify-between items-center">
                      <label class="text-xs font-bold uppercase tracking-wider text-content-secondary">Temperature 采样温度</label>
                      <span class="text-indigo-600 font-bold text-sm bg-indigo-50 px-2 py-0.5 rounded">{{ config.temperature }}</span>
                    </div>
                    <input class="w-full h-1.5 bg-slate-200 rounded-lg appearance-none cursor-pointer accent-indigo-600" max="1" min="0" step="0.1" type="range" v-model.number="config.temperature" />
                  </div>
                  
                </div>
              </div>
              
              <div class="p-6 bg-slate-50/50 dark:bg-slate-800/50 flex justify-end" v-if="key !== 'DecisionLayerAgent'">
                <button class="bg-brand text-white px-6 py-2.5 rounded-lg text-sm font-semibold hover:bg-brand-hover transition-all flex items-center gap-2" @click="saveAll" :disabled="loading">
                  <span>保存配置</span>
                </button>
              </div>
              <div class="p-6 mt-auto" v-else>
                <button class="w-full bg-brand text-white py-2.5 rounded-lg text-sm font-semibold hover:bg-brand-hover transition-all flex justify-center items-center gap-2" @click="saveAll" :disabled="loading">
                  <span>保存配置</span>
                </button>
              </div>
              
            </div>
          </template>

        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { loadAgentSettings, saveAgentSettings } from '../api/admin'

defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})

const loading = ref(false)
const hint = ref('在此统一管理各 Agent 模型参数')
const configs = ref({})

const normalizeConfig = (config) => ({
  enabled: config?.enabled ?? true,
  provider: config?.provider || 'OPENAI',
  modelName: config?.modelName || '',
  apiKey: config?.apiKey || '',
  temperature: Number(config?.temperature ?? 0.7)
})

const reload = async () => {
  loading.value = true
  hint.value = '正在加载配置...'
  try {
    const data = await loadAgentSettings()
    const normalized = {}
    Object.keys(data || {}).forEach((key) => {
      normalized[key] = normalizeConfig(data[key])
    })
    configs.value = normalized
    hint.value = '配置已加载'
  } catch (error) {
    hint.value = `加载失败: ${error.message || 'unknown'}`
  } finally {
    loading.value = false
  }
}

const saveAll = async () => {
  loading.value = true
  hint.value = '正在保存配置...'
  try {
    await saveAgentSettings(configs.value)
    hint.value = '配置已保存'
  } catch (error) {
    hint.value = `保存失败: ${error.message || 'unknown'}`
  } finally {
    loading.value = false
  }
}

onMounted(reload)
</script>
