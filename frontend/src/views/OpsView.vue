<template>
  <div class="bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100 antialiased min-h-screen">
    <!-- TopNavBar Shell -->
    <header class="fixed top-0 right-0 h-16 bg-white/80 dark:bg-slate-950/80 backdrop-blur-xl border-b border-slate-100 flex justify-between items-center px-8 z-40 transition-all duration-300" :class="sidebarCollapsed ? 'left-20' : 'left-64'">
      <div class="flex items-center gap-4">
        <h1 class="text-xl font-bold tracking-tight text-indigo-700 dark:text-indigo-400">观测与运维 <span class="text-slate-500 dark:text-slate-400 dark:text-slate-500 font-medium text-sm ml-2">/ 实时监控 RAG 检索链路性能与系统高可用状态</span></h1>
      </div>
    </header>

    <!-- Main Content Canvas -->
    <main class="pt-24 px-8 pb-12 min-h-screen bg-slate-50 dark:bg-slate-800/50 transition-all duration-300" :class="sidebarCollapsed ? 'ml-20' : 'ml-64'">
      <!-- Header & Tabs -->
      <div class="mb-8">
        <div class="flex items-end justify-between">
          <div>
            <h2 class="text-3xl font-extrabold tracking-tight text-slate-900 dark:text-slate-100 mb-2">系统全链路观测</h2>
            <p class="text-slate-500 dark:text-slate-400 dark:text-slate-500 max-w-2xl leading-relaxed">监控 RAG 检索链路性能、A2A 通信状态及核心运维审计日志，确保数字化叙事引擎的高可用运行。</p>
          </div>
          <div class="flex bg-white dark:bg-slate-900 border border-slate-200 p-1 rounded-xl shadow-sm">
            <button class="px-6 py-2 rounded-lg text-sm font-bold bg-slate-100 dark:bg-slate-800 text-indigo-700 shadow-sm transition-all" @click="reload" :disabled="loading">
              <span class="material-symbols-outlined align-middle mr-1 text-[18px]" :class="loading ? 'animate-spin' : ''">refresh</span>
              刷新数据
            </button>
          </div>
        </div>
        <p v-if="hint && hint !== '可执行清理与重放操作' && hint !== '数据已刷新'" class="mt-2 text-sm text-indigo-600">{{ hint }}</p>
      </div>

      <!-- Content Grid: Bento Style -->
      <div class="grid grid-cols-12 gap-6">
        <div class="col-span-12 bg-white dark:bg-slate-900 rounded-xl p-8 shadow-sm border border-slate-200">
          <div class="flex items-center justify-between mb-6">
            <div>
              <h3 class="text-xl font-bold flex items-center gap-2 text-slate-900 dark:text-slate-100">
                <span class="material-symbols-outlined text-indigo-700">tune</span>
                评测数据集控制台
              </h3>
              <p class="mt-2 text-sm text-slate-500 dark:text-slate-400 dark:text-slate-500">
                直接在 /ops 页面选择分层黄金集并触发检索评测或生成质量评测。
              </p>
            </div>
            <button @click="loadEvalDatasets" :disabled="datasetLoading" class="px-4 py-2 bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 rounded-lg text-sm font-bold hover:bg-slate-200 transition-all disabled:opacity-50 flex items-center gap-2">
              <span class="material-symbols-outlined text-sm" :class="datasetLoading ? 'animate-spin' : ''">{{ datasetLoading ? 'progress_activity' : 'sync' }}</span>
              {{ datasetLoading ? '加载中...' : '刷新数据集' }}
            </button>
          </div>

          <div class="grid grid-cols-2 gap-6">
            <section class="rounded-xl border border-slate-200 bg-slate-50 dark:bg-slate-800/50 p-5">
              <div class="flex items-center justify-between mb-4">
                <h4 class="text-sm font-bold text-slate-900 dark:text-slate-100">检索评测</h4>
                <span v-if="retrievalEvalReport" class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">
                  {{ retrievalEvalReport.totalCases || 0 }} 样本
                </span>
              </div>
              <select v-model="selectedRetrievalDataset" class="w-full px-3 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-medium text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-2 focus:ring-indigo-500">
                <option v-for="item in retrievalDatasets" :key="item.datasetId" :value="item.datasetId">
                  {{ item.title }} / {{ item.datasetId }}
                </option>
              </select>
              <p class="mt-2 min-h-10 text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">
                {{ selectedRetrievalDatasetDescription }}
              </p>
              <div class="mt-4 flex items-center gap-3">
                <button @click="runRetrievalEvalAction" :disabled="retrievalEvalLoading || !selectedRetrievalDataset" class="px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm font-bold hover:bg-indigo-700 transition-all disabled:opacity-50 flex items-center gap-2">
                  <span class="material-symbols-outlined text-sm" :class="retrievalEvalLoading ? 'animate-spin' : ''">{{ retrievalEvalLoading ? 'progress_activity' : 'play_arrow' }}</span>
                  {{ retrievalEvalLoading ? '评测中...' : '运行检索评测' }}
                </button>
                <button @click="loadRetrievalEvalHistoryAction" class="px-4 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-bold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:bg-slate-800 transition-all">
                  历史记录
                </button>
              </div>
              <div v-if="retrievalEvalReport" class="mt-4 grid grid-cols-4 gap-3">
                <div class="rounded-lg bg-white dark:bg-slate-900 p-3 border border-slate-200">
                  <div class="text-[10px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 dark:text-slate-500">Recall@1</div>
                  <div class="mt-1 text-lg font-black text-slate-900 dark:text-slate-100">{{ formatPercent(retrievalEvalReport.recallAt1) }}</div>
                </div>
                <div class="rounded-lg bg-white dark:bg-slate-900 p-3 border border-slate-200">
                  <div class="text-[10px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 dark:text-slate-500">Recall@3</div>
                  <div class="mt-1 text-lg font-black text-slate-900 dark:text-slate-100">{{ formatPercent(retrievalEvalReport.recallAt3) }}</div>
                </div>
                <div class="rounded-lg bg-white dark:bg-slate-900 p-3 border border-slate-200">
                  <div class="text-[10px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 dark:text-slate-500">Recall@5</div>
                  <div class="mt-1 text-lg font-black text-slate-900 dark:text-slate-100">{{ formatPercent(retrievalEvalReport.recallAt5) }}</div>
                </div>
                <div class="rounded-lg bg-white dark:bg-slate-900 p-3 border border-slate-200">
                  <div class="text-[10px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 dark:text-slate-500">MRR</div>
                  <div class="mt-1 text-lg font-black text-slate-900 dark:text-slate-100">{{ formatPercent(retrievalEvalReport.mrr) }}</div>
                </div>
              </div>
            </section>

            <section class="rounded-xl border border-slate-200 bg-slate-50 dark:bg-slate-800/50 p-5">
              <div class="flex items-center justify-between mb-4">
                <h4 class="text-sm font-bold text-slate-900 dark:text-slate-100">RAG 生成质量评测</h4>
                <span v-if="qualityEvalReport" class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">
                  {{ qualityEvalReport.totalCases || 0 }} 样本
                </span>
              </div>
              <div class="grid grid-cols-[1fr_auto] gap-3">
                <select v-model="selectedQualityDataset" class="w-full px-3 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-medium text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-2 focus:ring-indigo-500">
                  <option v-for="item in qualityDatasets" :key="item.datasetId" :value="item.datasetId">
                    {{ item.title }} / {{ item.datasetId }}
                  </option>
                </select>
                <select v-model="selectedEngine" class="px-3 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-medium text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-2 focus:ring-indigo-500">
                  <option value="">自动</option>
                  <option value="java">Java</option>
                  <option value="ragas">Ragas</option>
                </select>
              </div>
              <p class="mt-2 min-h-10 text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">
                {{ selectedQualityDatasetDescription }}
              </p>
              <div class="mt-4 flex items-center gap-3">
                <button @click="runQualityEval" :disabled="qualityEvalLoading || !selectedQualityDataset" class="px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm font-bold hover:bg-indigo-700 transition-all disabled:opacity-50 flex items-center gap-2">
                  <span class="material-symbols-outlined text-sm" :class="qualityEvalLoading ? 'animate-spin' : ''">{{ qualityEvalLoading ? 'progress_activity' : 'play_arrow' }}</span>
                  {{ qualityEvalLoading ? '评测中...' : '运行质量评测' }}
                </button>
                <button @click="loadQualityEvalHistory" class="px-4 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-bold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:bg-slate-800 transition-all">
                  历史记录
                </button>
                <span v-if="engineStatus" class="text-xs px-2 py-1 rounded-full" :class="engineStatus.ragasEngineAvailable ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 dark:text-slate-500'">
                  Ragas: {{ engineStatus.ragasEngineAvailable ? '可用' : '不可用' }}
                </span>
              </div>
            </section>
          </div>

          <div v-if="retrievalEvalRuns.length" class="mt-6">
            <h4 class="text-sm font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-widest mb-3">检索评测历史</h4>
            <div class="grid grid-cols-4 gap-3">
              <div v-for="run in retrievalEvalRuns.slice(0, 4)" :key="run.runId" class="rounded-lg border border-slate-200 bg-slate-50 dark:bg-slate-800/50 p-4">
                <div class="text-xs font-mono text-indigo-600">{{ run.runId?.substring(0, 8) || '-' }}</div>
                <div class="mt-1 text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">{{ run.datasetSource || '-' }}</div>
                <div class="mt-3 text-sm font-bold text-slate-900 dark:text-slate-100">Recall@5 {{ formatPercent(run.recallAt5) }}</div>
                <div class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">MRR {{ formatPercent(run.mrr) }}</div>
              </div>
            </div>
          </div>
        </div>

        <!-- Key Metrics Summary -->
        <div class="col-span-12 grid grid-cols-4 gap-6">
          <div class="bg-white dark:bg-slate-900 p-6 rounded-xl border border-slate-200 shadow-sm hover:border-indigo-300 transition-all">
            <div class="flex items-center gap-3 mb-4">
              <span class="material-symbols-outlined text-indigo-600 bg-indigo-50 p-2 rounded-lg" data-icon="speed">speed</span>
              <span class="text-xs font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-wider">平均耗时</span>
            </div>
            <div class="text-2xl font-black text-slate-900 dark:text-slate-100">{{ overview.avgLatencyMs ?? 0 }}<span class="text-sm font-medium ml-1 opacity-60">ms</span></div>
            <div class="mt-2 flex items-center gap-1 text-xs text-indigo-500 font-medium">
              <span class="material-symbols-outlined text-xs">analytics</span> 统计中
            </div>
          </div>
          <div class="bg-white dark:bg-slate-900 p-6 rounded-xl border border-slate-200 shadow-sm hover:border-indigo-300 transition-all">
            <div class="flex items-center gap-3 mb-4">
              <span class="material-symbols-outlined text-indigo-600 bg-indigo-50 p-2 rounded-lg">timer</span>
              <span class="text-xs font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-wider">P95 耗时</span>
            </div>
            <div class="text-2xl font-black text-slate-900 dark:text-slate-100">{{ p95Latency }}<span class="text-sm font-medium ml-1 opacity-60">ms</span></div>
            <div class="mt-2 flex items-center gap-1 text-xs text-amber-500 font-medium">
              <span class="material-symbols-outlined text-xs">bolt</span> 性能基准
            </div>
          </div>
          <div class="bg-white dark:bg-slate-900 p-6 rounded-xl border border-slate-200 shadow-sm hover:border-indigo-300 transition-all">
            <div class="flex items-center gap-3 mb-4">
              <span class="material-symbols-outlined text-indigo-600 bg-indigo-50 p-2 rounded-lg" data-icon="find_in_page">find_in_page</span>
              <span class="text-xs font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-wider">平均召回文档</span>
            </div>
            <div class="text-2xl font-black text-slate-900 dark:text-slate-100">{{ overview.avgRetrievedDocs ?? 0 }}<span class="text-sm font-medium ml-1 opacity-60">docs</span></div>
            <div class="mt-2 flex items-center gap-1 text-xs text-emerald-600 font-medium">
              <span class="material-symbols-outlined text-xs" data-icon="stable">video_stable</span> 稳定运行中
            </div>
          </div>
          <div class="bg-white dark:bg-slate-900 p-6 rounded-xl border border-slate-200 shadow-sm hover:border-indigo-300 transition-all">
            <div class="flex items-center gap-3 mb-4">
              <span class="material-symbols-outlined text-indigo-600 bg-indigo-50 p-2 rounded-lg">check_circle</span>
              <span class="text-xs font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-wider">成功率</span>
            </div>
            <div class="text-2xl font-black text-slate-900 dark:text-slate-100">{{ successRate }}</div>
            <div class="mt-2 flex items-center gap-1 text-xs text-indigo-600 font-medium">
              <span class="material-symbols-outlined text-xs">verified</span> 高可用指标
            </div>
          </div>
        </div>

        <div class="col-span-12 grid grid-cols-4 gap-6">
          <button class="bg-white dark:bg-slate-900 p-5 rounded-xl border border-slate-200 shadow-sm text-left transition-all hover:border-indigo-300"
                  @click="applyOverviewPreset('active')">
            <div class="text-[10px] font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-wider">活跃链路</div>
            <div class="mt-2 text-2xl font-black text-slate-900 dark:text-slate-100">{{ overview.activeTraceCount ?? 0 }}</div>
            <div class="mt-1 text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">当前仍在运行的 Trace</div>
          </button>
          <button class="bg-white dark:bg-slate-900 p-5 rounded-xl border border-slate-200 shadow-sm text-left transition-all hover:border-indigo-300"
                  @click="applyOverviewPreset('risky')">
            <div class="text-[10px] font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-wider">风险链路</div>
            <div class="mt-2 text-2xl font-black text-amber-700">{{ overview.riskyTraceCount ?? 0 }}</div>
            <div class="mt-1 text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">最近窗口内带风险标签的 Trace</div>
          </button>
          <button class="bg-white dark:bg-slate-900 p-5 rounded-xl border border-slate-200 shadow-sm text-left transition-all hover:border-indigo-300"
                  @click="applyOverviewPreset('fallback')">
            <div class="text-[10px] font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-wider">Fallback 次数</div>
            <div class="mt-2 text-2xl font-black text-orange-700">{{ overview.fallbackTraceCount ?? 0 }}</div>
            <div class="mt-1 text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">最近窗口内触发 fallback 的 Trace</div>
          </button>
          <button class="bg-white dark:bg-slate-900 p-5 rounded-xl border border-slate-200 shadow-sm text-left transition-all hover:border-indigo-300"
                  @click="applyOverviewPreset('emptyRetrieval')">
            <div class="text-[10px] font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-wider">空召回链路</div>
            <div class="mt-2 text-2xl font-black text-rose-700">{{ overview.emptyRetrievalTraceCount ?? 0 }}</div>
            <div class="mt-1 text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">最近窗口内召回为空的 Trace</div>
          </button>
        </div>

        <div class="col-span-12 rounded-xl border p-5 shadow-sm"
             :class="overviewAlertLevel === 'HIGH'
               ? 'bg-red-50 border-red-200'
               : overviewAlertLevel === 'MEDIUM'
                 ? 'bg-amber-50 border-amber-200'
                 : overviewAlertLevel === 'INFO'
                   ? 'bg-indigo-50 border-indigo-200'
                   : 'bg-emerald-50 border-emerald-200'">
          <div class="flex items-center justify-between gap-4">
            <div>
              <div class="text-[10px] font-bold uppercase tracking-widest"
                   :class="overviewAlertLevel === 'HIGH'
                     ? 'text-red-700'
                     : overviewAlertLevel === 'MEDIUM'
                       ? 'text-amber-700'
                       : overviewAlertLevel === 'INFO'
                         ? 'text-indigo-700'
                         : 'text-emerald-700'">
                观测告警
              </div>
              <div class="mt-2 text-sm font-bold text-slate-900 dark:text-slate-100">
                {{ overviewAlertHeadline }}
              </div>
            </div>
            <div class="flex flex-wrap justify-end gap-2">
              <button v-for="tag in overviewAlertTags" :key="tag" class="px-3 py-1.5 rounded-lg text-xs font-bold border transition-all hover:opacity-85"
                    @click="applyAlertPreset(tag)"
                    :class="overviewAlertLevel === 'HIGH'
                      ? 'bg-white text-red-700 border-red-200'
                      : overviewAlertLevel === 'MEDIUM'
                        ? 'bg-white text-amber-700 border-amber-200'
                        : overviewAlertLevel === 'INFO'
                          ? 'bg-white text-indigo-700 border-indigo-200'
                          : 'bg-white text-emerald-700 border-emerald-200'">
                {{ formatAlertTag(tag) }}
              </button>
            </div>
          </div>
        </div>

        <div class="col-span-12 grid grid-cols-[1.2fr_1fr] gap-6">
          <div class="bg-white dark:bg-slate-900 p-6 rounded-xl border border-slate-200 shadow-sm">
            <div class="flex items-center justify-between mb-4">
              <div>
                <h3 class="text-sm font-bold text-slate-900 dark:text-slate-100">状态分布</h3>
                <p class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500 mt-1">最近窗口内已完成链路的状态结构</p>
              </div>
            </div>
            <div class="space-y-3">
              <button v-for="item in overviewStatusEntries" :key="item.key" class="grid grid-cols-[88px_1fr_48px] gap-3 items-center w-full text-left transition-all hover:opacity-85"
                      @click="applyStatusPreset(item.key)">
                <span class="text-xs font-bold" :class="item.textClass">{{ item.label }}</span>
                <div class="h-2 rounded-full bg-slate-100 dark:bg-slate-800 overflow-hidden">
                  <div class="h-full rounded-full transition-all duration-500" :class="item.barClass" :style="{ width: `${item.percent}%` }"></div>
                </div>
                <span class="text-xs font-mono text-slate-500 dark:text-slate-400 dark:text-slate-500 text-right">{{ item.value }}</span>
              </button>
            </div>
          </div>

          <div class="bg-white dark:bg-slate-900 p-6 rounded-xl border border-slate-200 shadow-sm">
            <div class="flex items-center justify-between mb-4">
              <div>
                <h3 class="text-sm font-bold text-slate-900 dark:text-slate-100">风险标签分布</h3>
                <p class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500 mt-1">最近窗口内风险触发次数</p>
              </div>
            </div>
            <div v-if="overviewRiskEntries.length" class="flex flex-wrap gap-2">
              <button
                v-for="item in overviewRiskEntries"
                :key="item.key"
                class="px-3 py-1.5 rounded-lg text-xs font-bold border bg-amber-50 text-amber-800 border-amber-200 transition-all hover:opacity-85"
                @click="applyRiskTagPreset(item.key)"
              >
                {{ item.label }} · {{ item.value }}
              </button>
            </div>
            <div v-else class="text-sm text-emerald-700 bg-emerald-50 border border-emerald-200 rounded-lg px-4 py-3">
              最近窗口内未发现风险标签
            </div>
          </div>
        </div>

        <div class="col-span-12 bg-white dark:bg-slate-900 p-6 rounded-xl border border-slate-200 shadow-sm">
          <div class="flex items-center justify-between mb-5">
            <div>
              <h3 class="text-sm font-bold text-slate-900 dark:text-slate-100">近期退化趋势</h3>
              <p class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500 mt-1">基于最近窗口分桶观察慢链路、Fallback、空召回与失败趋势</p>
            </div>
            <div class="flex items-center gap-3 text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">
              <span>峰值风险桶: {{ trendPeakRisk }}</span>
              <span>峰值慢链路桶: {{ trendPeakSlow }}</span>
            </div>
          </div>
          <div v-if="overviewTrendBuckets.length" class="grid grid-cols-8 gap-3">
            <button v-for="bucket in overviewTrendBuckets" :key="bucket.key" class="rounded-xl border border-slate-200 bg-slate-50 dark:bg-slate-800/40 p-4 text-left transition-all hover:border-indigo-300"
                    @click="applyTrendBucketPreset(bucket)">
              <div class="flex items-center justify-between">
                <span class="text-[10px] font-black tracking-widest uppercase text-slate-500 dark:text-slate-400 dark:text-slate-500">{{ bucket.label }}</span>
                <span class="text-[10px] font-mono text-slate-400">{{ bucket.total }}</span>
              </div>
              <div class="mt-3 h-24 flex items-end gap-1">
                <div class="flex-1 rounded-t bg-amber-400/80" :style="{ height: `${bucket.riskyPercent}%` }" title="风险链路"></div>
                <div class="flex-1 rounded-t bg-orange-400/80" :style="{ height: `${bucket.fallbackPercent}%` }" title="Fallback"></div>
                <div class="flex-1 rounded-t bg-rose-400/80" :style="{ height: `${bucket.emptyRetrievalPercent}%` }" title="空召回"></div>
                <div class="flex-1 rounded-t bg-red-500/80" :style="{ height: `${bucket.failedPercent}%` }" title="失败"></div>
                <div class="flex-1 rounded-t bg-indigo-500/80" :style="{ height: `${bucket.slowPercent}%` }" title="慢链路"></div>
              </div>
              <div class="mt-3 space-y-1 text-[10px] text-slate-500 dark:text-slate-400 dark:text-slate-500">
                <div>risk {{ bucket.risky }}</div>
                <div>slow {{ bucket.slow }}</div>
                <div>fallback {{ bucket.fallback }}</div>
                <div>empty {{ bucket.emptyRetrieval }}</div>
                <div>failed {{ bucket.failed }}</div>
              </div>
            </button>
          </div>
          <div v-else class="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
            当前样本不足，暂无趋势分桶数据
          </div>
        </div>

        <!-- RAG Trace Table -->
        <div id="section-rag" class="col-span-8 bg-white dark:bg-slate-900 rounded-xl p-8 shadow-sm border border-slate-200">
          <div class="flex items-center justify-between mb-8">
            <h3 class="text-xl font-bold flex items-center gap-2 text-slate-900 dark:text-slate-100">
              <span class="material-symbols-outlined text-indigo-700" data-icon="route">route</span>
              RAG 链路实时追踪
            </h3>
            <div class="flex items-center gap-4">
              <button class="text-xs font-bold text-indigo-600 hover:text-indigo-800 transition-colors flex items-center gap-1" @click="toggleTraceExpansion" v-if="traces.length > 5 || showFullTraces">
                {{ showFullTraces ? '收起' : '展开全部' }}
                <span class="material-symbols-outlined text-[14px] transition-transform duration-300" :class="showFullTraces ? 'rotate-180' : ''">expand_more</span>
              </button>
              <div class="flex items-center gap-2">
                <span class="w-3 h-3 rounded-full bg-emerald-500 animate-pulse"></span>
                <span class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500 font-medium">实时监听中...</span>
              </div>
            </div>
          </div>
          <div class="mb-6 grid grid-cols-[1.4fr_repeat(5,auto)] gap-3 items-center">
            <input
              v-model.trim="traceSearch"
              type="text"
              placeholder="筛选 Trace ID / 风险标签"
              class="px-4 py-2 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 rounded-lg text-sm text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <select v-model="traceStatusFilter" class="px-3 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-medium text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-2 focus:ring-indigo-500">
              <option value="ALL">全部状态</option>
              <option value="SUCCESS">SUCCESS</option>
              <option value="SLOW">SLOW</option>
              <option value="FAILED">FAILED</option>
              <option value="RUNNING">RUNNING</option>
            </select>
            <label class="flex items-center gap-2 text-xs font-bold text-slate-600 dark:text-slate-300">
              <input v-model="onlyRiskyTraces" type="checkbox" class="w-4 h-4 accent-indigo-600">
              只看异常
            </label>
            <label class="flex items-center gap-2 text-xs font-bold text-slate-600 dark:text-slate-300">
              <input v-model="onlyFallbackTraces" type="checkbox" class="w-4 h-4 accent-indigo-600">
              只看 Fallback
            </label>
            <label class="flex items-center gap-2 text-xs font-bold text-slate-600 dark:text-slate-300">
              <input v-model="onlyEmptyRetrievalTraces" type="checkbox" class="w-4 h-4 accent-indigo-600">
              只看空召回
            </label>
            <label class="flex items-center gap-2 text-xs font-bold text-slate-600 dark:text-slate-300">
              <input v-model="onlySlowTraces" type="checkbox" class="w-4 h-4 accent-indigo-600">
              只看慢链路
            </label>
          </div>
          <div class="mb-6 flex items-center gap-3">
            <button @click="applyTraceFilters" :disabled="loading" class="px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm font-bold hover:bg-indigo-700 transition-all disabled:opacity-50">
              应用筛选
            </button>
            <button @click="resetTraceFilters" :disabled="loading" class="px-4 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-bold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:bg-slate-800 transition-all disabled:opacity-50">
              清空筛选
            </button>
          </div>
          <div v-if="activeTimeWindowLabel" class="mb-4">
            <span class="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-bold border border-indigo-200 bg-indigo-50 text-indigo-700">
              时间窗口: {{ activeTimeWindowLabel }}
            </span>
          </div>
          <div class="overflow-hidden">
            <table class="w-full text-left">
              <thead>
                <tr class="text-slate-500 dark:text-slate-400 dark:text-slate-500 text-xs uppercase tracking-widest font-bold border-b border-slate-100">
                  <th class="pb-4 font-bold">Trace ID</th>
                  <th class="pb-4 font-bold">耗时 (Latency)</th>
                  <th class="pb-4 font-bold">首 Token</th>
                  <th class="pb-4 font-bold">召回数量</th>
                  <th class="pb-4 font-bold">风险</th>
                  <th class="pb-4 font-bold">状态</th>
                  <th class="pb-4 text-right font-bold">操作</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                <tr v-for="item in displayedTraces" :key="item.traceId" class="group hover:bg-slate-50 dark:bg-slate-800/50 transition-all">
                  <td class="py-4 text-sm font-mono text-indigo-600">{{ item.traceId || '-' }}</td>
                  <td class="py-4 text-sm font-semibold text-slate-700 dark:text-slate-300">{{ item.latencyMs ?? 0 }} ms</td>
                  <td class="py-4 text-sm text-slate-700 dark:text-slate-300">{{ item.firstTokenMs ?? 0 }} ms</td>
                  <td class="py-4 text-sm text-slate-700 dark:text-slate-300">{{ item.retrievedCount ?? 0 }}</td>
                  <td class="py-4">
                    <div v-if="item.riskTags?.length" class="flex flex-wrap gap-1">
                      <span v-for="tag in item.riskTags.slice(0, 2)" :key="`${item.traceId}-${tag}`" class="px-2 py-1 rounded text-[10px] font-bold uppercase tracking-tighter bg-amber-100 text-amber-700">
                        {{ formatRiskTag(tag) }}
                      </span>
                    </div>
                    <span v-else class="text-xs text-emerald-600 font-semibold">none</span>
                  </td>
                  <td class="py-4">
                    <span class="px-2 py-1 rounded text-[10px] font-bold uppercase tracking-tighter"
                          :class="getTraceStatusClass(item.status)">
                      {{ item.status }}
                    </span>
                  </td>
                  <td class="py-4 text-right">
                    <button @click="viewDetail(item.traceId)" class="text-indigo-600 hover:text-indigo-800 text-xs font-bold flex items-center gap-1 ml-auto">
                      查看详情 <span class="material-symbols-outlined text-xs">arrow_forward</span>
                    </button>
                  </td>
                </tr>
                <tr v-if="!displayedTraces.length">
                  <td colspan="7" class="py-8 text-center text-sm text-slate-500 dark:text-slate-400 dark:text-slate-500">
                    {{ traces.length ? '当前筛选条件下暂无轨迹数据' : '暂无轨迹数据' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- A2A Idempotency Status -->
        <div id="section-a2a" class="col-span-4 space-y-6">
          <div class="bg-white dark:bg-slate-900 rounded-xl p-6 shadow-sm border border-slate-200">
            <div class="flex items-center justify-between mb-4">
              <h3 class="text-sm font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-widest flex items-center gap-2">
                <span class="material-symbols-outlined text-indigo-700 text-lg" data-icon="rebase_edit">rebase_edit</span>
                高级运维工具
              </h3>
              <button class="text-xs font-bold text-indigo-600 hover:text-indigo-800 transition-colors" @click="showAdvancedOps = !showAdvancedOps">
                {{ showAdvancedOps ? '收起' : '展开' }}
              </button>
            </div>
            <p v-if="!showAdvancedOps" class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500 leading-relaxed">
              这块主要用于 A2A 消息幂等排障与死信重放，日常看 RAG 链路时可保持收起。
            </p>
            <div v-else class="space-y-6">
              <div>
                <div class="flex justify-between items-end mb-2">
                  <span class="text-xs font-bold text-slate-900 dark:text-slate-100">L1 Memory Cache</span>
                  <span class="text-xs text-indigo-600 font-mono">{{ idempotency.inMemorySize || 0 }} keys</span>
                </div>
                <div class="h-2 w-full bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                  <div class="h-full bg-indigo-500 rounded-full" :style="{ width: Math.min(100, ((idempotency.inMemorySize || 0) / 1000) * 100) + '%' }"></div>
                </div>
                <p class="text-[10px] mt-2 text-slate-500 dark:text-slate-400 dark:text-slate-500">清理策略：LRU (Least Recently Used)</p>
              </div>
              <div>
                <div class="flex justify-between items-end mb-2">
                  <span class="text-xs font-bold text-slate-900 dark:text-slate-100">L2 Redis Dist. Cache</span>
                  <span class="text-xs text-indigo-600 font-mono">{{ idempotency.redisSize || 0 }} keys</span>
                </div>
                <div class="h-2 w-full bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                  <div class="h-full bg-indigo-400 rounded-full" :style="{ width: Math.min(100, ((idempotency.redisSize || 0) / 10000) * 100) + '%' }"></div>
                </div>
                <p class="text-[10px] mt-2 text-slate-500 dark:text-slate-400 dark:text-slate-500">持久化：AOF + RDB Enabled</p>
              </div>
            </div>
          </div>

          <!-- Danger Zone -->
          <div v-if="showAdvancedOps" class="rounded-xl border-2 border-dashed border-red-300 p-6 bg-red-50/50 group hover:border-red-400 transition-all">
            <div class="flex items-center gap-3 mb-4">
              <span class="material-symbols-outlined text-red-600" data-icon="dangerous">dangerous</span>
              <h3 class="text-sm font-bold text-red-600 uppercase tracking-widest">危险操作区 (Danger Zone)</h3>
            </div>
            <p class="text-xs text-red-800/80 mb-6 leading-relaxed">
              执行以下操作可能会导致部分正在进行的业务中断或数据不一致，请在确保备份的情况下谨慎操作。
            </p>
            <div class="grid grid-cols-1 gap-3">
              <button class="w-full flex items-center justify-center gap-2 py-3 bg-white dark:bg-slate-900 text-red-600 border border-red-200 rounded-lg text-xs font-bold hover:bg-red-600 hover:text-white transition-all active:scale-95 disabled:opacity-50" @click="purge" :disabled="loading">
                <span class="material-symbols-outlined text-sm" data-icon="delete_sweep">delete_sweep</span>
                清理幂等缓存 (Force Purge)
              </button>
              <button class="w-full flex items-center justify-center gap-2 py-3 bg-white dark:bg-slate-900 text-slate-700 dark:text-slate-300 border border-slate-200 rounded-lg text-xs font-bold hover:bg-slate-100 dark:bg-slate-800 transition-all active:scale-95 disabled:opacity-50" @click="replay" :disabled="loading">
                <span class="material-symbols-outlined text-sm" data-icon="replay">replay</span>
                死信队列重放 (DLQ Replay)
              </button>
            </div>
          </div>
        </div>

        <!-- RAG 生成质量评测 -->
        <div id="section-rag-quality" class="col-span-12 bg-white dark:bg-slate-900 rounded-xl p-8 shadow-sm border border-slate-200">
          <!-- 标题栏 + 运行评测按钮 -->
          <div class="flex items-center justify-between mb-8">
            <h3 class="text-xl font-bold flex items-center gap-2 text-slate-900 dark:text-slate-100">
              <span class="material-symbols-outlined text-indigo-700">lab_research</span>
              RAG 生成质量评测
            </h3>
            <div class="flex items-center gap-3">
              <span v-if="engineStatus" class="text-xs px-2 py-1 rounded-full" :class="engineStatus.ragasEngineAvailable ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 dark:text-slate-500'">
                Ragas: {{ engineStatus.ragasEngineAvailable ? '可用' : '不可用' }}
              </span>
              <button @click="loadQualityEvalHistory" class="px-4 py-2 bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 rounded-lg text-sm font-bold hover:bg-slate-200 transition-all flex items-center gap-2">
                <span class="material-symbols-outlined text-sm">history</span>
                历史记录
              </button>
            </div>
          </div>

          <!-- 四指标雷达图 + 指标卡片 -->
          <div class="grid grid-cols-12 gap-6" v-if="qualityEvalReport">
            <!-- 雷达图 -->
            <div class="col-span-5 flex items-center justify-center">
              <canvas ref="qualityChartRef" class="max-w-[320px] max-h-[320px]"></canvas>
            </div>
            <!-- 四指标卡片 -->
            <div class="col-span-7 grid grid-cols-2 gap-4">
              <div v-for="metric in qualityMetrics" :key="metric.key" class="bg-slate-50 dark:bg-slate-800/50 p-4 rounded-lg border border-slate-200">
                <div class="text-xs font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-wider mb-2">{{ metric.label }}</div>
                <div class="text-2xl font-black text-slate-900 dark:text-slate-100">{{ (metric.value * 100).toFixed(1) }}<span class="text-sm font-medium ml-1 opacity-60">%</span></div>
                <div class="mt-2 h-1.5 w-full bg-slate-200 rounded-full overflow-hidden">
                  <div class="h-full rounded-full transition-all duration-500" :class="metric.value >= 0.8 ? 'bg-emerald-500' : metric.value >= 0.5 ? 'bg-amber-500' : 'bg-red-500'" :style="{ width: (metric.value * 100) + '%' }"></div>
                </div>
              </div>
            </div>
          </div>

          <!-- 历史趋势表格 -->
          <div v-if="showQualityHistory && qualityEvalRuns.length" class="mt-8">
            <h4 class="text-sm font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-widest mb-4 flex items-center gap-2">
              <span class="material-symbols-outlined text-indigo-600 text-sm">timeline</span>
              评测历史
            </h4>
            <table class="w-full text-left">
              <thead>
                <tr class="text-slate-500 dark:text-slate-400 dark:text-slate-500 text-xs uppercase tracking-widest font-bold border-b border-slate-100">
                  <th class="pb-3">运行 ID</th>
                  <th class="pb-3">时间</th>
                  <th class="pb-3">引擎</th>
                  <th class="pb-3">样本数</th>
                  <th class="pb-3">忠实度</th>
                  <th class="pb-3">回答相关性</th>
                  <th class="pb-3">上下文精准度</th>
                  <th class="pb-3">上下文召回</th>
                  <th class="pb-3 text-right">操作</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-100">
                <tr v-for="run in qualityEvalRuns" :key="run.runId" class="hover:bg-slate-50 dark:bg-slate-800/50 transition-all">
                  <td class="py-3 text-sm font-mono text-indigo-600">{{ run.runId?.substring(0, 8) || '-' }}</td>
                  <td class="py-3 text-sm text-slate-500 dark:text-slate-400 dark:text-slate-500">{{ formatTime(run.timestamp) }}</td>
                  <td class="py-3 text-sm">
                    <span class="px-2 py-0.5 rounded-full text-[10px] font-bold" :class="run.engine === 'ragas' ? 'bg-purple-100 text-purple-700' : 'bg-blue-100 text-blue-700'">
                      {{ run.engine === 'ragas' ? 'Ragas' : 'Java' }}
                    </span>
                  </td>
                  <td class="py-3 text-sm">{{ run.totalCases }}</td>
                  <td class="py-3 text-sm font-semibold">{{ ((run.avgFaithfulness || 0) * 100).toFixed(1) }}%</td>
                  <td class="py-3 text-sm font-semibold">{{ ((run.avgAnswerRelevancy || 0) * 100).toFixed(1) }}%</td>
                  <td class="py-3 text-sm font-semibold">{{ ((run.avgContextPrecision || 0) * 100).toFixed(1) }}%</td>
                  <td class="py-3 text-sm font-semibold">{{ ((run.avgContextRecall || 0) * 100).toFixed(1) }}%</td>
                  <td class="py-3 text-right">
                    <button @click="viewQualityEvalDetail(run.runId)" class="text-indigo-600 hover:text-indigo-800 text-xs font-bold">详情</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 逐样本展开 -->
          <div v-if="qualityEvalDetail" class="mt-8">
            <h4 class="text-sm font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 uppercase tracking-widest mb-4">逐样本详情</h4>
            <div v-for="(item, idx) in qualityEvalDetail.results" :key="idx" class="mb-3 border border-slate-200 rounded-lg overflow-hidden">
              <button @click="toggleSample(idx)" class="w-full flex items-center justify-between px-4 py-3 bg-slate-50 dark:bg-slate-800/50 hover:bg-slate-100 dark:bg-slate-800 transition-all text-left">
                <div class="flex items-center gap-3">
                  <span class="px-2 py-0.5 bg-indigo-100 text-indigo-700 text-[10px] font-bold rounded-full">{{ item.tag || '-' }}</span>
                  <span class="text-sm font-medium text-slate-700 dark:text-slate-300">{{ item.query }}</span>
                </div>
                <div class="flex items-center gap-4 text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">
                  <span>忠实度: {{ (item.faithfulness * 100).toFixed(0) }}%</span>
                  <span>相关性: {{ (item.answerRelevancy * 100).toFixed(0) }}%</span>
                  <span class="material-symbols-outlined text-sm transition-transform" :class="expandedSamples.includes(idx) ? 'rotate-180' : ''">expand_more</span>
                </div>
              </button>
              <div v-if="expandedSamples.includes(idx)" class="px-4 py-4 space-y-3 text-sm">
                <div><span class="font-bold text-slate-600 dark:text-slate-400 dark:text-slate-500">标准答案：</span><span class="text-slate-700 dark:text-slate-300">{{ item.groundTruthAnswer }}</span></div>
                <div><span class="font-bold text-slate-600 dark:text-slate-400 dark:text-slate-500">生成答案：</span><span class="text-slate-700 dark:text-slate-300">{{ item.generatedAnswer }}</span></div>
                <div class="grid grid-cols-4 gap-3 mt-2">
                  <div class="bg-slate-50 dark:bg-slate-800/50 p-3 rounded"><div class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500 mb-1">忠实度</div><div class="font-bold">{{ (item.faithfulness * 100).toFixed(1) }}%</div></div>
                  <div class="bg-slate-50 dark:bg-slate-800/50 p-3 rounded"><div class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500 mb-1">回答相关性</div><div class="font-bold">{{ (item.answerRelevancy * 100).toFixed(1) }}%</div></div>
                  <div class="bg-slate-50 dark:bg-slate-800/50 p-3 rounded"><div class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500 mb-1">上下文精准度</div><div class="font-bold">{{ (item.contextPrecision * 100).toFixed(1) }}%</div></div>
                  <div class="bg-slate-50 dark:bg-slate-800/50 p-3 rounded"><div class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500 mb-1">上下文召回</div><div class="font-bold">{{ (item.contextRecall * 100).toFixed(1) }}%</div></div>
                </div>
                <div v-if="item.rationales" class="mt-2">
                  <div class="text-xs font-bold text-slate-500 dark:text-slate-400 dark:text-slate-500 mb-1">LLM 评分理由</div>
                  <div v-for="(reason, metric) in item.rationales" :key="metric" class="text-xs text-slate-600 dark:text-slate-400 dark:text-slate-500 mb-1">
                    <span class="font-semibold">{{ metric }}:</span> {{ reason }}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="col-span-12 bg-white dark:bg-slate-900 rounded-xl p-8 border border-slate-200 shadow-sm">
          <div class="flex items-start justify-between gap-4 mb-6">
            <div>
              <h3 class="text-lg font-bold text-slate-900 dark:text-slate-100">Skills 执行观测</h3>
              <p class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">观测真实可执行 skill 的最近执行事件、降级情况和 trace 关联。</p>
            </div>
            <button @click="loadSkillTelemetryPanel" :disabled="skillTelemetryLoading" class="px-4 py-2 bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 rounded-lg text-sm font-bold hover:bg-slate-200 transition-all disabled:opacity-50 flex items-center gap-2">
              <span class="material-symbols-outlined text-sm" :class="skillTelemetryLoading ? 'animate-spin' : ''">{{ skillTelemetryLoading ? 'progress_activity' : 'sync' }}</span>
              {{ skillTelemetryLoading ? '加载中...' : '刷新 Skills' }}
            </button>
          </div>

          <div class="grid grid-cols-12 gap-6">
            <section class="col-span-4 rounded-xl border border-slate-200 bg-slate-50 dark:bg-slate-800/50 p-5">
              <div class="grid grid-cols-3 gap-3">
                <div class="rounded-lg bg-white dark:bg-slate-900 p-3 border border-slate-200">
                  <div class="text-[10px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 dark:text-slate-500">事件数</div>
                  <div class="mt-1 text-lg font-black text-slate-900 dark:text-slate-100">{{ skillEventCount }}</div>
                </div>
                <div class="rounded-lg bg-white dark:bg-slate-900 p-3 border border-slate-200">
                  <div class="text-[10px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 dark:text-slate-500">降级</div>
                  <div class="mt-1 text-lg font-black text-amber-700">{{ skillFallbackCount }}</div>
                </div>
                <div class="rounded-lg bg-white dark:bg-slate-900 p-3 border border-slate-200">
                  <div class="text-[10px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 dark:text-slate-500">平均耗时</div>
                  <div class="mt-1 text-lg font-black text-slate-900 dark:text-slate-100">{{ skillAvgLatency }}<span class="text-sm font-medium ml-1 opacity-60">ms</span></div>
                </div>
              </div>

              <div class="mt-5 space-y-3">
                <div>
                  <label class="text-[11px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 dark:text-slate-500">Skill</label>
                  <select v-model="selectedSkillId" class="mt-1 w-full px-3 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-medium text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-2 focus:ring-indigo-500">
                    <option value="ALL">全部</option>
                    <option v-for="skillId in skillIds" :key="skillId" :value="skillId">{{ skillId }}</option>
                  </select>
                </div>
                <div>
                  <label class="text-[11px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 dark:text-slate-500">状态</label>
                  <select v-model="selectedSkillStatus" class="mt-1 w-full px-3 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-medium text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-2 focus:ring-indigo-500">
                    <option value="ALL">全部</option>
                    <option value="SUCCESS">SUCCESS</option>
                    <option value="FALLBACK">FALLBACK</option>
                    <option value="SKIPPED">SKIPPED</option>
                    <option value="FAILED">FAILED</option>
                  </select>
                </div>
                <div>
                  <label class="text-[11px] font-bold uppercase tracking-wider text-slate-500 dark:text-slate-400 dark:text-slate-500">Trace ID</label>
                  <input v-model="skillTraceQuery" type="text" placeholder="trace-..." class="mt-1 w-full px-3 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-medium text-slate-700 dark:text-slate-300 focus:outline-none focus:ring-2 focus:ring-indigo-500">
                </div>
                <div class="flex items-center gap-3">
                  <button @click="loadSkillTelemetryPanel" :disabled="skillTelemetryLoading" class="px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm font-bold hover:bg-indigo-700 transition-all disabled:opacity-50">应用过滤</button>
                  <button @click="resetSkillTelemetryFilters" :disabled="skillTelemetryLoading" class="px-4 py-2 bg-white dark:bg-slate-900 border border-slate-200 rounded-lg text-sm font-bold text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:bg-slate-800 transition-all disabled:opacity-50">重置</button>
                </div>
              </div>
            </section>

            <section class="col-span-8 rounded-xl border border-slate-200 bg-slate-50 dark:bg-slate-800/50 p-5">
              <div class="flex items-center justify-between mb-4">
                <h4 class="text-sm font-bold text-slate-900 dark:text-slate-100">最近事件</h4>
                <span class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">最多展示 12 条</span>
              </div>

              <div v-if="!skillTelemetry.length" class="py-10 text-center text-sm text-slate-500 dark:text-slate-400 dark:text-slate-500">
                暂无技能执行事件
              </div>

              <div v-else class="space-y-3">
                <article v-for="item in skillTelemetry" :key="`${item.timestamp}-${item.skillId}-${item.status}`" class="rounded-lg border border-slate-200 bg-white dark:bg-slate-900 p-4">
                  <div class="flex items-center justify-between gap-3">
                    <div class="flex items-center gap-3 flex-wrap">
                      <span class="text-sm font-bold text-slate-900 dark:text-slate-100">{{ item.skillId || '-' }}</span>
                      <span class="px-2 py-0.5 rounded-full text-[10px] font-bold" :class="getSkillStatusClass(item.status)">{{ formatSkillStatus(item.status) }}</span>
                      <span v-if="item.fallbackUsed" class="px-2 py-0.5 rounded-full text-[10px] font-bold bg-amber-100 text-amber-700">fallback</span>
                    </div>
                    <div class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">
                      {{ formatTime(item.timestamp) }}
                    </div>
                  </div>
                  <div class="mt-3 grid grid-cols-4 gap-3 text-xs">
                    <div class="rounded-lg bg-slate-50 dark:bg-slate-800/50 p-3">
                      <div class="text-slate-500 dark:text-slate-400 dark:text-slate-500">耗时</div>
                      <div class="mt-1 font-bold text-slate-900 dark:text-slate-100">{{ item.latencyMs || 0 }} ms</div>
                    </div>
                    <div class="rounded-lg bg-slate-50 dark:bg-slate-800/50 p-3">
                      <div class="text-slate-500 dark:text-slate-400 dark:text-slate-500">尝试次数</div>
                      <div class="mt-1 font-bold text-slate-900 dark:text-slate-100">{{ item.attempts || 0 }}</div>
                    </div>
                    <div class="rounded-lg bg-slate-50 dark:bg-slate-800/50 p-3">
                      <div class="text-slate-500 dark:text-slate-400 dark:text-slate-500">Trace</div>
                      <div class="mt-1 font-mono font-bold text-slate-900 dark:text-slate-100">{{ shortTraceId(skillTraceId(item)) }}</div>
                    </div>
                    <div class="rounded-lg bg-slate-50 dark:bg-slate-800/50 p-3">
                      <div class="text-slate-500 dark:text-slate-400 dark:text-slate-500">Operator</div>
                      <div class="mt-1 font-bold text-slate-900 dark:text-slate-100">{{ skillOperator(item) }}</div>
                    </div>
                  </div>
                  <p class="mt-3 text-sm text-slate-600 dark:text-slate-400 dark:text-slate-500 break-all">{{ item.message || '-' }}</p>
                </article>
              </div>
            </section>
          </div>
        </div>

        <!-- Recent Audit Log (Asymmetric Section) -->
        <div id="section-audit" class="col-span-12 bg-white dark:bg-slate-900 rounded-xl p-8 border border-slate-200 shadow-sm">
          <div class="flex items-center justify-between mb-6">
            <div>
              <h3 class="text-lg font-bold text-slate-900 dark:text-slate-100">最近运维审计</h3>
              <p class="text-xs text-slate-500 dark:text-slate-400 dark:text-slate-500">记录所有特权用户的写操作指令</p>
            </div>
            <button class="text-indigo-600 text-xs font-bold flex items-center gap-1 hover:underline">
              查看完整日志 <span class="material-symbols-outlined text-xs" data-icon="arrow_forward">arrow_forward</span>
            </button>
          </div>
          <div class="space-y-4">
            <div v-for="item in audits" :key="`${item.timestamp}-${item.operator}-${item.action}`" class="flex items-center justify-between bg-slate-50 dark:bg-slate-800/50 px-4 py-3 rounded-lg border-l-4 border-indigo-500 hover:bg-slate-100 dark:bg-slate-800 transition-colors">
              <div class="flex items-center gap-4">
                <span class="text-xs font-mono text-slate-500 dark:text-slate-400 dark:text-slate-500 w-36">{{ formatTime(item.timestamp) }}</span>
                <span class="px-2 py-0.5 bg-indigo-100 text-indigo-800 text-[10px] font-black rounded-full uppercase w-16 text-center">{{ item.action || 'OP' }}</span>
                <span class="text-sm font-medium text-slate-700 dark:text-slate-300">用户 <span class="font-bold text-slate-900 dark:text-slate-100">{{ item.operator || 'system' }}</span> {{ item.message || '-' }}</span>
              </div>
              <span class="text-xs text-slate-400 dark:text-slate-500 font-mono">System</span>
            </div>
            <div v-if="!audits.length" class="py-4 text-sm text-slate-500 dark:text-slate-400 dark:text-slate-500 text-center">暂无审计日志</div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import Chart from 'chart.js/auto'
import { computed, onMounted, ref, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { clearIdempotencyCache, loadOpsAudits, loadOpsIdempotency, loadOpsOverview, loadOpsTraces, loadSkillTelemetry as loadSkillTelemetryApi, replayDlq, runRagQualityEvalWithDataset, loadRagQualityEvalRuns, loadRagQualityEvalDetail, loadRagasEngineStatus, loadRetrievalEvalDatasets, loadRagQualityEvalDatasets, runRetrievalEval, loadRetrievalEvalRuns } from '../api/admin'

defineProps({
  sidebarCollapsed: {
    type: Boolean,
    default: false
  }
})

const router = useRouter()
const loading = ref(false)
const hint = ref('可执行清理与重放操作')
const overview = ref({})
const traces = ref([])
const traceSearch = ref('')
const traceStatusFilter = ref('ALL')
const onlyRiskyTraces = ref(false)
const onlyFallbackTraces = ref(false)
const onlyEmptyRetrievalTraces = ref(false)
const onlySlowTraces = ref(false)
const traceStartedAfter = ref('')
const traceEndedBefore = ref('')
const showFullTraces = ref(false)
const displayedTraces = computed(() => {
  if (showFullTraces.value) return traces.value
  return traces.value.slice(0, 5)
})
const audits = ref([])
const skillTelemetry = ref([])
const skillTelemetryLoading = ref(false)
const selectedSkillId = ref('ALL')
const selectedSkillStatus = ref('ALL')
const skillTraceQuery = ref('')
const idempotency = ref({})
const showAdvancedOps = ref(false)
const datasetLoading = ref(false)
const retrievalDatasets = ref([])
const qualityDatasets = ref([])
const selectedRetrievalDataset = ref('default')
const selectedQualityDataset = ref('default')
const retrievalEvalLoading = ref(false)
const retrievalEvalReport = ref(null)
const retrievalEvalRuns = ref([])

// ===== RAG 生成质量评测 =====
const selectedEngine = ref('')
const engineStatus = ref(null)
const comparisonReport = ref(null)
const qualityEvalLoading = ref(false)
const qualityEvalReport = ref(null)
const qualityEvalRuns = ref([])
const qualityEvalDetail = ref(null)
const expandedSamples = ref([])
const qualityChartRef = ref(null)
const qualityChartInstance = ref(null)
const showQualityHistory = ref(false)

const selectedRetrievalDatasetDescription = computed(() => {
  const current = retrievalDatasets.value.find(item => item.datasetId === selectedRetrievalDataset.value)
  if (!current) return '请选择检索评测使用的数据集。'
  return `${current.description || ''}${current.fileName ? ` | ${current.fileName}` : ''}`
})

const selectedQualityDatasetDescription = computed(() => {
  const current = qualityDatasets.value.find(item => item.datasetId === selectedQualityDataset.value)
  if (!current) return '请选择质量评测使用的数据集。'
  return `${current.description || ''}${current.fileName ? ` | ${current.fileName}` : ''}`
})

const skillIds = computed(() => {
  return Array.from(new Set(skillTelemetry.value.map(item => item.skillId).filter(Boolean))).sort()
})

const skillEventCount = computed(() => skillTelemetry.value.length)

const skillFallbackCount = computed(() => skillTelemetry.value.filter(item => item.fallbackUsed).length)

const skillAvgLatency = computed(() => {
  if (!skillTelemetry.value.length) return 0
  const total = skillTelemetry.value.reduce((sum, item) => sum + Number(item.latencyMs || 0), 0)
  return Math.round(total / skillTelemetry.value.length)
})

const qualityMetrics = computed(() => {
  if (!qualityEvalReport.value) return []
  const r = qualityEvalReport.value
  return [
    { key: 'faithfulness', label: '忠实度 (Faithfulness)', value: r.avgFaithfulness || 0 },
    { key: 'answerRelevancy', label: '回答相关性 (Answer Relevancy)', value: r.avgAnswerRelevancy || 0 },
    { key: 'contextPrecision', label: '上下文精准度 (Context Precision)', value: r.avgContextPrecision || 0 },
    { key: 'contextRecall', label: '上下文召回 (Context Recall)', value: r.avgContextRecall || 0 }
  ]
})

const renderQualityChart = (report) => {
  if (!qualityChartRef.value) return
  if (qualityChartInstance.value) {
    qualityChartInstance.value.destroy()
  }

  const isRagas = report.engine === 'ragas'
  const datasets = [{
    label: isRagas ? 'Ragas 框架' : 'Java 原生',
    data: [
      report.avgFaithfulness || 0,
      report.avgAnswerRelevancy || 0,
      report.avgContextPrecision || 0,
      report.avgContextRecall || 0
    ],
    fill: true,
    backgroundColor: isRagas ? 'rgba(147, 51, 234, 0.2)' : 'rgba(79, 70, 229, 0.2)',
    borderColor: isRagas ? 'rgba(147, 51, 234, 1)' : 'rgba(79, 70, 229, 1)',
    pointBackgroundColor: isRagas ? 'rgba(147, 51, 234, 1)' : 'rgba(79, 70, 229, 1)',
    pointBorderColor: '#fff'
  }]

  // 如果有对比数据（之前加载的另一个引擎的结果），叠加显示
  if (comparisonReport.value && comparisonReport.value.engine !== report.engine) {
    const comp = comparisonReport.value
    const isCompRagas = comp.engine === 'ragas'
    datasets.push({
      label: isCompRagas ? 'Ragas 框架' : 'Java 原生',
      data: [
        comp.avgFaithfulness || 0,
        comp.avgAnswerRelevancy || 0,
        comp.avgContextPrecision || 0,
        comp.avgContextRecall || 0
      ],
      fill: true,
      backgroundColor: isCompRagas ? 'rgba(147, 51, 234, 0.2)' : 'rgba(79, 70, 229, 0.2)',
      borderColor: isCompRagas ? 'rgba(147, 51, 234, 1)' : 'rgba(79, 70, 229, 1)',
      pointBackgroundColor: isCompRagas ? 'rgba(147, 51, 234, 1)' : 'rgba(79, 70, 229, 1)',
      pointBorderColor: '#fff'
    })
  }

  qualityChartInstance.value = new Chart(qualityChartRef.value, {
    type: 'radar',
    data: {
      labels: ['忠实度', '回答相关性', '上下文精准度', '上下文召回'],
      datasets
    },
    options: {
      scales: {
        r: {
          beginAtZero: true,
          max: 1.0,
          ticks: { stepSize: 0.2 }
        }
      },
      plugins: {
        legend: { position: 'bottom' }
      }
    }
  })
}

const runQualityEval = async () => {
  qualityEvalLoading.value = true
  try {
    const engine = selectedEngine.value || undefined
    const dataset = selectedQualityDataset.value || undefined
    const data = await runRagQualityEvalWithDataset(dataset, engine)
    qualityEvalReport.value = data
    qualityEvalDetail.value = data // 默认评测的 detail 就是 report 本身
    await nextTick()
    renderQualityChart(data)
    hint.value = `质量评测完成: ${data.runLabel || data.runId || dataset || 'default'}`
  } catch (error) {
    hint.value = `评测失败: ${error.message || 'unknown'}`
  } finally {
    qualityEvalLoading.value = false
  }
}

const runRetrievalEvalAction = async () => {
  retrievalEvalLoading.value = true
  try {
    const dataset = selectedRetrievalDataset.value || undefined
    const data = await runRetrievalEval(dataset)
    retrievalEvalReport.value = data
    hint.value = `检索评测完成: ${data.runLabel || data.runId || dataset || 'default'}`
    await loadRetrievalEvalHistoryAction()
  } catch (error) {
    hint.value = `检索评测失败: ${error.message || 'unknown'}`
  } finally {
    retrievalEvalLoading.value = false
  }
}

const loadRetrievalEvalHistoryAction = async () => {
  try {
    const resp = await loadRetrievalEvalRuns(8)
    retrievalEvalRuns.value = Array.isArray(resp) ? resp : (resp?.records || [])
  } catch (error) {
    hint.value = `加载检索评测历史失败: ${error.message || 'unknown'}`
  }
}

const loadQualityEvalHistory = async () => {
  try {
    const resp = await loadRagQualityEvalRuns(20)
    qualityEvalRuns.value = Array.isArray(resp) ? resp : (resp?.records || [])
    showQualityHistory.value = true
  } catch (error) {
    hint.value = `加载历史失败: ${error.message || 'unknown'}`
  }
}

const viewQualityEvalDetail = async (runId) => {
  try {
    const data = await loadRagQualityEvalDetail(runId)
    if (qualityEvalReport.value && qualityEvalReport.value.engine !== data.engine) {
      comparisonReport.value = qualityEvalReport.value
    }
    qualityEvalDetail.value = data
    qualityEvalReport.value = data
    await nextTick()
    renderQualityChart(data)
  } catch (error) {
    hint.value = `加载详情失败: ${error.message || 'unknown'}`
  }
}

const loadEngineStatus = async () => {
  try {
    engineStatus.value = await loadRagasEngineStatus()
  } catch (e) {
    // 静默失败，引擎状态不影响主流程
  }
}

const loadEvalDatasets = async () => {
  datasetLoading.value = true
  try {
    const [retrievalResp, qualityResp] = await Promise.all([
      loadRetrievalEvalDatasets(),
      loadRagQualityEvalDatasets()
    ])
    retrievalDatasets.value = Array.isArray(retrievalResp) ? retrievalResp : (retrievalResp?.records || [])
    qualityDatasets.value = Array.isArray(qualityResp) ? qualityResp : (qualityResp?.records || [])
    if (!retrievalDatasets.value.some(item => item.datasetId === selectedRetrievalDataset.value) && retrievalDatasets.value.length) {
      selectedRetrievalDataset.value = retrievalDatasets.value[0].datasetId
    }
    if (!qualityDatasets.value.some(item => item.datasetId === selectedQualityDataset.value) && qualityDatasets.value.length) {
      selectedQualityDataset.value = qualityDatasets.value[0].datasetId
    }
  } catch (error) {
    hint.value = `加载评测数据集失败: ${error.message || 'unknown'}`
  } finally {
    datasetLoading.value = false
  }
}

const formatPercent = (value) => `${(((typeof value === 'number' ? value : 0) || 0) * 100).toFixed(1)}%`

const fetchSkillTelemetry = () => {
  return loadSkillTelemetryApi({
    limit: 12,
    skillId: selectedSkillId.value,
    status: selectedSkillStatus.value,
    traceId: skillTraceQuery.value.trim()
  })
}

const assignSkillTelemetry = (payload) => {
  skillTelemetry.value = Array.isArray(payload) ? payload : (payload?.records || [])
}

const loadSkillTelemetryPanel = async () => {
  skillTelemetryLoading.value = true
  try {
    const data = await fetchSkillTelemetry()
    assignSkillTelemetry(data)
  } catch (error) {
    hint.value = `加载技能观测失败: ${error.message || 'unknown'}`
  } finally {
    skillTelemetryLoading.value = false
  }
}

const resetSkillTelemetryFilters = async () => {
  selectedSkillId.value = 'ALL'
  selectedSkillStatus.value = 'ALL'
  skillTraceQuery.value = ''
  await loadSkillTelemetryPanel()
}

const formatSkillStatus = (status) => {
  if (status === 'SUCCESS') return '成功'
  if (status === 'FALLBACK') return '降级'
  if (status === 'SKIPPED') return '跳过'
  if (status === 'FAILED') return '失败'
  return status || '未知'
}

const getSkillStatusClass = (status) => {
  if (status === 'SUCCESS') return 'bg-emerald-100 text-emerald-700'
  if (status === 'FALLBACK') return 'bg-amber-100 text-amber-700'
  if (status === 'SKIPPED') return 'bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300'
  if (status === 'FAILED') return 'bg-red-100 text-red-700'
  return 'bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300'
}

const skillTraceId = (item) => item?.attributes?.traceId || '-'

const skillOperator = (item) => item?.attributes?.operator || '-'

const shortTraceId = (value) => {
  if (!value || value === '-') return '-'
  return value.length > 10 ? value.slice(0, 10) : value
}

const toggleSample = (idx) => {
  const pos = expandedSamples.value.indexOf(idx)
  if (pos >= 0) {
    expandedSamples.value.splice(pos, 1)
  } else {
    expandedSamples.value.push(idx)
  }
}
// ===== RAG 生成质量评测 End =====

const SLOW_THRESHOLD_MS = Number(import.meta.env.VITE_RAG_SLOW_THRESHOLD_MS || 20000)

const p95Latency = computed(() => {
  if (typeof overview.value?.p95LatencyMs === 'number') {
    return overview.value.p95LatencyMs
  }
  if (!traces.value.length) return 0
  const latencies = traces.value.map(t => t.latencyMs).sort((a, b) => a - b)
  const index = Math.ceil(latencies.length * 0.95) - 1
  return latencies[index]
})

const successRate = computed(() => {
  if (typeof overview.value?.successRate === 'string' && overview.value.successRate.trim()) {
    return overview.value.successRate
  }
  if (!traces.value.length) return '0%'
  const successCount = traces.value.filter(t => t.status === 'SUCCESS' || t.status === 'SLOW').length
  return ((successCount / traces.value.length) * 100).toFixed(1) + '%'
})

const statusVisualMap = {
  SUCCESS: {
    label: 'SUCCESS',
    barClass: 'bg-emerald-500',
    textClass: 'text-emerald-700'
  },
  SLOW: {
    label: 'SLOW',
    barClass: 'bg-amber-500',
    textClass: 'text-amber-700'
  },
  FAILED: {
    label: 'FAILED',
    barClass: 'bg-red-500',
    textClass: 'text-red-700'
  },
  RUNNING: {
    label: 'RUNNING',
    barClass: 'bg-indigo-500',
    textClass: 'text-indigo-700'
  },
  UNKNOWN: {
    label: 'UNKNOWN',
    barClass: 'bg-slate-400',
    textClass: 'text-slate-700'
  }
}

const overviewStatusEntries = computed(() => {
  const counts = overview.value?.statusCounts && typeof overview.value.statusCounts === 'object'
    ? overview.value.statusCounts
    : {}
  const keys = Object.keys(counts)
  const total = keys.reduce((sum, key) => sum + Number(counts[key] || 0), 0)
  return keys.map(key => {
    const visual = statusVisualMap[key] || statusVisualMap.UNKNOWN
    const value = Number(counts[key] || 0)
    return {
      key,
      label: visual.label,
      value,
      percent: total > 0 ? Math.max((value / total) * 100, value > 0 ? 6 : 0) : 0,
      barClass: visual.barClass,
      textClass: visual.textClass
    }
  }).filter(item => item.value > 0)
})

const overviewRiskEntries = computed(() => {
  const counts = overview.value?.riskTagCounts && typeof overview.value.riskTagCounts === 'object'
    ? overview.value.riskTagCounts
    : {}
  return Object.entries(counts)
    .map(([key, value]) => ({
      key,
      label: formatRiskTag(key),
      value: Number(value || 0)
    }))
    .filter(item => item.value > 0)
    .sort((a, b) => b.value - a.value)
})

const overviewAlertLevel = computed(() => String(overview.value?.alertLevel || 'NONE').toUpperCase())

const overviewAlertTags = computed(() => {
  const tags = Array.isArray(overview.value?.alertTags) ? overview.value.alertTags : []
  return tags
})

const overviewAlertHeadline = computed(() => {
  if (!overviewAlertTags.value.length) return '最近窗口内未发现需要升级处理的告警'
  if (overviewAlertLevel.value === 'HIGH') return '检测到高优先级链路风险，建议立即排查'
  if (overviewAlertLevel.value === 'MEDIUM') return '检测到链路退化信号，建议持续关注'
  return '存在轻量告警信号，可结合趋势继续观察'
})

const activeTimeWindowLabel = computed(() => {
  if (!traceStartedAfter.value && !traceEndedBefore.value) return ''
  const from = traceStartedAfter.value ? formatTime(traceStartedAfter.value) : '...'
  const to = traceEndedBefore.value ? formatTime(traceEndedBefore.value) : '...'
  return `${from} ~ ${to}`
})

const overviewTrendBuckets = computed(() => {
  const buckets = Array.isArray(overview.value?.trendBuckets) ? overview.value.trendBuckets : []
  const peak = buckets.reduce((max, item) => {
    const currentMax = Math.max(
      Number(item?.risky || 0),
      Number(item?.slow || 0),
      Number(item?.fallback || 0),
      Number(item?.emptyRetrieval || 0),
      Number(item?.failed || 0)
    )
    return Math.max(max, currentMax)
  }, 0)
  return buckets.map((item, index) => {
    const total = Number(item?.total || 0)
    const toPercent = (value) => peak > 0 ? Math.max((Number(value || 0) / peak) * 100, Number(value || 0) > 0 ? 8 : 0) : 0
    return {
      key: `${item?.label || 'B'}-${index}`,
      label: item?.label || `B${index + 1}`,
      startAt: item?.startAt || '',
      endAt: item?.endAt || '',
      total,
      risky: Number(item?.risky || 0),
      slow: Number(item?.slow || 0),
      fallback: Number(item?.fallback || 0),
      emptyRetrieval: Number(item?.emptyRetrieval || 0),
      failed: Number(item?.failed || 0),
      riskyPercent: toPercent(item?.risky),
      slowPercent: toPercent(item?.slow),
      fallbackPercent: toPercent(item?.fallback),
      emptyRetrievalPercent: toPercent(item?.emptyRetrieval),
      failedPercent: toPercent(item?.failed)
    }
  })
})

const trendPeakRisk = computed(() => {
  if (!overviewTrendBuckets.value.length) return '-'
  const peak = [...overviewTrendBuckets.value].sort((a, b) => b.risky - a.risky)[0]
  return `${peak.label}/${peak.risky}`
})

const trendPeakSlow = computed(() => {
  if (!overviewTrendBuckets.value.length) return '-'
  const peak = [...overviewTrendBuckets.value].sort((a, b) => b.slow - a.slow)[0]
  return `${peak.label}/${peak.slow}`
})

const idempotencyTotal = computed(() => {
  const inMemory = idempotency.value.inMemorySize || 0
  const redisSize = idempotency.value.redisSize || 0
  return inMemory + redisSize
})

const formatTime = (value) => {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString()
}

const viewDetail = (traceId) => {
  router.push({ name: 'rag-trace-detail', params: { traceId } })
}

const toggleTraceExpansion = async () => {
  showFullTraces.value = !showFullTraces.value
  await reload()
}

const clearTraceFiltersState = () => {
  traceSearch.value = ''
  traceStatusFilter.value = 'ALL'
  onlyRiskyTraces.value = false
  onlyFallbackTraces.value = false
  onlyEmptyRetrievalTraces.value = false
  onlySlowTraces.value = false
  traceStartedAfter.value = ''
  traceEndedBefore.value = ''
}

const applyOverviewPreset = async (preset) => {
  clearTraceFiltersState()
  showFullTraces.value = true
  if (preset === 'active') {
    traceStatusFilter.value = 'RUNNING'
  } else if (preset === 'risky') {
    onlyRiskyTraces.value = true
  } else if (preset === 'fallback') {
    onlyFallbackTraces.value = true
  } else if (preset === 'emptyRetrieval') {
    onlyEmptyRetrievalTraces.value = true
  }
  await reload()
}

const applyStatusPreset = async (status) => {
  clearTraceFiltersState()
  showFullTraces.value = true
  traceStatusFilter.value = status || 'ALL'
  await reload()
}

const applyRiskTagPreset = async (riskTag) => {
  clearTraceFiltersState()
  showFullTraces.value = true
  if (riskTag === 'fallback_triggered') {
    onlyFallbackTraces.value = true
  } else if (riskTag === 'retrieval_empty') {
    onlyEmptyRetrievalTraces.value = true
  } else if (riskTag === 'slow_trace' || riskTag === 'slow_first_token') {
    onlySlowTraces.value = true
  } else {
    onlyRiskyTraces.value = true
    traceSearch.value = String(riskTag || '').trim()
  }
  await reload()
}

const applyAlertPreset = async (alertTag) => {
  clearTraceFiltersState()
  showFullTraces.value = true
  if (alertTag === 'high_active_trace_load' || alertTag === 'active_traces_present') {
    traceStatusFilter.value = 'RUNNING'
  } else if (alertTag === 'failed_traces_elevated' || alertTag === 'degrading_failed_trend') {
    traceStatusFilter.value = 'FAILED'
  } else if (alertTag === 'slow_traces_elevated' || alertTag === 'degrading_slow_trend') {
    onlySlowTraces.value = true
  } else if (alertTag === 'fallback_rate_elevated') {
    onlyFallbackTraces.value = true
  } else if (alertTag === 'empty_retrieval_elevated') {
    onlyEmptyRetrievalTraces.value = true
  } else if (alertTag === 'degrading_risky_trend') {
    onlyRiskyTraces.value = true
  }
  await reload()
}

const applyTrendBucketPreset = async (bucket) => {
  clearTraceFiltersState()
  showFullTraces.value = true
  traceStartedAfter.value = bucket?.startAt || ''
  traceEndedBefore.value = bucket?.endAt || ''
  await reload()
}

const resolveStatus = (traceStatus, durationMs, nodes) => {
  const normalizedTraceStatus = typeof traceStatus === 'string' ? traceStatus.trim().toUpperCase() : ''
  if (normalizedTraceStatus === 'FAILED' || normalizedTraceStatus === 'ERROR' || normalizedTraceStatus === 'TIMEOUT') {
    return 'FAILED'
  }
  if (normalizedTraceStatus === 'COMPLETED' || normalizedTraceStatus === 'SUCCESS') {
    return durationMs >= SLOW_THRESHOLD_MS ? 'SLOW' : 'SUCCESS'
  }
  if (normalizedTraceStatus === 'RUNNING') {
    return 'RUNNING'
  }
  const safeNodes = Array.isArray(nodes) ? nodes : []
  const nodeStatuses = safeNodes
    .map(node => (typeof node?.status === 'string' ? node.status.trim().toUpperCase() : ''))
    .filter(Boolean)
  if (nodeStatuses.includes('FAILED') || nodeStatuses.includes('ERROR') || nodeStatuses.includes('TIMEOUT')) {
    return 'FAILED'
  }
  if (nodeStatuses.includes('RUNNING')) {
    return 'RUNNING'
  }
  if (safeNodes.length > 0) {
    return durationMs >= SLOW_THRESHOLD_MS ? 'SLOW' : 'SUCCESS'
  }
  return 'UNKNOWN'
}

const getTraceStatusClass = (status) => {
  if (status === 'FAILED') {
    return 'bg-red-100 text-red-700'
  }
  if (status === 'SLOW') {
    return 'bg-amber-100 text-amber-700'
  }
  if (status === 'RUNNING') {
    return 'bg-indigo-100 text-indigo-700'
  }
  if (status === 'UNKNOWN') {
    return 'bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300'
  }
  return 'bg-emerald-100 text-emerald-700'
}

const formatRiskTag = (tag) => {
  if (tag === 'slow_trace') return 'slow trace'
  if (tag === 'slow_first_token') return 'slow first token'
  if (tag === 'fallback_triggered') return 'fallback'
  if (tag === 'retrieval_empty') return 'empty retrieval'
  return tag || 'risk'
}

const formatAlertTag = (tag) => {
  if (tag === 'high_active_trace_load') return 'high active load'
  if (tag === 'active_traces_present') return 'active traces present'
  if (tag === 'failed_traces_elevated') return 'failed traces elevated'
  if (tag === 'slow_traces_elevated') return 'slow traces elevated'
  if (tag === 'fallback_rate_elevated') return 'fallback elevated'
  if (tag === 'empty_retrieval_elevated') return 'empty retrieval elevated'
  if (tag === 'degrading_risky_trend') return 'risky trend rising'
  if (tag === 'degrading_slow_trend') return 'slow trend rising'
  if (tag === 'degrading_failed_trend') return 'failed trend rising'
  return tag || 'alert'
}

const reload = async () => {
  loading.value = true
  hint.value = '正在刷新运维数据...'
  try {
    const traceFilters = {
      limit: showFullTraces.value ? 50 : 20,
      status: traceStatusFilter.value,
      riskyOnly: onlyRiskyTraces.value,
      fallbackOnly: onlyFallbackTraces.value,
      emptyRetrievalOnly: onlyEmptyRetrievalTraces.value,
      slowOnly: onlySlowTraces.value,
      q: traceSearch.value.trim(),
      startedAfter: traceStartedAfter.value,
      endedBefore: traceEndedBefore.value
    }
    const [overviewData, tracesData, idempotencyData, auditsData, skillTelemetryData] = await Promise.all([
      loadOpsOverview(),
      loadOpsTraces(traceFilters),
      loadOpsIdempotency(),
      loadOpsAudits(5),
      fetchSkillTelemetry()
    ])
    overview.value = overviewData || {}
    traces.value = Array.isArray(tracesData)
      ? tracesData.map(item => {
          const businessDurationMs = typeof item?.businessDurationMs === 'number'
            ? item.businessDurationMs
            : 0
          const traceStatus = typeof item?.traceStatus === 'string'
            ? item.traceStatus.trim().toUpperCase()
            : ''
          const status = traceStatus === 'COMPLETED'
            ? (item?.slowTrace ? 'SLOW' : 'SUCCESS')
            : (traceStatus || 'UNKNOWN')
          return {
            traceId: item?.traceId ?? '-',
            latencyMs: businessDurationMs,
            firstTokenMs: typeof item?.firstTokenMs === 'number' ? item.firstTokenMs : 0,
            retrievedCount: typeof item?.retrievedDocCount === 'number' ? item.retrievedDocCount : 0,
            riskTags: Array.isArray(item?.riskTags) ? item.riskTags : [],
            status
          }
        })
      : []
    idempotency.value = idempotencyData || {}
    audits.value = Array.isArray(auditsData) ? auditsData : []
    assignSkillTelemetry(skillTelemetryData)
    hint.value = '数据已刷新'
  } catch (error) {
    hint.value = `刷新失败: ${error.message || 'unknown'}`
  } finally {
    loading.value = false
  }
}

const applyTraceFilters = async () => {
  await reload()
}

const resetTraceFilters = async () => {
  clearTraceFiltersState()
  await reload()
}

const purge = async () => {
  loading.value = true
  hint.value = '正在清理幂等缓存...'
  try {
    await clearIdempotencyCache()
    await reload()
    hint.value = '幂等缓存已清理'
  } catch (error) {
    hint.value = `清理失败: ${error.message || 'unknown'}`
    loading.value = false
  }
}

const replay = async () => {
  loading.value = true
  hint.value = '正在重放死信队列...'
  try {
    await replayDlq()
    await reload()
    hint.value = '死信重放指令已发送'
  } catch (error) {
    hint.value = `重放失败: ${error.message || 'unknown'}`
    loading.value = false
  }
}

onMounted(() => {
  reload()
  loadEngineStatus()
  loadEvalDatasets()
  loadRetrievalEvalHistoryAction()
})
</script>
