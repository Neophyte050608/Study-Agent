# 验证指南

本文档列出 Study-Agent 常用验证命令。除非特别说明，后端命令在仓库根目录运行，前端命令在 `frontend/` 目录运行。

## 后端检查

| 场景 | 命令 | 说明 |
| --- | --- | --- |
| 编译主代码 | `mvn -q compile` | 快速发现 Java 编译错误。 |
| 编译测试代码 | `mvn -q -DskipTests test-compile` | 快速发现测试源码编译错误。 |
| 运行全部测试 | `mvn test` | 行为变更后的默认检查。 |
| Maven verify | `mvn -q verify -DskipTests` | 跑 checkstyle 等 verify 阶段检查，不执行测试。 |
| 架构规则 | `mvn -q -Dtest=ArchitectureRulesTest test` | 修改包结构、依赖方向或迁移代码时运行。 |

## 前端检查

| 场景 | 命令 | 说明 |
| --- | --- | --- |
| 安装依赖 | `cd frontend && npm install` | 首次启动或依赖变化时运行。 |
| 本地开发 | `cd frontend && npm run dev` | 启动 Vite 开发服务。 |
| 前端单元测试 | `cd frontend && npm test` | Node 内置运行器；覆盖学习计划状态机、导航/管理路由、纯映射、主题偏好及模型表单语义，不是全站组件覆盖。 |
| 生产构建 | `cd frontend && npm run build` | 检查 Vue/Vite 构建是否通过。 |
| 构建到 Spring 静态目录 | `cd frontend && npm run build:spring` | 需要后端直接托管 SPA 时使用。 |

`npm test` 与 `npm run build` 分别验证可测试逻辑和 Vue/Vite 构建。浏览器 mock 检查需独立说明；不等于真实后台或模型联调。

导航或管理中心变更可先运行聚焦契约测试：

```bash
cd frontend && node --test \
  test/navigation.test.js \
  test/adminRoutes.test.js \
  test/modelAdminPresentation.test.js \
  test/ragAdminPresentation.test.js \
  test/agentAdminNavigation.test.js
```

这些测试验证固定四入口、管理嵌套路由、深链归属、页面状态 owner 和纯展示映射；仍需用 `npm run build` 检查 Vue SFC 与动态导入。后端 SPA 直达路径变更补跑：

```bash
mvn -q -Dtest=FrontendRouteControllerTest test
```

## 共享主题与模型管理 UI

```bash
cd frontend && node --test \
  test/theme.test.js \
  test/modelAdminPresentation.test.js \
  test/modelUiContract.test.js
```

- `theme.test.js` 检查调色板、CSS 变量、共享状态、偏好初始化、切换与存储不可用情形。
- `modelAdminPresentation.test.js` 检查表单必填规则、密钥留空保留/替换/显式清空、未知状态和实际成功率。
- `modelUiContract.test.js` 是源码结构约束：使用成熟控件、不保留旧控件或虚构指标、重试可见、窄屏导航不折成竖字；它不运行 Vue 组件。
- `npm run build` 仍需单独运行，并观察入口/页面 chunk 与 gzip 大小；组件库引入会增加包体，不能将 CSS 减少等同于构建体积下降。

浏览器验收使用隔离 dummy 模型/密钥，拦截实际 `/api/` 请求（不要误拦 `/src/api/` 模块），不读取本地真实账号、token 或已保存的 key：

1. 在 1440px 和 390px 检查侧栏偏移、页面无横向溢出、表格局部滚动、操作换行和弹窗视口边界。
2. 检查深浅色及刷新后的偏好，覆盖控件与浮层。
3. 检查真实 input 的标签关联、Esc 关闭及焦点恢复；保存中禁止重复提交和取消。
4. 检查新增/编辑、密钥为空不回填、保留/替换/清空请求语义、失败后保留输入重试、probe、受服务端开关约束的密钥复制及删除确认/取消。
5. 检查首次加载失败、空成功结果、未知健康、刷新失败保留快照及重试；对聊天和画像进行主题接入后的布局/挂载冒烟。

2026-10-30 的浏览器验收使用仓库外临时 Playwright 脚本，不是本仓库的持久自动化测试命令。mock 交互通过不代表真实模型、数据库、聊天 Markdown 全场景或全应用联调已验证。

## 本地启动脚本检查

修改 `scripts/dev-start.sh`、`scripts/dev-stop.sh` 或本地启动文档时，至少运行：

```bash
bash -n scripts/dev-start.sh
bash -n scripts/dev-stop.sh
```

如修改 Docker 编排，再运行：

```bash
docker compose config
```

如修改 `local-lite` profile 或 Spring 条件装配，补跑相关配置测试，例如：

```bash
mvn -q -Dtest=LocalLiteConditionalConfigTest test
```

## 按变更类型选择检查

### 只改文档

- 检查路径、命令、标题是否准确。
- 如文档包含脚本命令，确认命令名称存在。
- 可运行 `git diff --check` 检查空白问题。

### 只改后端业务代码

建议顺序：

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn test
```

如只改某个服务，可先运行 focused test，例如：

```bash
mvn -q -Dtest=RAGServiceTest test
```

### 改后端包结构或架构边界

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q verify -DskipTests
```

### 改前端页面或 API 调用

```bash
cd frontend && npm test && npm run build
```

如同时影响后端 API，补跑相关后端 controller/application 测试。

### 改启动配置或 Docker 依赖

至少检查：

```bash
mvn -q compile
bash -n scripts/dev-start.sh
bash -n scripts/dev-stop.sh
docker compose config
```

如果只改了某一个 shell 脚本，可只对该脚本运行 `bash -n`。

## 报告规则

- 只有当前会话实际运行过的命令，才能写“通过”。
- 命令失败时，报告失败命令、关键错误和下一步建议。
- 因耗时、缺依赖或网络限制跳过检查时，必须明确说明。

## Durable 动态计划快照

快照协议、v1/v2 workflow 与 Spring 注册：

```bash
mvn -q \
  -Dtest=LearningPlanWorkflowSnapshotCodecTest,LearningPlanWorkflowV1Test,LearningPlanWorkflowV2Test,LearningPlanDynamicPlanningServiceTest,LearningPlanPreviewWiringTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' \
  test
```

持久提交/恢复、节点集合一致性、局部重试与 v1/v2 结果投影：

```bash
mvn -q \
  -Dtest=DurableExecutionServiceTest,DurableExecutionWorkerTest,DurableLearningPlanServiceTest,LearningPlanTaskPresenterTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' \
  test
```

完整 durable/dynamic/planning 单测切片：

```bash
mvn -q \
  -Dtest='io.github.imzmq.interview.agent.application.execution.*Test,io.github.imzmq.interview.agent.application.execution.dynamic.*Test,io.github.imzmq.interview.learning.application.planning.*Test' \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' \
  test
```

这组单测不调用真实模型或外部 API，不需要 API Key。它证明 submit 保存 workflow 生成的定义、worker/retry 只恢复定义、节点集合不一致时拒绝执行，以及历史 v1 与当前 v2 同时注册；它不替代下面的真实 MySQL fencing/recovery 证据。

## 持久 DAG 的 MySQL 集成验证

`DurableExecutionMySqlIntegrationTest` 默认跳过数据库验证。使用一次性 MySQL 8 库（不得使用开发/生产库）：

```bash
mvn -q -Dtest=DurableExecutionMySqlIntegrationTest \
  -Dtest.mysql.execution-jdbc-url='jdbc:mysql://127.0.0.1:33318/isolated_execution_test?allowPublicKeyRetrieval=true&useSSL=false' \
  -Dtest.mysql.username=root \
  -Dtest.mysql.password='<temporary-test-password>' \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' \
  test
```

此测试执行 Flyway，并清理测试 workflow 的 task/node/attempt，需自行创建并回收隔离库。验证并发领取、epoch 接管、失效 owner 拒写、期限不续命、late output/attempt 预算、级联删除、幂等输入、并发 revision 重试、持久定义恢复与真实检查点恢复；模型节点使用 stub。另用 `FlywayMySqlMigrationIntegrationTest` 验证全新库与 V1 baseline 接管到 V5，两个 URL 都必须指向空的临时库。

只有 Surefire 明确显示测试实际执行且零失败/错误时，才能声称 MySQL recovery、fencing 或 retry 已验证。未配置 `test.mysql.execution-jdbc-url` 时，JUnit assumption 会跳过全部用例；即使命令 `BUILD SUCCESS`，`Tests run: 7, Skipped: 7` 也不是数据库通过证据，必须单独报告为未验证。

## 学习计划预览 HTTP（第三步）

```bash
mvn -q -Dtest=DurableLearningPlanServiceTest,LearningPlanTaskResponseTest,LearningPlanPreviewControllerTest,LearningPlanPreviewSecurityTest,ArchitectureRulesTest test
```

应用层投影/校验/冲突、四路由协议及真实HMAC JWT过滤链验证；存储与模型mock，非全应用启动测试。数据库迁移未变化，真实MySQL fencing证据仍由opt-in durable测试单独提供。


## 学习计划预览前端（第四步）

```bash
cd frontend && npm test && npm run build
```

覆盖幂等键/认证头、错误元数据、204 删除、提交结果不确定、串行轮询、
终态停止、任务切换/卸载的迟到响应、15 秒超时、revision 冲突只刷新、
401/404 清除结果、503 保留快照、响应格式校验及终态删除。
浏览器 mock 检查补充画像入口、URL 恢复、局部重试、删除确认、纯文本转义和窄屏布局；
不得读取本地真实 token，不得据此宣称真实模型质量或全应用启动已验证。
构建会生成本地产物，即使仓库已有历史跟踪文件，本次也不将它们作为源码提交。


## 隔离检索基准

文件入口默认关闭。先运行不依赖真实外部服务的操作分发与条件装配测试：

```bash
mvn -q -Dtest=RetrievalBenchmarkFileServiceTest,RetrievalBenchmarkRunnerTest,RetrievalBenchmarkRunnerConditionTest test
```

Milvus adapter 单测使用 Mockito；JDK 21 下应显式提供仓库当前 Byte Buddy agent，避免动态 agent 装载限制：

```bash
mvn -q \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -XX:+EnableDynamicAgentLoading' \
  -Dtest=MilvusBenchmarkIndexTest,MilvusBenchmarkIndexIntegrationTest \
  test
```

`MilvusBenchmarkIndexIntegrationTest` 默认跳过；只有显式设置
`RUN_MILVUS_INTEGRATION_TESTS=true` 才连接本地 Milvus，测试使用确定性 fake embedding，不需要 API Key。
`VALIDATE` 与本地 BM25 不需要 API Key；`INDEX` 会对语料调用 embedding provider，`COMPARE` 会对查询调用
provider。不得在未统计调用量、预计耗时和费用并取得单独确认时运行真实 `INDEX`。四个 operation、私密 Key
位置和数据集级清理确认方式见 `src/main/resources/eval/README.md`。报告仅代表 isolated-vector vs BM25，
不得当作完整生产 hybrid retrieval 的验证结果。
