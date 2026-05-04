# 排除知识点意图识别 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Slot Refinement 阶段提取用户"不要/排除/跳过"的知识点，传递给 Agent 用于过滤题目生成

**Architecture:** 在现有 `IntentTreeRoutingService.refineSlots()` → `readSlots()` 通道中增加 `excludedTopics` 字段解析；在 CodingPracticeAgent 和 InterviewOrchestratorAgent 中消费该字段，传入 RAG 生成方法作为排除约束

**Tech Stack:** Java 21, Spring Boot 3.3.6, Jackson, MyBatis Plus

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `IntentTreeRoutingService.java` | readSlots() 增加 excludedTopics 解析；defaultSlotRefineCases() 增加 few-shot 示例 |
| `CodingPracticeAgent.java` | 从 payload 提取 excludedTopics，传入 buildQuestion() 和 RAG 调用 |
| `InterviewStartTaskHandler.java` | 从 payload 提取 excludedTopics，传入 startSession() |
| `InterviewOrchestratorAgent.java` | startSession() 增加 excludedTopics 参数，传入首题生成 |
| `RAGService.java` | generateCodingQuestion()、generateBatchQuiz()、generateFirstQuestion() 增加 excludedTopics 参数 |
| DB `t_prompt_template` | `intent-slot-refine` 模板增加 excludedTopics 提取说明 |

---

### Task 1: readSlots() 解析 excludedTopics

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/routing/application/IntentTreeRoutingService.java:456`

- [ ] **Step 1: 在 readSlots() 末尾增加 excludedTopics 解析**

在 `putSlot(slots, "mode", readText(slotsNode, "mode"));` 之后，`return slots;` 之前插入：

```java
if (slotsNode.has("excludedTopics") && slotsNode.get("excludedTopics").isArray()) {
    List<String> excluded = new ArrayList<>();
    for (JsonNode item : slotsNode.get("excludedTopics")) {
        String text = item.asText("").trim();
        if (!text.isBlank()) {
            excluded.add(text);
        }
    }
    if (!excluded.isEmpty()) {
        slots.put("excludedTopics", excluded);
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./mvnw -q compile
```

预期: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/routing/application/IntentTreeRoutingService.java
git commit -m "feat: readSlots 增加 excludedTopics 字段解析"
```

---

### Task 2: defaultSlotRefineCases() 增加 few-shot 示例

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/routing/application/IntentTreeRoutingService.java:194-221`

- [ ] **Step 1: 更新 CODING_PRACTICE 的 defaultSlotRefineCases**

将现有 `"CODING_PRACTICE".equals(taskType)` 分支替换为：

```java
if ("CODING_PRACTICE".equals(taskType)) {
    return List.of(
            Map.of(
                    "user_query", "来2道Java选择题，简单点",
                    "ai_response", "{\"slots\":{\"topic\":\"Java\",\"questionType\":\"CHOICE\",\"difficulty\":\"easy\",\"count\":2,\"skipIntro\":null,\"mode\":\"\"}}"
            ),
            Map.of(
                    "user_query", "刷一道并发算法题",
                    "ai_response", "{\"slots\":{\"topic\":\"并发\",\"questionType\":\"ALGORITHM\",\"difficulty\":\"\",\"count\":1,\"skipIntro\":null,\"mode\":\"\"}}"
            ),
            Map.of(
                    "user_query", "来两道算法题，不要动态规划和贪心",
                    "ai_response", "{\"slots\":{\"topic\":\"算法\",\"questionType\":\"ALGORITHM\",\"difficulty\":\"\",\"count\":2,\"excludedTopics\":[\"动态规划\",\"贪心\"]}}"
            ),
            Map.of(
                    "user_query", "刷五道Java选择题，跳过并发和多线程",
                    "ai_response", "{\"slots\":{\"topic\":\"Java\",\"questionType\":\"CHOICE\",\"difficulty\":\"\",\"count\":5,\"excludedTopics\":[\"并发\",\"多线程\"]}}"
            )
    );
}
```

- [ ] **Step 2: 更新 INTERVIEW_START 的 defaultSlotRefineCases**

将现有 `"INTERVIEW_START".equals(taskType)` 分支替换为：

```java
if ("INTERVIEW_START".equals(taskType)) {
    return List.of(
            Map.of(
                    "user_query", "来一场Spring Boot面试，跳过自我介绍",
                    "ai_response", "{\"slots\":{\"topic\":\"Spring Boot\",\"questionType\":\"\",\"difficulty\":\"\",\"count\":null,\"skipIntro\":true,\"mode\":\"\"}}"
            ),
            Map.of(
                    "user_query", "开始Java面试，但不要Spring和MyBatis的内容",
                    "ai_response", "{\"slots\":{\"topic\":\"Java\",\"questionType\":\"\",\"difficulty\":\"\",\"count\":null,\"skipIntro\":null,\"excludedTopics\":[\"Spring\",\"MyBatis\"]}}"
            )
    );
}
```

- [ ] **Step 3: 编译验证**

```bash
./mvnw -q compile
```

预期: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/routing/application/IntentTreeRoutingService.java
git commit -m "feat: defaultSlotRefineCases 增加排除知识点 few-shot 示例"
```

---

### Task 3: RAGService 增加 excludedTopics 参数

**策略：不修改 DB 中的 prompt 模板。** 在 Java 层将 `excludedTopics` 织入 `topic` 字符串（如 `"算法（注意：不得涉及以下知识点：动态规划、贪心）"`），模板中已有的 `{{ topic }}` 变量自动携带排除约束。

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`
- Modify: `src/main/java/io/github/imzmq/interview/agent/runtime/EvaluationAgent.java`

- [ ] **Step 1: 新增 buildTopicWithExclusion() 辅助方法**

在 RAGService 类中增加：

```java
private String buildTopicWithExclusion(String topic, List<String> excludedTopics) {
    if (excludedTopics == null || excludedTopics.isEmpty()) {
        return topic;
    }
    return topic + "（注意：不得涉及以下知识点：" + String.join("、", excludedTopics) + "）";
}
```

- [ ] **Step 2: generateCodingQuestion() 增加 excludedTopics 参数**

修改方法签名（RAGService.java:1780）：

```java
public String generateCodingQuestion(String topic, String difficulty, String profileSnapshot,
        List<String> excludedTopics) {
```

将 `params.put("topic", normalizedTopic);`（第 1797 行）替换为：

```java
params.put("topic", buildTopicWithExclusion(normalizedTopic, excludedTopics));
```

- [ ] **Step 3: generateBatchQuiz() 增加 excludedTopics 参数**

修改方法签名（RAGService.java:1826）：

```java
public List<CodingPracticeAgent.QuizQuestion> generateBatchQuiz(
        String topic, String difficulty, int count, String profileSnapshot,
        List<String> excludedTopics) {
```

将 `params.put("topic", normalizedTopic);`（第 1843 行）替换为：

```java
params.put("topic", buildTopicWithExclusion(normalizedTopic, excludedTopics));
```

- [ ] **Step 4: generateFirstQuestion() 增加 excludedTopics 参数**

修改方法签名（RAGService.java:1690）：

```java
public String generateFirstQuestion(String resumeContent, String topic,
        String profileSnapshot, boolean skipIntro, List<String> excludedTopics) {
```

将 `vars.put("topic", topic == null ? "" : topic);`（第 1708 行）替换为：

```java
vars.put("topic", buildTopicWithExclusion(topic == null ? "" : topic, excludedTopics));
```

- [ ] **Step 5: 更新 EvaluationAgent 调用链**

`src/main/java/io/github/imzmq/interview/agent/runtime/EvaluationAgent.java:29-38`

更新重载方法，原有三参数/四参数的方法传空 List，新增五参数重载：

```java
public String generateFirstQuestion(String resumeContent, String topic) {
    return ragService.generateFirstQuestion(resumeContent, topic, "暂无历史画像。", false, List.of());
}

public String generateFirstQuestion(String resumeContent, String topic, String profileSnapshot) {
    return ragService.generateFirstQuestion(resumeContent, topic, profileSnapshot, false, List.of());
}

public String generateFirstQuestion(String resumeContent, String topic, String profileSnapshot, boolean skipIntro) {
    return ragService.generateFirstQuestion(resumeContent, topic, profileSnapshot, skipIntro, List.of());
}

// 新增：带 excludedTopics 的重载
public String generateFirstQuestion(String resumeContent, String topic, String profileSnapshot,
        boolean skipIntro, List<String> excludedTopics) {
    return ragService.generateFirstQuestion(resumeContent, topic, profileSnapshot, skipIntro, excludedTopics);
}
```

- [ ] **Step 6: 编译验证**

```bash
./mvnw -q compile
```

预期: BUILD SUCCESS（如有其他调用方编译失败，逐个修复传入 `List.of()`）

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java \
        src/main/java/io/github/imzmq/interview/agent/runtime/EvaluationAgent.java
git commit -m "feat: RAGService 题目生成方法增加 excludedTopics 参数"
```

---

### Task 4: CodingPracticeAgent 消费 excludedTopics

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/agent/runtime/CodingPracticeAgent.java`

- [ ] **Step 1: startNewChatSession() 提取 excludedTopics 并传递**

在 `startNewChatSession()` 方法中（约 125 行），在 `String message = text(input, "message");` 之后增加：

```java
List<String> excludedTopics = readTextList(input, "excludedTopics");
```

在调用 `handleBatchQuiz()` 时（约 192 行），将 `excludedTopics` 传入：

```java
return handleBatchQuiz(userId, topic, difficulty, count,
    learningProfileAgent.snapshotForPrompt(userId, topic), excludedTopics);
```

- [ ] **Step 2: 新增 readTextList 工具方法**

在 CodingPracticeAgent 类中增加：

```java
@SuppressWarnings("unchecked")
private List<String> readTextList(Map<String, Object> input, String key) {
    if (input == null) return List.of();
    Object raw = input.get(key);
    if (raw instanceof List<?> list) {
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) {
                result.add(item.toString().trim());
            }
        }
        return result;
    }
    return List.of();
}
```

**注意：** `TaskHandlerSupport` 已有 `readTextList()` 静态方法，但 CodingPracticeAgent 通常不依赖 TaskHandlerSupport。检查是否可以复用，如果不方便注入则直接在 CodingPracticeAgent 中添加实例方法。

- [ ] **Step 3: handleBatchQuiz() 接收并传递 excludedTopics**

修改 `handleBatchQuiz()` 方法签名（约 258 行）：

```java
private Map<String, Object> handleBatchQuiz(String userId, String topic,
        String difficulty, int count, String profileSnapshot, List<String> excludedTopics) {
```

内部调用 `ragService.generateBatchQuiz()` 时传入 `excludedTopics`：

```java
List<QuizQuestion> questions = ragService.generateBatchQuiz(topic, difficulty, count, profileSnapshot, excludedTopics);
```

- [ ] **Step 4: generateNextChatQuestion() 传递 excludedTopics**

`generateNextChatQuestion()` 需要获取 session 的 excludedTopics。在 `CodingSession` record 中增加字段：

```java
record CodingSession(String sessionId, String userId, String topic, String difficulty, String type,
        int totalQuestions, int currentQuestionIndex, String currentQuestion, String referenceAnswer,
        int score, int questionCount, Instant createdAt, List<String> excludedTopics) {
}
```

在 `startNewChatSession()` 创建 session 时（约 188 行）传入：

```java
CodingSession session = new CodingSession(sessionId, userId, topic, difficulty, type, count, 0, "", "", 0, 0, Instant.now(), excludedTopics);
```

在 `generateNextChatQuestion()` 中调用 `ragService.generateCodingQuestion()` 时（约 210 行）传入：

```java
String question = ragService.generateCodingQuestion(
    buildTopicWithType(session.topic(), session.type()), session.difficulty(), resolvedProfileSnapshot, session.excludedTopics());
```

降级 `buildQuestion()` 调用时（约 212 行）追加排除指令到 prompt：

```java
question = buildQuestion(session.topic(), session.difficulty(), session.type()) + buildExclusionSuffix(session.excludedTopics()) + " (" + session.type() + ")";
```

- [ ] **Step 5: 新增 buildExclusionSuffix() 辅助方法**

```java
private String buildExclusionSuffix(List<String> excludedTopics) {
    if (excludedTopics == null || excludedTopics.isEmpty()) {
        return "";
    }
    StringBuilder sb = new StringBuilder("\n不要出以下知识点的题目：");
    for (int i = 0; i < excludedTopics.size(); i++) {
        if (i > 0) sb.append("、");
        sb.append(excludedTopics.get(i));
    }
    sb.append("。");
    return sb.toString();
}
```

- [ ] **Step 6: startPractice() 同样处理**

在 `startPractice()` 方法中（约 619 行），提取 `excludedTopics` 并传入 `handleBatchQuiz()` 和 `CodingSession`，与 `startNewChatSession()` 保持一致。

阅读 `startPractice()` 全文，定位创建 session 和调用 RAG 的位置，应用相同改动。

- [ ] **Step 7: 编译验证**

```bash
./mvnw -q compile
```

预期: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/agent/runtime/CodingPracticeAgent.java
git commit -m "feat: CodingPracticeAgent 消费 excludedTopics 过滤题目生成"
```

---

### Task 5: InterviewOrchestratorAgent + InterviewStartTaskHandler 消费 excludedTopics

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/agent/router/handler/InterviewStartTaskHandler.java`
- Modify: `src/main/java/io/github/imzmq/interview/agent/runtime/InterviewOrchestratorAgent.java`

- [ ] **Step 1: InterviewStartTaskHandler 提取 excludedTopics**

在 `InterviewStartTaskHandler.handle()` 中（约 30 行），提取 excludedTopics：

```java
@Override
public Object handle(TaskRequest request) {
    boolean skipIntro = false;
    if (request.payload() != null && request.payload().containsKey("skipIntro")) {
        Object val = request.payload().get("skipIntro");
        if (val instanceof Boolean bool) {
            skipIntro = bool;
        } else if (val instanceof String text) {
            skipIntro = Boolean.parseBoolean(text);
        }
    }
    List<String> excludedTopics = TaskHandlerSupport.readTextList(request.payload(), "excludedTopics");
    return interviewOrchestratorAgent.startSession(
            TaskHandlerSupport.readText(request.payload(), "userId"),
            TaskHandlerSupport.readText(request.payload(), "topic"),
            TaskHandlerSupport.readText(request.payload(), "resumePath"),
            TaskHandlerSupport.readInt(request.payload(), "totalQuestions"),
            skipIntro,
            excludedTopics
    );
}
```

- [ ] **Step 2: InterviewOrchestratorAgent.startSession() 增加 excludedTopics 参数**

修改方法签名（约 87 行），在 `skipIntro` 后增加参数：

```java
public InterviewSession startSession(String userId, String topic, String resumePath, 
        Integer totalQuestions, boolean skipIntro, List<String> excludedTopics) {
```

在调用 `evaluationAgent.generateFirstQuestion()` 时（约 113 行）传入 excludedTopics：

```java
String firstQuestion = evaluationAgent.generateFirstQuestion(resumeContent, topic, profileSnapshot, skipIntro, excludedTopics);
```

- [ ] **Step 3: 编译验证**

```bash
./mvnw -q compile
```

预期: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/agent/router/handler/InterviewStartTaskHandler.java \
        src/main/java/io/github/imzmq/interview/agent/runtime/InterviewOrchestratorAgent.java
git commit -m "feat: Interview 消费 excludedTopics 过滤面试题目"
```

---

### Task 6: Prompt 模板更新（DB）

**Files:**
- DB 表: `t_prompt_template`，模板名: `intent-slot-refine`

- [ ] **Step 1: 编写 SQL 更新脚本**

在 `sql/` 目录下创建 `data.sql` 更新（或单独脚本），在现有 `intent-slot-refine` 模板中增加 excludedTopics 提取说明。

**关键指令追加（在模板的 JSON schema 说明区域）：**

```
- "excludedTopics": 字符串数组，当用户明确表示"不要"、"排除"、"跳过"、"不想涉及"某些知识点时填写。
  例如用户说"不要动态规划和贪心"则填写 ["动态规划", "贪心"]。
  如果用户没有排除任何知识点，此字段可省略。
```

**具体改动需在 Step 2 确认当前模板内容后才能精确编写。** 先用下方 SQL 定位模板：

```sql
SELECT id, name, content FROM t_prompt_template WHERE name = 'intent-slot-refine';
```

- [ ] **Step 2: 查看当前模板内容**

```bash
docker compose exec mysql mysql -u root -p interview_review -e "SELECT content FROM t_prompt_template WHERE name = 'intent-slot-refine' AND type = 'TASK' LIMIT 1\G" 2>/dev/null
```

根据实际模板内容，在 schema 说明部分追加 `excludedTopics` 字段描述。

- [ ] **Step 3: 更新模板**

```sql
UPDATE t_prompt_template 
SET content = '<修改后的完整模板>'
WHERE name = 'intent-slot-refine' AND type = 'TASK';
```

- [ ] **Step 4: 验证**

重启后端，发送测试请求确认 LLM 能正确提取 excludedTopics：

```bash
curl -s -X POST http://localhost:9596/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"来两道算法题，不要动态规划","sessionId":""}' | head -50
```

检查日志中的 slot refinement 输出是否包含 `excludedTopics`。

- [ ] **Step 5: Commit（SQL 脚本）**

```bash
git add sql/
git commit -m "feat: intent-slot-refine 模板增加 excludedTopics 提取说明"
```

---

### Task 7: 端到端验证

- [ ] **Step 1: 运行全部后端测试**

```bash
./mvnw test
```

预期: Tests run: 132, Failures: 0, Errors: 0, Skipped: 0

- [ ] **Step 2: 运行 Checkstyle + ArchUnit**

```bash
./mvnw -q verify -DskipTests
```

预期: BUILD SUCCESS

- [ ] **Step 3: 手动验证场景**

启动完整环境，通过前端或 curl 发送：

| 输入 | 预期 |
|---|---|
| "来两道算法题，不要动态规划" | 生成的题目不涉及动态规划 |
| "刷五道Java题，跳过并发" | 生成的Java题不涉及并发 |
| "开始面试，排除Spring" | 面试首题不涉及Spring |
| "来道算选择题" | 正常生成（无排除，向后兼容） |

- [ ] **Step 4: 最终 commit（如有遗漏改动）**

```bash
git status
# 如有未提交的修改
git add <files>
git commit -m "feat: 端到端验证通过，excludedTopics 完整链路联通"
```
