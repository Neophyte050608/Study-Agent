<template>
  <section class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h3 class="text-xl font-bold text-content">RAG 评测</h3>
        <p class="mt-1 text-sm text-content-secondary">
          统一管理数据集、检索指标与生成质量评测。
        </p>
      </div>
      <button
        class="rounded-lg border border-slate-200 px-4 py-2 text-sm font-bold text-indigo-700 disabled:opacity-50"
        :disabled="loading"
        @click="reload"
      >
        {{ loading ? "刷新中…" : "刷新评测数据" }}
      </button>
    </div>
    <div
      v-if="error"
      class="flex items-center justify-between rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
    >
      <span>{{ error }}</span
      ><button class="font-bold underline" @click="reload">重试</button>
    </div>
    <p
      v-if="message"
      class="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700"
    >
      {{ message }}
    </p>

    <div class="grid gap-6 lg:grid-cols-2">
      <article
        class="rounded-xl border border-slate-200 bg-surface-raised p-6 shadow-sm"
      >
        <div class="mb-5 flex items-center justify-between">
          <h4 class="font-bold text-content">检索评测</h4>
          <button
            class="text-xs font-bold text-indigo-600"
            :disabled="datasetLoading"
            @click="loadDatasets"
          >
            刷新数据集
          </button>
        </div>
        <select
          v-model="selectedRetrievalDataset"
          class="w-full rounded-lg border border-slate-200 bg-surface-field px-3 py-2 text-sm"
        >
          <option
            v-for="item in retrievalDatasets"
            :key="item.datasetId"
            :value="item.datasetId"
          >
            {{ item.title || item.datasetId }} / {{ item.datasetId }}
          </option>
        </select>
        <p class="mt-2 min-h-10 text-xs text-content-secondary">
          {{ selectedRetrievalDescription }}
        </p>
        <button
          class="mt-4 rounded-lg bg-brand px-4 py-2 text-sm font-bold text-white disabled:opacity-50"
          :disabled="retrievalLoading || !selectedRetrievalDataset"
          @click="runRetrieval"
        >
          {{ retrievalLoading ? "评测中…" : "运行检索评测" }}
        </button>
        <div
          v-if="retrievalReport"
          class="mt-5 grid grid-cols-2 gap-3 md:grid-cols-5"
        >
          <MetricCard
            label="Recall@1"
            :value="percent(retrievalReport.recallAt1)"
          /><MetricCard
            label="Recall@3"
            :value="percent(retrievalReport.recallAt3)"
          /><MetricCard
            label="Recall@5"
            :value="percent(retrievalReport.recallAt5)"
          /><MetricCard
            label="MRR"
            :value="percent(retrievalReport.mrr)"
          /><MetricCard label="样本" :value="retrievalReport.totalCases || 0" />
        </div>
        <div class="mt-6">
          <h5
            class="mb-3 text-xs font-bold uppercase tracking-wider text-content-secondary"
          >
            最近运行
          </h5>
          <div class="space-y-2">
            <div
              v-for="run in retrievalRuns.slice(0, 5)"
              :key="run.runId"
              class="grid gap-1 rounded-lg bg-surface-field px-3 py-2 text-xs sm:grid-cols-[auto_1fr_auto] sm:items-center sm:gap-3"
            >
              <span class="font-mono text-indigo-600">{{
                shortId(run.runId)
              }}</span>
              <span class="truncate">{{
                run.datasetSource || run.runLabel || run.datasetId || "-"
              }}</span>
              <span class="text-content-secondary">
                Recall@5 {{ percent(run.recallAt5) }} · MRR
                {{ percent(run.mrr) }}
              </span>
            </div>
            <p
              v-if="!retrievalRuns.length"
              class="text-xs text-content-secondary"
            >
              暂无检索评测历史
            </p>
          </div>
        </div>
      </article>

      <article
        class="rounded-xl border border-slate-200 bg-surface-raised p-6 shadow-sm"
      >
        <div class="mb-5 flex items-center justify-between">
          <h4 class="font-bold text-content">生成质量评测</h4>
          <span
            v-if="engineStatus"
            class="rounded-full px-2 py-1 text-xs"
            :class="
              engineStatus.ragasEngineAvailable
                ? 'bg-emerald-100 text-emerald-700'
                : 'bg-slate-100 text-slate-600'
            "
            >Ragas:
            {{ engineStatus.ragasEngineAvailable ? "可用" : "不可用" }}</span
          >
        </div>
        <div class="grid gap-3 md:grid-cols-2">
          <select
            v-model="selectedQualityDataset"
            class="rounded-lg border border-slate-200 bg-surface-field px-3 py-2 text-sm"
          >
            <option
              v-for="item in qualityDatasets"
              :key="item.datasetId"
              :value="item.datasetId"
            >
              {{ item.title || item.datasetId }} / {{ item.datasetId }}
            </option></select
          ><select
            v-model="selectedEngine"
            class="rounded-lg border border-slate-200 bg-surface-field px-3 py-2 text-sm"
          >
            <option value="">默认引擎</option>
            <option value="java">Java 原生</option>
            <option
              value="ragas"
              :disabled="engineStatus && !engineStatus.ragasEngineAvailable"
            >
              Ragas
            </option>
          </select>
        </div>
        <p class="mt-2 min-h-10 text-xs text-content-secondary">
          {{ selectedQualityDescription }}
        </p>
        <button
          class="mt-4 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-bold text-white disabled:opacity-50"
          :disabled="qualityLoading || !selectedQualityDataset"
          @click="runQuality"
        >
          {{ qualityLoading ? "评测中…" : "运行质量评测" }}
        </button>
        <div
          v-if="qualityReport"
          class="mt-5 grid items-center gap-4 lg:grid-cols-[minmax(0,260px)_1fr]"
        >
          <canvas
            ref="qualityChartRef"
            class="mx-auto max-h-[260px] max-w-[260px]"
          ></canvas>
          <div class="grid grid-cols-2 gap-3">
            <MetricCard
              v-for="metric in qualityMetrics"
              :key="metric.label"
              :label="metric.label"
              :value="percent(metric.value)"
            />
          </div>
        </div>
        <div class="mt-6">
          <h5
            class="mb-3 text-xs font-bold uppercase tracking-wider text-content-secondary"
          >
            最近运行
          </h5>
          <div class="space-y-2">
            <button
              v-for="run in qualityRuns.slice(0, 5)"
              :key="run.runId"
              class="grid w-full gap-1 rounded-lg bg-surface-field px-3 py-2 text-left text-xs hover:bg-surface-hover sm:grid-cols-[auto_auto_1fr] sm:items-center sm:gap-3"
              @click="openQualityDetail(run.runId)"
            >
              <span class="font-mono text-indigo-600">{{
                shortId(run.runId)
              }}</span>
              <span>{{ run.engine || "-" }}</span>
              <span class="text-right text-content-secondary">
                忠实度 {{ percent(run.avgFaithfulness) }} · 相关性
                {{ percent(run.avgAnswerRelevancy) }}
              </span>
            </button>
            <p
              v-if="!qualityRuns.length"
              class="text-xs text-content-secondary"
            >
              暂无质量评测历史
            </p>
          </div>
        </div>
      </article>
    </div>

    <article
      class="rounded-xl border border-slate-200 bg-surface-raised p-6 shadow-sm"
    >
      <div class="mb-5 flex flex-wrap items-center justify-between gap-4">
        <div>
          <h4 class="font-bold text-content">线上检索指标</h4>
          <p class="mt-1 text-xs text-content-secondary">
            按最近 Trace 数量或小时窗口聚合。
          </p>
        </div>
        <div class="flex items-center gap-2">
          <input
            v-model.number="retrievalMetricsLimit"
            class="w-24 rounded-lg border px-3 py-2 text-sm"
            type="number"
            min="1"
            max="1000"
            placeholder="Trace 数"
          /><input
            v-model.number="retrievalMetricsHours"
            class="w-24 rounded-lg border px-3 py-2 text-sm"
            type="number"
            min="1"
            max="168"
            placeholder="小时"
          /><button
            class="rounded-lg border px-3 py-2 text-sm font-bold text-indigo-600"
            :disabled="metricsLoading"
            @click="loadMetrics"
          >
            {{ metricsLoading ? "加载中…" : "更新" }}
          </button>
        </div>
      </div>
      <div class="grid grid-cols-2 gap-3 md:grid-cols-5">
        <MetricCard
          label="P95 延迟"
          :value="`${retrievalMetrics.retrievalLatencyP95Ms || 0} ms`"
        /><MetricCard
          label="P99 延迟"
          :value="`${retrievalMetrics.retrievalLatencyP99Ms || 0} ms`"
        /><MetricCard
          label="Top3 Recall"
          :value="percentOrDash(retrievalMetrics.top3Recall)"
        /><MetricCard
          label="平均召回"
          :value="retrievalMetrics.avgRetrievedDocs || 0"
        /><MetricCard
          label="样本"
          :value="retrievalMetrics.retrievalLatencySampleSize || 0"
        />
      </div>
    </article>

    <article
      v-if="qualityDetail"
      class="rounded-xl border border-indigo-200 bg-indigo-50/30 p-6"
    >
      <div class="mb-4 flex items-center justify-between">
        <div>
          <h4 class="font-bold text-content">质量评测详情</h4>
          <p class="mt-1 text-xs text-content-secondary">
            {{ qualityDetail.runLabel || qualityDetail.runId }} ·
            {{ qualityDetail.engine || "default" }}
          </p>
        </div>
        <button
          class="text-sm font-bold text-indigo-600"
          @click="qualityDetail = null"
        >
          关闭
        </button>
      </div>
      <div v-if="qualityDetailResults(qualityDetail).length" class="space-y-3">
        <details
          v-for="(sample, index) in qualityDetailResults(qualityDetail)"
          :key="`${sample.query || 'sample'}-${index}`"
          class="rounded-lg border border-slate-200 bg-white p-4"
        >
          <summary class="cursor-pointer text-sm font-bold">
            <span
              class="mr-2 rounded-full bg-indigo-100 px-2 py-0.5 text-[10px] text-indigo-700"
              >{{ sample.tag || "未分类" }}</span
            >
            {{ sample.query || `样本 ${index + 1}` }}
          </summary>
          <div class="mt-4 space-y-4 text-sm">
            <div class="grid grid-cols-2 gap-3 md:grid-cols-4">
              <MetricCard label="忠实度" :value="percent(sample.faithfulness)" />
              <MetricCard
                label="回答相关性"
                :value="percent(sample.answerRelevancy)"
              />
              <MetricCard
                label="上下文精准度"
                :value="percent(sample.contextPrecision)"
              />
              <MetricCard
                label="上下文召回"
                :value="percent(sample.contextRecall)"
              />
            </div>
            <div>
              <p class="text-xs font-bold text-content-secondary">标准答案</p>
              <p class="mt-1 whitespace-pre-wrap text-content">
                {{ sample.groundTruthAnswer || "-" }}
              </p>
            </div>
            <div>
              <p class="text-xs font-bold text-content-secondary">生成答案</p>
              <p class="mt-1 whitespace-pre-wrap text-content">
                {{ sample.generatedAnswer || "-" }}
              </p>
            </div>
            <details v-if="sample.retrievedContext">
              <summary class="cursor-pointer text-xs font-bold text-indigo-600">
                查看检索上下文
              </summary>
              <pre
                class="mt-2 max-h-80 overflow-auto whitespace-pre-wrap rounded-lg bg-surface-field p-3 text-xs text-content-secondary"
                >{{ sample.retrievedContext }}</pre
              >
            </details>
            <div v-if="sample.rationales">
              <p class="text-xs font-bold text-content-secondary">评分理由</p>
              <ul class="mt-2 space-y-1 text-xs text-content-secondary">
                <li v-for="(reason, metric) in sample.rationales" :key="metric">
                  <b>{{ metric }}：</b>{{ reason }}
                </li>
              </ul>
            </div>
          </div>
        </details>
      </div>
      <p v-else class="text-sm text-content-secondary">该运行没有逐样本结果。</p>
    </article>
  </section>
</template>
<script setup>
import Chart from "chart.js/auto";
import {
  computed,
  defineComponent,
  h,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { qualityDetailResults } from "./ragPresentation.js";
import { useRagEvaluation } from "./useRagEvaluation.js";
const MetricCard = defineComponent({
  props: { label: String, value: [String, Number] },
  setup: (props) => () =>
    h(
      "div",
      { class: "rounded-lg border border-slate-200 bg-surface-field p-3" },
      [
        h(
          "div",
          {
            class:
              "text-[10px] font-bold uppercase tracking-wider text-content-secondary",
          },
          props.label,
        ),
        h(
          "div",
          { class: "mt-1 text-lg font-black text-content" },
          String(props.value ?? "-"),
        ),
      ],
    ),
});
const state = useRagEvaluation();
const {
  retrievalDatasets,
  qualityDatasets,
  selectedRetrievalDataset,
  selectedQualityDataset,
  selectedRetrievalDescription,
  selectedQualityDescription,
  retrievalReport,
  retrievalRuns,
  retrievalMetrics,
  retrievalMetricsLimit,
  retrievalMetricsHours,
  selectedEngine,
  engineStatus,
  qualityReport,
  qualityRuns,
  qualityDetail,
  loading,
  datasetLoading,
  retrievalLoading,
  metricsLoading,
  qualityLoading,
  error,
  message,
  reload,
  loadDatasets,
  loadMetrics,
  runRetrieval,
  runQuality,
  loadQualityDetail,
} = state;
const qualityMetrics = computed(() =>
  qualityReport.value
    ? [
        { label: "忠实度", value: qualityReport.value.avgFaithfulness },
        { label: "回答相关性", value: qualityReport.value.avgAnswerRelevancy },
        {
          label: "上下文精准度",
          value: qualityReport.value.avgContextPrecision,
        },
        { label: "上下文召回", value: qualityReport.value.avgContextRecall },
      ]
    : [],
);
const qualityChartRef = ref(null);
let qualityChart = null;

async function renderQualityChart(report) {
  await nextTick();
  qualityChart?.destroy();
  qualityChart = null;
  if (!report || !qualityChartRef.value) return;
  qualityChart = new Chart(qualityChartRef.value, {
    type: "radar",
    data: {
      labels: ["忠实度", "回答相关性", "上下文精准度", "上下文召回"],
      datasets: [
        {
          label: report.engine === "ragas" ? "Ragas" : "Java 原生",
          data: [
            report.avgFaithfulness || 0,
            report.avgAnswerRelevancy || 0,
            report.avgContextPrecision || 0,
            report.avgContextRecall || 0,
          ],
          backgroundColor: "rgba(79, 70, 229, 0.18)",
          borderColor: "rgba(79, 70, 229, 1)",
          pointBackgroundColor: "rgba(79, 70, 229, 1)",
        },
      ],
    },
    options: {
      responsive: true,
      scales: { r: { min: 0, max: 1 } },
      plugins: { legend: { display: false } },
    },
  });
}
const percent = (value) => `${(Number(value || 0) * 100).toFixed(1)}%`;
const percentOrDash = (value) =>
  typeof value === "number" ? percent(value) : "--";
const shortId = (value) => (value ? String(value).slice(0, 8) : "-");
const formatTime = (value) => (value ? new Date(value).toLocaleString() : "-");
const openQualityDetail = async (runId) => loadQualityDetail(runId);
watch(qualityReport, renderQualityChart);
onMounted(reload);
onBeforeUnmount(() => qualityChart?.destroy());
</script>
