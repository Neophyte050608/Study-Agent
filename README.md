# Interview Review

一个面向技术面试复盘、知识检索、刷题训练与 RAG 评测的本地开发项目。


当前仓库包含：

- `src/`：Spring Boot 后端
- `frontend/`：Vue 3 + Vite 前端
- `sql/`：数据库建表与部分初始化数据
- `docker-compose.yml`：本地依赖服务编排
- `clip-service/`：图片向量服务
- `eval-service/`：Ragas 评测服务
- `visual/`: 程序链路可视化链路图以及架构图等，但是由于在更新可能会有出入

## 1. 项目能力概览

项目目前覆盖以下能力：

- 模拟面试与流式问答
- 知识库管理与 RAG 检索
- Graph / Local Knowledge 检索协同
- 算法刷题与学习画像
- 模型路由与可观测性
- RAG 检索评测、生成质量评测
- 多模态图片摘要与图片向量检索

## 2. 核心功能与机制说明

这一节更偏“项目设计说明”，重点说明系统是怎么工作的，而不是只列功能名。

### 2.1 意图识别与任务路由

系统的任务入口不是单一分类器，而是分层路由链路：

- 第一层是规则预过滤 `IntentPreFilter`，优先处理高确定性输入，例如 `/help`、`/clear`、明确的“开始面试 / 生成报告 / 刷题 / 查询画像”等。
- 第二层是域识别，先把请求裁到 `INTERVIEW / CODING / KNOWLEDGE / PROFILE` 这类业务域，再缩小后续分类范围。
- 第三层是意图树分类 `IntentTreeRoutingService`，在域内叶子意图集合上做小模型分类，而不是全量开放分类。
- 第四层是槽位精炼，在任务类型已基本确定后，继续抽取 `topic / questionType / difficulty / count / skipIntro / mode` 等结构化参数。
- 如果意图树关闭或分类失败，则会回退到 `TaskRouterAgent` 中的 ReAct 路由链路，保证主流程不断。

这套设计的目标是降低纯 LLM 路由的不稳定性，让“规则可确定的先走规则，模糊语义再走小模型，最后才走通用推理”。

### 2.2 意图树、上下文、澄清与承接

意图识别不是只看当前一句话，还会显式利用上下文：

- Web 对话侧会通过 `ChatContextCompressor.buildIntentRoutingContext(...)` 提供最近若干轮、适合路由的精简上下文。
- IM 侧在接入层维护会话状态，可以识别当前是否处于澄清阶段、面试进行中或普通闲聊状态。
- `IntentTreeRoutingService` 会把 `query + history` 一起送入域分类和域内叶子分类模板中。

低置信场景下系统不会硬判，而是主动澄清：

- 当 top1 置信度低于阈值
- 或 top1/top2 分差过小
- 或次优候选与首位候选过于接近

系统会返回澄清问题和候选项，而不是直接执行错误任务。

澄清后并不是简单“选项映射”：

- `ClarificationResolver` 支持用户回复编号，也支持回复候选名称或任务类型关键字。
- 澄清回复会与原始 query 合并，形成新的 query，再做一轮槽位精炼。
- 系统会把规则识别出的槽位与小模型补齐出的槽位合并，只补缺，不覆盖已经明确识别出的字段。

从当前代码实现看，意图承接与跳转本质上依赖三类信号共同决定：

- 规则信号：当前输入命中了明确规则或域级规则。
- 上下文信号：最近对话、会话摘要、用户画像中是否存在强约束语义。
- 会话状态信号：当前是否处于活跃面试、澄清待确认或已有任务上下文中。

这三类信号叠加后，系统可以完成：

- 当前轮意图识别
- 低置信澄清
- 澄清后的任务承接
- 在面试、刷题、知识问答、画像查询之间进行跳转

### 2.3 多模型候选、模型配置与三态熔断

系统的模型调用不是单模型直连，而是统一走模型路由层：

- 候选模型支持多提供商、多优先级配置。
- `app.model-routing.*` 中定义默认模型、深度思考模型、意图模型及熔断参数。
- 候选模型也支持落库到 `t_model_candidate` 中，便于管理端维护。

路由层支持三态熔断：

- `CLOSED`：正常放行。
- `OPEN`：连续失败达到阈值后熔断，直接拒绝请求并切换下一个候选。
- `HALF_OPEN`：冷却时间后允许少量探测请求，成功则恢复，失败则重新打开熔断。

这套熔断器是为 LLM 场景定制的，不只看异常，还考虑“首包假死”这类 HTTP 成功但流式首包迟迟不返回的问题。

模型路由同时承担以下职责：

- 按任务类型选择更合适的模型，例如意图识别优先走意图模型。
- 做首包探测和超时保护。
- 在主模型失败、超时、熔断时自动降级到下一个候选。
- 暴露运行态指标，支持查看请求数、成功数、失败数、熔断状态和最后失败原因。

### 2.4 并行多路检索与降级

RAG 检索不是单一路径，而是混合检索流水线：

- 向量检索：基于 Milvus 的语义召回。
- 关键词检索：基于 MySQL 词法索引，支持 `FULLTEXT / LIKE / AUTO` 模式。
- 图谱检索：基于 Neo4j / GraphRAG 的概念关联召回。
- 本地模型召回：本地知识图模式下，先做候选召回，再通过本地模型路由筛选命中节点。
- 网络搜索降级：本地召回为空或质量过低时，按策略触发 Web fallback。

主链路里还包含两个并行向量通道：

- 意图定向通道：基于当前 query 的意图焦点词，对知识标签和路径更聚焦。
- 全局向量通道：保留全局召回，避免过窄过滤带来的漏召回。

设计目标是兼顾“准”与“全”：

- 规则和词法保证高精度
- 向量与图谱保证语义扩展
- Web fallback 保证极端情况下仍能回答

### 2.5 重排、去重与证据整理

多路召回后并不会直接拼接给模型，而是进入融合和重排：

- 先做 RRF（Reciprocal Rank Fusion）融合。
- 再做去重，避免相同来源片段重复进入上下文。
- 再按 query overlap 和附加元数据做 rerank。
- 最终只保留有限的高价值文档进入提示词上下文。

系统在生成时还会额外构建一份 `retrievalEvidence`：

- 为召回证据统一编号
- 约束后续 `citations / conflicts` 只能引用这些编号
- 便于做可观测追踪、评测复现和错误定位

### 2.6 图文联合召回

项目支持图文联合知识检索，而不是把图片当作静态附件：

- 入库阶段通过 `ImageReferenceExtractor` 从 Markdown 中提取图片引用。
- `VisionModelService` / 图片处理流水线为图片生成语义摘要。
- `ImageService` 会建立 `文本块 -> 图片` 关联关系，落到 `t_text_image_relation`。
- 图片可走两条召回路径：
  - 关联召回：根据已命中的文本块，找相关图片。
  - 语义召回：直接对图片摘要 / 视觉向量做检索。

最终图文结果会合并去重，只把高相关图片放入 `imageContext`，供回答生成阶段引用。

### 2.7 长上下文压缩与跨会话记忆

系统没有无限堆叠原始对话，而是显式做上下文治理：

- `ChatContextCompressor` 在消息数量超过阈值后触发异步压缩。
- 保留最近若干轮原文对话，同时将更早内容总结成 `contextSummary`。
- 压缩完成后再触发跨会话记忆提取，把稳定用户偏好、能力特点沉淀到 `t_user_chat_memory`。

用于路由和生成的上下文通常是三段式：

- `【用户画像】`
- `【会话摘要】`
- `【近期对话】`

这样既能保留连续性，又能控制 token 成本，避免长期聊天后 prompt 膨胀。

### 2.8 学习画像异步更新

学习画像并不是同步塞在主链路里更新，而是异步沉淀：

- 面试回答结果会转成学习事件。
- 刷题结果、批量练习结果也会异步写入画像系统。
- 更新线程池和异步执行器独立配置，避免阻塞出题、评估、回复主流程。

画像侧沉淀的数据包括：

- topic 维度掌握度
- 难度分层能力
- 能力曲线与趋势
- 薄弱点、熟悉点、近期表现

这些画像快照又会反向注入到：

- 首题生成
- 追问策略
- 刷题推荐
- 学习计划生成

### 2.9 知识入库：父子文档、图文关联与任务化 ETL

知识入库不是“文件切块后直接写向量库”的简单流程，而是逐步任务化：

- 支持本地目录同步与浏览器上传两类入口。
- 入库任务具备 `taskId`、阶段日志、状态、耗时和摘要。
- 阶段包括扫描、解析、切块、增强、同步等，可做观测和扩展。

当前知识入库的核心设计有两点：

1. 父子文档模型

- 父文档 `t_rag_parent` 保存章节级或较大语义单元。
- 子文档 `t_rag_child` 保存细粒度检索块。
- 检索时先召回 child，再回填 parent 上下文。
- 回填不是直接塞整段 parent，而是用“父文上下文 + 命中片段”的方式构造证据，减少语义稀释。

2. 图文关联模型

- 图片在入库时会生成图片元数据 `t_image_metadata`
- 文本块与图片的关系落到 `t_text_image_relation`
- 父文档上保留 `image_refs`
- 最终支持从文档命中反查图片、从图片向量反查内容

### 2.10 RAG 评测：检索评测与生成质量评测

项目内置两类评测，不是只看最终回答好不好。

1. 检索评测 `RetrievalEvaluationService`

- 直接复用 `RAGService.buildKnowledgePacket(..., false)` 主检索链路
- 评测时显式关闭 Web fallback，确保指标只衡量本地检索能力
- 产出 `Recall@1 / Recall@3 / Recall@5 / MRR`
- 支持默认数据集、自定义数据集、CSV 导入
- 支持历史留档、详情回放、A/B 对比、趋势分析、失败聚类和参数模板

2. RAG 生成质量评测 `RAGQualityEvaluationService`

- 支持两种引擎：
  - `java`：在 Java 服务内直接调用现有模型链路，对 `faithfulness / answer relevancy / context precision / context recall` 打分
  - `ragas`：通过外部 `ragas-eval` Python 服务做批量评测
- 两种引擎都会保留运行记录、指标均值、逐样本结果和比较能力
- 可以按不同数据集运行，也可以做实验标签与参数快照留档

换句话说，这个项目把“能回答”与“回答链路可评估、可比较、可调优”一起做了。

## 3. 技术栈

- 后端：Java 21、Spring Boot 3.3、Spring AI、MyBatis-Plus
- 前端：Vue 3、Vite
- 数据库：MySQL 8
- 缓存：Redis
- 向量库：Milvus
- 图数据库：Neo4j
- 消息总线：RocketMQ
- 可选 AI 辅助服务：`ragas-eval`、`clip-embedding`

## 4. 快速启动

### 4.1 环境要求

- JDK 21
- Maven 3.9+
- Node.js 18+
- Docker Desktop / Docker Compose

### 4.2 推荐的第一版启动方式

这是当前仓库最稳妥的本地启动路径。

1. 启动基础依赖容器

```bash
docker compose up -d mysql redis etcd minio milvus neo4j
```

2. 初始化数据库

按顺序执行：

- `sql/schema.sql`
- `sql/data.sql`

建议先创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS interview_review DEFAULT CHARACTER SET utf8mb4;
```

3. 准备私有配置文件

在项目根目录创建 `application-private.yml`，至少补齐你自己的 API Key 和本地连接信息。

4. 启动后端

```bash
mvn spring-boot:run
```

默认端口：`http://localhost:9596`

5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

默认端口：`http://localhost:5173`

前端 `/api` 默认会代理到 `http://localhost:9596`。

## 5. 容器说明

### 5.1 当前默认配置下建议必启

以下容器建议视为默认启动集：

| 容器 | 是否建议必启 | 用途 | 不启动的影响 |
|---|---|---|---|
| `mysql` | 是 | 业务数据、配置、评测结果、会话数据 | 后端无法正常工作 |
| `redis` | 是 | 缓存、幂等等运行态能力 | 部分运行能力不可用，默认配置下不建议关闭 |
| `etcd` | 是 | Milvus 依赖 | Milvus 无法启动 |
| `minio` | 是 | Milvus 依赖 | Milvus 无法启动 |
| `milvus` | 是 | RAG 向量检索、图片向量检索 | 当前默认配置下后端启动与检索能力都依赖它 |
| `neo4j` | 建议开启 | 图检索、GraphRAG 相关能力 | 图检索相关能力不可用，默认开发环境建议开启 |

说明：

- 虽然从功能层面看 `neo4j`、`milvus` 属于增强型依赖，但当前仓库默认配置已经直接指向本地实例。
- 尤其 `milvus` 在当前实现下建议视为必选，否则后端启动和向量检索链路存在失败风险。

### 5.2 可选容器

| 容器 | 是否可选 | 何时需要 |
|---|---|---|
| `namesrv` | 可选 | 需要启用 RocketMQ A2A 总线时 |
| `broker` | 可选 | 需要启用 RocketMQ A2A 总线时 |
| `rocketmq-dashboard` | 可选 | 只在你需要查看 RocketMQ 控制台时 |
| `ragas-eval` | 可选 | 需要使用 Ragas 做 RAG 生成质量评测时 |
| `clip-embedding` | 可选 | 需要图片视觉向量能力时 |

### 5.3 哪些容器可以先不启动

如果你只是先跑通 Web 端、后端主流程和基础 RAG，通常可以先不启动：

- `namesrv`
- `broker`
- `rocketmq-dashboard`
- `ragas-eval`
- `clip-embedding`

其中：

- 不启 `RocketMQ` 时，请保持 `app.a2a.bus.type=inmemory`
- 不启 `ragas-eval` 时，RAG 质量评测引擎改用 `java`
- 不启 `clip-embedding` 时，保持 `app.image.visual-embedding.enabled=false`

### 5.4 可选服务的启动命令

启用 RAG 评测服务：

```bash
docker compose up -d ragas-eval
```

启用图片向量服务：

```bash
docker compose --profile optional-ai up -d clip-embedding
```

启用 RocketMQ：

```bash
docker compose up -d namesrv broker rocketmq-dashboard
```

## 6. 配置文件说明

项目采用双层配置：

- `src/main/resources/application.yml`：公共配置，已入库
- `application-private.yml`：本地私密配置，不应提交到 Git

`application.yml` 已通过：

```yaml
spring:
  config:
    import: optional:file:./application-private.yml
```

自动导入项目根目录下的 `application-private.yml`。

## 7. 必要配置项

下面是第一版最需要关注的配置项。

### 7.1 后端基础配置

| 配置项 | 是否必填 | 说明 |
|---|---|---|
| `server.port` | 否 | 后端端口，默认 `9596` |
| `spring.datasource.url` | 是 | MySQL 连接地址 |
| `spring.datasource.username` | 是 | MySQL 用户名 |
| `spring.datasource.password` 或 `MYSQL_PASSWORD` | 是 | MySQL 密码 |
| `spring.data.redis.host` | 是 | Redis 地址 |
| `spring.data.redis.port` | 是 | Redis 端口 |
| `spring.data.redis.password` | 视情况 | Redis 密码，当前默认配置使用密码 |
| `spring.neo4j.uri` | 建议填写 | Neo4j 地址 |
| `spring.neo4j.authentication.username` | 建议填写 | Neo4j 用户名 |
| `spring.neo4j.authentication.password` | 建议填写 | Neo4j 密码 |

### 7.2 模型与 Embedding 配置

| 配置项 | 是否必填 | 说明 |
|---|---|---|
| `spring.ai.openai.api-key` | 是 | 主对话模型 API Key，当前默认接 DeepSeek OpenAI 兼容接口 |
| `spring.ai.openai.base-url` | 是 | Chat 模型接口地址 |
| `spring.ai.zhipuai.api-key` | 是 | Embedding 使用的智谱 API Key |
| `spring.ai.zhipuai.embedding.options.model` | 否 | 默认 `embedding-3` |

### 7.3 Milvus / RAG 配置

| 配置项 | 是否必填 | 说明 |
|---|---|---|
| `spring.ai.vectorstore.milvus.client.host` | 是 | Milvus 地址 |
| `spring.ai.vectorstore.milvus.client.port` | 是 | Milvus 端口 |
| `spring.ai.vectorstore.milvus.collection.name` | 否 | 默认集合 `interview_notes` |
| `spring.ai.vectorstore.milvus.embedding-dimension` | 是 | 需与 Embedding 维度一致，当前默认 `2048` |

### 7.4 A2A / RocketMQ 配置

| 配置项 | 是否必填 | 说明 |
|---|---|---|
| `app.a2a.bus.type` | 是 | `inmemory` 或 `rocketmq` |
| `rocketmq.name-server` | 仅 RocketMQ 模式必填 | NameServer 地址 |
| `app.a2a.rocketmq.topic` | 否 | 默认 `a2a-events` |
| `rocketmq.producer.group` | 否 | Producer Group |

建议：

- 本地开发先用 `app.a2a.bus.type=inmemory`
- 只有在你明确要验证分布式消息链路时再启用 RocketMQ

### 7.5 知识入库相关配置

| 配置项 | 是否必填 | 说明 |
|---|---|---|
| `app.ingestion.vault-path` | 按需 | 知识库根目录 |
| `app.ingestion.image-path` | 按需 | 图片目录 |
| `app.ingestion.config.paths` | 按需 | 多路径知识源 |
| `app.ingestion.config.image-path` | 按需 | 配置化图片路径 |
| `app.ingestion.config.ignore-dirs` | 按需 | 需要忽略的目录列表 |

### 7.6 RAG 评测配置

| 配置项 | 是否必填 | 说明 |
|---|---|---|
| `app.eval.rag-quality.engine` | 否 | `java` 或 `ragas`，默认 `java` |
| `app.eval.ragas.service-url` | 仅启用 `ragas` 时必填 | Ragas 服务地址 |
| `app.eval.ragas.llm-api-key` | 仅启用 `ragas` 时必填 | 评测 LLM Key |
| `app.eval.ragas.embedding-api-key` | 仅启用 `ragas` 时必填 | 评测 Embedding Key |

### 7.7 图片多模态配置

| 配置项 | 是否必填 | 说明 |
|---|---|---|
| `app.image.visual-embedding.enabled` | 否 | 是否启用图片视觉向量，默认 `false` |
| `app.multimodal.clip.service-url` | 仅启用视觉向量时必填 | `clip-embedding` 服务地址 |
| `app.multimodal.vlm.api-key` | 按需 | 图片摘要/VLM 能力使用 |
| `app.multimodal.vlm.base-url` | 按需 | VLM 接口地址 |
| `app.multimodal.vlm.model` | 按需 | 默认 `glm-4v` |

## 8. 推荐的 `application-private.yml` 起步模板

下面这份模板适合先跑通一版本地开发，不包含真实密钥：

```yaml
spring:
  ai:
    openai:
      api-key: your-chat-api-key
    zhipuai:
      api-key: your-embedding-api-key

  data:
    redis:
      host: 127.0.0.1
      port: 6379
      password: your-redis-password

  neo4j:
    authentication:
      password: your-neo4j-password

app:
  a2a:
    bus:
      type: inmemory

  eval:
    rag-quality:
      engine: java
```

如果你要启用 RocketMQ，再补：

```yaml
rocketmq:
  name-server: 127.0.0.1:9876

app:
  a2a:
    bus:
      type: rocketmq
```

## 9. 常用开发命令

启动前端开发环境：

```bash
cd frontend
npm run dev
```

构建前端到 Spring Boot 静态目录：

```bash
cd frontend
npm run build:spring
```

启动后端：

```bash
mvn spring-boot:run
```

运行测试：

```bash
mvn test
```

## 10. 排障建议

### 10.1 后端启动失败

优先检查：

- `application-private.yml` 是否存在
- MySQL、Redis、Milvus、Neo4j 是否已启动
- API Key 是否填写
- MySQL 是否已执行 `sql/schema.sql` 与 `sql/data.sql`

### 10.2 页面能打开但接口报错

优先检查：

- 后端是否运行在 `9596`
- 前端代理是否指向 `http://localhost:9596`
- 浏览器请求是否命中 `/api`

### 10.3 RAG 或图片能力不可用

优先检查：

- `milvus` 是否健康
- Embedding 维度是否与配置一致
- `clip-embedding` 是否真的启动
- `app.image.visual-embedding.enabled` 是否误开

## 11. 启动建议总结

如果你只是想先写第一版、跑通主流程，建议直接按下面这一套：

1. 启动 `mysql redis etcd minio milvus neo4j`
2. 导入 `sql/schema.sql` 和 `sql/data.sql`
3. 准备自己的 `application-private.yml`
4. 后端执行 `mvn spring-boot:run`
5. 前端执行 `npm install && npm run dev`

先不要启动：

- `namesrv`
- `broker`
- `rocketmq-dashboard`
- `ragas-eval`
- `clip-embedding`

等主链路跑通后，再按需要逐个加。
