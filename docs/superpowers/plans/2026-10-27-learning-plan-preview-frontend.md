# 学习计划预览前端实施计划

本会话执行。保留用户AGENTS.md/.DS_Store与前3步未提交改动；不stage/commit/push。

- [x] 阅读架构/前端规则，确定入口与现有认证，spec自审。
- [x] RED：frontend/test/learningPlanApi.test.js，验证现有http.js最小扩展与四接口；GREEN：api/learningPlans.js、http.js可选headers/signal和安全错误元数据，不改其他调用的成功形状。
- [x] RED：frontend/test/learningPlanPreview.test.js，以注入时钟/API测幂等、轮询、终态、迟到响应、超时、冲突、删除；GREEN：composables/useLearningPlanPreview.js与展示helpers。
- [x] 页面：views/LearningPlanPreviewView.vue（表单/操作/URL）；views/learning/组件（节点、计划/证据），router注册；ProfileView只增加链接。
- [x] 文档：frontend/README、frontend-guidelines、verification、learning-plan-preview及架构路线同步，添加npm test（Node内置）。
- [x] 验证：npm test、npm run build、mock浏览器桌面/窄屏操作检查、后端架构回归、diff check；记录真实结果与未验证范围。

不做列表、取消、自动重试、服务端新能力。浏览器测试必须用隔离mock身份与数据，不读取/输出本地token。


## 实际验证（2026-10-27）

- `cd frontend && npm test`：26 tests，26 passed，0 failures；新增响应校验、运行时代码映射、
  reset 前尚未 dispatch 的 mutation 阻止发送与幂等键隔离回归均经过 RED/GREEN。
- `cd frontend && npm run build -- --outDir /private/tmp/study-plan-preview-final-build`：exit 0，94 modules。
  既有单 bundle 超过 500 kB 警告仍在；临时 outDir 警告是验证路径在仓库外，不是构建失败。
- `mvn -q -Dtest=ArchitectureRulesTest test`：68 tests，0 failures/errors/skips，exit 0。
- 本机 Chrome 隔离 mock 浏览器：画像链接、提交 URL、终态停止轮询、降级展示、精确 revision/局部重试、
  HTML 转义、URL 刷新恢复、390px 折叠侧栏无横向溢出及删除二次确认；10 requests/3 reads，pageErrors=[]。
  窄屏截图等待侧栏动画结束，未改全局 shell。
- 构建产物恢复原有跟踪版本，仅清理本轮生成文件；没有 stage/commit/push，保留 AGENTS.md、四个 .DS_Store 与前3步工作。

本轮不验证真实 MySQL/模型整应用启动、模型计划质量，不重报旧全量后端测试结果。
新增 docs 被仓库现有 ignore 规则忽略，本轮保留本地文件，不强制加入暂存区。
