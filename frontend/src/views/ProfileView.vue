<template>
  <div class="text-on-surface antialiased min-h-screen">
    <!-- TopNavBar Shell -->
    <header class="fixed top-0 right-0 left-64 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-md flex items-center justify-between px-8 z-40 shadow-sm dark:shadow-none">
      <div class="flex items-center space-x-4">
        <h1 class="text-xl font-bold tracking-tight text-slate-900">学习画像</h1>
        <span class="px-2 py-1 bg-indigo-100 text-indigo-700 text-xs font-bold rounded-full">BETA</span>
      </div>
    </header>

    <!-- Main Content Area -->
    <main class="ml-64 mt-16 p-8 min-h-screen bg-slate-50">
      <div v-if="error" class="mb-4 px-3 py-2 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">{{ error }}</div>
      
      <!-- Action Header -->
      <div class="flex flex-col md:flex-row md:items-center justify-between mb-8 gap-4">
        <div>
          <h2 class="text-2xl font-extrabold text-slate-900 tracking-tight">能力成长概览</h2>
          <p class="text-slate-500 text-sm mt-1">基于最近 30 天的练习数据生成的深度分析</p>
        </div>
        <div class="flex items-center space-x-3">
          <div class="relative">
            <select class="appearance-none bg-white border-none text-sm font-medium rounded-xl py-2.5 pl-4 pr-10 focus:ring-2 focus:ring-indigo-500/20 text-slate-600 cursor-pointer shadow-sm">
              <option>推荐模式：平衡提升</option>
              <option>侧重：攻克弱项</option>
              <option>侧重：深度钻研</option>
            </select>
            <span class="material-symbols-outlined absolute right-3 top-1/2 -translate-y-1/2 pointer-events-none text-slate-500">expand_more</span>
          </div>
          <button @click="reload" :disabled="loading" class="flex items-center justify-center p-2.5 bg-white text-slate-600 rounded-xl hover:bg-slate-50 transition-colors active:scale-95 shadow-sm disabled:opacity-60">
            <span class="material-symbols-outlined" :class="loading ? 'animate-spin' : ''">refresh</span>
          </button>
        </div>
      </div>

      <!-- Bento Grid Analysis Sections -->
      <div class="grid grid-cols-12 gap-6 mb-10">
        <!-- Radar Chart / Skills Profile -->
        <div class="col-span-12 lg:col-span-8 bg-white p-8 rounded-xl shadow-sm relative overflow-hidden border border-slate-100">
          <div class="flex justify-between items-start mb-6">
            <div>
              <h3 class="text-lg font-bold text-slate-900">核心竞争力图谱</h3>
              <p class="text-xs text-slate-500">对比行业基准（Top 10%）</p>
            </div>
            <div class="flex space-x-4 text-xs font-semibold">
              <div class="flex items-center"><span class="w-3 h-3 bg-indigo-600 rounded-full mr-2"></span>个人</div>
              <div class="flex items-center"><span class="w-3 h-3 bg-slate-300 rounded-full mr-2"></span>基准</div>
            </div>
          </div>
          <div class="h-64 w-full flex items-center justify-center relative">
            <canvas ref="chartRef" id="radarChart"></canvas>
            <div v-if="!chartInstance" class="absolute inset-0 flex items-center justify-center text-slate-400 text-sm">
              图表加载中...
            </div>
          </div>
        </div>

        <!-- Key Metrics Column -->
        <div class="col-span-12 lg:col-span-4 space-y-6">
          <div class="bg-indigo-600 p-6 rounded-xl text-white relative overflow-hidden shadow-sm">
            <div class="relative z-10">
              <p class="text-sm font-medium opacity-80 uppercase tracking-widest">综合评分</p>
              <div class="text-5xl font-extrabold mt-2">{{ scoreText }}</div>
              <p class="text-xs mt-4 flex items-center font-semibold">
                <span class="material-symbols-outlined text-sm mr-1">trending_up</span>
                近期趋势：{{ trendText }}
              </p>
            </div>
            <div class="absolute -right-4 -bottom-4 opacity-10">
              <span class="material-symbols-outlined text-9xl">auto_awesome</span>
            </div>
          </div>
          <div class="bg-white p-6 rounded-xl shadow-sm border-l-4 border-red-500">
            <p class="text-sm font-medium text-slate-500">核心弱项</p>
            <div class="mt-2 flex items-center justify-between">
              <span class="text-lg font-bold text-slate-900">{{ weakTopic }}</span>
              <span class="text-red-500 font-bold">-{{ Math.round(100 - (weakRank[0]?.averageScore || 0)) }}%</span>
            </div>
            <p class="text-xs text-slate-500 mt-2">建议加强：{{ weakTopic }} 练习</p>
          </div>
        </div>
      </div>

      <!-- Timeline / Event List Area -->
      <div class="bg-white rounded-xl shadow-sm overflow-hidden border border-slate-100">
        <div class="px-8 py-6 flex items-center justify-between border-b border-slate-100">
          <h3 class="text-lg font-bold text-slate-900">练习事件流</h3>
          <div class="flex space-x-2">
            <span class="px-3 py-1 bg-slate-100 text-slate-600 text-xs font-semibold rounded-full">全部练习</span>
            <span class="px-3 py-1 hover:bg-slate-100 text-slate-600 text-xs font-semibold rounded-full cursor-pointer transition-colors">高难度</span>
            <span class="px-3 py-1 hover:bg-slate-100 text-slate-600 text-xs font-semibold rounded-full cursor-pointer transition-colors">弱项复盘</span>
          </div>
        </div>
        <div class="overflow-x-auto">
          <table class="w-full text-left border-collapse">
            <thead class="bg-slate-50">
              <tr>
                <th class="px-8 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider">时间</th>
                <th class="px-8 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider">练习主题 (Topic)</th>
                <th class="px-8 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider">得分 (Score)</th>
                <th class="px-8 py-4 text-xs font-bold text-slate-500 uppercase tracking-wider">表现评估</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              <tr v-for="item in events" :key="`${item.timestamp}-${item.topic}-${item.score}`" class="hover:bg-slate-50/50 transition-colors">
                <td class="px-8 py-4 text-sm text-slate-500">{{ formatTime(item.timestamp) }}</td>
                <td class="px-8 py-4 text-sm font-semibold text-slate-900">{{ item.topic || '未知主题' }}</td>
                <td class="px-8 py-4">
                  <span class="inline-flex px-2 py-1 rounded-full bg-indigo-50 text-indigo-700 font-bold text-xs">{{ item.score ?? 0 }}</span>
                </td>
                <td class="px-8 py-4 text-sm text-slate-500">
                  {{ (item.score || 0) >= 80 ? '优秀' : (item.score || 0) >= 60 ? '良好' : '需提升' }}
                </td>
              </tr>
              <tr v-if="!events.length">
                <td colspan="4" class="px-8 py-8 text-center text-sm text-slate-500">暂无学习事件数据</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="px-8 py-4 bg-slate-50 flex justify-center">
          <button class="text-xs font-bold text-indigo-600 hover:underline transition-all">查看历史全部记录</button>
        </div>
      </div>

      <!-- Floating Recommendations -->
      <div class="mt-10 flex flex-col md:flex-row gap-6">
        <div class="flex-1 bg-white p-8 rounded-xl shadow-sm relative group overflow-hidden border-t-4 border-indigo-600">
          <div class="flex items-center mb-4">
            <span class="material-symbols-outlined text-indigo-600 mr-2">lightbulb</span>
            <h4 class="font-bold text-slate-900">下一步学习路径</h4>
          </div>
          <ul class="space-y-3">
            <li class="flex items-start">
              <span class="w-1.5 h-1.5 bg-indigo-600 rounded-full mt-2 mr-3 flex-shrink-0"></span>
              <p class="text-sm text-slate-600 leading-relaxed">基于你的弱项，建议练习：<strong class="text-slate-900">《{{ weakTopic }}》</strong></p>
            </li>
            <li class="flex items-start">
              <span class="w-1.5 h-1.5 bg-indigo-600 rounded-full mt-2 mr-3 flex-shrink-0"></span>
              <p class="text-sm text-slate-600 leading-relaxed">巩固熟项：<strong class="text-slate-900">《{{ familiarTopic }}》</strong></p>
            </li>
          </ul>
        </div>
        <div class="flex-1 bg-gradient-to-br from-indigo-50 to-white p-8 rounded-xl shadow-sm border border-indigo-100 flex items-center justify-between">
          <div>
            <h4 class="font-bold text-indigo-900 mb-2">生成 PDF 报告</h4>
            <p class="text-xs text-indigo-700/70 mb-4">导出包含完整多维分析与建议的周报</p>
            <button class="bg-indigo-600 text-white px-5 py-2 rounded-lg text-xs font-bold hover:bg-indigo-700 active:scale-95 transition-all flex items-center">
              <span class="material-symbols-outlined text-sm mr-2">download</span> 立即导出
            </button>
          </div>
          <div class="hidden sm:block opacity-20">
            <span class="material-symbols-outlined text-8xl text-indigo-900">description</span>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import Chart from 'chart.js/auto'
import { computed, onMounted, ref, nextTick, watch } from 'vue'
import { loadProfileEvents, loadProfileOverview } from '../api/profile'

const loading = ref(false)
const error = ref('')
const overview = ref({})
const events = ref([])

const snapshot = computed(() => overview.value.snapshot || {})
const weakRank = computed(() => snapshot.value.weakTopicRank || [])
const familiarRank = computed(() => snapshot.value.familiarTopicRank || [])

const scoreText = computed(() => {
  const list = [...weakRank.value, ...familiarRank.value]
  if (!list.length) {
    return 'N/A'
  }
  const sum = list.reduce((acc, item) => acc + (item.averageScore || 0), 0)
  return (sum / list.length).toFixed(1)
})
const trendText = computed(() => snapshot.value.recentTrend || '暂无')
const weakTopic = computed(() => weakRank.value[0]?.topic || '暂无数据')
const familiarTopic = computed(() => familiarRank.value[0]?.topic || '基础稳固')

const formatTime = (value) => {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

const chartRef = ref(null)
const chartInstance = ref(null)

const renderChart = () => {
  if (!chartRef.value) return
  
  if (chartInstance.value) {
    chartInstance.value.destroy()
  }

  const allTopics = [...familiarRank.value, ...weakRank.value]
  const uniqueTopicsMap = new Map()
  allTopics.forEach(item => uniqueTopicsMap.set(item.topic, item))
  
  const uniqueTopics = Array.from(uniqueTopicsMap.values())
    .sort((a, b) => (b.averageScore || 0) - (a.averageScore || 0))
    .slice(0, 5)

  let labels = []
  let dataScores = []
  let dataBaseline = []

  if (uniqueTopics.length > 0) {
    uniqueTopics.forEach(t => {
      labels.push(t.topic.length > 6 ? t.topic.substring(0, 6) + '..' : t.topic)
      dataScores.push(t.averageScore || 0)
      dataBaseline.push(Math.min(100, (t.averageScore || 60) + 15))
    })
  } else {
    labels = ['架构思维', '工程实践', '逻辑表达', '业务理解', '算法基础']
    dataScores = [0, 0, 0, 0, 0]
    dataBaseline = [80, 85, 75, 80, 70]
  }

  chartInstance.value = new Chart(chartRef.value, {
    type: 'radar',
    data: {
      labels,
      datasets: [
        {
          label: '个人',
          data: dataScores,
          fill: true,
          backgroundColor: 'rgba(79, 70, 229, 0.2)', // indigo-600 with opacity
          borderColor: 'rgba(79, 70, 229, 1)',
          pointBackgroundColor: 'rgba(79, 70, 229, 1)',
          pointBorderColor: '#fff',
          pointHoverBackgroundColor: '#fff',
          pointHoverBorderColor: 'rgba(79, 70, 229, 1)'
        },
        {
          label: '基准',
          data: dataBaseline,
          fill: true,
          backgroundColor: 'rgba(203, 213, 225, 0.1)', // slate-300 with opacity
          borderColor: 'rgba(203, 213, 225, 0.5)',
          borderDash: [5, 5],
          pointBackgroundColor: 'rgba(203, 213, 225, 1)',
          pointBorderColor: '#fff',
          pointHoverBackgroundColor: '#fff',
          pointHoverBorderColor: 'rgba(203, 213, 225, 1)'
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        r: {
          angleLines: { color: 'rgba(226, 232, 240, 0.5)' },
          grid: { color: 'rgba(226, 232, 240, 0.5)' },
          pointLabels: {
            font: { family: "'Inter', sans-serif", size: 11, weight: '600' },
            color: '#64748b'
          },
          ticks: { display: false, min: 0, max: 100 }
        }
      },
      plugins: {
        legend: { display: false },
        tooltip: {
          backgroundColor: 'rgba(15, 23, 42, 0.9)',
          titleFont: { size: 13 },
          bodyFont: { size: 12 },
          padding: 10,
          cornerRadius: 8,
          displayColors: true
        }
      }
    }
  })
}

const reload = async () => {
  loading.value = true
  error.value = ''
  try {
    const [overviewData, eventsData] = await Promise.all([
      loadProfileOverview(),
      loadProfileEvents(10)
    ])
    overview.value = overviewData || {}
    events.value = Array.isArray(eventsData) ? eventsData : []
    await nextTick()
    renderChart()
  } catch (e) {
    error.value = `加载画像失败: ${e.message || 'unknown'}`
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  reload()
})
</script>
