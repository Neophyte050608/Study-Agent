import { computed, ref } from "vue";
import * as ragAdminApi from "../../../api/ragAdmin.js";

const SLOW_THRESHOLD_MS = Number(
  import.meta.env?.VITE_RAG_SLOW_THRESHOLD_MS || 20000,
);
const errorMessage = (error) => error?.message || "请求失败";

function normalizeStatus(item) {
  const raw =
    typeof item?.traceStatus === "string"
      ? item.traceStatus.trim().toUpperCase()
      : "";
  if (raw === "COMPLETED") return item?.slowTrace ? "SLOW" : "SUCCESS";
  if (raw) return raw;
  return Number(item?.businessDurationMs || 0) >= SLOW_THRESHOLD_MS
    ? "SLOW"
    : "UNKNOWN";
}

function normalizeTrace(item = {}) {
  return {
    ...item,
    traceId: item.traceId ?? "-",
    latencyMs: Number(item.businessDurationMs || item.latencyMs || 0),
    firstTokenMs: Number(item.firstTokenMs || 0),
    retrievedCount: Number(item.retrievedDocCount || item.retrievedCount || 0),
    riskTags: Array.isArray(item.riskTags) ? item.riskTags : [],
    status: item.status || normalizeStatus(item),
  };
}

export function useRagTraces(api = ragAdminApi) {
  const traces = ref([]);
  const search = ref("");
  const status = ref("ALL");
  const riskyOnly = ref(false);
  const fallbackOnly = ref(false);
  const emptyRetrievalOnly = ref(false);
  const slowOnly = ref(false);
  const startedAfter = ref("");
  const endedBefore = ref("");
  const expanded = ref(false);
  const loading = ref(false);
  const error = ref("");
  const displayedTraces = computed(() =>
    expanded.value ? traces.value : traces.value.slice(0, 5),
  );

  function filters() {
    return {
      limit: expanded.value ? 50 : 20,
      status: status.value,
      riskyOnly: riskyOnly.value,
      fallbackOnly: fallbackOnly.value,
      emptyRetrievalOnly: emptyRetrievalOnly.value,
      slowOnly: slowOnly.value,
      q: search.value.trim(),
      startedAfter: startedAfter.value,
      endedBefore: endedBefore.value,
    };
  }

  async function reload() {
    loading.value = true;
    error.value = "";
    try {
      const payload = await api.loadOpsTraces(filters());
      traces.value = Array.isArray(payload) ? payload.map(normalizeTrace) : [];
      return true;
    } catch (cause) {
      error.value = errorMessage(cause);
      return false;
    } finally {
      loading.value = false;
    }
  }

  async function resetFilters() {
    search.value = "";
    status.value = "ALL";
    riskyOnly.value = false;
    fallbackOnly.value = false;
    emptyRetrievalOnly.value = false;
    slowOnly.value = false;
    startedAfter.value = "";
    endedBefore.value = "";
    return reload();
  }

  async function toggleExpanded() {
    expanded.value = !expanded.value;
    return reload();
  }

  return {
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
  };
}
