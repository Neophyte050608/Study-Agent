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
| 生产构建 | `cd frontend && npm run build` | 检查 Vue/Vite 构建是否通过。 |
| 构建到 Spring 静态目录 | `cd frontend && npm run build:spring` | 需要后端直接托管 SPA 时使用。 |

当前前端未配置统一测试脚本，不要声称前端测试通过；可用 `npm run build` 作为最低构建验证。

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
cd frontend && npm run build
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
