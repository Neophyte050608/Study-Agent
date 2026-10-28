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
- 关闭通用 Milvus VectorStore 自动装配与 schema 初始化（`spring.ai.vectorstore.type=none`）
- 开启持久学习计划预览后台任务（仍只依赖 MySQL/Flyway，不依赖 Neo4j）
- 同步学习计划动态规划默认保持 `FIXED`；需要本地影子观测时可显式设置 `APP_LEARNING_PLAN_DYNAMIC_MODE=SHADOW`
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

### 结构化聊天长期记忆

#### 启用条件与数据范围

`local-lite` 和默认配置都关闭结构化聊天长期记忆。Flyway 必须先成功应用 V3/V4；启用后复用现有 MySQL、Milvus 和 Embedding 配置：

```bash
APP_CHAT_MEMORY_ENABLED=true bash scripts/dev-start.sh
```

该开关只为**启用之后新保存的 user 消息**创建提取任务。系统不读取、不导入、不清空旧 `t_user_chat_memory.memory_text`，也不存在历史消息扫描或 backfill 命令。V1/V2、旧表和旧行保持原样；新任务、记忆、来源和 Outbox 分别写入 V3 创建的 `t_chat_memory_*` 表。

关闭功能或重启进程不会删除已经提交的提取任务或 Outbox 行。重新启用后，worker 会继续领取仍处于 `PENDING` / `RETRY` 或租约过期 `PROCESSING` 状态的记录；达到上限的 `DEAD` 记录必须在排除根因后按单个 ID 显式重置。

#### 真相源、降级与边界

MySQL 是唯一真相源；Milvus collection `chat_memories` 只是不可变版本投影。向量候选在注入上下文前必须回到 MySQL 复核 user、状态、版本、content hash、分类和有效 session/message 来源。

- Milvus、Embedding 或向量查询不可用/超时时，召回降级为 MySQL 最近有效记忆的有界词法排序，默认最多扫描 50 条。
- MySQL 查询或候选复核失败时，不注入长期记忆；不能绕过 MySQL 直接信任 Milvus。
- 外部观测发布失败只丢弃观测事件，不改变提取、投影、召回、repair 或重试结果。
- 关闭开关后不创建新任务、不运行 memory worker/repair，也不召回记忆；持久化数据保留。

当前用户可通过 `GET /api/chat/memories?limit=20&beforeId=...` 查看结构化记忆及仍可访问的来源消息，通过 `DELETE /api/chat/memories/{memoryId}` 幂等删除。接口只信任安全链 principal，不接受 `X-User-Id` 覆盖身份，也不返回 content hash 或向量字段。删除会立即更新 MySQL 真相源并写入 DELETE Outbox；Milvus 的物理清理由投影 worker 最终完成。

删除聊天会话会在同一数据库事务中逻辑删除 session/messages、取消未完成提取任务、失效该会话来源，并按剩余有效来源决定删除记忆或推进版本。`clearSessionContext` 只重置会话压缩边界，不删除长期记忆。

#### 默认运行上限

所有上限都位于 `app.chat.memory.*`，当前默认值如下：

| 能力 | 默认值 |
| --- | --- |
| 提取延迟 / 单批消息数 / 输入上限 | `15s` / `8` / `10,000` Unicode code point |
| 提取最大尝试次数 | `5` |
| worker 轮询 / 单批事件数 / 租约 | `1s` / `50` / `60s` |
| Milvus RPC timeout / 投影最大尝试次数 | `10s` / `10` |
| repair 周期 / 单页真相行数 | `30m` / `200` |
| 向量召回预算 / 候选数 / 最终条数 | `2s` / `20` / `6` |
| 单条正文 / 总记忆上下文 | `500` / `2,000` Unicode code point |
| MySQL fallback 扫描数 | `50` |

collection 名称默认为 `chat_memories`，embedding dimension/model 必须与当前 EmbeddingModel 输出一致。已有 collection schema 不兼容时投影保持禁用，不得自动破坏或重建线上 collection。

#### 可选真实 Milvus 联调

真实 adapter 联调默认跳过，只有显式提供 Milvus 地址和真实 Embedding provider 配置时才运行。API key 优先放在环境变量中，不要写入仓库或命令参数：

```bash
TEST_EMBEDDING_API_KEY='<temporary-test-key>' \
  mvn -q -Dtest=ChatMemoryMilvusAdapterIntegrationTest \
  -Dtest.milvus.host=localhost \
  -Dtest.milvus.port=19530 \
  -Dtest.embedding.provider=zhipuai \
  -Dtest.embedding.model=embedding-3 \
  -Dtest.embedding.dimension=2048 test
```

`test.embedding.provider` 支持 `zhipuai` 或 `openai`；兼容代理可额外设置 `-Dtest.embedding.base-url=...`。测试会创建 `chat_memory_it_<uuid>` 独立 collection，验证真实 schema 初始化、upsert、flush、search 和 delete，并且只清理由本次测试确认不存在后创建的 collection；不会访问 `interview_notes` 或正式 `chat_memories`。缺少任一显式配置时，JUnit 报告 skipped，不能将其表述为真实 Milvus 已验证。

#### 观测与安全日志

运行时发布四类无内容事件：`chat.memory.extract`、`chat.memory.project`、`chat.memory.recall`、`chat.memory.repair`。重点观察受控状态 `COMPLETED`、`RETRY`、`DEAD`、`HIT`、`FALLBACK`、`EMPTY`、`FAILED`，以及 `taskCount`、`pendingCount`、`deadCount`、`resultCount`、`queuedCount`、`latencyMs`、`lagMs`、`fallback`、`errorCode` 等聚合字段。

日志和外部观测不得包含用户原文、source quote、prompt/completion、raw JSON、embedding、content hash、密钥或原始异常文本。日志可以记录用于运维定位的 opaque task/event ID 与受控错误码；外部 attributes 不允许 user/session/memory/task 等业务 ID。详细白名单见 `docs/development/observability-guidelines.md`。

#### 单条 DEAD 记录重试

先修复模型、MySQL、Milvus、schema 或配置问题，再用 MySQL 客户端执行以下**按精确 ID 限定**的语句。先查询确认目标属于 V3 新系统且状态为 `DEAD`；不要删除记录、批量更新整表或操作旧 `t_user_chat_memory`。

重新排队一个提取任务：

```sql
SELECT task_id, status, attempt_count, error_code
FROM t_chat_memory_extraction_task
WHERE task_id = '<task-id>';

UPDATE t_chat_memory_extraction_task
SET status = 'PENDING',
    attempt_count = 0,
    next_attempt_at = CURRENT_TIMESTAMP(6),
    lease_token = NULL,
    lease_until = NULL,
    error_code = NULL,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE task_id = '<task-id>'
  AND status = 'DEAD';
```

重新排队一个投影 Outbox 事件：

```sql
SELECT event_id, memory_id, memory_version, operation, status, attempt_count, error_code
FROM t_chat_memory_outbox
WHERE event_id = '<event-id>';

UPDATE t_chat_memory_outbox
SET status = 'PENDING',
    attempt_count = 0,
    next_attempt_at = CURRENT_TIMESTAMP(6),
    lease_token = NULL,
    lease_until = NULL,
    error_code = NULL,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE event_id = '<event-id>'
  AND status = 'DEAD';
```

每次应确认 `ROW_COUNT() = 1`；为 0 时停止操作并重新核对 ID/状态。功能必须处于启用状态，worker 才会消费重置后的记录。

#### 回滚

应用层回滚只需设置 `app.chat.memory.enabled=false`（环境变量为 `APP_CHAT_MEMORY_ENABLED=false`）并重启。保留 V3/V4 表、任务、Outbox、prompt seed 和 `chat_memories` collection，不执行 down migration，也不清理旧 `memory_text`。确认回滚期间没有新提取/召回后，再针对根因修复并重新启用；持久化的非终态工作会继续处理。

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

## 持久学习计划预览

`local-lite` 现在默认启用持久学习计划预览后台任务（等价于 `app.agent.execution.enabled=true`），用于支持 `/api/learning/plan-previews` 的提交、查询、局部 retry 和终态删除。它会使用 MySQL 任务/节点/尝试表作为真相源，要求 MySQL 可用且 Flyway 正常执行 V5；不会因为 Milvus VectorStore、图片 Milvus 或 Neo4j 不可用而阻塞任务提交。共享节点线程和其他可调参数见 `learning-plan-preview.md`。

启用开关不会自动产生学习任务；模型仍须按已有方式配置。若只想启动后端但暂时关闭持久任务，可显式设置环境变量 `APP_AGENT_EXECUTION_ENABLED=false`，或追加启动参数 `--app.agent.execution.enabled=false`。

HTTP 使用和 401/409/503 含义见 `learning-plan-preview.md`。同步 `/api/task/dispatch` 的动态规划本地默认仍为 `FIXED`；如需验证影子规划观测，可临时设置 `APP_LEARNING_PLAN_DYNAMIC_MODE=SHADOW` 后启动，结束后取消该环境变量。open 仅本地单用户；共享部署应启用 JWT 认证，不用 X-User-Id 代替 owner。
