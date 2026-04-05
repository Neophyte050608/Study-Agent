## 任务
修复 5 个 P2 级前端问题

**重要提醒：修改文件时请确保不要破坏中文字符编码（UTF-8）。**

---

## P2-1：ChatView 双推消息

**文件**: `frontend/src/views/ChatView.vue`

**问题**: `onFinish` 和 `onDone` 回调都会 `messages.value.push({ role: 'assistant', content: streamingContent.value })`。如果两个事件都触发（SSE 流常见），助手消息会出现两次。

**修复方案**: 移除 `onDone` 中的 push 逻辑，只保留 `onFinish` 作为唯一的消息推入点。`onDone` 只做清理（设置 `isStreaming = false` 等），不再 push 消息。修改后的 `onDone`：
```javascript
onDone: () => {
  if (isStreaming.value) {
    // onFinish 未触发时的兜底清理
    isStreaming.value = false
    streamHandle.value = null
  }
}
```

---

## P2-2：ProfileView Chart.js 未 import

**文件**: `frontend/src/views/ProfileView.vue`

**问题**: 第 227 行使用 `new Chart(...)` 但没有 import Chart.js。

**修复方案**: 在 `<script setup>` 顶部添加：
```javascript
import Chart from 'chart.js/auto'
```
确认 `chart.js` 已在 `frontend/package.json` 的 dependencies 中。如果没有，运行 `cd frontend && npm install chart.js`。

---

## P2-3：菜单字段名不匹配

**文件**: `frontend/src/api/menu.js`

**问题**: 第 7 行排序用 `a.orderIndex - b.orderIndex`，但后端 `MenuConfigDO` 序列化为 JSON 时字段名是 `sortOrder`。

**修复方案**: 将 `.sort((a, b) => a.orderIndex - b.orderIndex)` 改为 `.sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0))`

---

## P2-4：WorkspaceView SPA 路径错误

**文件**: `frontend/src/views/WorkspaceView.vue`

**问题**: `toSpaPath` 函数生成 `/spa/interview` 等路径，但 Vue Router 定义的路由是 `/interview`，没有 `/spa/` 前缀。

**修复方案**: 移除 `/spa` 前缀。修改 `toSpaPath` 函数：
```javascript
const toSpaPath = (url) => {
  if (!url) return '#'
  const base = url.replace(/\.html$/, '')
  return base.startsWith('/') ? base : `/${base}`
}
```

---

## P2-6：前端无认证头

**文件**: `frontend/src/api/http.js`

**问题**: 所有 HTTP 请求（httpGet/httpPostJson/httpPut/httpDelete）都没有认证头，多用户场景无法识别用户。

**修复方案**: 添加一个统一的 headers 获取函数，从 localStorage 读取 token（如果存在）。修改所有请求方法添加认证头：

```javascript
function getAuthHeaders() {
  const headers = {}
  const token = localStorage.getItem('auth_token')
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  return headers
}
```

然后在每个请求方法中合并 headers，例如：
- `httpGet`: `fetch(url, { headers: getAuthHeaders() })`
- `httpPostJson`: `fetch(url, { headers: { 'Content-Type': 'application/json', ...getAuthHeaders() }, ... })`
- `httpPut`: 同上
- `httpDelete`: `fetch(url, { method: 'DELETE', headers: getAuthHeaders() })`
- `httpPostFormData`: `fetch(url, { method: 'POST', headers: getAuthHeaders(), body: formData })`

---

## 涉及文件
- frontend/src/views/ChatView.vue
- frontend/src/views/ProfileView.vue
- frontend/src/api/menu.js
- frontend/src/views/WorkspaceView.vue
- frontend/src/api/http.js

## 约束
- 不要破坏中文字符编码
- 保持现有代码风格
- 不引入未声明的依赖

## 完成要求
任务完成后，将结果摘要写入 D:/Practice/InterviewReview/context/gemini-result.md，格式：
完成时间: YYYY-MM-DD HH:MM
修改文件: [文件列表]
实现说明: [简短描述]
