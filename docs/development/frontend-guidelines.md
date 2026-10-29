# 前端开发规范

本文档定义 `frontend/` 中 Vue 3 + Vite 代码的默认组织方式。

## 目录职责

```text
frontend/src/App.vue                 # 应用入口
frontend/src/main.js                 # Vue 启动入口
frontend/src/theme.js                # 唯一调色板：语义 CSS 变量与 Naive UI overrides
frontend/src/router/                 # 路由配置
frontend/src/views/                  # 页面级组件
frontend/src/views/<domain>/         # 页面私有组件
frontend/src/api/                    # HTTP API 封装
frontend/src/services/               # 前端业务组合逻辑
frontend/src/composables/            # 组合式状态逻辑
frontend/src/utils/                  # 纯工具函数
frontend/src/shell/                  # 应用壳层布局
```

## 页面组件

页面组件负责展示结构和交互入口，不应承担过多业务编排。

建议：

- 页面超过约 300 行时，优先拆局部组件。
- 表单状态、轮询、复杂筛选逻辑可拆到 composable。
- 数据转换和多 API 编排可拆到 service。
- 页面私有组件放在 `views/<domain>/` 下，避免污染全局目录。

## API 层

`frontend/src/api` 只做 HTTP contract 封装：

- URL、method、query、body。
- 简单响应解析。
- 统一错误处理入口复用 `api/http.js`。

禁止在 API 文件中写页面状态、DOM 逻辑或复杂业务判断。

## Services 与 Composables

`services` 适合放：

- 多个 API 的组合调用。
- 面向页面的业务数据装配。
- 与 UI 无关的前端业务流程。

`composables` 适合放：

- 可复用响应式状态。
- 生命周期、订阅、轮询、主题等逻辑。
- 多页面共享交互状态。

## 路由与导航

一级产品导航只有一个真相源：`frontend/src/shell/navigation.js`。当前固定为四项：

- AI 助手：`/chat`
- 知识库：`/notes`
- 能力画像：`/profile`
- 管理中心：`/admin`

不要从后端、数据库或 Redis 加载导航，也不要恢复菜单 API 或本地兜底菜单。管理中心使用嵌套路由：`/admin/models` 归模型与路由，`/admin/rag` 归 RAG 运行与评测，`/admin/agents/{prompts,intents,mcp}` 归 Agent 管理；Agent 三个叶子页面独立加载数据，意图树/列表共享同一份页面状态。新增或调整页面时：

1. 在 `frontend/src/views/<domain>` 放置页面或页面私有组件。
2. 在 `frontend/src/router/routes.js` 注册当前路由；需要一级入口时同步修改 `shell/navigation.js`。
3. 管理页面同步维护所属布局的二级/三级导航，不创建跨域聚合页面或兼容跳转。
4. 后端直达 SPA 路径同步维护 `FrontendRouteController`，但已删除 URL 不保留转发或重定向。
5. 确认加载、空数据、失败和重试状态可见。

## 样式与组件库

前端使用 Naive UI 作为通用控件库，保留现有靛蓝品牌色与深浅色切换，不另建一套基础 UI 框架。

- `src/theme.js` 是唯一调色板来源，同时生成语义 CSS 变量和 Naive UI theme overrides；不要在 `index.html` 或各页面重复定义品牌/表面颜色。
- `useTheme()` 返回共享主题状态；保存的有效 light/dark 偏好优先于系统偏好，存储不可用时仍支持切换。`main.js` 在挂载前应用主题。
- `App.vue` 接入 `NConfigProvider`、中文 locale、`NMessageProvider` 与 `NDialogProvider`；不引入 `NGlobalStyle` 或全局 reset，避免影响未迁移页面。
- 显式按需导入 Naive UI 控件，直接使用 `NButton`、`NInput`、`NForm`、`NModal`、`NCard` 等；不要全量注册，也不要创建 `BaseButton`/`AppButton` 等只转发属性的基础包装。
- 业务组件仍放在所属页面目录，例如模型编辑器；表单转换/校验可使用纯函数，API 编排留在现有 composable。
- 布局继续使用 Tailwind utilities；控件圆角默认 8px、卡片 12px、正文 14px。页面 padding 为 16px/桌面 24px，主要分区间距 24px。
- 必要的页面私有 CSS 靠近组件，只处理组件库未解决的布局约束，如弹窗视口边界和表格局部滚动。迁移时同步删除替换掉的控件与样式，不保留旧版开关或过渡包装。
- Loading、空数据、失败与重试必须可见；刷新失败保留上次成功快照。未知运行指标显示“—”或“未知”，不编造健康状态、成功率或耗时。

当前已统一共享主题与 `/admin/models` 的候选模型、编辑表单、路由健康页面。知识库、画像、RAG/Agent 管理以及聊天控件按后续页面逐步迁移；不为统一样式强行把不同任务页面做成同一布局。

## 后端契约

前端不得把后端业务规则当作 source of truth。

- 权限、状态流转、持久化结果以后端为准。
- 前端可以做输入提示和轻量校验，但后端仍需校验。
- API response shape 变化时，同步更新前端 `api`、页面和相关文档。

## 验证

前端使用 Node 内置测试运行器，不额外引入测试框架。改前端后运行：

```bash
cd frontend && npm test && npm run build
```

如果新增前端测试框架或 lint 命令，需要同步更新 `package.json`、本文档和 `docs/development/verification.md`。

测试位于 `frontend/test/`，目前覆盖学习计划 API、composable 状态机/轮询、一级导航与管理路由契约，以及模型/RAG/Agent 管理的纯展示和 ownership 映射、主题偏好与密钥更新语义。模型页还有源码结构契约测试，不等于组件交互覆盖。
可注入 API、时钟与幂等键生成器测试并发、超时和迟到响应；不要把这些单元测试描述为全站组件覆盖。
浏览器交互检查应使用隔离 mock 身份和数据，并明确与真实模型/数据库联调的区别。
学习计划预览使用独立路由与画像页入口，不需要新增后台菜单；轮询、幂等与重试状态归 composable，
模型文本/证据直接以 Vue 插值转义，不注入 HTML。
