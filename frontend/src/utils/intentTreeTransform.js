import { toLinesArray, toMultilineText } from './textArray'

// 中文注释：将 path 字符串规范为数组，兼容 "/" 与 ">" 两种路径分隔符
export function normalizePathToArray(path) {
  if (!path) return []
  return String(path)
    .replace(/>/g, '/')
    .split('/')
    .map((item) => item.trim())
    .filter(Boolean)
}

// 中文注释：将叶子意图配置转换为列表页可用扁平结构，补充路径文本与示例数量
export function flattenLeafIntents(leafIntents) {
  return (leafIntents || []).map((leaf, index) => {
    const pathSegments = normalizePathToArray(leaf.path)
    const pathText = pathSegments.length > 0 ? pathSegments.join(' / ') : '-'
    return {
      index,
      intentId: String(leaf.intentId || '').trim(),
      name: String(leaf.name || '').trim(),
      description: String(leaf.description || '').trim(),
      taskType: String(leaf.taskType || '').trim(),
      path: String(leaf.path || '').trim(),
      pathSegments,
      pathText,
      enabled: leaf.enabled !== false,
      examples: Array.isArray(leaf.examples) ? leaf.examples : [],
      slotHints: Array.isArray(leaf.slotHints) ? leaf.slotHints : [],
      exampleCount: Array.isArray(leaf.examples) ? leaf.examples.length : 0
    }
  })
}

// 中文注释：将配置中的叶子意图转换为编辑表单结构，方便 examples 与 slotHints 进行多行编辑
export function leafToEditorForm(leaf) {
  return {
    intentId: String(leaf?.intentId || '').trim(),
    name: String(leaf?.name || '').trim(),
    description: String(leaf?.description || '').trim(),
    taskType: String(leaf?.taskType || '').trim(),
    path: String(leaf?.path || '').trim(),
    enabled: leaf?.enabled !== false,
    examplesText: toMultilineText(Array.isArray(leaf?.examples) ? leaf.examples : []),
    slotHintsText: toMultilineText(Array.isArray(leaf?.slotHints) ? leaf.slotHints : [])
  }
}

// 中文注释：将编辑表单结构回写为后端配置结构，确保 examples 与 slotHints 为数组
export function editorFormToLeaf(form) {
  return {
    intentId: String(form?.intentId || '').trim(),
    name: String(form?.name || '').trim(),
    description: String(form?.description || '').trim(),
    taskType: String(form?.taskType || '').trim(),
    path: String(form?.path || '').trim(),
    enabled: form?.enabled !== false,
    examples: toLinesArray(form?.examplesText),
    slotHints: toLinesArray(form?.slotHintsText)
  }
}

// 中文注释：从路径构建父级 path，用于树页快速创建子节点时自动回填父路径
export function resolveParentPath(path) {
  const segments = normalizePathToArray(path)
  if (segments.length <= 1) return ''
  return segments.slice(0, -1).join('/')
}
