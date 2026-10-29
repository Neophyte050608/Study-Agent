<template>
  <section class="space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <h3 class="text-xl font-bold text-content">运行审计与维护</h3>
        <p class="mt-1 text-sm text-content-secondary">
          技能执行、审计事件与受控运维操作。
        </p>
      </div>
      <button
        class="rounded-lg border border-slate-200 px-4 py-2 text-sm font-bold text-indigo-700 disabled:opacity-50"
        :disabled="loading"
        @click="reload"
      >
        {{ loading ? "刷新中…" : "刷新审计" }}
      </button>
    </div>
    <div
      v-if="error"
      class="flex items-center justify-between rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
    >
      <span>审计数据加载失败：{{ error }}</span
      ><button class="font-bold underline" @click="reload">重试</button>
    </div>
    <p
      v-if="message"
      class="rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700"
    >
      {{ message }}
    </p>

    <article
      class="rounded-xl border border-slate-200 bg-surface-raised p-6 shadow-sm"
    >
      <div class="mb-5 flex items-center justify-between">
        <h4 class="font-bold text-content">技能执行遥测</h4>
        <span class="text-xs text-content-secondary">最近 12 条</span>
      </div>
      <div class="mb-5 grid grid-cols-3 gap-3">
        <Metric label="事件数" :value="eventCount" /><Metric
          label="Fallback"
          :value="fallbackCount"
        /><Metric label="平均耗时" :value="`${averageLatency} ms`" />
      </div>
      <form
        class="mb-5 grid gap-3 md:grid-cols-[1fr_1fr_2fr_auto_auto]"
        @submit.prevent="loadTelemetry"
      >
        <select
          v-model="selectedSkillId"
          class="rounded-lg border px-3 py-2 text-sm"
        >
          <option value="ALL">全部技能</option>
          <option v-for="id in skillIds" :key="id" :value="id">
            {{ id }}
          </option></select
        ><select
          v-model="selectedSkillStatus"
          class="rounded-lg border px-3 py-2 text-sm"
        >
          <option value="ALL">全部状态</option>
          <option
            v-for="value in ['SUCCESS', 'FAILED', 'FALLBACK']"
            :key="value"
          >
            {{ value }}
          </option></select
        ><input
          v-model="traceQuery"
          class="rounded-lg border px-3 py-2 text-sm"
          placeholder="Trace ID"
        /><button
          class="rounded-lg bg-brand px-4 py-2 text-sm font-bold text-white"
          :disabled="telemetryLoading"
        >
          筛选</button
        ><button
          class="rounded-lg border px-4 py-2 text-sm font-bold"
          type="button"
          @click="resetTelemetryFilters"
        >
          重置
        </button>
      </form>
      <div class="space-y-3">
        <article
          v-for="item in telemetry"
          :key="`${item.timestamp}-${item.skillId}-${item.status}`"
          class="rounded-lg border border-slate-200 bg-surface-field p-4"
        >
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <b>{{ item.skillId || "-" }}</b
              ><span
                class="rounded-full px-2 py-0.5 text-[10px] font-bold"
                :class="skillStatusClass(item.status)"
                >{{ item.status || "UNKNOWN" }}</span
              ><span
                v-if="item.fallbackUsed"
                class="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold text-amber-700"
                >fallback</span
              >
            </div>
            <span class="text-xs text-content-secondary">{{
              formatTime(item.timestamp)
            }}</span>
          </div>
          <div class="mt-3 grid grid-cols-4 gap-3 text-xs">
            <span
              >耗时 <b>{{ item.latencyMs || 0 }} ms</b></span
            ><span
              >尝试 <b>{{ item.attempts || 0 }}</b></span
            ><span
              >Trace
              <b class="font-mono">{{
                shortId(item.attributes?.traceId)
              }}</b></span
            ><span
              >Operator <b>{{ item.attributes?.operator || "-" }}</b></span
            >
          </div>
          <p class="mt-3 break-all text-sm text-content-secondary">
            {{ item.message || "-" }}
          </p>
        </article>
        <p
          v-if="!telemetry.length"
          class="py-8 text-center text-sm text-content-secondary"
        >
          暂无技能执行事件
        </p>
      </div>
    </article>

    <div class="grid gap-6 lg:grid-cols-3">
      <article
        class="rounded-xl border border-slate-200 bg-surface-raised p-6 shadow-sm lg:col-span-2"
      >
        <h4 class="mb-4 font-bold text-content">最近运维审计</h4>
        <div class="space-y-3">
          <div
            v-for="item in audits"
            :key="`${item.timestamp}-${item.operator}-${item.action}`"
            class="flex items-center gap-4 rounded-lg border-l-4 border-indigo-500 bg-surface-field px-4 py-3"
          >
            <span class="w-36 text-xs text-content-secondary">{{
              formatTime(item.timestamp)
            }}</span
            ><span
              class="rounded-full bg-indigo-100 px-2 py-0.5 text-[10px] font-black text-indigo-800"
              >{{ item.action || "OP" }}</span
            ><span class="text-sm text-content-secondary"
              ><b>{{ item.operator || "system" }}</b>
              {{ item.message || "-" }}</span
            >
          </div>
          <p
            v-if="!audits.length"
            class="py-8 text-center text-sm text-content-secondary"
          >
            暂无审计日志
          </p>
        </div>
      </article>
      <article
        class="rounded-xl border border-slate-200 bg-surface-raised p-6 shadow-sm"
      >
        <div class="flex items-center justify-between">
          <h4 class="font-bold text-content">A2A 运维</h4>
          <button
            class="text-xs font-bold text-indigo-600"
            @click="advancedOperationsVisible = !advancedOperationsVisible"
          >
            {{ advancedOperationsVisible ? "收起" : "展开" }}
          </button>
        </div>
        <p class="mt-3 text-xs text-content-secondary">
          L1 {{ idempotency.inMemorySize || 0 }} keys · L2
          {{ idempotency.redisSize || 0 }} keys
        </p>
        <div
          v-if="advancedOperationsVisible"
          class="mt-5 space-y-3 rounded-xl border-2 border-dashed border-red-200 bg-red-50/50 p-4"
        >
          <p class="text-xs text-red-700">
            以下操作可能影响正在处理的业务，请确认后执行。
          </p>
          <button
            class="w-full rounded-lg border border-red-200 bg-white py-2 text-xs font-bold text-red-600 disabled:opacity-50"
            :disabled="operationLoading"
            @click="clearCache"
          >
            清理幂等缓存</button
          ><button
            class="w-full rounded-lg border border-slate-200 bg-white py-2 text-xs font-bold text-content-secondary disabled:opacity-50"
            :disabled="operationLoading"
            @click="replayDeadLetters"
          >
            死信队列重放
          </button>
        </div>
      </article>
    </div>
  </section>
</template>
<script setup>
import { defineComponent, h, onMounted } from "vue";
import { useRagAudit } from "./useRagAudit.js";
const Metric = defineComponent({
  props: { label: String, value: [String, Number] },
  setup: (props) => () =>
    h(
      "div",
      { class: "rounded-lg border border-slate-200 bg-surface-field p-3" },
      [
        h(
          "div",
          { class: "text-[10px] font-bold uppercase text-content-secondary" },
          props.label,
        ),
        h(
          "div",
          { class: "mt-1 text-lg font-black" },
          String(props.value ?? "-"),
        ),
      ],
    ),
});
const state = useRagAudit();
const {
  telemetry,
  audits,
  idempotency,
  selectedSkillId,
  selectedSkillStatus,
  traceQuery,
  advancedOperationsVisible,
  loading,
  telemetryLoading,
  operationLoading,
  error,
  message,
  skillIds,
  eventCount,
  fallbackCount,
  averageLatency,
  loadTelemetry,
  reload,
  resetTelemetryFilters,
  clearCache,
  replayDeadLetters,
} = state;
const formatTime = (value) => (value ? new Date(value).toLocaleString() : "-");
const shortId = (value) => (value ? String(value).slice(0, 8) : "-");
const skillStatusClass = (value) =>
  ({
    SUCCESS: "bg-emerald-100 text-emerald-700",
    FAILED: "bg-red-100 text-red-700",
    FALLBACK: "bg-amber-100 text-amber-700",
  })[String(value || "").toUpperCase()] || "bg-slate-100 text-slate-600";
onMounted(reload);
</script>
