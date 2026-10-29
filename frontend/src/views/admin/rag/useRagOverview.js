import { computed, ref } from "vue";
import * as ragAdminApi from "../../../api/ragAdmin.js";
import { overviewCards } from "./ragPresentation.js";

const errorMessage = (error) => error?.message || "请求失败";

export function useRagOverview(api = ragAdminApi) {
  const dashboard = ref({});
  const historySnapshots = ref([]);
  const selectedRange = ref(168);
  const selectedMetrics = ref([
    "avgLatencyMs",
    "p95LatencyMs",
    "satisfactionRate",
  ]);
  const loading = ref(false);
  const historyLoading = ref(false);
  const snapshotLoading = ref(false);
  const error = ref("");
  const snapshotMessage = ref("");

  const metricCards = computed(() => overviewCards(dashboard.value));
  const currentHour = computed(() => dashboard.value?.currentHour || {});
  const feedbackSummary = computed(() => currentHour.value?.feedback || {});
  const alertLevel = computed(() => dashboard.value?.alertLevel || "NONE");
  const alertTags = computed(() =>
    Array.isArray(dashboard.value?.alertTags) ? dashboard.value.alertTags : [],
  );

  async function loadDashboard() {
    try {
      dashboard.value = (await api.loadRagDashboard()) || {};
      return true;
    } catch (cause) {
      error.value = errorMessage(cause);
      return false;
    }
  }

  async function loadHistory() {
    historyLoading.value = true;
    try {
      const payload = await api.loadMetricsHistory(
        selectedRange.value,
        selectedMetrics.value.join(","),
      );
      historySnapshots.value = Array.isArray(payload) ? payload : [];
      return true;
    } catch (cause) {
      error.value = errorMessage(cause);
      return false;
    } finally {
      historyLoading.value = false;
    }
  }

  async function reload() {
    loading.value = true;
    error.value = "";
    try {
      const results = await Promise.all([loadDashboard(), loadHistory()]);
      return results.every(Boolean);
    } finally {
      loading.value = false;
    }
  }

  async function generateSnapshot() {
    snapshotLoading.value = true;
    snapshotMessage.value = "";
    error.value = "";
    try {
      const result = await api.triggerSnapshot();
      snapshotMessage.value = result?.message || "快照已生成";
      await reload();
      return true;
    } catch (cause) {
      error.value = errorMessage(cause);
      snapshotMessage.value = "快照生成请求失败";
      return false;
    } finally {
      snapshotLoading.value = false;
    }
  }

  return {
    dashboard,
    historySnapshots,
    selectedRange,
    selectedMetrics,
    loading,
    historyLoading,
    snapshotLoading,
    error,
    snapshotMessage,
    metricCards,
    currentHour,
    feedbackSummary,
    alertLevel,
    alertTags,
    loadDashboard,
    loadHistory,
    reload,
    generateSnapshot,
  };
}
