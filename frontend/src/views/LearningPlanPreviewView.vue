<template>
  <div class="preview-page" :class="{ collapsed: sidebarCollapsed }">
    <header class="page-header">
      <RouterLink to="/profile" class="back-link">返回能力画像</RouterLink>
      <h1>学习计划预览</h1>
      <p class="note">结合学习画像与知识证据生成计划。任务在后台执行，离开页面不会取消。</p>
    </header>
    <main>
      <div v-if="error" class="notice" role="alert">{{ error }}</div>
      <div v-if="uncertain" class="notice" role="status">上次提交可能已受理。重发原输入会复用幂等键；刷新此页前尚未获得任务 ID 的提交无法自动恢复。</div>
      <div class="workspace">
        <section class="form-panel" aria-labelledby="form-heading">
          <h2 id="form-heading">预览主题</h2>
          <form @submit.prevent="submit(topic, instruction)">
            <label for="plan-topic">学习主题 <span class="note">必填，最多 200 字符</span></label>
            <input id="plan-topic" v-model="topic" required maxlength="200" :disabled="locked" placeholder="例如：Java 并发" />
            <label for="plan-request">学习要求 <span class="note">可选，最多 2000 字符</span></label>
            <textarea id="plan-request" v-model="instruction" maxlength="2000" rows="4" :disabled="locked" placeholder="例如：用三天复习锁与线程池，每天安排一道练习题" />
            <button v-if="!taskId" type="submit" class="primary" :disabled="!!busy || !topic.trim()">{{ busy === 'submit' ? '正在提交…' : uncertain ? '重发原提交' : '生成预览' }}</button>
          </form>
          <p v-if="taskId" class="note">已受理任务的输入不可修改。要调整主题或主动刷新成功数据，请新建预览。</p>
          <div v-if="taskId || uncertain" class="actions">
            <button type="button" :disabled="!!busy" @click="newPreview">新建预览</button>
            <button v-if="taskId" type="button" :disabled="!!busy" @click="refresh">{{ busy === 'query' ? '正在查询…' : '刷新状态' }}</button>
          </div>
          <p class="note setup">需要后台启用持久执行，并配置 MySQL 与模型。open 模式为本地单用户；共享部署请使用 JWT 认证。</p>
        </section>
        <LearningPlanNodes :task="task" :can-retry="canRetry" :failed-nodes="failedNodes" v-model:selected="selected" :disabled="!!busy" />
      </div>
      <section v-if="taskId" class="task-bar" aria-label="任务状态">
        <div aria-live="polite"><strong>{{ statusLabel(task?.status) }}</strong><span v-if="task"> · 版本 {{ task.revision }}</span><span v-if="paused"> · 查询已暂停</span></div>
        <p class="task-id">任务 ID：{{ taskId }}</p>
        <p v-if="task?.errorCode" class="note">{{ codeLabel(task.errorCode) }}</p>
        <div class="actions">
          <button v-if="canRetry" class="primary" type="button" :disabled="!!busy || !failedNodes.length" @click="retry(selected)">{{ busy === 'retry' ? '正在重试…' : selected.length ? '重试所选失败节点' : '重试全部失败节点' }}</button>
          <button v-if="canDelete" type="button" :disabled="!!busy" @click="confirmDelete = true">删除终态任务</button>
        </div>
        <div v-if="confirmDelete" class="delete-confirm" role="group" aria-label="确认删除">
          <p>删除此任务及执行记录？此操作不可撤销，不是取消执行。</p>
          <div class="actions"><button type="button" :disabled="!!busy" @click="removeConfirmed">{{ busy === 'delete' ? '正在删除…' : '确认删除' }}</button><button type="button" :disabled="!!busy" @click="confirmDelete = false">保留任务</button></div>
        </div>
      </section>
      <LearningPlanResult :result="task?.result" />
      <p v-if="!taskId" class="empty">提交主题后，查看三个执行步骤与最终计划。本页不自动写入笔记或长期记忆。</p>
    </main>
  </div>
</template>
<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLearningPlanPreview } from '../composables/useLearningPlanPreview.js'
import LearningPlanNodes from './learning/LearningPlanNodes.vue'
import LearningPlanResult from './learning/LearningPlanResult.vue'
import { statusLabel, codeLabel } from './learning/planPresentation.js'
defineProps({ sidebarCollapsed: Boolean })
const route = useRoute(), router = useRouter()
const topic = ref(''), instruction = ref(''), selected = ref([]), confirmDelete = ref(false)
const vm = useLearningPlanPreview()
const { task, taskId, busy, error, paused, uncertain, canRetry, canDelete, failedNodes, submit, refresh, retry } = vm
const locked = computed(() => !!taskId.value || !!busy.value || uncertain.value)
watch(() => route.query.taskId, id => { void vm.load(id || '') }, { immediate: true })
watch(taskId, id => {
  if ((route.query.taskId || '') !== id) void router.replace({ path: route.path, query: id ? { taskId: id } : {} })
})
watch(() => [task.value?.taskId, task.value?.revision], () => { selected.value = []; confirmDelete.value = false })
function newPreview() {
  if (uncertain.value && !window.confirm('旧提交可能已受理。新建预览将放弃当前幂等键，可能产生另一项任务。继续？')) return
  vm.reset(); topic.value = ''; instruction.value = ''; selected.value = []; confirmDelete.value = false
}
async function removeConfirmed() { await vm.remove(); confirmDelete.value = false }
onBeforeUnmount(vm.dispose)
</script>
<style scoped>
.preview-page { margin-left: 16rem; min-height: 100vh; width: calc(100% - 16rem); background: rgb(var(--color-surface)); color: rgb(var(--color-content)); }
.preview-page.collapsed { margin-left: 5rem; width: calc(100% - 5rem); }
.page-header { padding: 2rem 2rem 1.5rem; border-bottom: 1px solid rgb(var(--color-divider)); background: rgb(var(--color-surface-raised)); }
h1 { font-size: 1.65rem; font-weight: 800; margin: .75rem 0 .5rem; } h2 { font-size: 1.05rem; font-weight: 700; margin-bottom: 1rem; }
.back-link { font-size: .9rem; color: rgb(var(--color-brand-text)); }
main { padding: 1.5rem 2rem 3rem; max-width: 76rem; margin: 0 auto; }
.workspace { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 2rem; }
.form-panel, :deep(.node-panel) { padding: 1.5rem; background: rgb(var(--color-surface-raised)); border: 1px solid rgb(var(--color-divider)); border-radius: .5rem; min-width: 0; }
label { display: block; font-weight: 600; font-size: .9rem; margin-bottom: .5rem; } label .note { display: block; font-weight: 400; }
input, textarea { display: block; width: 100%; margin-bottom: 1rem; padding: .7rem; border: 1px solid rgb(var(--color-divider)); border-radius: .3rem; background: rgb(var(--color-surface-field)); color: inherit; font: inherit; }
textarea { resize: vertical; } button { font: inherit; padding: .65rem .9rem; border: 1px solid rgb(var(--color-divider)); border-radius: .3rem; color: inherit; background: rgb(var(--color-surface-raised)); cursor: pointer; font-size: .9rem; }
button.primary { background: rgb(var(--color-brand)); color: white; border-color: transparent; } button:disabled, input:disabled, textarea:disabled { opacity: .65; cursor: not-allowed; }
button:focus-visible, input:focus-visible, textarea:focus-visible, a:focus-visible { outline: 2px solid rgb(var(--color-brand)); outline-offset: 3px; }
.note { font-size: .85rem; color: rgb(var(--color-content-secondary)); line-height: 1.7; }
.setup { margin-top: 1.25rem; } .actions { display: flex; flex-wrap: wrap; gap: .6rem; margin-top: 1rem; }
.notice { border-left: 3px solid rgb(var(--color-brand)); background: rgb(var(--color-surface-raised)); padding: .8rem 1rem; margin-bottom: 1rem; overflow-wrap: anywhere; }
.task-bar { margin-top: 1.5rem; padding: 1.25rem; border: 1px solid rgb(var(--color-divider)); border-radius: .3rem; }
.task-id { overflow-wrap: anywhere; font-size: .85rem; color: rgb(var(--color-content-secondary)); margin-top: .5rem; }
.delete-confirm { margin-top: 1rem; padding-top: 1rem; border-top: 1px solid rgb(var(--color-divider)); font-size: .9rem; }
.empty { margin-top: 1.5rem; color: rgb(var(--color-content-secondary)); font-size: .9rem; line-height: 1.7; }
@media (max-width: 1000px) { .workspace { grid-template-columns: minmax(0, 1fr); gap: 1rem; } }
@media (max-width: 640px) { .page-header { padding: 1rem; } main { padding: 1rem; } .form-panel, :deep(.node-panel) { padding: 1rem; } h1 { font-size: 1.35rem; } }
</style>
