# 排除知识点意图识别 — 设计文档

## 问题

用户在进行刷题/面试时，有时希望排除特定知识点。例如：

- "来几道算法题，不要动态规划"
- "刷两道 Java 选择题，跳过并发和 JVM"
- "开始面试，排除 Spring Boot 相关内容"

当前系统的意图路由只提取正向槽位（`topic`），不区分"要什么"和"不要什么"。LLM 看到"不要动态规划"和"来道动态规划的题"都会提取 `topic=动态规划`。

## 方案选择

**方案 B：在 Slot Refinement 阶段处理排除语义。**

理由：
- 复用已有的 `refineSlots()` 通道，不增加额外 LLM 调用
- Slot Refinement 本身就负责"从自然语言中提取结构化参数"，`excludedTopics` 是其自然扩展
- Few-shot cases 可配置到 DB（`IntentSlotRefineCaseService`），持续优化无需改代码
- 职责分离：意图分类只管"用户要做什么"，槽位精炼管"具体参数（含排除）"

## 数据流

```
用户: "来几道算法题，不要动态规划和贪心"
  │
  ├─ IntentPreFilter: 规则预过滤 → domain=CODING
  ├─ IntentTreeRoutingService.route():
  │    intent-tree-classifier prompt → taskType=CODING_PRACTICE, slots={topic:"算法", ...}
  │
  ├─ TaskRouterAgent: merge slots → payload
  │
  ├─ IntentTreeRoutingService.refineSlots("CODING_PRACTICE", query, history):
  │    intent-slot-refine prompt → {topic:"算法", excludedTopics:["动态规划","贪心"]}
  │    (refined 结果会合并进入 payload，覆盖/补充分类阶段的 slot)
  │
  ├─ TaskRouterAgent: merge refined slots → final payload
  │
  └─ Agent (CodingPracticeAgent / InterviewOrchestratorAgent):
        topic → RAG 检索方向
        excludedTopics → 过滤检索结果 / prompt 中排除指定知识点
```

## 改动清单

### 1. Prompt 模板：`intent-slot-refine`（DB 更新）

在现有模板中增加 `excludedTopics` 字段说明。关键指令：
- 当用户明确表示"不要"、"排除"、"跳过"、"不想"某些知识点时，提取到 `excludedTopics`
- `excludedTopics` 为字符串数组，如 `["动态规划", "贪心"]`
- 当用户没有排除意图时，不填此字段

### 2. `IntentTreeRoutingService.readSlots()` — 解析 excludedTopics

```java
// 在现有 readSlots() 方法末尾增加
if (slotsNode.has("excludedTopics") && slotsNode.get("excludedTopics").isArray()) {
    List<String> excluded = new ArrayList<>();
    for (JsonNode item : slotsNode.get("excludedTopics")) {
        String text = item.asText("").trim();
        if (!text.isBlank()) excluded.add(text);
    }
    if (!excluded.isEmpty()) slots.put("excludedTopics", excluded);
}
```

### 3. `IntentTreeRoutingService.defaultSlotRefineCases()` — 增加 few-shot

```java
// 在 CODING_PRACTICE 的 defaultSlotRefineCases 中增加
Map.of(
    "user_query", "来两道算法题，不要动态规划和贪心",
    "ai_response", "{\"slots\":{\"topic\":\"算法\",\"questionType\":\"ALGORITHM\",\"difficulty\":\"\",\"count\":2,\"excludedTopics\":[\"动态规划\",\"贪心\"]}}"
),
Map.of(
    "user_query", "刷五道Java选择题，跳过并发和多线程",
    "ai_response", "{\"slots\":{\"topic\":\"Java\",\"questionType\":\"CHOICE\",\"difficulty\":\"\",\"count\":5,\"excludedTopics\":[\"并发\",\"多线程\"]}}"
)
```

同时在 `INTERVIEW_START` 的 cases 中增加：
```java
Map.of(
    "user_query", "开始面试，但不要Spring和MyBatis的内容",
    "ai_response", "{\"slots\":{\"topic\":\"Java\",\"questionType\":\"\",\"difficulty\":\"\",\"count\":null,\"skipIntro\":null,\"excludedTopics\":[\"Spring\",\"MyBatis\"]}}"
)
```

### 4. Agent 消费 excludedTopics

**CodingPracticeAgent** — 两个消费点：
- `handleSingleQuestion()`: 将 `excludedTopics` 传入 `buildQuestion()` 或 `ragService.generateCodingQuestion()`，在 prompt 中追加排除指令
- `handleBatchQuiz()`: 将 `excludedTopics` 传入 `ragService.generateBatchQuiz()`，过滤检索结果

**InterviewOrchestratorAgent** — 在 `startSession()` 时使用 `excludedTopics`：
- 将排除主题传给 RAG 检索，在检索结果中过滤掉相关知识点
- 或在面试 prompt 中声明"不要围绕以下主题出题"

### 5. RAG 检索适配（可选增强）

在 `ragService.generateCodingQuestion(topic, difficulty, profileSnapshot)` 签名中增加 `List<String> excludedTopics` 参数。RAG 层：
- 检索时，对返回结果做后处理过滤：如果 chunk 的 topic 标签命中 `excludedTopics`，降权或移除
- 在 LLM 生成 prompt 中追加：`题目不得涉及以下知识点：{excludedTopics}`

## 影响范围

| 组件 | 影响 |
|---|---|
| `IntentTreeRoutingService` | `readSlots()` 增加 excludedTopics 解析；`defaultSlotRefineCases()` 增加示例 |
| DB `t_prompt_template` | `intent-slot-refine` 模板增加 excludedTopics 字段说明 |
| `CodingPracticeAgent` | 接收 payload 中的 excludedTopics，传入题目生成流程 |
| `InterviewOrchestratorAgent` | 同上 |
| `RAGService`（可选） | 检索方法签名增加 excludedTopics 参数 |

## 不涉及

- 意图分类 prompt（`intent-tree-classifier`）：不做改动，排除语义交给 slot refine 阶段
- 前端：本期不改，后续可在题目列表页增加"排除知识点"标签展示
- 数据库 schema：不新增表/字段，excludedTopics 作为 JSON 字段透传
