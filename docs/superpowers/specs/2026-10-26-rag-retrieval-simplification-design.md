# RAG 检索内核精简设计

## 已批准方向

六段理解（Catalog / Ingestion / Indexing / Retrieval / Grounding / Evaluation），三块落地；保持模块化单体。本轮只提取检索内核，不新增基础设施、不拆 Maven 模块、不建设通用插件平台。

## 当前事实

`KnowledgePacketBuilder` 已负责 query rewrite → 检索 → 图片 → 质量判断 → Web fallback；两个评测服务已复用 `RAGService.buildKnowledgePacket(..., false)`，不是另写检索算法。当前问题是 Builder 通过 `RetrievalDelegate` 回调 RAGService，召回、融合、父子回填和展示规则仍集中在兼容门面。

## 本轮职责

全部新增类放 `knowledge.application.retrieval`，使用具体实现，不新建唯一实现接口。

- `HybridEvidenceRetriever`：现有词法、向量、图谱三路并发召回；复用原线程池、异常隔离和图谱文本格式；调用融合及父子回填，保持 RRF → 追加图谱 → 回填 → 重排顺序。
- `EvidenceFusionService`：RRF 去重、渠道/来源权重、core/expand 重排、最佳质量分数。继续使用既有两种 tokenizer，避免改变评测口径。
- `GroundingContextAssembler`：父子文档回填、上下文拼接、证据目录、trace 文档摘要、图片去重筛选与视觉意图判定。父文读取继续复用 ParentChildIndexService，不新增 persistence port。
- `KnowledgePacketBuilder`：保留既有流程，直接组合三类及 trace 契约；删除 RetrievalDelegate 回调。trace root 仍归 RAGService，child 归 Builder。
- `RAGService`：保留公开知识包方法和内部 KnowledgePacket record、面试/学习相关既有方法；不继续承载检索算法。

## 兼容性约束

- 跨模块 KnowledgeRetrievalOperations/KnowledgePacket 和 HTTP API 不变。
- 内部仍允许 Spring AI Document；本轮不引入 RetrievedEvidence 大规模类型迁移。
- 通道排名、去重键、来源/词法加分、parent-child metadata 与证据编号保持原样。
- Web fallback 仍替换上下文和证据目录，保留本地 retrievedDocs / 图片；空 Web 结果仍标记 fallback 使用，不顺便改变语义。
- trace 类型、字段、root/child 顺序及既有线程池上下文传播机制不变。
- 不新增超时/重试策略、Outbox、Registry/Factory、事件、模型或服务配置。

## 防止过度设计

不新增 Engine 包装：Builder 已是编排入口。删除原 delegate，而非把新类叠在旧回调上。RAGService 未涉及的面试生成逻辑保持原样；不以强制行数目标迁移无关行为。

## 验证与后续

先锁定旧门面行为，再为三块增加直接测试及 ArchUnit 禁止边界回退。验证包括 focused tests、全量 mvn test、compile、test-compile、ArchitectureRulesTest、verify 和 diff check。后续 Catalog/Ingestion/索引一致性、durable projection、统一证据类型及删除兼容门面需另行评估，不属于本轮交付。
