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

之后在项目根目录执行：

```bash
bash scripts/dev-start.sh
```

脚本会自动完成：

1. 启动基础依赖容器：`mysql redis etcd minio milvus neo4j`
2. 等待 MySQL、Redis、Milvus、Neo4j 端口就绪
3. 使用 `local-lite` profile 启动后端
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

### 数据库还没初始化

如果后端启动后提示表不存在，需要先初始化数据库。参考 README 的“手动启动方式”部分，执行：

- `sql/schema.sql`
- `sql/data.sql`

## 不在一键脚本中默认启动的服务

以下服务默认不启动，需要时手动开启：

```bash
docker compose up -d namesrv broker rocketmq-dashboard
docker compose --profile optional-ai up -d clip-embedding
```

默认不启动它们是为了让本地开发更轻量、更稳定。
