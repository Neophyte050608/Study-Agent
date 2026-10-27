# Eval Dataset Split

当前 `eval` 目录下的数据集分为两类：

- 默认全集
  - `rag_ground_truth.json`
  - `rag_quality_ground_truth.json`
- 分层黄金集
  - `rag_ground_truth_baseline.json`
  - `rag_ground_truth_advanced.json`
  - `rag_ground_truth_project.json`
  - `rag_quality_ground_truth_baseline.json`
  - `rag_quality_ground_truth_advanced.json`
  - `rag_quality_ground_truth_project.json`

三档定义：

- `baseline`
  - 面向基础知识召回与基础回答质量。
  - 侧重 Java / MySQL / JUC / JVM / 网络基础，以及少量基础 AI 概念。
- `advanced`
  - 面向中高级技术方案理解。
  - 侧重 RAG 切分、RRF、Agent 设计、多级缓存、长对话记忆等架构能力。
- `project`
  - 面向项目经验表达和项目知识召回。
  - 侧重实习产出中的责任链、装饰器、批量处理、大 Excel 导入等实战方案。

建议使用方式：

- 做回归基线时先跑 `baseline`
- 做架构优化或提示词优化时跑 `advanced`
- 做项目问答、面试模拟、知识库贴合度验证时跑 `project`


## 隔离检索基准

`knowledge.application.evaluation.goldset` 接受数据集无关的 UTF-8 JSONL 查询集与语料集，提供数据校验、
本地 Okapi BM25 基线、隔离向量索引以及结构化对比报告：

- 查询集每行是一个 JSON 对象，至少包含非空 `query_id`、`question` 和 `ground_truth`；
  `ground_truth` 保存相关 `chunk_id`，可选 `relevance` 保存分级相关性。
- 语料集每行是一个 JSON 对象，至少包含非空 `chunk_id` 和 `content`。
- 比较使用相同 `query_id`、ground truth、relevance 与 Top-K，汇总 Recall@5/10/20、
  HitRate@5/10/20、MRR、NDCG@10 及其差值。
- 解析与校验会拒绝重复标识、空问题、空 ground truth 和不存在于语料集的 chunk 引用；错误会带物理行号，
  不会静默丢弃样本。

运行入口默认关闭。启用后一次启动只执行 `VALIDATE`、`INDEX`、`COMPARE`、`CLEAN` 中的一个显式操作。
数据集由 `dataset-id` 标识；语料指纹作为 dataset version，向量数据按 dataset ID、version 与 embedding
model ID 隔离，不写入正常知识库 collection。

### VALIDATE：只校验文件

下面的操作只读取并校验 JSONL，不访问 Milvus、embedding provider、checkpoint 或报告文件，也不需要
API Key：

```bash
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_ENABLED=true \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_OPERATION=VALIDATE \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_DATASET_ID=generic-rag \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_GOLD_FILE=/absolute/path/gold.jsonl \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_CORPUS_FILE=/absolute/path/corpus.jsonl \
mvn spring-boot:run
```

### INDEX：建立隔离向量索引

`INDEX` 会调用配置的 embedding provider，并可能产生调用费用。真实语料执行前，必须先估算 chunk 数、
批次数、耗时和费用，并获得单独确认。未经确认只运行单元测试中的 fake/mock embedding，不执行此命令：

```bash
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_ENABLED=true \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_OPERATION=INDEX \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_DATASET_ID=generic-rag \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_GOLD_FILE=/absolute/path/gold.jsonl \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_CORPUS_FILE=/absolute/path/corpus.jsonl \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_CHECKPOINT_FILE=/absolute/path/generic-rag-checkpoint.json \
mvn spring-boot:run
```

断点文件记录已完成批次；重复运行会校验数据集身份、语料指纹和 embedding 模型，避免把不兼容数据混入
已有索引。智谱 embedding Key 只放在仓库根目录、已被 Git 忽略的 `application-private.yml` 中，例如
`spring.ai.zhipuai.api-key: ${ZHIPU_API_KEY:}`；不要写进命令、公共配置、日志或提交记录。

### COMPARE：隔离向量检索与 BM25 对比

`COMPARE` 要求同一数据集已经成功完成 `INDEX`，读取 checkpoint 后运行两条固定通道：
`isolated-vector/<model-id>` 与本地 `bm25`。BM25 本身完全本地且不需要 API Key；隔离向量通道会为查询
生成 embedding，因此仍可能调用 provider 并产生费用。成功时报告通过临时文件原子替换，失败不会覆盖旧报告：

```bash
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_ENABLED=true \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_OPERATION=COMPARE \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_DATASET_ID=generic-rag \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_GOLD_FILE=/absolute/path/gold.jsonl \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_CORPUS_FILE=/absolute/path/corpus.jsonl \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_CHECKPOINT_FILE=/absolute/path/generic-rag-checkpoint.json \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_OUTPUT_FILE=/absolute/path/retrieval-comparison.md \
mvn spring-boot:run
```

报告只比较 **isolated-vector vs BM25**，不经过 `RAGService`，也不代表完整生产 hybrid retrieval 链路、
GraphRAG、融合、重排或 parent-child hydration 的质量。

### CLEAN：按数据集清理

`CLEAN` 不读取查询集或语料集，只删除匹配 `dataset-id` 的隔离向量数据。为了防止误删，
`cleanup-confirmation` 必须与 `dataset-id` 完全相同：

```bash
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_ENABLED=true \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_OPERATION=CLEAN \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_DATASET_ID=generic-rag \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_CLEANUP_CONFIRMATION=generic-rag \
mvn spring-boot:run
```

该操作不会删除 collection，也不会影响其他 dataset ID、正常知识库向量或本地输入文件。
