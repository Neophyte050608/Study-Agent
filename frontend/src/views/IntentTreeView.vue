<template>
  <!-- Header -->
  <header class="fixed top-0 right-0 left-64 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-md flex items-center justify-between px-8 z-40 shadow-sm dark:shadow-none">
    <div class="flex items-center gap-8">
      <h1 class="text-xl font-bold text-indigo-700 dark:text-indigo-400">意图配置管理</h1>
      <nav class="flex gap-6 font-['Plus_Jakarta_Sans'] text-sm font-medium">
        <a href="#" @click.prevent="activeTab = 'params'" :class="activeTab === 'params' ? 'text-[#0057c2] dark:text-blue-400 border-b-2 border-[#0057c2] pb-1' : 'text-slate-500 dark:text-slate-400 hover:text-[#1a1c1c]'">基础参数</a>
        <a href="#" @click.prevent="activeTab = 'leafs'" :class="activeTab === 'leafs' ? 'text-[#0057c2] dark:text-blue-400 border-b-2 border-[#0057c2] pb-1' : 'text-slate-500 dark:text-slate-400 hover:text-[#1a1c1c]'">叶子意图</a>
        <a href="#" @click.prevent="activeTab = 'cases'" :class="activeTab === 'cases' ? 'text-[#0057c2] dark:text-blue-400 border-b-2 border-[#0057c2] pb-1' : 'text-slate-500 dark:text-slate-400 hover:text-[#1a1c1c]'">槽位样例</a>
      </nav>
    </div>
    <div class="flex items-center gap-4">
      <div class="flex items-center gap-2">
        <button @click="reload" :disabled="loading" class="px-4 py-1.5 text-xs font-semibold text-slate-600 hover:bg-slate-100 rounded transition-all active:scale-95 disabled:opacity-50">重置</button>
        <button @click="save" :disabled="loading" class="px-4 py-1.5 text-xs font-semibold bg-[#0057c2] text-white rounded shadow-sm hover:opacity-90 transition-all active:scale-95 disabled:opacity-50">保存配置</button>
      </div>
    </div>
  </header>

  <!-- Main Content Area -->
  <main class="ml-64 flex flex-col min-w-0 bg-[#f9f9f9] min-h-screen pt-24 pb-12 px-8 space-y-6">
    <!-- Summary Bar -->
    <div class="grid grid-cols-4 gap-4">
      <div class="bg-white p-4 rounded shadow-sm border-l-4 border-[#0057c2]">
        <div class="text-[10px] uppercase tracking-wider text-[#727786] font-semibold mb-1">系统状态</div>
        <div class="text-2xl font-bold font-headline text-[#1a1c1c]">{{ stats.enabled ? '已启用' : '已禁用' }}</div>
      </div>
      <div class="bg-white p-4 rounded shadow-sm border-l-4 border-[#425d97]">
        <div class="text-[10px] uppercase tracking-wider text-[#727786] font-semibold mb-1">叶子意图数</div>
        <div class="text-2xl font-bold font-headline text-[#1a1c1c]">{{ stats.leafIntentCount ?? '--' }}</div>
      </div>
      <div class="bg-white p-4 rounded shadow-sm border-l-4 border-[#9e3d00]">
        <div class="text-[10px] uppercase tracking-wider text-[#727786] font-semibold mb-1">槽位精炼用例</div>
        <div class="text-2xl font-bold font-headline text-[#1a1c1c]">{{ stats.slotRefineCaseCount ?? '--' }}</div>
      </div>
      <div class="bg-white p-4 rounded shadow-sm border-l-4 border-[#ba1a1a]">
        <div class="text-[10px] uppercase tracking-wider text-[#727786] font-semibold mb-1">置信度阈值</div>
        <div class="text-2xl font-bold font-headline text-[#1a1c1c]">{{ stats.confidenceThreshold ? Number(stats.confidenceThreshold).toFixed(2) : '--' }}</div>
      </div>
    </div>

    <div class="flex gap-6 h-full min-h-0 items-start">
      <!-- Form Area -->
      <div v-show="activeTab === 'params'" class="flex-1 space-y-4">
        <div class="bg-white p-5 rounded shadow-sm">
          <div class="flex justify-between items-center mb-4">
            <h2 class="text-sm font-semibold flex items-center gap-2">
              <span class="material-symbols-outlined text-[#0057c2] text-lg" data-icon="tune">tune</span>
              核心路由参数
            </h2>
          </div>
          <div class="grid grid-cols-2 gap-x-8 gap-y-4">
            <div class="space-y-1.5">
              <label class="text-[11px] font-medium text-slate-600 flex items-center gap-1">
                意图引擎开关
              </label>
              <div class="flex items-center h-10">
                <input v-model="form.enabled" class="w-10 h-5 bg-[#c1c6d7] rounded-full appearance-none checked:bg-[#0057c2] relative before:content-[''] before:absolute before:w-4 before:h-4 before:bg-white before:rounded-full before:top-0.5 before:left-0.5 checked:before:translate-x-5 before:transition-all cursor-pointer" type="checkbox"/>
              </div>
            </div>
            <div class="space-y-1.5">
              <label class="text-[11px] font-medium text-slate-600">置信度阈值 (Confidence Threshold)</label>
              <input v-model.number="form.confidenceThreshold" class="w-full accent-[#0057c2] h-1 bg-[#e2e2e2] rounded-lg appearance-none cursor-pointer" type="range" min="0" max="1" step="0.05"/>
              <div class="flex justify-between text-[10px] text-[#727786]">
                <span>0.0</span>
                <span class="text-[#0057c2] font-bold">{{ Number(form.confidenceThreshold || 0).toFixed(2) }}</span>
                <span>1.0</span>
              </div>
            </div>
            <div class="space-y-1.5">
              <label class="text-[11px] font-medium text-slate-600">最小置信差 (Min Gap)</label>
              <input v-model.number="form.minGap" class="w-full bg-[#f3f3f3] border-none rounded text-xs py-2 px-3 focus:ring-2 focus:ring-[#0057c2]/20" type="number" step="0.01"/>
            </div>
            <div class="space-y-1.5">
              <label class="text-[11px] font-medium text-slate-600">歧义比例 (Ambiguity Ratio)</label>
              <input v-model.number="form.ambiguityRatio" class="w-full bg-[#f3f3f3] border-none rounded text-xs py-2 px-3 focus:ring-2 focus:ring-[#0057c2]/20" type="number" step="0.01"/>
            </div>
            <div class="space-y-1.5">
              <label class="text-[11px] font-medium text-slate-600">澄清 TTL (分钟)</label>
              <input v-model.number="form.clarificationTtlMinutes" class="w-full bg-[#f3f3f3] border-none rounded text-xs py-2 px-3 focus:ring-2 focus:ring-[#0057c2]/20" type="number"/>
            </div>
            <div class="space-y-1.5">
              <label class="text-[11px] font-medium text-slate-600">最大候选数</label>
              <input v-model.number="form.maxCandidates" class="w-full bg-[#f3f3f3] border-none rounded text-xs py-2 px-3 focus:ring-2 focus:ring-[#0057c2]/20" type="number"/>
            </div>
            <div class="col-span-2 space-y-1.5 pt-2 border-t border-slate-100 flex items-center justify-between">
              <label class="text-[11px] font-medium text-slate-600">意图引擎失败时回退到旧版路由</label>
              <input v-model="form.fallbackToLegacyTaskRouter" class="w-10 h-5 bg-[#c1c6d7] rounded-full appearance-none checked:bg-[#0057c2] relative before:content-[''] before:absolute before:w-4 before:h-4 before:bg-white before:rounded-full before:top-0.5 before:left-0.5 checked:before:translate-x-5 before:transition-all cursor-pointer" type="checkbox"/>
            </div>
          </div>
        </div>
      </div>

      <!-- Tab: Leaf Intents -->
      <div v-show="activeTab === 'leafs'" class="flex-1 space-y-4">
        <div class="bg-white p-5 rounded shadow-sm">
          <div class="flex justify-between items-center mb-4">
            <h2 class="text-sm font-semibold flex items-center gap-2">
              <span class="material-symbols-outlined text-[#0057c2] text-lg">list_alt</span>
              叶子意图配置
            </h2>
            <button @click="addLeaf" class="text-xs text-[#0057c2] font-bold hover:underline">+ 新增意图</button>
          </div>
          <div class="space-y-3">
            <div v-for="(leaf, idx) in form.leafIntents" :key="idx" class="p-3 bg-[#f3f3f3] rounded relative group">
              <button @click="removeLeaf(idx)" class="absolute top-2 right-2 text-[#ba1a1a] hidden group-hover:block material-symbols-outlined text-sm">delete</button>
              <div class="grid grid-cols-2 gap-2 text-[10px]">
                <input v-model="leaf.intentId" class="border-none bg-white p-1 rounded focus:ring-1 focus:ring-[#0057c2]/30" placeholder="Intent ID"/>
                <input v-model="leaf.name" class="border-none bg-white p-1 rounded focus:ring-1 focus:ring-[#0057c2]/30" placeholder="名称"/>
                <input v-model="leaf.description" class="border-none bg-white p-1 rounded col-span-2 focus:ring-1 focus:ring-[#0057c2]/30" placeholder="描述"/>
                <input v-model="leaf.taskType" class="border-none bg-white p-1 rounded focus:ring-1 focus:ring-[#0057c2]/30" placeholder="TaskType"/>
                <input v-model="leaf.path" class="border-none bg-white p-1 rounded focus:ring-1 focus:ring-[#0057c2]/30" placeholder="Path"/>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Tab: Slot Refine Cases -->
      <div v-show="activeTab === 'cases'" class="flex-1 space-y-4">
        <div class="bg-white p-5 rounded shadow-sm">
          <div class="flex justify-between items-center mb-4">
            <h2 class="text-sm font-semibold flex items-center gap-2">
              <span class="material-symbols-outlined text-[#0057c2] text-lg">psychology</span>
              槽位精炼 Few-shot 样例
            </h2>
            <button @click="addCase" class="text-xs text-[#0057c2] font-bold hover:underline">+ 新增样例</button>
          </div>
          <div class="space-y-3">
            <div v-for="(c, idx) in form.slotRefineCases" :key="idx" class="p-3 bg-[#f3f3f3] rounded relative group">
              <button @click="removeCase(idx)" class="absolute top-2 right-2 text-[#ba1a1a] hidden group-hover:block material-symbols-outlined text-sm">delete</button>
              <div class="space-y-2 text-[10px]">
                <input v-model="c.taskType" class="w-full border-none bg-white p-1 rounded focus:ring-1 focus:ring-[#0057c2]/30" placeholder="TaskType"/>
                <textarea v-model="c.userQuery" class="w-full border-none bg-white p-1 rounded resize-none focus:ring-1 focus:ring-[#0057c2]/30" placeholder="用户输入"></textarea>
                <textarea v-model="c.aiOutput" class="w-full border-none bg-white p-1 rounded resize-none focus:ring-1 focus:ring-[#0057c2]/30" placeholder="AI 输出 (JSON)"></textarea>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Sidebar Panel -->
      <div class="w-80 bg-[#f3f3f3] rounded-lg p-5 flex flex-col gap-4 border border-[#c1c6d7]/20 h-fit shrink-0">
        <div class="flex items-center justify-between">
          <h3 class="text-xs font-bold text-[#1a1c1c]">策略配置指南</h3>
          <span class="material-symbols-outlined text-[#727786] text-sm">lightbulb</span>
        </div>
        <div class="text-[11px] leading-relaxed text-slate-600 bg-white p-3 rounded border-l-2 border-[#0057c2]">
          <p class="font-semibold text-[#0057c2] mb-1">最佳实践：</p>
          置信度阈值建议设置在 <b>0.6 - 0.7</b>。设置过高会频繁触发澄清，过低可能导致错误路由。
        </div>
        <div class="space-y-4 mt-2">
          <div>
            <div class="flex items-center gap-2 text-[11px] font-bold text-[#727786]">
              什么是最小置信差？
            </div>
            <p class="text-[10px] text-[#c1c6d7] mt-1 leading-tight text-slate-500">第一名与第二名意图的分数差距。若差距小于此值，说明存在歧义，系统将触发澄清。</p>
          </div>
        </div>
      </div>
    </div>
  </main>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { loadIntentTreeConfig, loadIntentTreeStats, saveIntentTreeConfig } from '../api/admin'

const loading = ref(false)
const hint = ref('在线配置意图树核心阈值')
const stats = ref({})
const rawConfig = ref({})
const activeTab = ref('params')

const form = ref({
  enabled: true,
  confidenceThreshold: 0.65,
  minGap: 0.12,
  ambiguityRatio: 0.9,
  clarificationTtlMinutes: 10,
  maxCandidates: 3,
  fallbackToLegacyTaskRouter: false,
  leafIntents: [],
  slotRefineCases: []
})

const reload = async () => {
  loading.value = true
  hint.value = '正在加载配置...'
  try {
    const [configData, statsData] = await Promise.all([
      loadIntentTreeConfig(),
      loadIntentTreeStats()
    ])
    rawConfig.value = configData || {}
    stats.value = statsData || {}
    form.value = {
      enabled: !!configData?.enabled,
      confidenceThreshold: Number(configData?.confidenceThreshold ?? 0.65),
      minGap: Number(configData?.minGap ?? 0.12),
      ambiguityRatio: Number(configData?.ambiguityRatio ?? 0.9),
      clarificationTtlMinutes: Number(configData?.clarificationTtlMinutes ?? 10),
      maxCandidates: Number(configData?.maxCandidates ?? 3),
      fallbackToLegacyTaskRouter: !!configData?.fallbackToLegacyTaskRouter,
      leafIntents: configData?.leafIntents ? JSON.parse(JSON.stringify(configData.leafIntents)) : [],
      slotRefineCases: configData?.slotRefineCases ? JSON.parse(JSON.stringify(configData.slotRefineCases)) : []
    }
    hint.value = '配置已加载'
  } catch (error) {
    hint.value = `加载失败: ${error.message || 'unknown'}`
  } finally {
    loading.value = false
  }
}

const save = async () => {
  loading.value = true
  hint.value = '正在保存配置...'
  try {
    const payload = {
      ...rawConfig.value,
      ...form.value
    }
    await saveIntentTreeConfig(payload)
    await reload()
    hint.value = '配置已保存'
  } catch (error) {
    hint.value = `保存失败: ${error.message || 'unknown'}`
    loading.value = false
  }
}

const addLeaf = () => {
  form.value.leafIntents.push({ intentId: '', name: '', description: '', taskType: '', path: '', examples: [], slotHints: [] })
}

const removeLeaf = (idx) => {
  form.value.leafIntents.splice(idx, 1)
}

const addCase = () => {
  form.value.slotRefineCases.push({ taskType: '', userQuery: '', aiOutput: '' })
}

const removeCase = (idx) => {
  form.value.slotRefineCases.splice(idx, 1)
}

onMounted(reload)
</script>
