import { computed, ref } from "vue";
import * as ragAdminApi from "../../../api/ragAdmin.js";
import { normalizeEvaluationRuns } from "./ragPresentation.js";

const errorMessage = (error) => error?.message || "请求失败";
const records = (payload) =>
  Array.isArray(payload)
    ? payload
    : Array.isArray(payload?.records)
      ? payload.records
      : normalizeEvaluationRuns(payload);

export function useRagEvaluation(api = ragAdminApi) {
  const retrievalDatasets = ref([]);
  const qualityDatasets = ref([]);
  const selectedRetrievalDataset = ref("default");
  const selectedQualityDataset = ref("default");
  const retrievalReport = ref(null);
  const retrievalRuns = ref([]);
  const retrievalMetrics = ref({});
  const retrievalMetricsLimit = ref(200);
  const retrievalMetricsHours = ref(null);
  const selectedEngine = ref("");
  const engineStatus = ref(null);
  const qualityReport = ref(null);
  const qualityRuns = ref([]);
  const qualityDetail = ref(null);
  const loading = ref(false);
  const datasetLoading = ref(false);
  const retrievalLoading = ref(false);
  const metricsLoading = ref(false);
  const qualityLoading = ref(false);
  const error = ref("");
  const message = ref("");

  const selectedRetrievalDescription = computed(() => {
    const current = retrievalDatasets.value.find(
      (item) => item.datasetId === selectedRetrievalDataset.value,
    );
    return current
      ? `${current.description || ""}${current.fileName ? ` | ${current.fileName}` : ""}`
      : "请选择检索评测使用的数据集。";
  });
  const selectedQualityDescription = computed(() => {
    const current = qualityDatasets.value.find(
      (item) => item.datasetId === selectedQualityDataset.value,
    );
    return current
      ? `${current.description || ""}${current.fileName ? ` | ${current.fileName}` : ""}`
      : "请选择质量评测使用的数据集。";
  });

  function metricsParams() {
    const params = {};
    const limit = Number(retrievalMetricsLimit.value);
    const hours = Number(retrievalMetricsHours.value);
    params.limit =
      Number.isFinite(limit) && limit > 0
        ? Math.min(Math.round(limit), 1000)
        : 200;
    if (Number.isFinite(hours) && hours > 0)
      params.hours = Math.min(Math.round(hours), 168);
    return params;
  }

  async function capture(action, failurePrefix) {
    try {
      return await action();
    } catch (cause) {
      error.value = `${failurePrefix}: ${errorMessage(cause)}`;
      return null;
    }
  }

  async function loadDatasets() {
    datasetLoading.value = true;
    try {
      const result = await capture(
        () =>
          Promise.all([
            api.loadRetrievalEvalDatasets(),
            api.loadRagQualityEvalDatasets(),
          ]),
        "加载评测数据集失败",
      );
      if (!result) return false;
      retrievalDatasets.value = records(result[0]);
      qualityDatasets.value = records(result[1]);
      if (
        !retrievalDatasets.value.some(
          (item) => item.datasetId === selectedRetrievalDataset.value,
        )
      )
        selectedRetrievalDataset.value =
          retrievalDatasets.value[0]?.datasetId || "";
      if (
        !qualityDatasets.value.some(
          (item) => item.datasetId === selectedQualityDataset.value,
        )
      )
        selectedQualityDataset.value =
          qualityDatasets.value[0]?.datasetId || "";
      return true;
    } finally {
      datasetLoading.value = false;
    }
  }

  async function loadRetrievalHistory() {
    const result = await capture(
      () => api.loadRetrievalEvalRuns(8),
      "加载检索评测历史失败",
    );
    if (result !== null) retrievalRuns.value = records(result);
    return result !== null;
  }

  async function loadQualityHistory() {
    const result = await capture(
      () => api.loadRagQualityEvalRuns(20),
      "加载质量评测历史失败",
    );
    if (result !== null) qualityRuns.value = records(result);
    return result !== null;
  }

  async function loadMetrics() {
    metricsLoading.value = true;
    try {
      const result = await capture(
        () => api.loadRetrievalMetrics(metricsParams()),
        "加载检索指标失败",
      );
      if (result !== null) retrievalMetrics.value = result || {};
      return result !== null;
    } finally {
      metricsLoading.value = false;
    }
  }

  async function loadEngineStatus() {
    const result = await capture(
      () => api.loadRagasEngineStatus(),
      "加载评测引擎状态失败",
    );
    if (result !== null) engineStatus.value = result || {};
    return result !== null;
  }

  async function reload() {
    loading.value = true;
    error.value = "";
    try {
      const results = await Promise.all([
        loadDatasets(),
        loadRetrievalHistory(),
        loadQualityHistory(),
        loadMetrics(),
        loadEngineStatus(),
      ]);
      return results.every(Boolean);
    } finally {
      loading.value = false;
    }
  }

  async function runRetrieval() {
    retrievalLoading.value = true;
    error.value = "";
    try {
      const result = await capture(
        () => api.runRetrievalEval(selectedRetrievalDataset.value || undefined),
        "检索评测失败",
      );
      if (result === null) return false;
      retrievalReport.value = result;
      message.value = `检索评测完成: ${result?.runLabel || result?.runId || selectedRetrievalDataset.value || "default"}`;
      await Promise.all([loadRetrievalHistory(), loadMetrics()]);
      return true;
    } finally {
      retrievalLoading.value = false;
    }
  }

  async function runQuality() {
    qualityLoading.value = true;
    error.value = "";
    try {
      const result = await capture(
        () =>
          api.runRagQualityEvalWithDataset(
            selectedQualityDataset.value || undefined,
            selectedEngine.value || undefined,
          ),
        "质量评测失败",
      );
      if (result === null) return false;
      qualityReport.value = result;
      qualityDetail.value = result;
      message.value = `质量评测完成: ${result?.runLabel || result?.runId || selectedQualityDataset.value || "default"}`;
      await loadQualityHistory();
      return true;
    } finally {
      qualityLoading.value = false;
    }
  }

  async function loadQualityDetail(runId) {
    error.value = "";
    const result = await capture(
      () => api.loadRagQualityEvalDetail(runId),
      "加载质量评测详情失败",
    );
    if (result === null) return false;
    qualityDetail.value = result;
    qualityReport.value = result;
    return true;
  }

  return {
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
    loadRetrievalHistory,
    loadQualityHistory,
    loadMetrics,
    loadEngineStatus,
    runRetrieval,
    runQuality,
    loadQualityDetail,
  };
}
