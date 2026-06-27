# 面试答案评估链路拆分设计

## 背景

第一阶段已经把 `RAGService.buildKnowledgePacket(...)` 的知识包构建主链路拆到 `KnowledgePacketBuilder`，并抽出了 query rewrite、证据评估和 Web fallback 服务。`RAGService` 仍然保留大量非检索职责，其中最核心的是面试回答评估链路：`processAnswer(...)`、`evaluateWithKnowledge(...)`、证据引用校验、评估 prompt 构建、策略提示和 fallback 评估。

本阶段目标是继续减轻 `RAGService`，先拆出“面试答案评估”这一条完整业务链路，不同时迁移首题生成、最终报告、刷题和学习计划。

## 目标

- 新增独立服务承接面试答案评估链路。
- 保持现有 public API、DTO、Controller、Agent 调用行为不变。
- 让 `RAGService` 对评估能力只保留兼容委托入口。
- 为后续将 `EvaluationAgent` 直接依赖评估服务打基础。

## 非目标

- 不迁移首题生成、最终报告、刷题、学习计划。
- 不修改 HTTP API、数据库、Mapper 或前端契约。
- 不重命名 `RAGService.KnowledgePacket` / `RAGService.EvaluationResult`。
- 不引入新的 Agent Runtime 或 Mastra。
- 不重写评估 prompt、评分逻辑或证据引用规则。

## 目标结构

```text
knowledge.application
├── RAGService.java                         # 兼容门面：检索入口 + 评估委托
└── evaluation
    ├── InterviewAnswerEvaluationService.java   # 面试回答评估主链路
    ├── EvaluationServiceHelper.java            # 既有 helper，暂不强行合并
    ├── RAGQualityEvaluationService.java
    └── RetrievalEvaluationService.java
```

## 职责边界

### `InterviewAnswerEvaluationService`

负责：

- `evaluateWithKnowledge(...)` 的 prompt 构建和模型调用。
- `evaluateAndValidate(...)`：接收调用方已经构建好的 `KnowledgePacket`，执行评估并校验证据引用。
- 从 `KnowledgePacket` 读取 `context`、`imageContext`、`retrievalEvidence`。
- 调用 `question-strategy` skill 生成评估阶段策略提示。
- 评估异常时返回 fallback JSON。
- 校验和修补 citations / conflicts 的证据编号。
- 自己注入 `RAGObservabilityService` 维护评估 trace root / trace node 语义，不能反向调用 `RAGService` 的 private helper。

### `RAGService`

保留：

- `buildKnowledgePacket(...)`
- `processAnswer(...)`
- `evaluateWithKnowledge(...)`
- 其他暂未迁移的首题、报告、刷题、学习计划方法

其中 `evaluateWithKnowledge(...)` 改为直接委托 `InterviewAnswerEvaluationService`。`processAnswer(...)` 短期保留薄编排职责：先调用 `buildKnowledgePacket(...)`，再把得到的 `KnowledgePacket` 交给 `InterviewAnswerEvaluationService.evaluateAndValidate(...)`。这样可以避免 `RAGService -> InterviewAnswerEvaluationService -> RAGService` 的 Spring 循环依赖，同时让 `EvaluationAgent`、Controller 和测试可以暂时不改依赖。

## 类型兼容

本阶段继续复用：

```java
RAGService.KnowledgePacket
RAGService.EvaluationResult
```

原因：这两个类型已经被 `EvaluationAgent`、`KnowledgePacketBuilder`、评测服务和测试引用。强行提取类型会扩大影响面，建议后续单独做“知识包/评估结果类型独立化”。

## 迁移策略

1. 先补最小 characterization tests，固定：
   - evidence 引用过滤行为。
   - 模型异常 fallback 行为。
   - `evaluateWithKnowledge(...)` 仍返回 `EvaluationResult`。
2. 新建 `InterviewAnswerEvaluationService`，复制并收拢评估相关私有方法，但不注入或调用 `RAGService`。
3. `RAGService` 注入该服务；`evaluateWithKnowledge(...)` 直接委托，`processAnswer(...)` 只保留“构建知识包 + 委托评估校验”的薄编排。
4. 移除 `RAGService` 中已迁出的私有方法和不再需要的构造依赖。
5. 更新包约定文档，说明新代码不要继续向 `RAGService` 添加评估职责。

## 验证策略

每个小步至少运行：

```bash
mvn -q compile
mvn -q -Dtest=ArchitectureRulesTest test
```

评估链路相关变更优先运行：

```bash
mvn -q -Dtest=RAGServiceTest test
mvn -q -Dtest=QueryRewriteServiceTest test
```

如果当前本机仍因 Mockito inline Byte Buddy self-attach 导致 `RAGServiceTest` 无法运行，应记录环境限制，并使用以下命令作为最低门禁：

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest,QueryRewriteServiceTest test
mvn -q verify -DskipTests
```

## 风险与缓解

- **方法搬迁导致 trace 行为变化**：迁移时保留 trace root、node 名称、异常记录语义。
- **评估 helper 依赖过多**：新服务只接收评估链路需要的依赖，禁止复制 `RAGService` 全量构造函数。
- **服务循环依赖**：新服务允许引用 `RAGService.KnowledgePacket` / `EvaluationResult` 类型，但禁止注入 `RAGService`；`processAnswer(...)` 中的知识包构建仍由 `RAGService` 自己完成。
- **测试环境限制**：不把 Mockito attach 失败误判为业务失败；但必须保证编译和 test-compile 通过。

## 完成标准

- `InterviewAnswerEvaluationService` 承接面试回答评估主链路。
- `RAGService.evaluateWithKnowledge(...)` 变成兼容委托；`processAnswer(...)` 只保留知识包构建和评估服务调用。
- 外部调用行为和返回结构不变。
- `RAGService` 中评估相关私有方法明显减少。
- 文档明确评估新职责应进入 `knowledge.application.evaluation`。
