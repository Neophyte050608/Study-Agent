# Study-Agent AI Coding Guide

本文件是本仓库中 AI coding agent 的最高优先级项目级开发说明。`README.md` 负责项目概览，`ARCHITECTURE.md` 和 `PACKAGE_CONVENTIONS.md` 负责架构与包结构细则；真正开始改代码前，先读本文件。

## 项目心智模型

Study-Agent 是一个面向技术面试复盘、知识检索、刷题训练、学习画像和 RAG 评测的本地开发项目。

主要区域：

- `src/main/java/io/github/imzmq/interview`：Spring Boot 后端。
- `src/test/java/io/github/imzmq/interview`：后端自动化测试。
- `frontend/`：Vue 3 + Vite 前端。
- `sql/`：数据库建表和初始化脚本。
- `docker-compose.yml`：MySQL、Redis、Milvus、Neo4j 等本地依赖编排。
- `clip-service/`、`eval-service/`：独立辅助服务。
- `docs/`、`ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md`：开发规范、架构说明和设计计划。

## 指令优先级

按以下顺序执行：

1. 当前用户请求中的显式约束。
2. 本文件。
3. `ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md`、`docs/development/*.md`。
4. `README.md` 和历史设计文档。

如规则冲突，优先遵循更靠近当前任务、更新更近期且更具体的规则，并在总结中说明取舍。

## 默认工作流

1. 修改前运行 `git status --short --branch`，确认不要覆盖用户未提交工作。
2. 识别本次影响范围：后端、前端、配置、数据库、文档或启动脚本。
3. 修改生产代码前阅读 `ARCHITECTURE.md`、`PACKAGE_CONVENTIONS.md` 和对应 `docs/development/*-guidelines.md`。
4. 行为变更默认先补测试，再实现最小改动。
5. 改完先跑最窄相关检查，再按影响范围跑更宽检查。
6. 如果改了启动方式、配置项、目录约定或架构边界，同步更新文档。

详细流程见 `docs/development/workflow.md`，验证命令见 `docs/development/verification.md`。

## 后端代码放置规则

后端采用 domain-first 包结构。新增业务代码必须优先放入业务域包，而不是技术类型包。

推荐结构：

```text
io.github.imzmq.interview.<domain>.api
io.github.imzmq.interview.<domain>.application
io.github.imzmq.interview.<domain>.domain
io.github.imzmq.interview.<domain>.infrastructure
```

硬性规则：

- 不要新增业务代码到旧式顶层 `service`、`entity`、`mapper`、`dto` 包。
- Controller 不要直接调用 MyBatis Mapper。
- MyBatis DO 和 Mapper 放在 `<domain>.infrastructure.persistence`。
- Controller-facing DTO 放在 `<domain>.api`。
- application 内部 DTO 放在 `<domain>.application.dto`。
- domain 层不能依赖 controller、Spring Web DTO 或持久化对象。
- 不要继续把新职责塞进 `RAGService`；知识检索、刷题、评测、上下文、catalog 等逻辑按 `PACKAGE_CONVENTIONS.md` 分包。

后端细则见 `docs/development/backend-guidelines.md`。

## 前端代码放置规则

前端采用 Vue 3 + Vite。

- `frontend/src/views`：页面级组件。
- `frontend/src/views/<domain>`：页面私有组件。
- `frontend/src/api`：HTTP 请求封装，只处理请求路径、参数和响应。
- `frontend/src/services`：前端业务组合逻辑。
- `frontend/src/composables`：可复用组合式状态逻辑。
- `frontend/src/router`：路由注册。

页面组件不要直接堆积复杂 API 编排、数据转换和业务判断；可下沉到 `services`、`composables` 或局部组件。

前端细则见 `docs/development/frontend-guidelines.md`。

## 测试与验证

常用命令：

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn test
mvn -q verify -DskipTests

cd frontend && npm run build
```

修改架构边界时优先运行：

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

禁止声称检查通过，除非本轮会话实际运行过并确认退出码为 0。

## 文档维护

以下变更必须同步文档：

- 启动命令、依赖服务、环境变量变化。
- 后端包结构、模块职责、架构边界变化。
- 新增数据库表、外部服务、模型提供商或重要配置项。
- 前端页面结构、路由、API contract 变化。
- AI agent 开发规则或验证命令变化。

## Commit 约定

使用：

```text
type: subject
type(scope): subject
```

允许的 type：`feat`、`fix`、`refactor`、`test`、`docs`、`chore`。

示例：

```text
docs: 补充 AI coding 开发规范
refactor(knowledge): 拆分检索编排服务
```

不要添加 AI 生成 footer 或无意义的 `update/misc/stuff` 前缀。

## 禁止事项

- 不要提交 `.env`、密钥、token、真实账号凭证或本地私有配置。
- 不要提交 `target/`、`node_modules/`、日志、临时文件或本地构建产物。
- 不要为了“看起来架构完整”创建空抽象、空 port、空 adapter。
- 不要绕过现有包结构把新逻辑放到旧目录。
- 不要在未验证的情况下宣称“已完成”“测试通过”。
