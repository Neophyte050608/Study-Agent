<template>
  <main class="min-h-screen space-y-8 bg-surface-field px-8 py-8 text-content">
    <header>
      <h2 class="text-2xl font-extrabold">RAG 运行与评测</h2>
      <p class="mt-2 text-sm text-content-secondary">
        按概览、链路、评测和审计拆分运行管理职责。
      </p>
    </header>
    <RagOverviewPanel />
    <RagTracePanel />
    <RagEvaluationPanel />
    <RagAuditPanel />
  </main>
</template>
<script setup>
import RagAuditPanel from "./RagAuditPanel.vue";
import RagEvaluationPanel from "./RagEvaluationPanel.vue";
import RagOverviewPanel from "./RagOverviewPanel.vue";
import RagTracePanel from "./RagTracePanel.vue";
</script>
