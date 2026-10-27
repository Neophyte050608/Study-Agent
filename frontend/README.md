# Frontend 模块

## 本地开发
```bash
npm install
npm run dev
```

默认开发端口 `5173`，`/api` 会代理到 `http://localhost:9596`。

## 构建到 Spring Boot 静态目录
```bash
npm run build:spring
```

构建结果输出到 `src/main/resources/static/spa`。


## 学习计划预览

从「能力画像」页面进入「学习计划预览」，路由为 `/learning/plan-preview`。
提交受理后地址包含 `?taskId=<UUID>`；刷新页面按 ID 查询，不自动重新提交。
后台仍需启用 `app.agent.execution.enabled` 并配置 MySQL 与模型，详情见
`../docs/development/learning-plan-preview.md`。沿用已有 `auth_token` bearer：open 为本地单用户，
共享部署必须配置 JWT；本页不提供登录或令牌签发。

- 展示画像/证据/生成计划三个节点的状态、尝试次数、计划、引用与降级原因。
- 只对排队/运行中任务轮询，前一请求结束后 2 秒再查询；15 秒超时或错误暂停，手动恢复。
- 重试携带当前 revision，可选失败根，后继重算；409 只刷新，不自动重复重试或删除。
- 删除只允许终态且需确认；离开页面只中止客户端请求，不取消后台任务。
- 网络异常或无效响应使提交结果不确定时，冻结原输入并复用内存幂等键。
  「新建预览」需确认放弃未确认提交；尚未拿到 taskId 时刷新会丢失幂等键，旧任务仍可能已受理。
- URL 不保存输入，浏览器存储不保存主题、要求、计划或幂等键；模型文本与证据以转义纯文本展示。

## 验证

```bash
npm test
npm run build
```

`npm test` 使用 Node 内置测试运行器，覆盖 API、状态/轮询和纯展示映射，不是全站组件或真实后台端到端测试。
构建产物用于本地验证，不随源码变更提交。
