<template>
  <section
    id="section-rag"
    class="rounded-xl border border-slate-200 bg-surface-raised p-8 shadow-sm"
  >
    <div class="mb-6 flex items-center justify-between">
      <div>
        <h3 class="text-xl font-bold text-content">RAG 链路实时追踪</h3>
        <p class="mt-1 text-sm text-content-secondary">
          筛选并查看最近链路，详情在独立页面打开。
        </p>
      </div>
      <button
        class="rounded-lg border px-4 py-2 text-sm font-bold text-indigo-700 disabled:opacity-50"
        :disabled="loading"
        @click="reload"
      >
        {{ loading ? "刷新中…" : "刷新" }}
      </button>
    </div>
    <div
      v-if="error"
      class="mb-5 flex items-center justify-between rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
    >
      <span>链路加载失败：{{ error }}</span
      ><button class="font-bold underline" @click="reload">重试</button>
    </div>
    <form
      class="mb-6 grid gap-3 lg:grid-cols-[1.4fr_repeat(5,auto)]"
      @submit.prevent="reload"
    >
      <input
        v-model="search"
        class="rounded-lg border border-slate-200 bg-surface-field px-4 py-2 text-sm"
        placeholder="筛选 Trace ID / 风险标签"
      />
      <select
        v-model="status"
        class="rounded-lg border border-slate-200 bg-surface-raised px-3 py-2 text-sm"
      >
        <option value="ALL">全部状态</option>
        <option
          v-for="value in ['SUCCESS', 'SLOW', 'FAILED', 'RUNNING']"
          :key="value"
        >
          {{ value }}
        </option>
      </select>
      <label
        v-for="filter in booleanFilters"
        :key="filter.key"
        class="flex items-center gap-2 text-xs font-bold text-content-secondary"
        ><input
          v-model="filter.model.value"
          type="checkbox"
          class="accent-indigo-600"
        />{{ filter.label }}</label
      >
      <div class="grid grid-cols-2 gap-3 lg:col-span-6">
        <label class="text-xs font-bold text-content-secondary">开始时间
          <input v-model="startedAfter" class="mt-1 w-full rounded-lg border border-slate-200 bg-surface-field px-3 py-2 text-sm font-normal" type="datetime-local" />
        </label>
        <label class="text-xs font-bold text-content-secondary">结束时间
          <input v-model="endedBefore" class="mt-1 w-full rounded-lg border border-slate-200 bg-surface-field px-3 py-2 text-sm font-normal" type="datetime-local" />
        </label>
      </div>
      <div class="flex gap-2 lg:col-span-6">
        <button
          class="rounded-lg bg-brand px-4 py-2 text-sm font-bold text-white"
          type="submit"
        >
          应用筛选</button
        ><button
          class="rounded-lg border border-slate-200 px-4 py-2 text-sm font-bold"
          type="button"
          @click="resetFilters"
        >
          清空筛选
        </button>
      </div>
    </form>
    <div class="overflow-x-auto">
      <table class="w-full text-left">
        <thead>
          <tr
            class="border-b text-xs uppercase tracking-wider text-content-secondary"
          >
            <th class="pb-4">Trace ID</th>
            <th class="pb-4">耗时</th>
            <th class="pb-4">首 Token</th>
            <th class="pb-4">召回</th>
            <th class="pb-4">风险</th>
            <th class="pb-4">状态</th>
            <th class="pb-4 text-right">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y">
          <tr v-for="item in displayedTraces" :key="item.traceId">
            <td class="py-4 font-mono text-sm text-indigo-600">
              {{ item.traceId }}
            </td>
            <td>{{ item.latencyMs }} ms</td>
            <td>{{ item.firstTokenMs }} ms</td>
            <td>{{ item.retrievedCount }}</td>
            <td>
              <span
                v-for="tag in item.riskTags.slice(0, 2)"
                :key="tag"
                class="mr-1 rounded bg-amber-100 px-2 py-1 text-[10px] font-bold text-amber-700"
                >{{ formatRiskTag(tag) }}</span
              ><span
                v-if="!item.riskTags.length"
                class="text-xs text-emerald-600"
                >none</span
              >
            </td>
            <td>
              <span
                class="rounded px-2 py-1 text-[10px] font-bold"
                :class="statusClass(item.status)"
                >{{ item.status }}</span
              >
            </td>
            <td class="text-right">
              <button
                class="text-xs font-bold text-indigo-600"
                @click="viewDetail(item.traceId)"
              >
                查看详情 →
              </button>
            </td>
          </tr>
          <tr v-if="!displayedTraces.length">
            <td
              colspan="7"
              class="py-8 text-center text-sm text-content-secondary"
            >
              暂无轨迹数据
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <button
      v-if="traces.length > 5 || expanded"
      class="mt-5 text-sm font-bold text-indigo-600"
      @click="toggleExpanded"
    >
      {{ expanded ? "收起" : "展开全部" }}
    </button>
  </section>
</template>
<script setup>
import { computed, onMounted } from "vue";
import { useRouter } from "vue-router";
import { useRagTraces } from "./useRagTraces.js";
const router = useRouter();
const state = useRagTraces();
const {
  traces,
  displayedTraces,
  search,
  status,
  riskyOnly,
  fallbackOnly,
  emptyRetrievalOnly,
  slowOnly,
  startedAfter,
  endedBefore,
  expanded,
  loading,
  error,
  reload,
  resetFilters,
  toggleExpanded,
} = state;
const booleanFilters = computed(() => [
  { key: "risky", label: "只看异常", model: riskyOnly },
  { key: "fallback", label: "只看 Fallback", model: fallbackOnly },
  { key: "empty", label: "只看空召回", model: emptyRetrievalOnly },
  { key: "slow", label: "只看慢链路", model: slowOnly },
]);
const viewDetail = (traceId) =>
  router.push({ name: "admin-rag-trace", params: { traceId } });
const formatRiskTag = (tag) =>
  ({
    slow_trace: "slow trace",
    slow_first_token: "slow first token",
    fallback_triggered: "fallback",
    retrieval_empty: "empty retrieval",
  })[tag] || tag;
const statusClass = (value) =>
  ({
    FAILED: "bg-red-100 text-red-700",
    SLOW: "bg-amber-100 text-amber-700",
    RUNNING: "bg-indigo-100 text-indigo-700",
    SUCCESS: "bg-emerald-100 text-emerald-700",
  })[value] || "bg-slate-100 text-slate-600";
onMounted(reload);
</script>
