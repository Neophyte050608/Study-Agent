完成时间: 2026-04-01 23:42
修改文件: 
- frontend/src/views/ChatView.vue
- frontend/src/views/ProfileView.vue
- frontend/package.json
- frontend/src/api/menu.js
- frontend/src/views/WorkspaceView.vue
- frontend/src/api/http.js

实现说明: 
1. `ChatView.vue`: 移除 `onDone` 中多余的推流消息逻辑，修复双推问题。
2. `ProfileView.vue` & `package.json`: 引入 `chart.js/auto` 并安装了 `chart.js` 依赖，修复渲染问题。
3. `menu.js`: 将排序字段由 `orderIndex` 变更为后端对应字段 `sortOrder`。
4. `WorkspaceView.vue`: 移除了 `toSpaPath` 函数中错误的 `/spa` 前缀，对齐 Vue Router 路径。
5. `http.js`: 新增 `getAuthHeaders` 从 localStorage 获取并注入 `Authorization` 头至所有的 HTTP 统一请求封装中。