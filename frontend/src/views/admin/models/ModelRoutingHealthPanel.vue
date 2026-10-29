<script setup>
import { computed } from 'vue'
import { NCard, NStatistic, NTag, NTable, NDescriptions, NDescriptionsItem, NEmpty, NAlert, NFlex } from 'naive-ui'
import { healthTagType, routingSummary, runtimeRows } from './modelPresentation.js'

const props = defineProps({ stats: { type: Object, default: null } })
const summary = computed(() => routingSummary(props.stats))
const rows = computed(() => runtimeRows(props.stats))
const counters = computed(() => [
  ['总请求', props.stats?.runtime?.health?.totalRequests],
  ['总失败', props.stats?.runtime?.health?.totalFailureCount],
  ['路由降级', props.stats?.runtime?.routeFallbackCount]
])
const ollama = computed(() => props.stats?.ollama)
const logs = computed(() => props.stats?.healthLogs || [])
function checkedAt(value) {
  const date = value ? new Date(value) : null
  return date && !Number.isNaN(date.getTime()) ? date.toLocaleString('zh-CN') : '尚未检测'
}
</script>

<template>
  <section class="space-y-6 min-w-0" aria-label="路由运行健康">
    <NFlex align="center" justify="space-between">
      <h3 class="text-lg font-semibold">运行健康</h3>
      <NTag :type="summary.type">{{ summary.label }}</NTag>
    </NFlex>
    <div class="grid grid-cols-1 gap-4 sm:grid-cols-3">
      <NCard v-for="[label, value] in counters" :key="label" size="small">
        <NStatistic :label="label">{{ value ?? '—' }}</NStatistic>
      </NCard>
    </div>
    <NCard title="模型熔断与请求快照" size="small">
      <NEmpty v-if="!rows.length" description="暂无模型运行状态。发起请求或探测后刷新快照。" />
      <div v-else class="overflow-x-auto">
        <NTable :single-line="false" size="small" class="runtime-table">
          <thead><tr><th>模型</th><th>熔断状态</th><th>请求</th><th>成功</th><th>失败</th><th>连续失败</th><th>成功率</th></tr></thead>
          <tbody>
            <template v-for="row in rows" :key="row.name">
              <tr>
                <td class="break-all">{{ row.name }}</td>
                <td><NTag size="small" :type="healthTagType(row.state)">{{ row.state }}</NTag></td>
                <td>{{ row.requestCount ?? '—' }}</td><td>{{ row.successCount ?? '—' }}</td>
                <td>{{ row.failureCount ?? '—' }}</td><td>{{ row.consecutiveFailureCount ?? '—' }}</td>
                <td>{{ row.successRate }}</td>
              </tr>
              <tr v-if="row.lastFailureMessage"><td colspan="7"><NAlert type="error" :show-icon="false"><span class="break-all">最近失败：{{ row.lastFailureMessage }}</span></NAlert></td></tr>
            </template>
          </tbody>
        </NTable>
      </div>
    </NCard>
    <NCard title="本地 Ollama" size="small">
      <NEmpty v-if="!ollama" description="暂无本地模型检测结果" />
      <template v-else>
        <NFlex class="mb-4" align="center">
          <NTag :type="ollama.status === 'UP' ? 'success' : ollama.status === 'DEGRADED' ? 'warning' : ollama.status === 'DOWN' ? 'error' : 'default'">{{ ollama.status || 'UNKNOWN' }}</NTag>
          <span class="text-sm text-content-secondary">{{ checkedAt(ollama.checkedAt) }}</span>
        </NFlex>
        <NDescriptions :column="1" label-placement="left" bordered size="small" :label-style="{ width: '96px', whiteSpace: 'nowrap' }">
          <NDescriptionsItem label="地址"><span class="break-all">{{ ollama.baseUrl || '—' }}</span></NDescriptionsItem>
          <NDescriptionsItem label="模型"><span class="break-all">{{ ollama.model || '—' }}</span></NDescriptionsItem>
          <NDescriptionsItem label="服务可达">{{ ollama.serviceUp == null ? '未知' : ollama.serviceUp ? '是' : '否' }}</NDescriptionsItem>
          <NDescriptionsItem label="模型就绪">{{ ollama.modelReady == null ? '未知' : ollama.modelReady ? '是' : '否' }}</NDescriptionsItem>
          <NDescriptionsItem label="检测耗时">{{ ollama.latencyMs == null ? '—' : `${ollama.latencyMs} ms` }}</NDescriptionsItem>
        </NDescriptions>
        <NAlert v-if="ollama.error" type="error" class="mt-4"><span class="break-all">{{ ollama.error }}</span></NAlert>
      </template>
    </NCard>
    <NCard title="健康日志" size="small">
      <NEmpty v-if="!logs.length" description="暂无健康日志" />
      <ul v-else class="space-y-2 text-sm font-mono"><li v-for="(line, index) in logs" :key="index" class="break-all">{{ line }}</li></ul>
    </NCard>
  </section>
</template>

<style scoped>
.runtime-table { min-width: 720px; }
</style>
