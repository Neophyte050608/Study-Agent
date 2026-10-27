const statuses = { QUEUED: '排队中', RUNNING: '运行中', PENDING: '等待执行', SUCCEEDED: '已完成',
  COMPLETED: '已完成', DEGRADED: '降级完成', FAILED: '执行失败', TIMED_OUT: '已超时', SKIPPED: '已跳过' }
const reasons = { PROFILE_UNAVAILABLE: '画像读取不可用，本次使用通用学习建议。',
  PROFILE_EMPTY: '暂无画像数据，本次未使用个性化信息。',
  EVIDENCE_UNAVAILABLE: '知识证据读取不可用，本次未使用知识库引用。',
  EVIDENCE_EMPTY: '未找到相关知识证据，本次未使用知识库引用。' }
const codes = { OK: '执行成功', PENDING: '等待执行', SUCCEEDED: '执行成功', FAILED: '执行失败',
  TIMED_OUT: '执行超时', TIMEOUT: '执行超时', SKIPPED: '依赖未满足，已跳过',
  RUNNING: '正在执行', DEPENDENCY_FAILED: '依赖执行失败', OVERLOADED: '执行资源繁忙',
  INTERRUPTED: '执行已中断', OUTPUT_INVALID: '执行输出不合法', LEASE_LOST: '执行租约已失效',
  ATTEMPT_BUDGET_EXHAUSTED: '已达到节点尝试次数上限', NODE_FAILED: '节点执行失败', EXECUTION_FAILED: '执行失败', EMPTY: '暂无可用数据' }
export const statusLabel = value => statuses[value] || '未知状态'
export const degradationLabel = value => reasons[value] || '部分上下文不可用，已使用降级结果。'
export const codeLabel = value => value ? codes[value] || '执行未完成' : ''
