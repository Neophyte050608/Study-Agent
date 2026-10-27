<template>
  <section v-if="result" class="result" aria-labelledby="result-heading">
    <h2 id="result-heading">学习计划</h2>
    <p class="note">{{ result.personalized ? '已参考学习画像' : '通用学习建议' }}；{{ result.evidenceAvailable ? '已参考知识库证据' : '未使用知识库证据' }}。仅预览，不会保存为笔记。</p>
    <ul v-if="result.degradations?.length" class="degradations"><li v-for="reason in result.degradations" :key="reason">{{ degradationLabel(reason) }}</li></ul>
    <div v-if="result.plan" class="plan-text">{{ result.plan }}</div>
    <p v-else class="note">尚未生成可用计划。可在执行预算内重试失败节点。</p>
    <section v-if="result.evidence?.length" class="evidence" aria-labelledby="evidence-heading">
      <h3 id="evidence-heading">参考证据</h3>
      <article v-for="item in result.evidence" :key="item.documentId">
        <h4>{{ item.title || '未命名文档' }}</h4>
        <p class="note">文档 ID：{{ item.documentId }}</p>
        <p>{{ item.excerpt }}</p>
      </article>
    </section>
  </section>
</template>
<script setup>
import { degradationLabel } from './planPresentation.js'
defineProps({ result: Object })
</script>
<style scoped>
.result { margin-top: 1.5rem; padding: 1.5rem; background: rgb(var(--color-surface-raised)); border: 1px solid rgb(var(--color-divider)); border-radius: .5rem; }
h2, h3, h4 { font-weight: 700; } h2 { font-size: 1.2rem; } h3 { font-size: 1rem; }
.note { margin-top: .5rem; color: rgb(var(--color-content-secondary)); font-size: .85rem; line-height: 1.7; overflow-wrap: anywhere; }
.degradations { list-style: disc; padding-left: 1.25rem; margin-top: 1rem; font-size: .9rem; color: rgb(var(--color-content-secondary)); }
.plan-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; margin-top: 1.25rem; }
.evidence { margin-top: 2rem; border-top: 1px solid rgb(var(--color-divider)); padding-top: 1rem; }
article { margin-top: 1rem; padding-left: 1rem; border-left: 3px solid rgb(var(--color-brand)); }
article p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; margin-top: .5rem; }
</style>
