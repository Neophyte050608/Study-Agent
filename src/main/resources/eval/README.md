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

