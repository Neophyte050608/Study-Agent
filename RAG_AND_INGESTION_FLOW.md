# InterviewReview：RAG 检索与知识入库全流程（面试复习版）

本文档面向“面试复习”场景，完整梳理你当前项目里两条主链路：
1. 用户提问到最终答案返回（RAG 主链路）。
2. 新数据入库到多索引生效（Ingestion ETL 链路）。

同时给出每个关键技术栈的“为什么选它”“和常见替代方案相比的优势与取舍”，便于你在面试中解释架构决策。

---

## 一、RAG 全流程：从用户提问到结果返回

## 1. 入口层：消息接入与标准化

### 1.1 IM 渠道接入
- 飞书 Webhook 入口：`FeishuWebhookController`，负责签名校验、事件解析、幂等去重和异步分发。
- 飞书长连接、QQ 长连接分别由 `FeishuWsService`、`QqWsService` 接入。
- 所有渠道最终都会汇聚为统一消息模型 `UnifiedMessage`，避免业务层写多套适配逻辑。

### 1.2 消息标准化
- 解析器（如 `FeishuEventParser`）负责把平台原始 JSON 统一成 `UnifiedMessage`。
- 同时清理 @ 占位符、补齐用户标识和会话标识，后续业务不再关心平台差异。

### 1.3 幂等与去重
- 事件幂等依赖 Redis 前缀键去重，防止平台重试导致重复处理。
- 面试中可总结为“入口层先做防抖与标准化，业务层只处理语义，不处理平台噪音”。

---

## 2. 会话层：上下文判断与意图决策

### 2.1 会话态优先规则
- `ImWebhookService` 先识别特殊指令（如 `/clear`、`/help`）。
- 再看 `ImConversationStore` 是否存在 `activeInterviewId` 或 `pendingClarification`。
- 若用户处于活跃面试会话，输入会优先按 `INTERVIEW_ANSWER` 解释，保证连续性。

### 2.2 意图树路由
- 无强会话约束时，交由 `TaskRouterAgent + IntentTreeRoutingService` 做树形分类。
- 分类输出包含 `taskType + intentId + confidence + slots + candidates`。
- 若置信度低或缺关键槽位（如刷题缺题型），触发澄清问题，用户回复编号后进入二次路由。

### 2.3 刷题题型路由（当前版本）
- 已按 `CHOICE/FILL/ALGORITHM/SCENARIO` 进行题型识别。
- 意图节点存储在 MySQL `t_intent_node`，支持在线配置。
- 这保证“路由决策”与“题目生成策略”一致，不再出现“题型是选择题但 prompt 仍是算法教练”的错配。

---

## 3. 编排层：任务分发与四层 Agent 协作

### 3.1 统一分发
- `TaskRouterAgent.dispatch` 负责把任务分发给面试、刷题、画像等子能力。
- 通过 A2A Bus 发布任务状态（PENDING/PROCESSING/DONE/FAILED），便于观测与排障。

### 3.2 面试回答链路
- `InterviewOrchestratorAgent.submitAnswer` 按四层推进：
  1) 决策层（是否追问、难度调整）
  2) 知识层（RAG 取证据）
  3) 评估层（评分与反馈）
  4) 成长层（画像更新与建议）

### 3.3 刷题链路
- `CodingPracticeAgent` 负责题型、主题、难度、题数会话化管理。
- 题目生成、答案评估、下一题追问都通过 `RAGService` 实现，且按题型注入不同 prompt 约束。

---

## 4. 检索层：RAGService 的核心流水线

以下是“回答评估/刷题评估”都会走到的核心思想。

### 4.1 Query Rewrite 与 HyDE
- 输入不是直接检索，而是先改写（原问题 + 用户回答 + 当前场景）。
- 对短回答触发 HyDE（假设理想答案术语）以增强召回词覆盖。
- 目的：减少“用户表达不完整导致检索偏题”。

### 4.2 三路并行混合检索
- 线程池并发执行三个通道：
  1) 词法通道（Lexical Index）：高精度关键词命中。
  2) 向量通道（Milvus）：语义相似召回，兜底泛化。
  3) 图谱通道（Neo4j）：实体邻域扩展（两跳关系）。

### 4.3 融合与重排
- 先做 RRF（倒数排名融合）消除不同通道分值尺度差异。
- 再按 query overlap、来源权重、证据质量重排，截取 TopK。
- Parent-Child 模式下：Child 命中后回填 Parent 文本，避免片段语义缺主语。

### 4.4 Web 兜底
- 本地知识库完全无命中时触发 `WebSearchTool`。
- 结果会带 `[web]` 证据标记，便于后续审计“是否来自外部网络”。

---

## 5. 生成层：Prompt 组装、模型路由、容错

### 5.1 Prompt 组装
- `PromptManager + Jinjava` 渲染 `prompts.txt` 模板。
- 注入：context、evidence、profileSnapshot、skillBlock、few-shot 样例、题型槽位。

### 5.2 模型路由
- `RoutingChatService` 做候选模型选择、优先级调度、首包探测、熔断判定。
- 支持 `GENERAL/THINKING` 路由类型，分别用于普通生成与深度评估。

### 5.3 容错与降级
- 首包失败或超时时尝试候选切换。
- 仍失败时返回 fallback JSON（评分、反馈、下一题/下一步建议）。
- 这保证链路“可用性优先”，不会因单模型异常导致整链路硬失败。

---

## 6. 校验层：证据约束与回复下发

### 6.1 引用校验
- 模型输出 JSON 含 `citations` 和 `conflicts`。
- `validateEvidenceReferences` 强校验引用编号必须存在于证据目录。
- 非法引用会被剔除并扣分，降低幻觉风险。

### 6.2 回复清洗与回包
- `ImWebhookService.formatResponse` 去除技术元数据（Agent、TraceId、SessionId）。
- 最终仅保留用户可读内容，通过飞书/QQ ReplyAdapter 异步回推。

---

## 二、新数据入库全流程（Ingestion ETL）

## 1. 触发方式

### 1.1 本地目录同步
- 接口：`POST /api/ingest`
- 适用于周期性批量同步知识库目录。

### 1.2 浏览器上传同步
- 接口：`POST /api/ingest-files`
- 适用于临时补充材料、简历、专题文档。

### 1.3 统一任务化
- 两种入口都先进入 `IngestionTaskService`，生成 `taskId` 并按 pipeline 执行。
- 每阶段产生日志节点 `IngestionNodeLog`，支持后续观测分析。

---

## 2. Pipeline 阶段执行

默认阶段序列（可配置）：
1. FETCH
2. PARSE
3. ENHANCE
4. CHUNK
5. EMBED_INDEX
6. LEXICAL_INDEX
7. GRAPH_SYNC
8. SYNC_MARK / LEGACY_SYNC

### 2.1 FETCH
- 本地模式：扫描目录，支持 `ignoredDirs` 过滤。
- 上传模式：读取 `MultipartFile`，过滤非 `.md` 资源。

### 2.2 PARSE
- `ObsidianKnowledgeExtractor` 解析 Markdown：
  - FrontMatter（知识标签等）
  - `[[wiki_links]]`（图谱关系）
  - 文本主体结构

### 2.3 CHUNK
- `DocumentSplitter` 使用 `STRUCTURE_RECURSIVE_WITH_METADATA`：
  - 先按标题层级切 Section。
  - 再递归细分长段落。
  - 为每个 chunk 注入文档名、章节路径等元数据。

### 2.4 ENHANCE
- 执行轻量清洗、统计与阶段级增强。
- 作为后续扩展位，可插入质量分级、敏感词标注等节点。

### 2.5 SYNC（落库）
- `IngestionService.sync` / `syncUploadedNotes` 执行真实写入：
  - MD5 增量判定（仅处理新增/修改）。
  - Milvus 向量写入。
  - MySQL 词法索引写入。
  - Neo4j 图谱关系写入。
  - `t_sync_index` 更新哈希与 docIds。
  - 清理已删除文件对应历史索引。

---

## 3. Parent-Child 索引链路（当前关键能力）

### 3.1 入库阶段
- 每个 Parent 文档切分为多个 Child 片段。
- 通过 `ParentChildIndexService` 建立 `parent_id -> child_id` 映射并持久化。

### 3.2 检索阶段
- 先召回 Child（提高细粒度匹配精度）。
- 再回填 Parent（保证上下文完整性）。
- 证据中保留 parent/child 命中信息，支持可解释性输出。

### 3.3 优势
- 相比纯大 chunk：更精准，不易被无关段落稀释。
- 相比纯小 chunk：上下文完整，不易丢主语和语义前提。

---

## 三、技术栈与选型理由（含对比）

以下按“为什么采用 + 对比常见替代”给出面试可直接复述的话术。

## 1. Java 21/23 + Spring Boot 3.3.6

### 为什么采用
- 团队主栈是 Java，生态完整、工程化稳定。
- Spring Boot 对 Web、数据、AI、消息中间件整合成熟。

### 对比替代
- 对比 Python FastAPI：Python 在模型实验快，但大型业务工程一致性、类型约束和治理能力通常弱于 Java 主干团队。
- 对比 Node.js：I/O 性能好，但复杂企业后端（事务、数据层、多中间件）治理成本更高。

### 优势总结
- 更强的企业级稳定性、依赖治理、长期维护与团队可用人才密度。

---

## 2. Spring AI + Jinjava Prompt 管理

### 为什么采用
- Spring AI 提供统一模型调用抽象，减少 provider 耦合。
- Jinjava 模板让 Prompt 集中管理，支持变量注入、条件渲染和版本化演进。

### 对比替代
- 对比“代码硬编码 prompt”：快速但不可维护，无法审计和复用。
- 对比手写字符串拼接：易出错，难以对齐多场景模板。

### 优势总结
- Prompt 资产化、可配置化、可回归测试化。

---

## 3. MySQL + Redis + Milvus + Neo4j 多存储协同

### 为什么采用
- MySQL 负责结构化元数据、配置、倒排索引与增量状态。
- Redis 负责会话态、幂等、热点缓存，降低读延迟。
- Milvus 负责语义向量检索。
- Neo4j 负责实体关系扩展（GraphRAG）。

### 对比替代
- 只用向量库：语义强但关键词精确命中、配置治理、事务管理薄弱。
- 只用关系库全文：可治理但语义召回能力有限。
- 只用图数据库：关系解释强但自然语言召回效率不足。

### 优势总结
- 各存储“各司其职”，组合后在召回率、精度、可解释性、可维护性上更平衡。

---

## 4. 混合检索（Lexical + Vector + Graph）与 RRF 重排

### 为什么采用
- 单一路径检索在真实业务里常见“召回偏科”：
  - 词法：精准但不泛化。
  - 向量：泛化但可能语义漂移。
  - 图谱：关联强但不覆盖长尾文本。
- 混合 + RRF 可显著降低单路偏差。

### 对比替代
- 对比单路向量检索：实现简单，但复杂问答稳定性不够。
- 对比 BM25 单路：中文语义泛化弱，错失同义表达。

### 优势总结
- 在复杂问答场景下，答案稳定性与可解释性更高。

---

## 5. 模型路由、首包探测与熔断

### 为什么采用
- 多模型候选能避免“单模型波动导致业务不可用”。
- 首包探测提前识别慢/异常模型，降低长尾延迟。
- 三态熔断（OPEN/HALF_OPEN/CLOSED）保护系统整体吞吐。

### 对比替代
- 对比固定单模型：链路简单但故障域集中。
- 对比纯随机负载：缺少健康反馈，错误传播快。

### 优势总结
- 更强可用性与降级能力，尤其适合面试高峰期与第三方模型波动场景。

---

## 6. RocketMQ 事件总线（A2A Bus）

### 为什么采用
- 任务状态与异步事件解耦，提高系统弹性。
- 便于扩展更多异步消费者（画像更新、审计、离线评估）。

### 对比替代
- 对比应用内同步调用：实现简单但强耦合，故障扩散快。
- 对比无消息总线：难做异步削峰与链路解耦。

### 优势总结
- 更利于系统演进为可观测、可扩展、可分层治理的 AI 应用。

---

## 四、面试复习建议（答题模板）

可按“六段法”描述你的系统：
1. 入口标准化：多渠道统一消息模型 + 幂等。
2. 语义路由：会话优先 + 意图树 + 澄清机制。
3. 编排执行：TaskRouter + 多 Agent 分层协作。
4. RAG 检索：改写 + 三路混合 + 融合重排 + Web 兜底。
5. 生成容错：Prompt 模板化 + 多模型路由 + 熔断降级。
6. 数据闭环：ETL 入库 + 增量同步 + Parent-Child 检索优化。

用这六段法，你可以把“为什么不是单一路径方案”讲清楚，并体现工程化思维。
