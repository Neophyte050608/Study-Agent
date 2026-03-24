<template>
  <!-- TopNavBar Anchor -->
  <header class="fixed top-0 right-0 left-64 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-md flex items-center justify-between px-8 z-40 border-none shadow-[0_1px_2px_0_rgba(0,0,0,0.05)]">
    <div class="flex items-center space-x-4">
      <h1 class="text-xl font-bold text-indigo-700 dark:text-indigo-400 tracking-tight">数字化叙事</h1>
      <span class="text-slate-300">/</span>
      <span class="text-sm font-medium text-slate-600">MCP 协议调试控制台</span>
    </div>
  </header>

  <!-- Main Content Canvas -->
  <main class="ml-64 pt-24 pb-12 px-8 min-h-screen bg-[#f8f9fa]">
    <div class="max-w-6xl mx-auto space-y-8">
      <!-- Header Section -->
      <div class="flex flex-col space-y-2">
        <h2 class="text-3xl font-extrabold tracking-tight text-[#191c1d]">MCP 调试工作台</h2>
        <p class="text-slate-600 max-w-2xl leading-relaxed">
          大模型上下文协议 (Model Context Protocol) 实时调试环境。在此发现已注册的工具能力，模拟调用参数，并实时观测协议响应。
        </p>
      </div>

      <!-- Bento Grid Layout for MCP Workflow -->
      <div class="grid grid-cols-12 gap-6 items-start">
        <!-- Left Column: Config & Input -->
        <div class="col-span-12 lg:col-span-7 space-y-6">
          <!-- Step 1: Discovery -->
          <div class="bg-white p-6 rounded-xl shadow-sm border border-transparent hover:shadow-md transition-shadow duration-300">
            <div class="flex items-center justify-between mb-4">
              <div class="flex items-center space-x-3">
                <div class="w-8 h-8 rounded-full bg-indigo-100 flex items-center justify-center text-[#2a14b4]">
                  <span class="text-sm font-bold">01</span>
                </div>
                <h3 class="text-lg font-bold text-[#191c1d]">能力发现 (Discovery)</h3>
              </div>
              <button @click="reload" :disabled="loading" class="flex items-center space-x-2 text-[#2a14b4] font-semibold text-sm hover:underline disabled:opacity-50">
                <span class="material-symbols-outlined text-sm">refresh</span>
                <span>重新发现能力</span>
              </button>
            </div>
            <div class="space-y-4">
              <label class="block text-xs font-bold text-slate-500 uppercase tracking-wider">选择已发现的能力</label>
              <div class="relative">
                <select v-model="selectedCapability" @change="onCapabilityChange" class="w-full bg-[#f3f4f5] border-none rounded-xl py-3 pl-4 pr-10 text-sm focus:ring-2 focus:ring-indigo-300 appearance-none text-[#191c1d]">
                  <option value="" disabled>请选择工具能力...</option>
                  <option v-for="cap in capabilities" :key="cap.name" :value="cap.name">
                    {{ cap.name }}{{ cap.description ? ` - ${cap.description}` : '' }}
                  </option>
                </select>
                <div class="absolute inset-y-0 right-0 flex items-center pr-3 pointer-events-none text-slate-500">
                  <span class="material-symbols-outlined">unfold_more</span>
                </div>
              </div>
            </div>
          </div>

          <!-- Step 2: Params Editor -->
          <div class="bg-white p-6 rounded-xl shadow-sm border border-transparent">
            <div class="flex items-center justify-between mb-4">
              <div class="flex items-center space-x-3">
                <div class="w-8 h-8 rounded-full bg-indigo-100 flex items-center justify-center text-[#2a14b4]">
                  <span class="text-sm font-bold">02</span>
                </div>
                <h3 class="text-lg font-bold text-[#191c1d]">输入参数 (Params)</h3>
              </div>
              <span class="text-[10px] font-mono bg-indigo-100/50 text-[#5a5893] px-2 py-1 rounded">application/json</span>
            </div>
            <div class="relative">
              <textarea v-model="paramsText" class="w-full h-64 bg-slate-900 text-indigo-100 font-mono text-sm p-6 rounded-xl border-none focus:ring-2 focus:ring-indigo-300 resize-none shadow-inner" spellcheck="false"></textarea>
              <div class="absolute top-4 right-4 opacity-50 text-slate-400">
                <span class="material-symbols-outlined">code</span>
              </div>
            </div>
            <div class="mt-6 flex justify-end">
              <button @click="invoke" :disabled="loading || !selectedCapability" class="bg-[#4338ca] text-white px-8 py-3 rounded-xl font-bold flex items-center space-x-3 hover:shadow-lg active:scale-95 transition-all group disabled:opacity-60 disabled:active:scale-100 disabled:hover:shadow-none">
                <span class="material-symbols-outlined group-hover:rotate-12 transition-transform">play_arrow</span>
                <span>执行调用 (Invoke)</span>
              </button>
            </div>
          </div>
        </div>

        <!-- Right Column: Results & Context -->
        <div class="col-span-12 lg:col-span-5 space-y-6">
          <!-- Step 3: Result Section -->
          <div class="bg-white rounded-xl shadow-sm border border-transparent flex flex-col h-full overflow-hidden">
            <div class="p-6 border-b border-slate-200 bg-slate-50/50">
              <div class="flex items-center justify-between">
                <div class="flex items-center space-x-3">
                  <div class="w-8 h-8 rounded-full bg-indigo-100 flex items-center justify-center text-[#2a14b4]">
                    <span class="text-sm font-bold">03</span>
                  </div>
                  <h3 class="text-lg font-bold text-[#191c1d]">执行结果</h3>
                </div>
                <div class="flex items-center space-x-2">
                  <span :class="['flex h-2 w-2 rounded-full', statusColorDot]"></span>
                  <span :class="['text-[11px] font-bold uppercase tracking-tighter', statusColorText]">{{ statusText }}</span>
                </div>
              </div>
            </div>
            <!-- Result Display Area -->
            <div class="p-6 space-y-6 flex-1">
              <div class="grid grid-cols-2 gap-4">
                <div class="p-3 bg-[#edeeef] rounded-lg">
                  <p class="text-[10px] font-bold text-slate-500 uppercase mb-1">重试状态</p>
                  <p class="text-sm font-semibold text-[#191c1d]">{{ retryCount }} 次重试</p>
                </div>
                <div class="p-3 bg-[#edeeef] rounded-lg">
                  <p class="text-[10px] font-bold text-slate-500 uppercase mb-1">状态码</p>
                  <p :class="['text-sm font-semibold font-mono', statusCodeColor]">{{ statusCode }}</p>
                </div>
              </div>
              <div class="space-y-2">
                <div class="flex items-center justify-between">
                  <label class="text-xs font-bold text-slate-500 uppercase">Response Body</label>
                  <button @click="copyResult" class="text-[#2a14b4] text-[11px] hover:underline transition-all">{{ copyText }}</button>
                </div>
                <pre class="w-full p-4 bg-slate-50 rounded-xl overflow-x-auto border border-slate-200 min-h-[160px]"><code class="text-xs font-mono text-slate-700 leading-relaxed">{{ resultText }}</code></pre>
              </div>
            </div>
          </div>

          <!-- Side Info Card -->
          <div class="bg-indigo-900 rounded-xl p-6 text-indigo-100 shadow-xl relative overflow-hidden">
            <div class="relative z-10 space-y-4">
              <div class="flex items-center space-x-2">
                <span class="material-symbols-outlined text-indigo-300">info</span>
                <h4 class="font-bold">什么是 MCP？</h4>
              </div>
              <p class="text-sm text-indigo-200/90 leading-relaxed">
                Model Context Protocol 是用于大语言模型与外部工具进行标准通信的协议。通过该控制台，您可以绕过前端 UI 直接测试后端工具的兼容性与逻辑正确性。
              </p>
              <a class="inline-flex items-center space-x-1 text-xs font-bold text-white hover:text-indigo-300 transition-colors" href="#">
                <span>阅读协议文档</span>
                <span class="material-symbols-outlined text-xs">arrow_forward</span>
              </a>
            </div>
            <!-- Abstract Gradient Pattern -->
            <div class="absolute -bottom-10 -right-10 w-40 h-40 bg-indigo-500/20 rounded-full blur-3xl"></div>
          </div>
        </div>
      </div>

      <!-- Additional Contextual Stats -->
      <div class="bg-white rounded-xl p-8 shadow-sm flex flex-col md:flex-row items-center justify-between space-y-6 md:space-y-0 mt-8">
        <div class="flex items-center space-x-6">
          <div class="space-y-1">
            <p class="text-xs font-bold text-slate-500 uppercase">当前协议版本</p>
            <p class="text-2xl font-black text-[#2a14b4]">v1.0.0</p>
          </div>
          <div class="h-10 w-px bg-slate-200"></div>
          <div class="space-y-1">
            <p class="text-xs font-bold text-slate-500 uppercase">已连接工具</p>
            <p class="text-2xl font-black text-[#5a5893]">{{ capabilities.length }} 个实例</p>
          </div>
        </div>
        <div class="flex items-center -space-x-3">
          <div v-for="(cap, i) in displayedIcons" :key="i" :class="`w-10 h-10 rounded-full border-4 border-white bg-indigo-100 flex items-center justify-center text-sm font-bold text-indigo-700 z-${30-i*10}`">
            {{ cap }}
          </div>
          <div v-if="capabilities.length > 3" class="w-10 h-10 rounded-full border-4 border-white bg-slate-200 flex items-center justify-center text-[10px] font-bold text-slate-600 z-0">
            +{{ capabilities.length - 3 }}
          </div>
        </div>
      </div>
    </div>
  </main>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import { invokeMcpCapability, loadMcpCapabilities } from '../api/admin'

const loading = ref(false)
const hint = ref('加载可用能力后进行调用')
const capabilities = ref([])
const selectedCapability = ref('')
const paramsText = ref('{\n  \n}')
const resultText = ref('等待调用...')

// New reactive state for detailed status
const statusText = ref('Waiting')
const statusColorDot = ref('bg-slate-300')
const statusColorText = ref('text-slate-500')
const statusCode = ref('---')
const statusCodeColor = ref('text-slate-500')
const retryCount = ref(0)
const copyText = ref('复制结果')

// Mock 参数映射表
const mockParamsMap = {
  'obsidian.read': '{\n  "path": "learning-note.md"\n}',
  'obsidian.write': '{\n  "topic": "Spring Boot",\n  "content": "学习笔记内容"\n}',
  'web.search': '{\n  "query": "2024年最受欢迎的编程语言",\n  "limit": 5\n}',
  'code.execute': '{\n  "language": "python",\n  "code": "print(\'Hello MCP!\')"\n}',
  'sql_query': '{\n  "query": "SELECT * FROM users LIMIT 10"\n}',
  'file_read': '{\n  "path": "/etc/hosts"\n}'
}

const normalizeCapabilities = (data) => {
  if (Array.isArray(data)) {
    return data.map((item) => (typeof item === 'string' ? { name: item, description: '' } : item))
  }
  if (Array.isArray(data?.capabilities)) {
    return data.capabilities.map((item) => (typeof item === 'string' ? { name: item, description: '' } : item))
  }
  if (Array.isArray(data?.tools)) {
    return data.tools.map((item) => (typeof item === 'string' ? { name: item, description: '' } : item))
  }
  return []
}

const displayedIcons = computed(() => {
  return capabilities.value.slice(0, 3).map(c => c.name.charAt(0).toUpperCase())
})

const onCapabilityChange = () => {
  if (mockParamsMap[selectedCapability.value]) {
    paramsText.value = mockParamsMap[selectedCapability.value]
  } else {
    paramsText.value = '{\n  \n}'
  }
}

const reload = async () => {
  loading.value = true
  hint.value = '正在加载能力列表...'
  try {
    const data = await loadMcpCapabilities()
    capabilities.value = normalizeCapabilities(data)
    hint.value = `已加载 ${capabilities.value.length} 个能力`
  } catch (error) {
    hint.value = `加载失败: ${error.message || 'unknown'}`
  } finally {
    loading.value = false
  }
}

const invoke = async () => {
  loading.value = true
  hint.value = '正在调用能力...'
  
  statusText.value = 'Invoking'
  statusColorDot.value = 'bg-slate-300 animate-pulse'
  statusColorText.value = 'text-slate-500'
  statusCode.value = 'Pending'
  statusCodeColor.value = 'text-slate-500'
  resultText.value = 'Loading...'
  
  try {
    const params = JSON.parse(paramsText.value || '{}')
    const data = await invokeMcpCapability(selectedCapability.value, params)
    
    resultText.value = JSON.stringify(data, null, 2)
    hint.value = '调用完成'
    
    statusText.value = 'Success'
    statusColorDot.value = 'bg-emerald-500'
    statusColorText.value = 'text-emerald-600'
    statusCode.value = '200 OK'
    statusCodeColor.value = 'text-emerald-600'
    retryCount.value = data.attempt ? Math.max(0, data.attempt - 1) : 0
  } catch (error) {
    resultText.value = String(error.message || error)
    hint.value = `调用失败: ${error.message || 'unknown'}`
    
    statusText.value = 'Failed'
    statusColorDot.value = 'bg-[#ba1a1a]'
    statusColorText.value = 'text-[#ba1a1a]'
    statusCode.value = 'Error'
    statusCodeColor.value = 'text-[#ba1a1a]'
  } finally {
    loading.value = false
  }
}

const copyResult = async () => {
  try {
    await navigator.clipboard.writeText(resultText.value)
    copyText.value = '已复制!'
    setTimeout(() => {
      copyText.value = '复制结果'
    }, 2000)
  } catch (err) {
    alert('复制失败，请手动复制')
  }
}

onMounted(reload)
</script>
