// 中文注释：文本数组转换工具，提供从多行文本到字符串数组的双向转换，确保前端表单与后端数组字段一致
export function toLinesArray(value) {
  // 中文注释：将多行文本拆分为字符串数组，去除空行与两端空白
  return String(value || '')
    .split('\n')
    .map((item) => item.trim())
    .filter(Boolean);
}

export function toMultilineText(arr) {
  // 中文注释：将字符串数组合成为多行文本，用于表单回填显示
  if (!Array.isArray(arr)) return '';
  return arr.map((item) => String(item || '').trim()).filter(Boolean).join('\n');
}
