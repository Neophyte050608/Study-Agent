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
| 前端单元测试 | `cd frontend && npm test` | Node 内置运行器；学习计划 API/composable/纯展示映射，不是全站组件覆盖。 |
| 生产构建 | `cd frontend && npm run build` | 检查 Vue/Vite 构建是否通过。 |
| 构建到 Spring 静态目录 | `cd frontend && npm run build:spring` | 需要后端直接托管 SPA 时使用。 |

`npm test` 与 `npm run build` 分别验证可测试逻辑和 Vue/Vite 构建。浏览器 mock 检查需独立说明；不等于真实后台或模型联调。

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

## 持久 DAG 的 MySQL 集成验证

`DurableExecutionMySqlIntegrationTest` 默认跳过数据库验证。使用一次性 MySQL 8 库（不得使用开发/生产库）：

```bash
mvn -q -Dtest=DurableExecutionMySqlIntegrationTest \
  -Dtest.mysql.execution-jdbc-url='jdbc:mysql://127.0.0.1:33318/isolated_execution_test?allowPublicKeyRetrieval=true&useSSL=false' \
  -Dtest.mysql.username=root -Dtest.mysql.password='<temporary-test-password>' test
```

此测试执行 Flyway，并清理测试 workflow 的 task/node/attempt，需自行创建并回收隔离库。验证并发领取、epoch 接管、失效 owner 拒写、期限不续命、late output/attempt 预算、级联删除、幂等输入、并发 revision 重试、真实检查点恢复；模型节点使用 stub。另用 `FlywayMySqlMigrationIntegrationTest` 验证全新库与 V1 baseline 接管到 V5，两个 URL 都必须指向空的临时库。

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


## Chunk 级检索对比报告

文件入口默认关闭。先运行不依赖真实外部服务的单元与条件装配验证：

```bash
mvn -q -Dtest=GoldSetBenchmarkFileServiceTest,GoldSetBenchmarkRunnerTest,GoldSetBenchmarkRunnerConditionTest test
```

真实数据运行方式及环境变量见 `src/main/resources/eval/README.md`。真实生产检索结果只在
MySQL/Milvus/Neo4j 等已启用后端可用时有意义；BM25 基线不需要 API Key。
