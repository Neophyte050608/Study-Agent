import { computed, ref } from "vue";
import * as ragAdminApi from "../../../api/ragAdmin.js";

const errorMessage = (error) => error?.message || "请求失败";
const records = (payload) =>
  Array.isArray(payload)
    ? payload
    : Array.isArray(payload?.records)
      ? payload.records
      : [];

export function useRagAudit(api = ragAdminApi) {
  const telemetry = ref([]);
  const audits = ref([]);
  const idempotency = ref({});
  const selectedSkillId = ref("ALL");
  const selectedSkillStatus = ref("ALL");
  const traceQuery = ref("");
  const advancedOperationsVisible = ref(false);
  const loading = ref(false);
  const telemetryLoading = ref(false);
  const operationLoading = ref(false);
  const error = ref("");
  const message = ref("");

  const skillIds = computed(() =>
    [
      ...new Set(telemetry.value.map((item) => item.skillId).filter(Boolean)),
    ].sort(),
  );
  const eventCount = computed(() => telemetry.value.length);
  const fallbackCount = computed(
    () => telemetry.value.filter((item) => item.fallbackUsed).length,
  );
  const averageLatency = computed(() =>
    telemetry.value.length
      ? Math.round(
          telemetry.value.reduce(
            (sum, item) => sum + Number(item.latencyMs || 0),
            0,
          ) / telemetry.value.length,
        )
      : 0,
  );

  function telemetryFilters() {
    return {
      limit: 12,
      skillId: selectedSkillId.value,
      status: selectedSkillStatus.value,
      traceId: traceQuery.value.trim(),
    };
  }

  async function loadTelemetry() {
    telemetryLoading.value = true;
    try {
      telemetry.value = records(
        await api.loadSkillTelemetry(telemetryFilters()),
      );
      return true;
    } catch (cause) {
      error.value = errorMessage(cause);
      return false;
    } finally {
      telemetryLoading.value = false;
    }
  }

  async function reload() {
    loading.value = true;
    error.value = "";
    try {
      const results = await Promise.allSettled([
        loadTelemetry(),
        api.loadOpsAudits(5),
        api.loadOpsIdempotency(),
      ]);
      if (results[1].status === "fulfilled")
        audits.value = records(results[1].value);
      else error.value = errorMessage(results[1].reason);
      if (results[2].status === "fulfilled")
        idempotency.value = results[2].value || {};
      else error.value = errorMessage(results[2].reason);
      return !error.value && results[0].value === true;
    } finally {
      loading.value = false;
    }
  }

  async function resetTelemetryFilters() {
    selectedSkillId.value = "ALL";
    selectedSkillStatus.value = "ALL";
    traceQuery.value = "";
    return loadTelemetry();
  }

  async function runOperation(action, successMessage) {
    operationLoading.value = true;
    error.value = "";
    try {
      await action();
      message.value = successMessage;
      await reload();
      return true;
    } catch (cause) {
      error.value = errorMessage(cause);
      return false;
    } finally {
      operationLoading.value = false;
    }
  }

  const clearCache = () =>
    runOperation(() => api.clearIdempotencyCache(), "幂等缓存已清理");
  const replayDeadLetters = () =>
    runOperation(() => api.replayDlq(), "死信重放指令已发送");

  return {
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
  };
}
