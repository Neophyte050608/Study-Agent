# 本地开发启动指南

本文档说明本项目在本地开发时如何一键启动、停止和排查问题。

## 推荐方式：一键启动

首次启动前，先确认基础环境已安装：

- JDK 21
- Maven 3.9+
- Node.js 18+
- Docker Desktop / Docker Compose

前端依赖只需要安装一次：

```bash
cd frontend
npm install
cd ..
```

首次启动还需要准备本地私有配置。示例文件不包含密钥，复制后通过本机环境变量提供主对话与 Embedding Key：

```bash
cp application-private.example.yml application-private.yml
export DEEPSEEK_API_KEY="<你的主对话模型 Key>"
export ZHIPU_API_KEY="<你的 Embedding Key>"
```

`application-private.yml` 已被 Git 忽略。不要把真实 Key 写入聊天、公共配置或提交到仓库。

之后在项目根目录执行：

```bash
bash scripts/dev-start.sh
```

脚本会自动完成：

1. 启动基础依赖容器：`mysql redis etcd minio milvus neo4j`
2. 等待 MySQL、Redis、Milvus、Neo4j 端口就绪
3. 使用 `local-lite` profile 启动后端，Flyway 自动迁移 MySQL
4. 启动前端 Vite 开发服务

启动完成后访问：

- 前端：`http://localhost:5173`
- 后端：`http://localhost:9596`

## 停止服务

只停止后端和前端进程，保留 Docker 容器：

```bash
bash scripts/dev-stop.sh
```

同时停止后端、前端和基础依赖容器：

```bash
bash scripts/dev-stop.sh --with-docker
```

默认建议只执行 `dev-stop.sh`，这样下次启动不用重新等待所有容器冷启动。

## 日志和 PID 文件

脚本运行时会写入 `.dev/` 目录：

```text
.dev/backend.pid
.dev/frontend.pid
.dev/logs/backend.log
.dev/logs/frontend.log
```

常用排查命令：

```bash
tail -f .dev/logs/backend.log
tail -f .dev/logs/frontend.log
```

`.dev/` 是本地运行态目录，不应提交到 Git。

## local-lite profile 做了什么

`local-lite` 是轻量本地开发配置，文件位于：

```text
src/main/resources/application-local-lite.yml
```

它会关闭或降低以下启动成本：

- 关闭检索后端启动预热：`app.knowledge.retrieval.warmup-enabled=false`
- 关闭 QQ / 飞书 WebSocket 长连接
- 关闭图片 Milvus collection 初始化
- 关闭 Milvus schema 自动初始化
- 降低 Hikari 本地连接池规模
- 将 Spring AI 日志从 DEBUG 降到 INFO

后端实际启动命令是：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local-lite
```

## 轻量启动开关

默认本地启动不会自动连接 QQ/飞书 WebSocket，也不会执行模型预热、模型健康探测或自动记忆整理。需要开启时使用环境变量：

```bash
IM_FEISHU_USE_WS=true IM_QQ_USE_WS=true APP_STARTUP_MODEL_PREHEAT_ENABLED=true APP_MODEL_ROUTING_PROBE_ENABLED=true APP_DREAM_ENABLED=true bash scripts/dev-start.sh
```

启动完成后，后端日志会输出 `Study-Agent startup`，用于确认当前 profile 和关键模块启停状态。

## 手动启动方式

如果不使用脚本，可以手动执行：

```bash
docker compose up -d mysql redis etcd minio milvus neo4j
mvn spring-boot:run -Dspring-boot.run.profiles=local-lite
```

另开一个终端启动前端：

```bash
cd frontend
npm run dev
```

## 常见问题

### Docker 未运行

脚本会提示 Docker 未运行。先启动 Docker Desktop，再重新执行：

```bash
bash scripts/dev-start.sh
```

### 模型 Key 未配置

如果后端提示 `OpenAI API key must be set`，确认：

- 项目根目录存在 `application-private.yml`
- 当前启动终端已设置 `DEEPSEEK_API_KEY`
- 当前启动终端已设置 `ZHIPU_API_KEY`

配置 Key 只让 Spring AI 完成初始化；Milvus 仍须可连接，主对话和向量化功能调用时也需要有效 Key。

### 前端依赖未安装

如果看到 `frontend/node_modules` 缺失提示，执行：

```bash
cd frontend
npm install
cd ..
```

### 后端或前端已在运行

脚本检测到 `.dev/*.pid` 中的进程仍在运行时会拒绝重复启动。先停止：

```bash
bash scripts/dev-stop.sh
```

### Docker 镜像拉取长时间无进度

先验证 Docker daemon 的代理配置和镜像仓库连通性：

```bash
docker info
docker compose pull etcd
docker compose pull minio
```

如果不同仓库的镜像都无进度，而宿主机可以访问仓库，优先检查 Docker Desktop 的代理设置并重启 Docker Desktop；这不是应用配置或模型 Key 导致的问题。

### 端口被占用

默认端口：

- 后端：`9596`
- 前端：`5173`
- MySQL：`3307`
- Redis：`6379`
- Milvus：`19530`
- Neo4j Bolt：`7687`

可以用以下命令排查：

```bash
lsof -nP -iTCP:9596 -sTCP:LISTEN
lsof -nP -iTCP:5173 -sTCP:LISTEN
```

### 数据库迁移失败

MySQL 表结构和必要的参考数据由 Flyway 自动管理，不再手工导入 `sql/schema.sql` 或 `sql/data.sql`。启动失败时先查看后端日志中的 Flyway 错误，并检查：

- MySQL 是否可连接，以及账号是否有建表、建索引和写入权限
- `flyway_schema_history` 中是否存在失败记录
- 是否有人修改过已执行的迁移脚本，导致 checksum 校验失败

迁移脚本位于 `src/main/resources/db/migration/`。已经应用的脚本不可修改；数据库变化必须新增更高版本迁移。

已有的非空 Study-Agent 数据库首次接入时会按 V1 建立基线，再执行 V2 及后续迁移。基线不会自动识别或修复历史库中的未知字段差异；这类差异需要审计后新增 V3+ 显式迁移。

## 不在一键脚本中默认启动的服务

以下服务默认不启动，需要时手动开启：

```bash
docker compose up -d namesrv broker rocketmq-dashboard
docker compose --profile optional-ai up -d clip-embedding
```

默认不启动它们是为了让本地开发更轻量、更稳定。
