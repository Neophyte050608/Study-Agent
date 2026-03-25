# InterviewReview RAG与知识入库全流程深度解析

本文档详细梳理了 `InterviewReview` 项目中，从用户在 IM 渠道发出消息到最终获取大模型 RAG 结果的全链路流程，以及本地或浏览器文档上传到底层多模态数据库（Vector/Lexical/Graph）的全流程。同时列出了系统所依赖的核心技术栈。

---

## 一、 从用户消息到 RAG 返回结果的全流程链路

整个提问与回答流程采用了异步事件驱动、多路混合检索与动态意图路由的架构设计。

### 1. 渠道接入与消息解析 (IM Integration & Parsing)
*   **多渠道监听**：系统通过 `FeishuWebhookController` / `FeishuWsService`（飞书）和 `QqWsService`（QQ长连接）接收终端用户的输入。
*   **消息标准化**：各渠道的事件解析器（如 `FeishuEventParser`）将原始 JSON 转换为系统内部统一模型 `UnifiedMessage`，并完成 @占位符的清洗与幂等性校验（基于 Redis 的 `EVENT_IDEMPOTENCY_PREFIX`）。

### 2. 意图路由与会话管理 (Intent Routing)
*   **会话态拦截**：`ImWebhookService` 首先检查特殊指令（如 `/clear`, `/help`）。接着通过 `ImConversationStore` (Redis) 获取当前用户的活跃面试 ID (`activeInterviewId`) 或待澄清状态 (`pendingClarification`)。如果在活跃面试中，用户的输入会被强制判定为“回答面试问题 (`INTERVIEW_ANSWER`)”。
*   **树形意图分类**：若无明确上下文，任务请求会交由 `TaskRouterAgent` 与 `IntentTreeRoutingService`。利用大模型进行树形意图分类，判定用户是需要“模拟面试”、“算法刷题”还是“查询学习画像”。
*   **槽位提取与澄清**：低置信度时，系统会主动发起短路澄清（让用户选择 1, 2, 3）。确认意图后，通过模型补全缺失槽位（如题型、难度、技术栈主题）。

### 3. RAG 核心检索引擎 (RAG Engine)
当任务进入评估环节（如回答面试题或刷题），将触发 `RAGService.processAnswer` 的核心流水线：
*   **查询改写 (Query Rewrite & HyDE)**：利用 `query-optimizer` 技能块，对用户的“原问题 + 回答”进行提炼。针对极短回答，系统运用 **HyDE (假设性文档嵌入)** 策略，推测理想答案中应有的专业术语，扩写检索词。
*   **三路并行混合检索 (Hybrid Retrieval)**：通过自定义线程池 `ragRetrieveExecutor` 并发执行三个检索通道，彻底解决“语义漂移”问题：
    1.  **意图定向通道 (Lexical Index)**：基于 Jieba 分词提取核心焦点词，利用 MySQL 倒排索引服务 (`LexicalIndexService`) 进行高精度的 `knowledge_tags` 和 `file_path` 匹配。
    2.  **全局向量通道 (Vector Search)**：基于 Milvus 的纯语义相似度检索，作为泛化兜底。
    3.  **知识图谱通道 (GraphRAG)**：基于 Neo4j (`TechConceptRepository`)，查找提炼实体在图谱中的两跳以内 (Within Two Hops) 关联概念，进行实体扩展。
*   **融合去重与重排 (RRF & Re-ranking)**：
    *   采用 **RRF (Reciprocal Rank Fusion, 倒数排名融合)** 算法将意图通道和向量通道的文档进行融合，抹平绝对分数差异，并对意图定向通道给予 1.12 倍权重提权。
    *   加入图谱召回结果后，最终使用 **Query Overlap (查询词重合度)** 结合来源权重（如 `interview_experience` 提权 1.18）进行二次重排 (Re-rank)，截取 Top 5 文档。
*   **网络兜底 (Web Fallback)**：若本地知识库未命中任何上下文，系统自动调用 `WebSearchTool` 进行互联网搜索，并生成 `[web]` 标记的证据。

### 4. 模型评估与生成 (LLM Evaluation)
*   **提示词组装**：通过 `PromptManager` (基于 Jinjava) 渲染模板，注入检索到的 Context、证据目录 (Evidence)、用户学习画像快照 (Profile Snapshot)、技能块 (Skill Block) 以及 Few-shot 样例。
*   **模型路由与容错**：请求交由 `RoutingChatService` 处理。系统采用多模型候选、优先级调度，并启用**首包探测 (First Packet Probe)** 和三态熔断器。一旦主模型超时或失败，自动降级到备用模型或返回 Fallback JSON。

### 5. 证据校验与异步回复 (Validation & Async Reply)
*   **幻觉约束 (Evidence Validation)**：大模型生成的 JSON 中包含 `citations` (引用) 和 `conflicts` (冲突)。系统通过 `validateEvidenceReferences` 方法强制校验这些序号是否存在于前面生成的证据目录中。若存在虚构引用，自动剔除并追加扣分项。
*   **回复清洗与下发**：`ImWebhookService.formatResponse` 将结果剥离 Agent、TraceID 等技术元数据，转换为纯净、对人类友好的文本，并通过 `FeishuReplyAdapter` 或 `QqReplyAdapter` 异步推回至 IM 客户端。

---

## 二、 文档上传到数据库的知识入库全流程 (Ingestion ETL)

系统支持读取本地 Vault 目录 (`/api/ingest`) 或通过浏览器上传多文件 (`/api/ingest-files`)，采用基于 Pipeline 编排的 ETL (提取-转换-加载) 架构。

### 1. 入库任务触发与编排
*   `IngestionTaskService` 接收请求，生成全局唯一 `taskId`，并加载对应的管道定义 (`IngestionPipelineDefinition`)。
*   整个入库过程被切分为一系列严格的生命周期阶段，并在 `IngestionNodeLog` 中留下结构化可观测日志。

### 2. 阶段执行流 (ETL Stages)
1.  **FETCH (数据获取)**：使用 `NoteLoader` 扫描本地目录或直接读取 `MultipartFile` 字节流。支持基于 `ignoredDirs` 的目录过滤，并过滤非 `.md` 后缀的文件。
2.  **PARSE (文档解析)**：使用核心组件 `ObsidianKnowledgeExtractor`。它能解析 Markdown 语法，识别 YAML FrontMatter 中的元数据（如 `knowledge_tags`），并提取正文内的 `[[双向链接]]` 作为图谱关联 (`wiki_links`)。
3.  **CHUNK (智能分块)**：调用 `DocumentSplitter`。系统放弃了暴力的固定 Token 切分，采用 **`STRUCTURE_RECURSIVE_WITH_METADATA`** 策略。
    *   首先按 Markdown 标题层级切分出逻辑段落 (Section)。
    *   若段落过长，再使用递归分隔符（换行、句号等）二次切分。
    *   切分后，在每个 Chunk 的头部注入文件名、章节路径等轻量级元数据，解决大模型检索时常见的“主语缺失”问题。
4.  **ENHANCE (数据增强)**：阶段占位与轻量级数据清洗计算。

### 3. 数据落库与索引构建 (SYNC)
在 `SYNC_MARK` / `LEGACY_SYNC` 阶段，执行核心的 `IngestionService.sync` 逻辑：
*   **哈希校验 (Hash & Delta Sync)**：通过 `DigestUtils.md5DigestAsHex` 计算文件 MD5。比对 MySQL `t_sync_index` 表中的历史 Hash，只有新增或修改的文件才会进入后续构建流程，大幅节约 Token 成本。
*   **多模态索引落库**：
    1.  **Vector Store (Milvus)**：将 Document 块列表通过 Embedding 模型（如 ZhiPuAI）转化为向量，存入 Milvus。
    2.  **Lexical Index (MySQL)**：将 Document 块提取出的关键词及元数据持久化到 MySQL `t_lexical_index`，用于倒排词法检索。
    3.  **GraphRAG (Neo4j)**：提取文档元数据中的 `wiki_links`（双链）。以文档名为中心节点 (`TechConcept`)，双链目标为关联节点，使用 Spring Data Neo4j 自动在图数据库中创建实体和边 (Relationships)。
*   **状态收尾**：将本次成功处理的 `docIds` 及最新文件 Hash 写回 MySQL `t_sync_index`，并清理在本次同步中被判定为“已删除”的旧文件向量与倒排记录。

---

## 三、 核心技术栈清单 (Tech Stack)

该系统是一个典型的现代 Java AI Native 应用，核心技术栈如下：

### 1. 基础架构与语言
*   **Java 21 / 23**：充分利用 Records, Switch Expressions, Virtual Threads (异步优化) 等新语法。
*   **Spring Boot 3.3.6**：核心应用框架。
*   **COLA StateMachine**：阿里开源的轻量级状态机组件，用于复杂流程控制。
*   **Maven**：依赖管理。

### 2. AI 与大模型集成
*   **Spring AI**：核心 AI 抽象层。
    *   `spring-ai-starter-model-openai`：对接主干推理模型。
    *   `spring-ai-starter-model-zhipuai`：主要用于中文文本的 Embedding 向量化。
*   **Jinjava**：HubSpot 开源的模板引擎，用于管理和渲染复杂的 Prompt 提示词 (`prompts.txt`)。
*   **fastmcp-java**：MCP (Model Context Protocol) Java 实现，用于实现工具的快速定义和原生大模型能力的联动。

### 3. 数据存储与搜索引擎
*   **关系型数据库 (MySQL + MyBatis-Plus 3.5.5)**：
    *   存储用户学习画像 (`t_learning_profile`, `t_learning_event`)。
    *   存储倒排词法索引 (`t_lexical_index`)。
    *   存储入库增量同步记录 (`t_sync_index`) 与意图树/Agent配置。
*   **缓存与会话存储 (Redis)**：通过 `spring-boot-starter-data-redis` 实现 IM 会话滑动窗口、幂等性拦截、热点画像缓存。
*   **向量数据库 (Milvus)**：`spring-ai-starter-vector-store-milvus`，用于高维语义向量存储与相似度检索（处理与 Protobuf 3.25.3 的冲突兼容）。
*   **图数据库 (Neo4j)**：`spring-boot-starter-data-neo4j`，用于构建知识图谱实体关联 (GraphRAG)。

### 4. 文本处理与通信中间件
*   **Jieba 分词 (jieba-analysis)**：用于中文文本分词、Query Overlap 重合度计算以及焦点词提取。
*   **飞书开放平台 SDK (`oapi-sdk`)**：用于飞书渠道的 Webhook/WebSocket 接入与卡片消息推送。
*   **Spring WebSocket**：实现底层 WebSocket 协议，用于直连 QQ 官方机器人长连接 (Socket Mode)。
*   **RocketMQ**：`rocketmq-spring-boot-starter`，用于异步解耦底层核心链路组件间的事件投递 (A2A Bus)。