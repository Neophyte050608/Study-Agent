# 前端开发规范

本文档定义 `frontend/` 中 Vue 3 + Vite 代码的默认组织方式。

## 目录职责

```text
frontend/src/App.vue                 # 应用入口
frontend/src/main.js                 # Vue 启动入口
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

## 路由与菜单

新增页面时：

1. 在 `frontend/src/views` 新增页面组件。
2. 在 `frontend/src/router/index.js` 注册路由。
3. 如有菜单或工作区入口，同步更新后端/前端菜单配置。
4. 确认空状态、加载状态和错误状态可见。

## 样式

- 优先复用已有布局和样式变量。
- 页面私有样式靠近页面组件。
- 不引入新的 UI 框架，除非有明确收益并更新文档。
- 避免大段内联 style；可读性差时抽 class。

## 后端契约

前端不得把后端业务规则当作 source of truth。

- 权限、状态流转、持久化结果以后端为准。
- 前端可以做输入提示和轻量校验，但后端仍需校验。
- API response shape 变化时，同步更新前端 `api`、页面和相关文档。

## 验证

当前前端没有统一测试脚本。改前端后至少运行：

```bash
cd frontend && npm run build
```

如果新增前端测试框架或 lint 命令，需要同步更新 `package.json`、本文档和 `docs/development/verification.md`。
