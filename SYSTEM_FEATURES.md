# 系统功能文档 (System Features)

本文档用于记录当前 `InterviewReview` 项目的主要系统功能、核心架构与关键模块机制。

> **注意**：每当 AI 助手添加、修改或重构了相关系统功能后，都必须同步更新此文档。

***

## 16. 检索评测趋势、失败聚类与参数模板（2026-03-26）
**新增职责**：
- `RetrievalEvaluationService` 新增趋势聚合能力，可对最近若干次评测运行计算 `avgRecallAt5`、`avgMrr`、`bestRecallAt5` 以及实验标签分布，帮助快速判断一组调参是否整体变好。
- 新增失败样本聚类能力，可按评测 `tag` 聚合单次运行中的失败样本，并返回失败数量、代表问题与代表关键词，便于快速定位哪一类主题回退最明显。
- 新增内置参数模板能力，后端统一提供 `baseline-rrf`、`high-rerank`、`fallback-observe` 三类实验模板，减少前端硬编码实验参数的成本。
- 控制层补充 `GET /api/observability/retrieval-eval/trend`、`GET /api/observability/retrieval-eval/runs/{runId}/failure-clusters`、`GET /api/observability/retrieval-eval/templates` 三个接口，形成“历史运行 -> 趋势观察 -> 失败定位 -> 参数复用”的调优闭环。

## 17. Prompt 去文件化与数据库单一数据源（2026-04-02）
**新增职责**：
- `PromptManager` 已移除对 `src/main/resources/prompts.txt` 与 `src/main/resources/system-prompts.txt` 的运行时依赖，模板加载改为仅从 `t_prompt_template` 读取并缓存。
- 新增关键 TASK 模板完整性校验，系统启动阶段会校验 `task-router`、`intent-tree-classifier`、`intent-slot-refine`、`evaluation`、`knowledge-qa` 等主链路模板，缺失时抛出明确错误并输出模板名清单。
- `POST /api/settings/prompts/reload` 在模板缺失场景下会返回结构化失败响应，便于管理端快速定位并补齐数据库模板。
- 资源文件 `prompts.txt` 与 `system-prompts.txt` 已删除，后续 Prompt 维护统一通过数据库与提示词管理接口进行，不再执行文件自动迁移。

## 18. 意图树 Few-shot 可见化与数据库持久化（2026-04-02）
**新增职责**：
- `IntentTreeView` 的“叶子意图”页已补齐 `examples` 与 `slotHints` 可编辑区域，并在保存时按行转数组，读取时自动回填，形成前端可视化编辑闭环。
- `IntentTreeController` 已补齐 `path` 回填与 `parentCode` 解析逻辑，叶子意图保存由“全量删除重建”改为“差量 upsert + 未提交节点下线”，降低逻辑删除与唯一键冲突风险。
- 新增 `t_intent_slot_refine_case` 表与 `IntentSlotRefineCaseService`，`slotRefineCases` 改为数据库持久化读写，服务重启后不再丢失。
- `IntentTreeRoutingService` 的槽位精炼样例加载已接入数据库样例服务，`/api/intent-tree/stats` 的 `slotRefineCaseCount` 也改为数据库口径，确保管理页统计与运行链路一致。

## 19. 意图树前端三页管理对齐（2026-04-02）
**新增职责**：
- 前端 `IntentTreeView` 已从“单页 tab 配置”升级为“树结构浏览 + 节点详情 + 快捷操作”形态，支持通过 `intentCode` 查询参数定位节点并展开路径。
- 新增 `IntentListView` 列表页，支持关键词、任务类型、启用状态筛选，支持分页查看、批量下线与行级“定位树页 / 编辑页”操作。
- 新增 `IntentEditView` 独立编辑页，支持 `intentId/name/taskType/path/examples/slotHints/enabled` 全字段编辑与唯一性校验，保存后可按来源路由回跳。
- 新增 `intentTreeAdmin.js`、`useIntentTreeAdmin.js` 与 `intentTreeTransform.js` 等前端适配层，统一处理 `leafIntents` 扁平化、路径分段、`examples/slotHints` 文本数组互转。
- 在保持后端 `GET/POST /api/intent-tree/config` 与 `GET /api/intent-tree/stats` 不变的前提下，批量启停采用“本地变更 + 全量提交配置”的兼容策略实现。

## 20. RAG 全链路观测字段对齐修复（2026-04-02）
**修复职责**：
- 运维页“系统全链路观测”的 RAG 轨迹表出现数值全为 0 的问题，已在前端完成契约对齐与度量派生。
- 列表项耗时字段由后端的 `durationMs` 对齐为前端展示的 `latencyMs`；召回数量由节点 `metrics.retrievedDocs` 聚合得到。
- 保持后端接口不变（`GET /api/observability/rag-traces`、`GET /api/observability/rag/overview`），仅在 `OpsView.vue` 的 `reload` 映射阶段进行修复。
- 后续如需展示相似度或 tokens 指标，将在后端补充统一输出字段后再接入前端。

## 21. RAG 实时追踪与活动态接口（2026-04-02）
**新增职责**：
- 新增 **SSE 实时追踪接口**：`GET /api/observability/rag-traces/{traceId}/stream`，输出事件流（`trace_started/node_started/node_finished/trace_finished/trace_failed`），用于前端甘特图的实时更新。
- 新增 **活动态链路接口**：`GET /api/observability/rag-traces/active?limit=20`，返回当前运行中的 Trace 列表与节点快照，便于全局实时面板展示。
- 增强 **观测概览接口**：`GET /api/observability/rag/overview` 追加 `p95LatencyMs/successRate/failedTraceCount` 字段，统一高阶指标口径。
- 新增组件 **RagTraceEventBus**（进程内发布订阅）与 **RagTraceStreamController**（SSE 控制器），并在 `RAGObservabilityService` 的节点开始/结束与根节点归档阶段注入事件发布钩子。

**兼容策略**：
- 不破坏既有列表与详情接口，仅追加能力；SSE 推送异常不会影响主链路执行。
- `successRate` 为字符串百分比（如 `86.5%`），`p95LatencyMs` 与 `failedTraceCount` 为数值型，前端可直接渲染卡片。

## 22. RAG 运维页超时误判口径修复（2026-04-02）
**修复职责**：
- 运维页 `OpsView` 已移除 `durationMs > 1000` 即标记 `TIMEOUT` 的前端兜底逻辑，避免将正常慢链路误判为超时失败。
- RAG 列表状态改为与后端 `traceStatus` 对齐：`COMPLETED -> SUCCESS`、`FAILED -> FAILED`、未知状态回落 `UNKNOWN`；同时引入 `SLOW` 作为独立性能告警态，不计入失败统计。
- 成功率与 P95 指标改为优先使用后端概览接口（`/api/observability/rag/overview`）返回值，仅在字段缺失时才启用前端兜底计算。
- 详情页 `RagTraceDetailView` 的链路状态展示已同步对齐列表口径，避免“列表成功/详情完成”或“列表超时/详情完成”这类语义冲突。
- 新增慢链路阈值环境变量 `VITE_RAG_SLOW_THRESHOLD_MS`（默认 `20000`），用于前端统一判定 `SLOW` 展示。
- 补充 `traceStatus` 缺失场景的节点级兜底推断：当前端收到旧历史数据或缺字段响应时，会基于节点状态与耗时推断 `FAILED/RUNNING/SLOW/SUCCESS`，避免列表批量显示 `UNKNOWN`。
- 运维页已下线“相似度均值”列（后端未产出 `score`，长期为 `-`），并将 A2A 幂等与危险操作区调整为“高级运维工具”默认收起，降低日常观测噪音。

## 1. 核心业务功能

### 1.1 智能面试编排与评估 (Interview System)
本系统作为 AI 驱动的技术面试官，支持全流程的模拟面试与专业评估。
- **面试流程总控 (`InterviewOrchestratorAgent`)**：协调四层架构（决策、知识、评估、成长）进行面试推进。
- **动态追问机制**：支持基于候选人的回答进行深挖追问。
- **最终复盘报告**：面试结束后，生成包含综合评价、薄弱点、不完整点、误区以及下一步学习建议的最终报告。

### 1.2 沉浸式算法刷题训练 (Coding Practice)
专门针对编程和算法面试设计的刷题与评估模块。
- **意图识别对话流 (`CodingPracticeAgent`)**：自动提取技术栈、题型、难度和数量。
- **智能出题**：结合用户历史画像推荐薄弱点相关的算法题。
- **代码评估与异步画像更新**：通过 RAG 服务评估代码，并在后台异步同步至学习画像系统。

### 1.3 IM 渠道统一接入 (IM Integration)
将系统能力输出到飞书、QQ 等即时通讯平台，实现“随时随地、对话即服务”。
- **多渠道接收支持**：
  1. **飞书 Webhook 模式 (`FeishuWebhookController`)**：支持标准的 HTTP 回调机制，需公网 IP 或域名映射。
  2. **飞书长连接模式 (`FeishuWsService`)**：基于飞书 WebSocket (Socket Mode) 接入，无需公网域名。
  3. **QQ 长连接模式 (`QqWsService`)**：基于 QQ 官方 WebSocket API 接入，支持沙箱与正式环境无缝切换，处理私聊与群聊（@）消息。
- **QQ 鉴权令牌策略 (`QqAuthTokenProvider`)**：优先使用 `appId + appSecret` 调用官方接口获取 `access_token`（`QQBot` 方案），在失败时自动回退到本地 token 兼容模式，适配“无独立 token”场景。
- **QQ 事件与回包兼容策略**：`QqWsService` 采用复合 `intents` 订阅并记录事件类型；`QqReplyAdapter` 会按会话类型自动选择 `users/groups/channels` 不同回复通道，保障私聊与群聊的回包可达。
- **统一模型分发**：不同渠道的消息均由对应的解析器（如 `FeishuEventParser`, `QqEventParser`）转化为 `UnifiedMessage`，并交由统一的 `ImWebhookService` 分发给大模型意图识别。
- **ReAct 模式增强**：通过大模型自主推理用户意图，支持模拟面试、算法训练与计划查询的自动切换。
- **题目生成精准化**：`RAGService` 会根据传入的主题和类型（选择、填空、算法）动态调整提示词，确保生成的题目格式符合用户预期。
- **意图细分逻辑**：支持细粒度的意图识别，能够准确区分“选择题”、“填空题”、“算法题”与“模拟面试”。
    - **算法训练 (`CodingPracticeAgent`)**：支持 `ALGORITHM` (算法)、`CHOICE` (选择)、`FILL` (填空) 三种题目类型。
    - **面试编排 (`InterviewOrchestratorAgent`)**：专注于多轮次的综合素质评估与深度追问。
- **活跃会话追踪**：IM 渠道现在支持追踪活跃的面试会话。当用户处于面试中时，后续输入将自动判定为答题意图，无需重复识别，极大地提升了对话的连贯性。
- **新用户友好化**：优化了画像缺失时的提示逻辑，对于初次使用的用户提供引导性建议而非报错，确保“零门槛”开启面试体验。
- **消息标准化 (`FeishuEventParser`)**：将飞书私聊/群聊消息转换为系统内部统一模型 `UnifiedMessage`，包含自动清洗 @ 占位符逻辑，支持驼峰与下划线字段名兼容。
- **会话记忆 (`ImConversationStore`)**：基于 Redis 滑动维护 IM 端的对话上下文，支持多轮对话理解。
- **异步回复 (`FeishuReplyAdapter`)**：任务处理完成后，通过飞书官方 API 异步将结果推回给用户。
- **回复格式化优化**：自动过滤技术元数据（如 Agent 名称、SessionID 等），仅向终端用户展示核心业务内容（如题目、评分、反馈），显著提升了 IM 交互的“人类可读性”。
- **IM 事件幂等原子化优化（2026-03-26）**：`ImWebhookService` 新增基于 Redis `SET NX EX` 的原子事件占位能力，`FeishuWebhookController`、`FeishuWsService`、`QqWsService` 已切换为“抢占处理权成功才分发”，减少 Webhook/长连接并发场景下的重复消费窗口。

***

## 2. 核心技术支撑模块

### 2.1 检索增强生成引擎 (RAG Service)
- **混合检索策略 (Hybrid Retrieval)**：结合向量检索、本地倒排词法检索以及 **Neo4j 图谱检索 (GraphRAG)**。
- **双通道并行检索（2026-03-24）**：`RAGService` 已升级为“意图定向通道（按 `knowledge_tags/file_path` 聚焦）+ 全局向量通道”并行检索，随后统一进入 RRF 去重融合与重排序流水线，兼顾精准召回与全局兜底。
- **结构化优先与递归分块（2026-03-25）**：`DocumentSplitter` 已升级，由固定 Token 长度切分转为 `STRUCTURE_RECURSIVE_WITH_METADATA` 组合策略。系统首先基于 Markdown 标题解析逻辑段落 (Section)，再通过递归分隔符进行二次切分，最后在 Chunk 头部注入文档名、章节路径等轻量级元数据。这极大地减少了代码块和句子的断裂，并解决了长文档在检索时的“主语缺失”问题。
- **Parent-Child 检索主链路（开发版直切，2026-03-25）**：新增 `t_rag_parent` / `t_rag_child` 双层索引模型与 `ParentChildIndexService`，入库阶段会按 `parent_id -> child_id` 建立映射并持久化；检索阶段 `RAGService` 先召回 Child，再批量回填 Parent 文本参与重排与证据构建，evidence 中保留 `parent/child` 命中信息，支持开发态 `fallback-to-flat-retrieval` 快速回退。
- **Parent-Child 全量重建入口（2026-03-25）**：新增 `POST /api/ingestion/reindex/parent-child` 触发重建接口与 `GET /api/ingestion/reindex/parent-child/report` 报告接口，调用 `IngestionService.forceReindexParentChild` 后可直接获取 `parentCount/childCount/avgChildrenPerParent` 统计；同时提供脚本 `InterviewReview/reindex-parent-child.ps1` 支持一键触发并落盘报告。
- **无迁移重建流程已验证（开发环境，2026-03-25）**：收口策略调整为“删除旧索引并通过同步接口全量回灌”，不再执行历史 flat 数据迁移；已完成一轮实操验证（先触发旧索引删除，再调用 Parent-Child 重建接口），并确认 `parent/child` 统计与关键测试通过。
- **知识库同步与简历上传容错优化（2026-03-25）**：`NotesView` 已补充“逐路径失败原因”透出，避免多路径同步时仅显示失败计数；`/api/resume/upload` 已将 PDF 限制统一为 20MB，并放宽 `application/octet-stream` 的 Content-Type 兼容，降低浏览器上传被误拒风险。
- **Neo4j 弃用告警降噪（2026-03-25）**：针对 Neo4j 驱动/SDN 在关系读写中触发的 `id(...)` 弃用提示，已将 `org.springframework.data.neo4j.cypher.deprecation` 日志级别下调为 `ERROR`，避免知识库同步时大量 `FeatureDeprecationWarning` 重复刷屏影响排障。
- **观测与评测开关（2026-03-24）**：新增 `app.observability.rag-trace-enabled` 与 `app.observability.retrieval-eval-enabled` 两个配置项；前者用于控制 RAG Trace 记录与关键观测日志输出，后者用于控制召回率离线评测接口与执行流程，关闭后将阻止评测运行并返回明确提示。同时新增运行时开关接口 `GET/PUT /api/observability/switches`，支持不改配置文件的在线开关控制。
- **HyDE 与 Self-RAG**：通过假设性文档嵌入提升语义匹配度，并引入自我反思机制减少模型幻觉。

### 2.2 全局学习画像系统 (Learning Profile)
- **统一数据整合**：汇总面试与刷题产生的 `LearningEvent`。
- **自适应权重融合**：支持时间衰减机制，动态计算用户的薄弱点与熟练点。

### 2.3 智能体协作总线 (A2A Bus)
- **异步协作架构**：通过事件总线解耦各 Agent，支持分布式追踪与任务状态观测。

### 2.4 未来演进计划 (Evolution Plan)
- **MCP 深度优化**：计划引入 `fastMCP4J` 实现注解驱动的工具定义，并接入 Neo4j/Milvus 原生 MCP 提升 RAG 自主性。详细路线请参考 [MCP_OPTIMIZATION_PLAN.md](file:///d:/Practice/InterviewReview/MCP_OPTIMIZATION_PLAN.md)。
- **Phase 1 已落地（2026-03-22）**：已完成 `fastmcp-java 0.4.0-beta` 依赖接入、新增 `app.mcp.fastmcp.*` 配置组、实现 `FastMcpCapabilityGateway`，并在 `McpGatewayService` 中支持 `mode=fastmcp` 与 `auto` 优先选路。
- **Token 优化已落地（2026-03-22）**：`McpGatewayService` 已支持文件读取参数标准化（`lineStart/lineEnd -> offset/limit`）与边界校验，非法参数将以 `MCP_INVALID_PARAMS` 返回，避免下游网关处理异常请求。
- **数据库 MCP 适配骨架已落地（2026-03-22）**：新增 `DatabaseMcpAdapter`/`DatabaseMcpAdapterRouter`，并提供 `Neo4jMcpAdapter`、`MilvusMcpAdapter` 占位实现，当前支持数据库能力命名规范与最小调用通路。
- **方案二专项规格已落地（2026-03-22）**：新增 `specs/mcp-phase2-native-db/spec.md`、`tasks.md`、`checklist.md`，明确可参考 Python 实现的 `mcp-neo4j` 通过 MCP 协议接入 Java 网关。
- **方案三专项规格已落地（2026-03-22）**：新增 `specs/mcp-phase3-milvus/spec.md`、`tasks.md`、`checklist.md`，明确 `mcp-server-milvus`（Python）可通过 MCP 协议接入 Java 网关并完成 Milvus 能力联动。
- **Dev FileScopeMCP 已落地（2026-03-22）**：新增 `application-dev.yml` 的 `app.mcp.filescope.*` 配置模板与开发接入文档 `specs/mcp-fastmcp4j/filescope-dev-guide.md`，用于本地依赖拓扑与影响面分析，不影响生产环境。
- **阶段验证记录已落地（2026-03-22）**：新增 `specs/mcp-fastmcp4j/validation-report.md` 汇总 fastMCP、Token 优化、数据库 MCP 的已验证项与主干编译阻塞项，便于后续回归收口。
- **测试环境注意项（2026-03-22）**：已在 JDK 23 下完成 MCP 关键回归并修复历史测试签名漂移；当前待补项为 `mode=fastmcp` 对真实 fastMCP 服务端的端到端能力验证。
- **QQ 机器人接入已落地（2026-03-22）**：基于统一消息模型 `UnifiedMessage`，完成了 QQ 长连接事件监听与回复的接入，使得项目支持多 IM 渠道的独立会话与分发。
- **树形意图分类专项规格已落地（2026-03-23）**：新增 `specs/intent-tree-clarification/spec.md`、`tasks.md`、`checklist.md`，明确引入树形意图分类、低置信主动澄清短路机制，并约束保持 IM 活跃面试会话优先答题逻辑不变。
- **树形意图分类后端首版已落地（2026-03-23）**：已新增 `IntentTreeRoutingService`、`IntentTreeProperties` 与意图决策模型，`TaskRouterAgent` 在 `taskType=null` 时优先走树分类并在低置信场景返回澄清问题；`ImConversationStore`/`ImWebhookService` 已支持澄清状态 TTL 存储与“回复编号后二次路由”闭环。
- **澄清后槽位补全已落地（2026-03-23）**：`ImWebhookService` 已在用户完成澄清选择后执行二次槽位抽取，支持 `CODING_PRACTICE` 的题型/难度/数量/主题补全，并保留原始 query 语义拼接后再进入任务路由，降低“仅回复编号”导致的参数缺失风险。
- **澄清后模型化槽位精炼已落地（2026-03-23）**：`IntentTreeRoutingService` 新增 `intent-slot-refine` 提示词驱动的轻量槽位提取能力；`ImWebhookService` 在规则补全后会合并模型提取结果，仅补齐缺失字段，提升澄清后 `topic/questionType/difficulty/count` 的命中率与稳定性。
- **槽位精炼 Few-shot 配置化已落地（2026-03-23）**：`IntentTreeProperties` 新增 `slotRefineCases` 配置结构，`IntentTreeRoutingService.refineSlots` 会按 `taskType` 加载配置化样例并注入 `intent-slot-refine` 模板，未配置时回退内置样例，便于后续在线调参与迭代。
- **意图树配置管理接口已落地（2026-03-23）**：新增 `IntentTreeController`，提供 REST 接口用于查询统计信息、在线修改意图树核心参数（阈值、开关）、叶子意图定义及槽位精炼示例，为前端管理页面提供后端支持。
- **意图树配置前端页面已落地（2026-03-23）**：新增 `intent-tree.html`，集成现有 `layout.js` 侧边栏布局，支持在线可视化管理意图树结构、置信度策略及 Few-shot 槽位精炼用例，实现“意图引擎”的可视化闭环。
- **模型路由与容错专项规格已落地（2026-03-23）**：新增 `specs/model-routing-failover/spec.md`、`tasks.md`、`checklist.md`，规划引入多模型候选、优先级调度、首包探测、三态熔断器与自动降级能力，作为后续统一模型高可用改造蓝图。
- **模型路由与容错后端首版已落地（2026-03-23）**：新增 `modelrouting` 组件（`ModelSelector`、`ModelHealthStore`、`ModelRoutingExecutor`、`FirstPacketAwaiter`、`RoutingChatService`）与 `app.model-routing.*` 配置；`RAGService`、`TaskRouterAgent`、`IntentTreeRoutingService` 已接入统一路由，实现多候选调度、三态熔断与自动降级，并在首题生成链路启用首包探测。
- **模型路由观测与演练能力已落地（2026-03-23）**：新增 `ModelRoutingController` 的 `/api/model-routing/stats` 运行态快照接口，输出熔断状态与降级/超时计数；并补充 `specs/model-routing-failover/fault-drill.ps1` 用于故障演练与回归校验。
- **模型路由监控指标口径修复已落地（2026-04-02）**：修复监控页与 `/api/model-routing/stats` 的字段契约错位问题；后端 `ModelHealthStore` 新增 `totalRequests/totalSuccessCount/totalFailureCount` 聚合指标与模型级 `requestCount/successCount/failureCount/lastFailureMessage` 明细，前端 `MonitoringView` 改为读取真实字段并直接展示 `routeFallbackCount`，避免右侧卡片因字段缺失长期显示 0。
- **前端模块化与业务页面迁移已落地（2026-03-24）**：新增 `frontend/` 前端工程（Vue3 + Vite），实现统一壳层与菜单驱动路由；`monitoring`、`notes`、`coding`、`profile` 已迁移为独立模块并分别接入原有后端 API；后端新增 `FrontendRouteController` 支持 `monitoring/knowledge/practice/profile` 旧 `.html` 入口兼容跳转与多路径转发到 `static/spa/index.html`，构建产物可通过 `npm run build:spring` 发布。
- **前端剩余管理模块迁移已落地（2026-03-24）**：`ops`、`settings`、`workspace`、`mcp`、`intent-tree` 已新增为 SPA 独立页面并接入对应后端接口；`MenuConfigService` 已补充旧路径归一（如 `*.html -> 新路由`）与缺省菜单补齐能力，`FrontendRouteController` 已新增上述页面旧入口重定向，确保历史链接与菜单配置统一指向 SPA 路由。
- **前端跨域与错误解析容错优化（2026-03-25）**：`app.security.allowed-origins` 已补充 `localhost/127.0.0.1:5173` 以适配 Vite 开发端口；前端 `httpPostJson/httpPostFormData` 已增加非 JSON 响应容错，避免后端返回文本错误（如 CORS 拒绝）时触发 `Unexpected token` 二次异常。
- **启动安全兜底与上传限制一致性修复已落地（2026-03-26）**：`SecurityConfig` 已调整为仅在配置 `app.security.jwt-secret` 时启用 JWT Resource Server，避免开发/测试环境因缺失密钥直接启动失败；`WebCorsConfig` 改为真正使用 `app.security.allowed-origins` 白名单而非放开全部来源；同时将 `spring.servlet.multipart.*` 与简历上传接口统一到 20MB，保证服务端限制、接口提示与异常文案一致。
- **RAG 观测统计结构化优化已落地（2026-03-26）**：`RAGObservabilityService` 已为检索节点补充结构化 `retrievedDocs/webFallbackUsed` 指标，概览统计不再依赖脆弱的字符串解析；同时链路总耗时改为优先按根节点计算，避免节点插入顺序变化导致观测失真。
- **RAG Trace 持久化与详情回放已落地（2026-03-26）**：新增 `t_rag_trace` 与 `t_rag_trace_node` 两张观测表、对应 DO/Mapper 以及迁移脚本；`RAGObservabilityService` 现支持在根节点结束时归档整条 Trace，并在“数据库历史 + 内存活动态”之间合并查询 `listRecent/getTraceDetail`。同时 `RAGService` 已为 `processAnswer/buildKnowledgePacket/evaluateWithKnowledge` 增加根 Trace 包装，避免子节点独立归档导致链路碎片化；控制层新增 `GET /api/observability/rag-traces/{traceId}` 可按 Trace ID 查看完整节点明细。
- **检索评测留档与 A/B 对比能力已落地（2026-03-26）**：新增 `t_retrieval_eval_run` 与 `t_retrieval_eval_case` 两张评测留档表、对应 DO/Mapper 与迁移脚本；`RetrievalEvaluationService` 现会在每次执行默认评测、自定义评测或 CSV 上传评测后自动生成 `runId` 并持久化汇总指标与逐样本结果，同时保留内存历史兜底。后端新增 `GET /api/observability/retrieval-eval/runs`、`GET /api/observability/retrieval-eval/runs/{runId}`、`GET /api/observability/retrieval-eval/compare` 三个接口，支持评测历史列表、单次详情回放与两次运行的 Recall/MRR 差异对比，为后续检索参数调优提供可留档基线。
- **默认评测集与实验元数据能力已扩展（2026-03-26）**：新增 `src/main/resources/eval/rag_ground_truth.json` 正式默认评测集，覆盖 50+ 条高频技术问题，默认评测不再退化为 3 条兜底样本；同时检索评测运行已支持 `experimentTag/parameterSnapshot/notes` 三类实验元数据，`POST /api/observability/retrieval-eval/run` 可直接携带参数快照与实验备注，后续评测历史与 A/B 对比接口都会一并回显，为调参实验保留完整上下文。
- **RAG 第一阶段检索治理已推进至 FULLTEXT 方案（2026-03-26）**：新增 `RetrievalTokenizerService` 统一词法检索、意图抽词与 RAG 重排的分词口径；`RetrievalEvaluationService` 已切换为复用 `RAGService.buildKnowledgePacket(..., false)` 主检索链路，评测时显式关闭 Web fallback，减少离线评测与线上召回口径分叉；同时新增 `app.rag.retrieval.lexical-search-mode`、`web-fallback-mode` 与 `web-fallback-quality-threshold` 配置，`LexicalIndexService` 已支持 `AUTO/FULLTEXT/LIKE` 三种词法召回模式，默认优先 FULLTEXT 并在 SQL 不兼容或索引缺失时自动回退 LIKE；仓库已补充 `sql/migrations/20260326_add_fulltext_index_to_lexical.sql` 与 `schema.sql` 中的 `ft_lexical_text` 索引定义。
- **Parent-Child 回填已升级为“父文上下文 + 命中片段”拼接模式（2026-03-26）**：`RAGService` 在 parent-child 检索回填阶段不再直接用整段 parent 文本覆盖 child，而是优先抽取 child 命中正文，再围绕命中位置裁剪 parent 上下文窗口，最终以 `【父文上下文】 + 【命中片段】` 的结构参与重排与提示词构建；同时在 metadata 中补充 `hydration_mode/parent_context_excerpt/child_match_excerpt/evidence_snippet`，让 retrieval evidence 能明确区分“局部证据”和“章节语境”。
- **GraphRAG 描述型召回已落地（2026-03-26）**：新增 `TechConceptSnippetView` 作为图谱轻量投影视图，`TechConceptRepository` 现返回 `name/description/type` 三元信息；`RAGService` 会将图谱邻居概念拼装成“概念名 + 类型 + 描述”的自然语言上下文，并写入 `graph_anchor/evidence_snippet` metadata，减少旧实现仅返回概念名列表导致的语义稀薄问题。
- **面试 Web 流式输出首版已落地（2026-03-25）**：新增 `InterviewStreamController` 与 `InterviewStreamingService`，提供 `POST /api/interview/stream/start|answer|report|stop` 四个接口；后端基于 `SseEmitter` 输出 `meta/progress/message/finish/cancel/error/done` 事件并支持 `streamTaskId` 取消闭环，前端新增 `stream.js` 流解析器并改造 `InterviewView.vue` 实现题目、反馈、报告的增量渲染与停止生成能力，同时保留原 `/api/start|answer|report` 同步接口兼容模式。
- **刷题题型与意图树对齐修复已落地（2026-03-25）**：`CodingPracticeAgent` 已改为按用户输入显式识别并规范 `选择题/填空题/场景题/算法题`，不再默认强制“算法”；`RAGService` 已按题型动态注入 Prompt 参数并仅在算法题启用 `coding-interview-coach` 技能，避免选择/填空/场景请求被算法教练身份覆盖；`IntentTreeServiceImpl` 与 `IntentTreeRoutingService` 已补齐 `CODING.PRACTICE.CHOICE/FILL/ALGORITHM/SCENARIO` 叶子意图基线，支持数据库未完整配置时自动补全并与当前刷题流程保持一致。
- **知识入库 ETL 专项规格已落地（2026-03-24）**：新增 `specs/knowledge-ingestion-etl/spec.md`、`tasks.md`、`checklist.md`，系统化梳理当前 `IngestionService` 主导的 Markdown 入库链路，并规划向 `Pipeline + Task + NodeLog` 的节点编排式 ETL 演进，覆盖本地目录、浏览器上传、增量同步、向量索引、词法索引与 GraphRAG 同步等核心语义。
- **知识入库任务化观测首版已落地（2026-03-24）**：新增 `ingestion` 领域骨架（`IngestionTaskService`、`IngestionTaskSnapshot`、`IngestionNodeLog` 与状态/阶段枚举），`/api/ingest` 与 `/api/ingest-files` 已返回 `taskId/taskStatus`，并新增 `GET /api/ingestion/tasks`、`GET /api/ingestion/tasks/{taskId}` 用于查询任务与节点日志，作为从固定链路向 Pipeline 编排演进的第一步。
- **知识入库管道注册与查询能力已落地（2026-03-24）**：新增 `IngestionPipelineProperties` 与 `IngestionPipelineRegistry`，支持按 `sourceType` 加载可配置管道（本地目录/浏览器上传）并校验启停状态；`IngestionTaskService` 已基于管道阶段生成任务节点日志，后端新增 `GET /api/ingestion/pipelines` 用于管理端查询当前生效管道定义。
- **知识入库阶段驱动执行已落地（2026-03-25）**：`IngestionTaskService` 已升级为按管道阶段顺序执行并逐阶段记录 `SUCCESS/FAILED` 日志，`SYNC_MARK/LEGACY_SYNC` 阶段执行真实同步；同时 `FETCH` 阶段已支持本地目录扫描计数与上传文件（`.md + ignoreDirs`）计数，为后续节点解耦提供可观测基线。
- **知识入库任务筛选与聚合观测已落地（2026-03-25）**：`GET /api/ingestion/tasks` 已支持 `sourceType/status` 条件筛选；`/api/observability/ingest/stats` 已融合任务运行态概览，补充最近任务总数与成功/部分成功/失败/运行中分布，便于运维侧快速判断入库健康度。
- **知识入库阶段节点抽象已落地（2026-03-25）**：新增 `IngestionStageNode`、`IngestionStageNodeRegistry`、`IngestionExecutionContext` 与 `IngestionStageResult`，`IngestionTaskService.runPipeline` 已由 `switch` 逻辑切换为节点注册表驱动执行，为后续替换 `PARSE/CHUNK` 等真实节点实现提供扩展位。
- **知识入库 PARSE/CHUNK 真实节点已落地（2026-03-25）**：`IngestionStageNodeRegistry` 中的 `PARSE` 与 `CHUNK` 阶段已从透传升级为真实计数节点，分别基于 `ObsidianKnowledgeExtractor` 与 `DocumentSplitter` 统计结构化文档数与切块数；`IngestionExecutionContext` 新增阶段计数懒加载缓存，`IngestionTaskService` 已接入本地目录与上传文件两类数据源的真实阶段计数供应器。
- **知识入库 ENHANCE 与结构化节点日志已落地（2026-03-25）**：`ENHANCE` 阶段已升级为真实节点并接入阶段计数供应器；`IngestionNodeLog` 新增 `details` 结构化字段，阶段执行可输出 `parsedDocuments/chunks/newFiles` 等可解析元数据，便于后续前端与告警系统消费。
- **知识入库任务视图摘要已落地（2026-03-25）**：`GET /api/ingestion/tasks` 与 `GET /api/ingestion/tasks/{taskId}` 已输出 `stageOverview` 阶段摘要（耗时、输入输出、消息与 details），并支持 `includeNodeLogs` 开关按需回传完整节点日志，兼顾前端展示与接口负载控制。
- **知识入库瓶颈阶段观测已落地（2026-03-25）**：`stageOverview` 新增 `durationRatio` 字段用于单任务阶段耗时占比分析；任务视图与概览新增 `hotStages` 热点阶段排序，输出总耗时、均耗时与失败率，支持快速定位性能瓶颈与高风险阶段。
- **知识入库窗口化与分组观测已落地（2026-03-25）**：`/api/observability/ingest/stats` 已支持 `windowMinutes/sourceType/groupBySourceType` 参数，能够按时间窗口和数据源过滤最近任务，并输出 `hotStagesBySourceType` 分组热点阶段统计，便于对比本地入库与上传入库的瓶颈差异。
- **迁移残留清理首版已落地（2026-04-01）**：后端已完成学习画像链路收敛，`InterviewOrchestratorAgent` 与 `InterviewService` 不再依赖 `InterviewLearningProfileService`，统一改为复用 `LearningProfileAgent`；`InterviewController` 的 `/api/ingest/config` 已移除对根目录 `sync_config.json` 的文件读写硬依赖，改为基于 `app.ingestion.config.*` 的运行态配置；`RetrievalEvaluationService` 已删除过时兼容方法 `getHitRate()`，减少历史接口残留。
- **入库配置数据库持久化已落地（2026-04-01）**：新增 `t_ingest_config` 表及 `IngestConfigDO/Mapper/Service`，`/api/ingest/config` 已从“仅内存态保存”升级为数据库持久化读写；当数据库无记录时回退 `app.ingestion.config.paths` 与 `app.ingestion.config.ignore-dirs` 默认值，确保配置在重启后可恢复且具备可观测的一致来源；同时已删除根目录遗留 `sync_config.json` 文件，完成文件态配置收口。

## 13. 意图树与核心配置数据库迁移架构 (2026-03)
**重构职责**：
- 引入了 `MyBatis-Plus` 与 `MySQL` + `Redis` 混合存储架构。
- 将原来依赖于本地 JSON 文件（如 `intent_tree_configs.json`, `agent_configs.json`, `menu_configs.json`, `interview_sessions.json`）的数据迁移到了 MySQL 中（`t_intent_node`, `t_agent_config`, `t_menu_config`, `t_interview_session` 表）。
- 实现了 `DataMigrationRunner`，在系统启动时如发现数据库为空，自动将本地的 JSON 配置平滑迁移至数据库。
- 保留了 `IntentTreeProperties` 的基础属性加载，叶子意图和配置数据均通过 `IntentTreeService`, `AgentConfigService` 等服务使用 `@Cacheable` 和 `@CacheEvict` 进行 Redis 缓存管理。

## 14. 学习画像与知识库索引数据库迁移 (Phase 2) (2026-03)
**重构职责**：
- 将用户的学习画像（`learning_profiles_v2.json`）、词法索引（`lexical_index.json`）与同步索引（`sync_index.json`）迁移至 MySQL 数据库。
- 新增了 `t_learning_profile`、`t_learning_event`、`t_lexical_index` 和 `t_sync_index` 四张核心表，分别使用 `LearningProfileDO` 等对应 MyBatis-Plus 实体进行映射。
- `LearningProfileAgent` 移除了基于 JSON 文件的读写，转为使用 `@Cacheable` 和 `@CacheEvict` 结合 Redis 与 MySQL 管理用户热点画像。
- `LexicalIndexService` 和 `IngestionService` 改为通过数据库持久化文档元数据和词法倒排记录，支持 `LIKE` 模糊匹配或精确召回。
- `DataMigrationRunner` 新增了这三种历史数据的开机平滑迁移逻辑。

## 15. 面试会话JSON兼容性修复 (2026-03)
**修复职责**：
- `InterviewSession` 新增 Jackson 未知字段容错，支持忽略历史会话中的冗余字段，避免反序列化因字段漂移失败。
- `InterviewSession` 的 `averageScore` 计算属性已从会话持久化 JSON 中排除，避免再次写入并触发回读兼容问题。
- `DbSessionRepository.findById` 增加读取失败降级保护，遇到会话反序列化异常时记录错误并安全返回空结果，防止链路硬失败。

***

## 3. 开发与协作规范 (Rules)

为了保证项目的长期可维护性、安全性和一致性，所有开发活动（包括 AI 助手）必须严格遵守以下规则：

> **强制补充说明（2026-03-26）**：项目新增独立规则文件 `D:\Practice\InterviewReview\PROJECT_RULES.md`。后续所有代码编写、命令执行、架构重构与文档维护，均以该文件中的强制规则为最高优先级执行基线；如本节与独立规则文件存在表述差异，以 `PROJECT_RULES.md` 为准。

1.  **代码注释**：所有编写、修改的代码必须配有详细的**中文注释**（包括类、方法及关键逻辑块）。
2.  **操作安全**：**严禁执行任何破坏性命令**，如 `git reset --hard`、`rm -rf /` 等。撤销操作需使用安全手段。
3.  **文档同步**：每当新增功能、重构架构或引入关键依赖管理规则时，必须同步更新本 `SYSTEM_FEATURES.md` 文档。
4.  **技术栈一致性**：项目基于 **Java 21/23** 开发，充分利用 `record`、`switch expressions` 等现代语法特性。
5.  **依赖管理规则**：
    -   **Milvus & Protobuf 冲突**：由于 Milvus SDK 版本要求，必须强制指定 `protobuf-java` 版本为 **3.25.x** 或更高以解决 `NoSuchMethodError`。
6.  **Lombok 编译兜底**：对于关键配置类与核心消息模型（如 `FeishuProperties`、`UnifiedMessage`），允许保留显式 Getter/Setter 与显式日志对象，避免在注解处理异常时出现 `找不到符号` 编译错误。
7.  **提示词管理规范**：所有 AI 提示词（Prompts）必须通过 **Jinjava** 模板引擎进行渲染，并统一存放在数据库表 `t_prompt_template` 中集中管理，严禁在代码中硬编码。
8.  **Skill 与工具调用规范**：Skill 编写需遵循按需加载原则，不能每次全量加载。需要使用外部能力时，必须优先采用 Function Calling 进行工具调用。

***

*(最后更新时间：2026-04-02)*

## 16. 检索评测趋势、失败聚类与参数模板（2026-03-26）
**新增职责**：
- RetrievalEvaluationService 新增趋势聚合能力，可对最近若干次评测运行计算 vgRecallAt5、vgMrr、estRecallAt5 以及实验标签分布，帮助快速判断一组调参是否整体变好。
- 新增失败样本聚类能力，可按评测 	ag 聚合单次运行中的失败样本，并返回失败数量、代表问题与代表关键词，便于快速定位哪一类主题回退最明显。
- 新增内置参数模板能力，后端统一提供 aseline-rrf、high-rerank、allback-observe 三类实验模板，减少前端硬编码实验参数的成本。
- 控制层补充 GET /api/observability/retrieval-eval/trend、GET /api/observability/retrieval-eval/runs/{runId}/failure-clusters、GET /api/observability/retrieval-eval/templates 三个接口，形成“历史运行 -> 趋势观察 -> 失败定位 -> 参数复用”的调优闭环。
