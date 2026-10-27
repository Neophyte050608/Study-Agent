# 数据集无关的隔离向量检索评测设计

## 1. 目标与决策

本轮把外部 chunk 语料的检索评测建设成通用能力，不为 Saber 或任何单一数据集编写专属适配器。Saber 的 `gold.txt` 和 `test.txt` 只是当前第一份符合统一 JSONL 契约的输入。

采用独立 Milvus collection 和数据集命名空间，第一版只比较：

- 候选：隔离向量检索；
- 基线：进程内 Okapi BM25。

报告必须明确标为 `isolated-vector` 与 `bm25`，不得使用 `production` 或 `hybrid`，因为本轮不覆盖正常知识库的词法索引、父子回填、Neo4j GraphRAG、融合和重排链路。

## 2. 设计原则

- **数据集无关**：生产代码、配置键、collection 和报告不出现 Saber 名称。
- **不污染业务索引**：评测语料不写入正常知识库 collection、MySQL 目录或 Neo4j。
- **精确可复现**：保留输入 `chunk_id`，命中只按 chunk ID 精确相等计算。
- **显式副作用**：索引、比较和清理均默认关闭，普通应用启动不调用 embedding API，也不操作评测 collection。
- **可恢复**：批量导入使用确定性主键和原子 checkpoint，进程中断后可以安全续跑。
- **诚实标注**：评测结果只代表指定数据版本、embedding 模型和向量检索配置。
- **小步落地**：先完成可测试的索引边界，再接真实 Milvus；最后才由用户确认是否执行大规模 embedding 调用。

## 3. 统一输入契约

### 3.1 查询 JSONL

每个非空行必须是 JSON object：

```json
{
  "query_id": "q-001",
  "question": "什么是 RRF？",
  "ground_truth": ["chunk-17", "chunk-23"],
  "relevance": {"chunk-17": 2, "chunk-23": 1},
  "category": "rag"
}
```

必填字段：

- `query_id`：数据集内唯一、非空；
- `question`：非空；
- `ground_truth`：至少包含一个 `chunk_id`。

`relevance` 和 `category` 可选。未知字段忽略，便于数据集携带来源信息。

### 3.2 语料 JSONL

每个非空行必须是 JSON object：

```json
{
  "chunk_id": "chunk-17",
  "content": "RRF 通过倒数排名融合多个召回列表……",
  "document_id": "doc-3",
  "title": "混合检索",
  "category": "rag",
  "url": "https://example.test/doc-3"
}
```

必填字段：

- `chunk_id`：数据集内唯一、非空；
- `content`：非空。

其余字段均为可选 metadata。第一版只索引 `chunk_id`、`content` 和运行身份，不把任意动态 metadata 映射成 Milvus schema。

### 3.3 数据身份

一次评测由以下字段唯一描述：

- `dataset_id`：调用方提供的稳定逻辑名，只允许字母、数字、点、下划线和连字符；
- `dataset_version`：系统对 corpus 文件原始字节计算的 SHA-256；
- `embedding_model_id`：当前 embedding 模型标识；
- `embedding_dimension`：向量维度。

查询集不参与 `dataset_version`，因此可以在同一 corpus 上替换 gold set；报告另行记录查询文件 SHA-256。

## 4. 组件边界

代码仍属于 Knowledge 模块，按现有职责放置：

```text
knowledge.application.evaluation.goldset
  已有输入记录、数据校验、指标、BM25、run comparator
  新增隔离向量评测编排与索引端口

knowledge.infrastructure.evaluation
  JSONL 文件入口、checkpoint 文件适配、显式运行器

knowledge.infrastructure.evaluation.milvus
  评测 collection schema、批量 upsert、按命名空间搜索与清理

config.knowledge
  评测配置属性
```

不创建跨模块公开契约，因为当前没有真实跨模块调用方；也不建设通用检索插件注册表。application 只依赖窄端口，Milvus SDK 和 `EmbeddingModel` 留在 infrastructure。

### 4.1 应用层端口

`IsolatedBenchmarkIndex` 表达四项能力：

1. `upsertBatch(identity, chunks)`：写入一批已验证 chunk；
2. `search(identity, query, topK)`：返回保留原始 `chunk_id` 的有序结果；
3. `count(identity)`：核验指定数据版本和模型的索引条数；
4. `delete(identity or datasetId)`：只清理明确命名空间。

端口不暴露 Milvus request、Spring AI `Document`、SDK response 或 collection schema。

`IsolatedBenchmarkCheckpointStore` 负责读取和保存 checkpoint。application 只看到 checkpoint record；文件路径、JSON 编解码、临时文件和原子替换属于 infrastructure。

### 4.2 索引编排

文件入口先完整解析并校验 query/corpus，确保任何外部调用前数据合法；随后把已验证的 chunk、数据身份和运行参数交给 `IsolatedBenchmarkIndexingService`。该服务：

1. 通过 checkpoint 端口读取并校验进度；
2. 按稳定输入顺序分批调用索引端口；
3. 每批成功后通过 checkpoint 端口保存进度；
4. 完成后通过 `count` 校验，标记 `COMPLETED`。

application 不直接读写文件，也不调用 Milvus SDK。corpus fingerprint 由文件适配器基于原始字节计算，并随命令传入 application。

### 4.3 比较编排

`IsolatedVectorBenchmarkService` 对每个 question 调用隔离索引的 `search`，复用现有 `GoldSetMetricsCalculator` 生成候选 run；BM25 继续复用 `GoldSetBm25Evaluator`。两个 run 交给已有 `GoldSetRunComparator`。

比较前必须确认：

- checkpoint 状态是 `COMPLETED`；
- checkpoint 的 dataset identity 与本次配置一致；
- `count` 与 corpus 的唯一 chunk 数一致。

任何条件不满足都停止比较，不输出部分报告。

现有直接调用正常 RAG 链路的 `GoldSetRetrievalEvaluator` 可保留用于“已在业务索引中存在相同 chunk ID”的内部回归，但通用外部语料文件入口不再调用它，也不再把结果命名为 `production`。

## 5. Milvus 隔离模型

默认 collection 使用通用名称 `study_agent_retrieval_benchmark`，允许通过配置覆盖。collection 只服务离线评测，不与 Spring AI 默认 VectorStore 共用。

建议 schema：

| 字段 | 类型 | 说明 |
|---|---|---|
| `benchmark_vector_id` | VarChar，主键 | `dataset_id + dataset_version + model_id + chunk_id` 的 SHA-256 |
| `dataset_id` | VarChar | 逻辑数据集命名空间 |
| `dataset_version` | VarChar | corpus SHA-256 |
| `chunk_id` | VarChar | 输入原始 chunk ID |
| `content_hash` | VarChar | chunk content SHA-256 |
| `embedding_model_id` | VarChar | embedding 模型标识 |
| `embedding` | FloatVector | corpus embedding |

向量检索必须同时过滤 `dataset_id`、`dataset_version` 和 `embedding_model_id`，避免不同语料版本或模型相互污染。collection description 记录 schema version 和向量维度；发现维度或 schema 不兼容时失败，不自动删除或重建已有 collection。

使用 COSINE 和 HNSW；具体 HNSW 参数沿用项目已有默认值，首版不做参数搜索。返回值只包含 `chunk_id`、rank 和 score，指标仍按 rank 与精确 ID 计算。

## 6. 幂等、续跑与版本演进

确定性主键保证同一批次重复 upsert 不产生重复记录。checkpoint 使用 JSON，至少记录：

- dataset identity；
- corpus 路径和 fingerprint；
- 总 chunk 数；
- 已完成 chunk 数；
- batch size；
- 状态 `IN_PROGRESS` 或 `COMPLETED`；
- 最近更新时间。

checkpoint 不保存 corpus 内容、向量或 API Key。写入流程采用“临时文件写完后原子替换”：

1. Milvus batch upsert 成功；
2. 写入临时 checkpoint；
3. 原子替换正式 checkpoint。

若在步骤 1 与 3 之间崩溃，恢复时最多重复 upsert 已成功批次，结果仍幂等。若 corpus fingerprint、模型或维度变化，旧 checkpoint 不可续用，系统创建新的数据版本；旧版本只通过显式清理移除。第一版不支持多个进程并发索引同一数据身份；启动时使用进程内互斥拒绝本进程内的重复任务，跨进程并发由操作约束禁止。确定性 upsert 保证误操作时不会制造重复主键。

## 7. 运行入口与安全门

沿用显式启用的 `ApplicationRunner` 模式，但增加通用 operation：

- `VALIDATE`：只解析、校验并输出摘要，不调用 embedding 或 Milvus；
- `INDEX`：创建/校验隔离 schema 并批量索引；
- `COMPARE`：运行隔离向量检索与 BM25，写 Markdown 报告；
- `CLEAN`：删除指定 `dataset_id` 的评测数据。

`enabled=false` 是固定默认值。`CLEAN` 还要求独立确认配置与 `dataset_id` 完全一致，禁止空 dataset ID、通配清理和自动 drop collection。

索引和比较分别执行，避免一次启动意外完成一万条 embedding 调用。`INDEX` 开始前日志只输出 dataset ID、fingerprint 前缀、chunk 数、batch 数和模型标识，不输出 corpus 内容或凭证。失败日志使用稳定错误码及安全摘要。

## 8. 报告口径

Markdown 报告新增运行元数据：

- candidate：`isolated-vector/<embedding_model_id>`；
- baseline：`bm25`；
- dataset ID 与 corpus fingerprint；
- query fingerprint；
- collection/schema version；
- embedding dimension；
- Top-K 与样本数。

指标继续使用 Recall@5/10/20、HitRate@5/10/20、MRR 和 NDCG@10。报告顶部明确写明：

> 本报告比较隔离向量检索与本地 BM25，不代表完整生产混合检索链路。

解析、校验、索引完整性或任一 run 失败时，不覆盖已有报告。输出仍使用临时文件加原子替换，避免留下半份结果。

## 9. 配置与密钥

新增或扩展的公开配置只提供安全默认值，不包含密钥。配置至少包括：

- operation；
- dataset ID；
- gold/corpus/output 路径；
- collection 名称；
- checkpoint 路径；
- batch size；
- embedding model ID 与 dimension；
- cleanup confirmation。

embedding API Key 继续放在仓库根目录未跟踪的 `application-private.yml`，不新增数据集专属 Key。只有执行 `INDEX` 或 `COMPARE` 且查询需要在线 embedding 时才要求可用的模型配置。`VALIDATE` 和 BM25 不需要 API Key。

## 10. 失败处理

以下情况必须在产生外部副作用前失败：

- JSONL 格式错误、重复 ID、空问题或空 content；
- ground truth 引用不存在；
- dataset ID 非法；
- 路径缺失或不可读；
- batch size 越界。

以下情况在访问基础设施时失败，并保留可恢复状态：

- collection schema/维度不兼容；
- embedding 返回空向量、数量不一致或维度错误；
- Milvus upsert/search/count 失败；
- checkpoint 与当前 identity 不一致；
- 索引 count 与 corpus 数量不一致。

异常消息不得包含输入正文、API Key、Authorization header 或上游完整响应。

## 11. 测试策略

按 TDD 小步实现：

1. **通用契约与配置**：覆盖数据身份、dataset ID、fingerprint 和 operation 安全默认值；
2. **索引编排**：用内存 fake 证明批处理顺序、checkpoint 恢复、重复 upsert、identity 变化拒绝续跑；
3. **向量评测**：证明 Top-20、chunk ID 精确匹配、过滤空结果及指标复用；
4. **Milvus adapter**：覆盖字段映射、过滤表达式、维度校验、安全错误映射；条件允许时增加容器集成测试；
5. **运行器**：证明默认不加载、各 operation 隔离、CLEAN 双重确认；
6. **文件输出**：证明失败不覆盖旧报告、成功原子替换并包含免责声明；
7. **架构约束**：Milvus SDK 不得进入 application，Controller/其他模块不得直接依赖评测 infrastructure。

完成后运行 focused tests、`ArchitectureRulesTest`、compile、test-compile、全量 test 和 verify。未启动 Milvus 时，真实 adapter 集成测试必须明确标记为条件跳过，不能伪称通过。

## 12. 分步交付

### S1：修正评测语义与通用契约

- 去掉文件入口中的 Saber 专属描述；
- 外部语料 runner 不再硬编码 `production vs bm25`；
- 增加数据身份、运行元数据和诚实报告标签；
- 不调用外部 API。

### S2：隔离索引 application 边界

- 增加窄端口、批处理编排和 checkpoint；
- 用 fake 完成 TDD；
- 不连接真实 Milvus，不产生 embedding 费用。

### S3：Milvus adapter

- 创建/校验通用评测 collection；
- 接入批量 embedding、upsert、search、count 和 dataset-scoped cleanup；
- 默认关闭，不执行真实全量导入。

### S4：真实数据验证

- 先运行 `VALIDATE`；
- 用户确认调用量、预计耗时和费用后再运行 `INDEX`；
- 索引完整后运行 `COMPARE` 并记录基线。

S1 至 S3 可以在不索引真实一万条 corpus 的情况下开发、测试和推送。S4 必须得到用户对外部 embedding 调用的单独确认。

## 13. 非目标

本轮不做：

- 数据集专属 parser、adapter、collection 或配置；
- 正常知识库导入；
- 全生产 HybridEvidenceRetriever 评测；
- Neo4j 图谱构建、父子 chunk 回填或 RRF 融合；
- embedding 模型、chunk 策略或 HNSW 参数调优；
- 评测 Web UI、HTTP API 或调度平台；
- 自动执行真实全量索引；
- 将评测能力抽成独立 Maven 模块或通用插件框架。
