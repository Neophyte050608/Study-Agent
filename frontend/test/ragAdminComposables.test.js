import { test } from "node:test";
import assert from "node:assert/strict";
import { useRagOverview } from "../src/views/admin/rag/useRagOverview.js";
import { useRagTraces } from "../src/views/admin/rag/useRagTraces.js";
import { useRagEvaluation } from "../src/views/admin/rag/useRagEvaluation.js";
import { useRagAudit } from "../src/views/admin/rag/useRagAudit.js";

test("overview owns dashboard, history, and snapshot requests", async () => {
  const calls = [];
  const api = {
    loadRagDashboard: async () => {
      calls.push("dashboard");
      return { currentHour: { traceCount: 2 } };
    },
    loadMetricsHistory: async (hours, metrics) => {
      calls.push(["history", hours, metrics]);
      return [{ hour: "2026-10-30T10:00:00" }];
    },
    triggerSnapshot: async () => {
      calls.push("snapshot");
      return { message: "ok" };
    },
  };
  const state = useRagOverview(api);

  await state.reload();
  await state.generateSnapshot();

  assert.equal(state.dashboard.value.currentHour.traceCount, 2);
  assert.equal(state.historySnapshots.value.length, 1);
  assert.equal(calls.filter((call) => call === "dashboard").length, 2);
  assert.equal(
    calls.filter((call) => Array.isArray(call) && call[0] === "history").length,
    2,
  );
  assert.equal(calls.filter((call) => call === "snapshot").length, 1);
});

test("trace state maps filters and exposes retryable errors", async () => {
  const calls = [];
  let fails = true;
  const state = useRagTraces({
    loadOpsTraces: async (filters) => {
      calls.push(filters);
      if (fails) throw new Error("trace unavailable");
      return [
        { traceId: "t-1", traceStatus: "COMPLETED", businessDurationMs: 20 },
      ];
    },
  });

  state.search.value = " t-1 ";
  state.riskyOnly.value = true;
  await state.reload();
  assert.equal(state.error.value, "trace unavailable");

  fails = false;
  await state.reload();
  assert.equal(state.error.value, "");
  assert.equal(state.traces.value[0].status, "SUCCESS");
  assert.equal(calls.at(-1).q, "t-1");
  assert.equal(calls.at(-1).riskyOnly, true);
});

test("evaluation owns datasets, metrics, and both run histories", async () => {
  const calls = [];
  const state = useRagEvaluation({
    loadRetrievalEvalDatasets: async () => {
      calls.push("retrieval-datasets");
      return [{ datasetId: "r1" }];
    },
    loadRagQualityEvalDatasets: async () => {
      calls.push("quality-datasets");
      return [{ datasetId: "q1" }];
    },
    loadRetrievalEvalRuns: async () => {
      calls.push("retrieval-runs");
      return { records: [{ runId: "rr" }] };
    },
    loadRagQualityEvalRuns: async () => {
      calls.push("quality-runs");
      return { records: [{ runId: "qr" }] };
    },
    loadRetrievalMetrics: async () => {
      calls.push("metrics");
      return { top3Recall: 0.8 };
    },
    loadRagasEngineStatus: async () => {
      calls.push("engine");
      return { ragasEngineAvailable: true };
    },
  });

  await state.reload();

  assert.deepEqual(
    calls.sort(),
    [
      "engine",
      "metrics",
      "quality-datasets",
      "quality-runs",
      "retrieval-datasets",
      "retrieval-runs",
    ].sort(),
  );
  assert.equal(state.retrievalRuns.value[0].runId, "rr");
  assert.equal(state.qualityRuns.value[0].runId, "qr");
  assert.equal(state.error.value, "");
});

test("audit owns telemetry, audits, idempotency, and operations", async () => {
  const calls = [];
  const api = {
    loadSkillTelemetry: async () => {
      calls.push("telemetry");
      return { records: [{ skillId: "search" }] };
    },
    loadOpsAudits: async () => {
      calls.push("audits");
      return [{ action: "CLEAR" }];
    },
    loadOpsIdempotency: async () => {
      calls.push("idempotency");
      return { inMemorySize: 3 };
    },
    clearIdempotencyCache: async () => {
      calls.push("clear");
    },
    replayDlq: async () => {
      calls.push("replay");
    },
  };
  const state = useRagAudit(api);

  await state.reload();
  await state.clearCache();
  await state.replayDeadLetters();

  assert.equal(state.telemetry.value[0].skillId, "search");
  assert.equal(state.audits.value[0].action, "CLEAR");
  assert.ok(calls.includes("clear"));
  assert.ok(calls.includes("replay"));
});
