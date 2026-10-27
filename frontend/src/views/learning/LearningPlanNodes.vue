<template>
  <section class="node-panel" aria-labelledby="nodes-heading">
    <h2 id="nodes-heading">执行步骤</h2>
    <p class="note">画像与证据可并行读取，计划在两者结束后生成。</p>
    <ol class="steps">
      <li v-for="(name, id) in names" :key="id">
        <div class="step-top"><strong>{{ name }}</strong><span>{{ statusLabel(task?.nodes?.[id]?.status || 'PENDING') }}</span></div>
        <p v-if="task?.nodes?.[id]" class="note">已尝试 {{ task.nodes[id].attempts }} / 3 次 <span v-if="task.nodes[id].code">；{{ codeLabel(task.nodes[id].code) }}</span></p>
        <label v-if="canRetry && failedNodes.includes(id)" class="choice">
          <input type="checkbox" :value="id" :checked="selected.includes(id)" :disabled="disabled" @change="toggle(id, $event.target.checked)" />
          重试{{ name }}
        </label>
      </li>
    </ol>
    <p v-if="canRetry" class="note">不选节点将重试全部失败根；选择节点会同时重算后继。成功的无关节点复用，执行预算由后端校验。</p>
  </section>
</template>
<script setup>
import { statusLabel, codeLabel } from './planPresentation.js'
const props = defineProps({ task: Object, canRetry: Boolean, failedNodes: Array, selected: Array, disabled: Boolean })
const emit = defineEmits(['update:selected'])
const names = { profile: '读取画像', evidence: '检索证据', compose: '生成计划' }
function toggle(id, checked) { emit('update:selected', checked ? [...props.selected, id] : props.selected.filter(item => item !== id)) }
</script>
<style scoped>
.node-panel h2 { font-size: 1.05rem; font-weight: 700; margin-bottom: .5rem; }
.note { color: rgb(var(--color-content-secondary)); font-size: .85rem; line-height: 1.65; }
.steps { padding: 0; list-style: none; margin: 1rem 0; }
.steps li { padding: 1rem 0; border-top: 1px solid rgb(var(--color-divider)); }
.step-top { display: flex; justify-content: space-between; gap: .7rem; font-size: .9rem; }
.step-top span { color: rgb(var(--color-brand-text)); }
.choice { display: flex; align-items: center; gap: .5rem; margin-top: .5rem; font-size: .9rem; }
input { accent-color: rgb(var(--color-brand)); }
input:focus-visible { outline: 2px solid rgb(var(--color-brand)); outline-offset: 3px; }
</style>
