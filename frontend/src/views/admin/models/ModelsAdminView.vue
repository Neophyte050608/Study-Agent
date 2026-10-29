<script setup>
import ModelCandidatesPanel from './ModelCandidatesPanel.vue'
import ModelRoutingHealthPanel from './ModelRoutingHealthPanel.vue'
</script>

<template>
  <main class="space-y-8 px-8 py-8">
    <section>
      <h2 class="text-2xl font-extrabold">模型与路由</h2>
      <p class="text-content-secondary">管理运行时候选模型并查看路由健康。</p>
    </section>
    <ModelCandidatesPanel />
    <ModelRoutingHealthPanel />
  </main>
</template>
