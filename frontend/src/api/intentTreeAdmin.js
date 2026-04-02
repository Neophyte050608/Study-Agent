import { loadIntentTreeConfig, loadIntentTreeStats, saveIntentTreeConfig } from './admin'
import { editorFormToLeaf, flattenLeafIntents, leafToEditorForm } from '../utils/intentTreeTransform'

// 中文注释：加载意图树配置与统计，返回管理页统一数据结构
export async function loadIntentTreeAdminData() {
  const [config, stats] = await Promise.all([loadIntentTreeConfig(), loadIntentTreeStats()])
  const leafIntents = Array.isArray(config?.leafIntents) ? config.leafIntents : []
  return {
    config: config || {},
    stats: stats || {},
    flatLeafs: flattenLeafIntents(leafIntents)
  }
}

// 中文注释：按 index 读取叶子节点编辑态，适配编辑页独立表单
export function buildLeafEditorData(config, index) {
  const leafIntents = Array.isArray(config?.leafIntents) ? config.leafIntents : []
  const leaf = leafIntents[index]
  if (!leaf) return null
  return leafToEditorForm(leaf)
}

// 中文注释：保存单个叶子节点，采用全量配置回写策略保持后端接口兼容
export async function saveSingleLeaf(config, index, form) {
  const leafIntents = Array.isArray(config?.leafIntents) ? [...config.leafIntents] : []
  leafIntents[index] = editorFormToLeaf(form)
  const payload = { ...(config || {}), leafIntents }
  await saveIntentTreeConfig(payload)
  return payload
}

// 中文注释：批量切换启用状态，采用本地变更后全量提交
export async function batchToggleLeafEnabled(config, indexes, enabled) {
  const leafIntents = Array.isArray(config?.leafIntents) ? [...config.leafIntents] : []
  indexes.forEach((index) => {
    if (!leafIntents[index]) return
    leafIntents[index] = { ...leafIntents[index], enabled }
  })
  const payload = { ...(config || {}), leafIntents }
  await saveIntentTreeConfig(payload)
  return payload
}
