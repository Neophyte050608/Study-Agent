<template>
  <section class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h3 class="text-xl font-bold text-content">运行概览</h3>
        <p class="mt-1 text-sm text-content-secondary">
          核心指标、趋势、告警与用户反馈。
        </p>
      </div>
      <button
        class="rounded-lg border border-slate-200 px-4 py-2 text-sm font-bold text-indigo-700 disabled:opacity-50"
        :disabled="loading"
        @click="reloadAndRender"
      >
        {{ loading ? "刷新中…" : "刷新概览" }}
      </button>
    </div>

    <div
      v-if="error"
      class="flex items-center justify-between rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
    >
      <span>概览加载失败：{{ error }}</span>
      <button class="font-bold underline" @click="reloadAndRender">重试</button>
    </div>

    <div class="grid grid-cols-2 gap-4 lg:grid-cols-6">
      <div
        v-for="card in metricCards"
        :key="card.key"
        class="rounded-xl border border-divider bg-surface-raised p-4 shadow-sm"
      >
        <div
          class="text-[10px] font-bold uppercase tracking-widest text-content-secondary"
        >
          {{ card.label }}
        </div>
        <div class="mt-1 text-2xl font-black text-content">
          {{ card.value }}
        </div>
      </div>
    </div>

    <div class="grid gap-6 lg:grid-cols-3">
      <div
        class="rounded-xl border border-divider bg-surface-raised p-6 shadow-sm lg:col-span-2"
      >
        <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h4
            class="text-sm font-bold uppercase tracking-wider text-content-secondary"
          >
            趋势分析
          </h4>
          <div class="flex gap-2">
            <button
              v-for="range in timeRanges"
              :key="range.value"
              class="rounded-lg px-3 py-1 text-xs font-bold"
              :class="
                selectedRange === range.value
                  ? 'bg-indigo-600 text-white'
                  : 'bg-surface-hover text-content-secondary'
              "
              @click="changeRange(range.value)"
            >
              {{ range.label }}
            </button>
            <button
              class="rounded-lg bg-emerald-600 px-3 py-1 text-xs font-bold text-white disabled:opacity-50"
              :disabled="snapshotLoading"
              @click="generateAndRender"
            >
              {{ snapshotLoading ? "生成中…" : "生成快照" }}
            </button>
          </div>
        </div>
        <div class="mb-4 flex flex-wrap gap-3">
          <label
            v-for="metric in metrics"
            :key="metric.key"
            class="flex cursor-pointer items-center gap-1 text-xs font-bold text-content-secondary"
          >
            <input
              v-model="selectedMetrics"
              class="accent-indigo-600"
              type="checkbox"
              :value="metric.key"
              @change="reloadHistoryAndRender"
            />
            {{ metric.label }}
          </label>
        </div>
        <p
          v-if="snapshotMessage"
          class="mb-3 rounded-lg bg-emerald-50 px-3 py-2 text-xs font-bold text-emerald-700"
        >
          {{ snapshotMessage }}
        </p>
        <div
          v-if="historyLoading"
          class="py-20 text-center text-sm text-content-secondary"
        >
          正在加载趋势…
        </div>
        <div
          v-else-if="!historySnapshots.length"
          class="py-20 text-center text-sm text-content-secondary"
        >
          暂无趋势数据，请先生成快照。
        </div>
        <canvas v-else ref="chartCanvas" height="300"></canvas>
      </div>

      <div class="space-y-6">
        <div
          class="rounded-xl border border-divider bg-surface-raised p-6 shadow-sm"
        >
          <h4
            class="mb-4 text-sm font-bold uppercase tracking-wider text-content-secondary"
          >
            告警状态
          </h4>
          <div class="rounded-xl p-6 text-center" :class="alertBgClass">
            <div class="text-4xl font-black">{{ alertLevel }}</div>
            <div class="mt-2 text-xs font-bold">当前级别</div>
          </div>
          <div class="mt-4 space-y-2">
            <div
              v-for="tag in alertTags"
              :key="tag"
              class="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs font-bold text-amber-700"
            >
              {{ formatAlertTag(tag) }}
            </div>
            <p v-if="!alertTags.length" class="text-xs text-content-secondary">
              无活跃告警
            </p>
          </div>
        </div>
        <div
          class="rounded-xl border border-divider bg-surface-raised p-6 shadow-sm"
        >
          <h4
            class="mb-4 text-sm font-bold uppercase tracking-wider text-content-secondary"
          >
            用户反馈
          </h4>
          <div class="grid grid-cols-3 gap-3 text-center">
            <div>
              <b>{{ feedbackSummary.thumbsUp || 0 }}</b>
              <p class="text-xs text-content-secondary">点赞</p>
            </div>
            <div>
              <b>{{ feedbackSummary.thumbsDown || 0 }}</b>
              <p class="text-xs text-content-secondary">点踩</p>
            </div>
            <div>
              <b>{{
                feedbackSummary.copy || feedbackSummary.copyCount || 0
              }}</b>
              <p class="text-xs text-content-secondary">复制</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from "vue";
import { useRagOverview } from "./useRagOverview.js";

const {
  historySnapshots,
  selectedRange,
  selectedMetrics,
  loading,
  historyLoading,
  snapshotLoading,
  error,
  snapshotMessage,
  metricCards,
  feedbackSummary,
  alertLevel,
  alertTags,
  loadDashboard,
  loadHistory,
  reload,
  generateSnapshot,
} = useRagOverview();
const chartCanvas = ref(null);
let refreshTimer;
const timeRanges = [
  { label: "24h", value: 24 },
  { label: "7d", value: 168 },
];
const metrics = [
  { key: "avgLatencyMs", label: "平均延迟" },
  { key: "p95LatencyMs", label: "P95 延迟" },
  { key: "successRate", label: "成功率" },
  { key: "satisfactionRate", label: "满意度" },
  { key: "fallbackRate", label: "Fallback 率" },
  { key: "emptyRetrievalRate", label: "空召回率" },
  { key: "traceCount", label: "请求量" },
];
const alertBgClass = computed(
  () =>
    ({
      HIGH: "bg-red-50 text-red-700",
      MEDIUM: "bg-amber-50 text-amber-700",
      INFO: "bg-blue-50 text-blue-700",
    })[alertLevel.value] || "bg-emerald-50 text-emerald-700",
);
const labels = {
  failed_traces_elevated: "失败 Trace 增多",
  fallback_rate_elevated: "Fallback 率过高",
  satisfaction_dropped: "满意度下降",
  slow_traces_elevated: "慢请求增多",
  empty_retrieval_elevated: "空召回增多",
  latency_degrading: "延迟劣化",
  success_rate_degrading: "成功率下降",
};
const formatAlertTag = (tag) => labels[tag] || tag;

function renderChart() {
  const canvas = chartCanvas.value;
  if (!canvas || historySnapshots.value.length < 2) return;
  const ctx = canvas.getContext("2d");
  const width = canvas.parentElement.clientWidth;
  canvas.width = width;
  canvas.height = 300;
  ctx.clearRect(0, 0, width, 300);
  const padding = { top: 20, right: 30, bottom: 35, left: 45 };
  const w = width - padding.left - padding.right;
  const h = 300 - padding.top - padding.bottom;
  const colors = {
    avgLatencyMs: "#6366f1",
    p95LatencyMs: "#f59e0b",
    successRate: "#10b981",
    satisfactionRate: "#8b5cf6",
    fallbackRate: "#ef4444",
    emptyRetrievalRate: "#f97316",
    traceCount: "#6b7280",
  };
  selectedMetrics.value.forEach((key) => {
    const values = historySnapshots.value.map((item) =>
      Number.parseFloat(String(item[key] || 0).replace("%", "")),
    );
    const max = Math.max(...values, 1);
    const min = Math.min(...values, 0);
    ctx.beginPath();
    ctx.strokeStyle = colors[key] || "#6b7280";
    ctx.lineWidth = 2;
    values.forEach((value, index) => {
      const x = padding.left + (w * index) / (values.length - 1);
      const y =
        padding.top + h - h * (max === min ? 0.5 : (value - min) / (max - min));
      index ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
    });
    ctx.stroke();
  });
}
async function reloadAndRender() {
  await reload();
  await nextTick();
  renderChart();
}
async function reloadHistoryAndRender() {
  await loadHistory();
  await nextTick();
  renderChart();
}
async function changeRange(value) {
  selectedRange.value = value;
  await reloadHistoryAndRender();
}
async function generateAndRender() {
  await generateSnapshot();
  await nextTick();
  renderChart();
}
onMounted(() => {
  reloadAndRender();
  refreshTimer = setInterval(loadDashboard, 30000);
});
onUnmounted(() => clearInterval(refreshTimer));
</script>
