# 编程练习链路拆分设计

## 背景

前两阶段已经把 `RAGService` 中的知识包构建和面试答案评估链路拆到独立服务。`RAGService` 仍承担编程练习相关职责，包括编程题生成、批量选择题生成、编程答案评估、下一题生成、题型归一化、coding-coach skill 注入和多套 fallback。该职责与 RAG 检索本身关系弱，更接近独立的练习/刷题能力。

本阶段目标是继续瘦身 `RAGService`，先以轻量门面方式拆出完整编程练习链路，不改变 `CodingPracticeAgent` 的依赖和外部行为。

## 目标

- 新增 `CodingPracticeService` 承接编程练习主链路。
- 保持 `RAGService` 现有 public API 和返回结构不变。
- 保持 `CodingPracticeAgent`、Controller、prompt 模板、skill 使用方式不变。
- 让后续 `CodingPracticeAgent` 直接依赖新服务、以及 `CodingAssessment` 类型独立化具备基础。

## 非目标

- 不修改 HTTP API、前端契约、数据库或 Mapper。
- 不重写编程题 prompt、评分规则、题型识别规则或 fallback 文案。
- 不迁移首题生成、最终报告、学习计划链路。
- 不重命名或迁出 `RAGService.CodingAssessment`。
- 不引入新的 Agent Runtime 或 Mastra。

## 目标结构

```text
knowledge.application
├── RAGService.java                         # 兼容门面：保留 public 编程练习入口并委托
└── coding
    └── CodingPracticeService.java          # 编程题生成/评估/下一题/批量选择题
```

## 职责边界

### `CodingPracticeService`

负责：

- `generateCodingQuestion(...)`：生成单道编程题、选择题、填空题或场景题。
- `generateBatchQuiz(...)`：批量生成选择题，模板缺失或解析失败时降级。
- `evaluateCodingAnswer(...)`：评估用户编程题答案，解析结构化 JSON，失败时返回 fallback 评分。
- `generateNextCodingQuestion(...)`：根据当前题目、答案和得分生成下一题。
- 编程练习相关 helper：题型归一化、topic 排除、inline batch prompt、fallback 题目/评分/下一题、coding-coach skill summary。
- 注入 `RoutingChatService`、`AgentSkillService`、`PromptManager`、`SkillOrchestrator`、`LlmJsonParser` 等编程链路需要的依赖。

### `RAGService`

保留：

- `generateCodingQuestion(...)`
- `generateBatchQuiz(...)`
- `evaluateCodingAnswer(...)`
- `generateNextCodingQuestion(...)`
- `RAGService.CodingAssessment`

这些方法改为直接委托 `CodingPracticeService`，作为兼容入口服务 `CodingPracticeAgent` 和既有测试。

## 类型兼容

本阶段继续复用：

```java
RAGService.CodingAssessment
CodingPracticeAgent.QuizQuestion
```

原因：`CodingPracticeAgent` 当前多处引用 `RAGService.CodingAssessment`，批量选择题使用 `CodingPracticeAgent.QuizQuestion`。本阶段只拆职责，不做类型独立化，避免扩大修改面。后续可单独做“编程练习 DTO 独立化”。

## 迁移策略

1. 先补 characterization tests，固定：
   - 生成编程题时注入 `coding-interview-coach` skill。
   - 评估编程答案时注入 `coding-interview-coach` skill。
   - 空答案直接返回降级评分。
   - 评估 JSON 解析失败时返回 fallback 评分。
2. 新建 `CodingPracticeService`，迁移编程练习 public 方法和相关私有 helper。
3. `RAGService` 注入 `CodingPracticeService`，编程练习 public 方法只做委托。
4. 移除 `RAGService` 中不再使用的编程练习 helper 和构造依赖，但保留其他链路仍使用的通用 helper。
5. 更新 `PACKAGE_CONVENTIONS.md`，明确编程练习能力归属 `knowledge.application.coding`，新逻辑不要继续写入 `RAGService`。

## 验证策略

每个小步至少运行：

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
```

编程链路相关变更优先运行：

```bash
mvn -q -Dtest=RAGServiceTest test
mvn -q -Dtest=CodingPracticeServiceTest test
```

最终门禁：

```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q -Dtest=RAGServiceTest,CodingPracticeServiceTest test
mvn -q verify -DskipTests
git diff --check
```

如果本机遇到 Maven 写 `~/.m2` 权限限制，应按沙箱规则提升权限后重跑同一命令；不能把沙箱权限错误当作业务测试失败。

## 风险与缓解

- **降级行为变化**：迁移时保留现有 fallback 文案、默认 topic、默认 difficulty 和分数估算逻辑。
- **题型识别变化**：`normalizeCodingQuestionType(...)` 原样迁移，避免影响选择题/填空题/场景题分支。
- **依赖扩大**：新服务只注入编程链路需要的依赖，禁止复制 `RAGService` 全量构造参数。
- **循环依赖**：`CodingPracticeService` 不注入 `RAGService`；只允许返回 `RAGService.CodingAssessment` 类型。
- **批量题降级递归**：`fallbackBatchQuiz(...)` 在新服务内调用本服务的 `generateCodingQuestion(...)`，不经由 `RAGService`，避免循环委托。

## 完成标准

- `CodingPracticeService` 承接编程练习完整链路。
- `RAGService` 编程练习 public 方法变成兼容委托。
- `CodingPracticeAgent` 无需修改即可继续工作。
- 外部调用行为、返回结构、fallback 文案保持不变。
- `RAGService` 中编程练习相关私有方法明显减少。
- 文档明确新编程练习职责应进入 `knowledge.application.coding`。
