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


## Chunk 级检索对比

`knowledge.application.evaluation.goldset` 提供 Saber 风格 JSONL 数据加载、生产检索评测、
本地 Okapi BM25 基线和结构化对比报告：

- 使用相同 `query_id`、ground truth、relevance 与 Top-K 比较两次运行。
- 汇总 Recall@5/10/20、HitRate@5/10/20、MRR、NDCG@10 及其差值。
- 逐题按 NDCG@10、Recall@20、MRR 的顺序判定胜负，并可渲染为 Markdown。
- BM25 基线不依赖数据库、向量库、模型服务或 API Key；生产检索仍依赖项目已配置的本地检索基础设施。

比较器会拒绝重复或不一致的 `query_id`、不同 Top-K、不同 ground truth，避免输出口径不公平的报告。


### 从 JSONL 文件生成对比报告

文件运行入口默认关闭，不影响正常启动。显式配置后，应用启动时读取 Saber 风格的
`gold.txt` 与 `test.txt`，运行生产检索和 BM25，并将 Markdown 报告写入指定路径：

```bash
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_ENABLED=true \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_GOLD_FILE=/absolute/path/gold.txt \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_CORPUS_FILE=/absolute/path/test.txt \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_OUTPUT_FILE=/absolute/path/retrieval-comparison.md \
mvn spring-boot:run
```

三条路径必须显式填写；输入使用 UTF-8 JSONL，输出目录会自动创建。生产检索需要项目原有
MySQL/Milvus/Neo4j 等已启用检索后端，但不需要额外的评测 API Key；BM25 本身仍完全本地运行。
