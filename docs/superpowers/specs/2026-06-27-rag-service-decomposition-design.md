# RAGService 拆分第一阶段设计

## 背景

`knowledge.application.RAGService` 当前承担了知识包构建、query rewrite、混合检索、Web fallback、证据评估、面试答案评估、报告策略、学习计划策略、编码题策略和 trace 编排等职责。它已经成为 `knowledge` 模块的事实中心，也让 `agent`、`interview`、`conversation` 后续能力容易被迫依赖一个大服务。

本设计目标是先做第一阶段拆分：不改变 API 行为、不改数据库、不迁移 controller，只把 `RAGService` 内最明显的可独立职责抽成小服务，让 `knowledge` 更像“知识能力提供者”。

## 目标

- 降低 `RAGService` 单类复杂度，使其保留兼容入口和薄编排职责。
- 抽出可复用的知识包构建链路，供后续 Agent/Conversation 调用。
- 把 query rewrite、证据评估、Web fallback 从主流程中分离。
- 保持现有测试语义和外部调用行为不变。

## 非目标

- 不引入 Mastra 或新的 Agent Runtime。
- 不改 HTTP API、DTO、数据库表或 Mapper。
- 不迁移 `chatstream` 到 `conversation`，该项作为后续阶段。
- 不重写 prompt、模型路由、检索算法或 trace 存储。
- 不追求一次性把 `RAGService` 清空。

## 目标结构

第一阶段新增或调整以下服务：

```text
knowledge.application
├── RAGService.java                         # 保留兼容入口，委托新服务
├── KnowledgePacketBuilder.java             # 构建 KnowledgePacket 主链路
└── retrieval
    ├── QueryRewriteService.java            # query rewrite、缓存、fallback
    ├── EvidenceEvaluationService.java      # evidence-evaluator skill 包装
    └── WebFallbackService.java             # 受控外部检索/MCP web.search
```

后续阶段再考虑：

```text
knowledge.application.evaluation
├── InterviewAnswerEvaluationService.java
├── InterviewReportStrategyService.java
└── LearningPlanStrategyService.java
```

## 职责边界

### `RAGService`

保留现有 public 方法，作为兼容门面：

- `processAnswer(...)`
- `buildKnowledgePacket(...)`
- 面试题生成、报告、学习计划等当前 public 能力

第一阶段只把 `buildKnowledgePacket` 相关逻辑委托给 `KnowledgePacketBuilder`。暂不迁移所有评估/报告方法，避免一次性改动过大。

### `KnowledgePacketBuilder`

负责完整知识包构建流程：

1. 创建 skill budget。
2. 调用 `QueryRewriteService` 得到 `RewrittenQuery`。
3. 执行混合检索、图片关联和 parent-child 补充。
4. 调用 `EvidenceEvaluationService` 判断是否允许 Web fallback。
5. 需要时调用 `WebFallbackService`。
6. 返回原有 `RAGService.KnowledgePacket` 或等价兼容类型。

为了减少破坏，第一阶段可以继续复用 `RAGService` 的内部 record，或先把 `KnowledgePacket` / `RewrittenQuery` 提升为 package-private/public record。最终以最小编译改动为准。

### `QueryRewriteService`

负责：

- `shouldRewriteQuery`
- rewrite cache key 和 TTL 判断
- 调用 `SkillOrchestrator` 的 `query-optimizer`
- fallback 到原有 LLM rewrite 或原问答检索

它不负责实际检索。

### `EvidenceEvaluationService`

负责包装：

- `evidence-evaluator` skill 调用
- 从 `SkillExecutionResult` 中解析 `allowExternalLookup`、`reason` 等判断

它不直接调用 MCP/WebSearch。

### `WebFallbackService`

负责：

- 根据 `SkillDefinition` 校验 `web.search` capability
- 调用 `SkillMcpClient.invokeForSkill(...)`
- 从结果中提取 snippets

它不决定是否应该 fallback，只执行受控外部检索。

## 迁移策略

采用“提取 + 委托”方式，避免重写：

1. 给当前 `RAGService` 相关私有逻辑补最小 characterization tests，覆盖 query rewrite fallback、web fallback 禁用、knowledge packet 基本构建行为。
2. 抽 `QueryRewriteService`，让 `RAGService` 委托它；保持测试通过。
3. 抽 `EvidenceEvaluationService` 和 `WebFallbackService`；保持测试通过。
4. 抽 `KnowledgePacketBuilder`，把 `buildKnowledgePacket` 主体迁入；`RAGService.buildKnowledgePacket` 只委托。
5. 更新文档，说明 `RAGService` 是兼容入口，新代码优先依赖更小的 application service。

## 验证策略

每个小步至少运行：

```bash
mvn -q compile
mvn -q -Dtest=ArchitectureRulesTest test
```

涉及 RAG 行为的小步优先运行相关测试：

```bash
mvn -q -Dtest=RAGServiceTest test
mvn -q -Dtest=ParentChildRetrievalHydrationTest test
mvn -q -Dtest=KnowledgeRetrievalCoordinatorTest test
```

如果本地 `mvn test` 因 Mockito inline Byte Buddy self-attach 失败，不把它表述为全量通过；需记录环境限制，并在支持 attach 的 JVM 环境补跑。

## 风险与缓解

- **构造函数依赖过多继续扩散**：新服务只接收自身需要的依赖，禁止复制 `RAGService` 的完整构造函数。
- **record 可见性导致大范围改动**：优先最小提升类型可见性，不同时改 API shape。
- **trace 行为丢失**：迁移时保留原 trace node 创建、完成和失败记录顺序。
- **fallback 行为变化**：先用 characterization tests 固定现有行为，再抽服务。

## 完成标准

- `RAGService` 中 `buildKnowledgePacket` 相关主链路明显变薄。
- Query rewrite、evidence evaluation、web fallback 至少三个职责已独立成服务。
- 外部 public 行为不变。
- 文档明确新代码不要继续向 `RAGService` 堆职责。
