# RAGService Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `RAGService.buildKnowledgePacket` 第一阶段拆成更小的知识检索服务，同时保持现有 API、测试语义和运行行为不变。

**Architecture:** 采用“提取 + 委托”的模块化单体改造：`RAGService` 保留兼容门面，新建 retrieval 子包服务承接 query rewrite、证据评估和 Web fallback，最后由 `KnowledgePacketBuilder` 负责知识包主编排。每一步都先固定行为，再迁移少量逻辑，避免一次性重写。

**Tech Stack:** Java 21、Spring Boot、Spring AI `Document`/`VectorStore`、JUnit 5、Mockito、ArchUnit、Maven。

---

## File Structure

- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`
  - 保留 public 方法与兼容入口；逐步把 `buildKnowledgePacket` 相关私有逻辑委托给新服务。
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/RewrittenQuery.java`
  - 承载 query rewrite 结果，替代 `RAGService` 私有嵌套 record。
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/QueryRewriteService.java`
  - 负责 rewrite 判断、缓存、`query-optimizer` skill 调用和 LLM fallback。
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/EvidenceEvaluationService.java`
  - 负责 `evidence-evaluator` skill 调用，以及 skill 失败时的 legacy fallback 判定。
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/WebFallbackService.java`
  - 负责受控 `web.search` MCP 调用、结果提取和 `WebSearchTool` 后备调用。
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/KnowledgePacketBuilder.java`
  - 承接 `buildKnowledgePacket` 主链路；第一阶段允许复用 `RAGService.KnowledgePacket`。
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`
  - 增加 characterization tests，保证 query rewrite fallback、禁用 Web fallback、MCP snippets 等行为迁移前后不变。
- Modify: `PACKAGE_CONVENTIONS.md`
  - 明确 `RAGService` 是兼容门面，新代码优先依赖小服务。

## Task 1: Baseline Verification

**Files:**
- Inspect: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`
- Inspect: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`

- [ ] **Step 1: Record current git state**

Run:

```bash
git status --short --branch
```

Expected: working tree only contains this plan commit or is clean before implementation starts. If unrelated user changes exist, stop and ask before editing.

- [ ] **Step 2: Run compile baseline**

Run:

```bash
mvn -q compile
```

Expected: exit code `0`.

- [ ] **Step 3: Run architecture baseline**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: exit code `0`.

- [ ] **Step 4: Run focused RAG baseline**

Run:

```bash
mvn -q -Dtest=RAGServiceTest test
```

Expected: exit code `0`. If Mockito inline fails with Byte Buddy self-attach, record the exact failure and continue with `mvn -q compile` plus `ArchitectureRulesTest` until a JVM with attach support is available.

## Task 2: Add Characterization Tests

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`

- [ ] **Step 1: Add imports if missing**

Ensure these static imports are present:

```java
import static org.mockito.Mockito.never;
```

- [ ] **Step 2: Add query rewrite no-op characterization test**

Add this test near the existing knowledge packet tests:

```java
@Test
void shouldSkipQueryRewriteForShortQuestionWithoutAnswer() {
    when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);
    when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());
    when(mcpGatewayService.invoke(anyString(), anyString(), anyMap(), anyMap())).thenReturn(Map.of(
            "status", "blocked",
            "result", Map.of()
    ));

    RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket("Redis", "", false);

    assertEquals("Redis", packet.retrievalQuery());
    assertFalse(packet.webFallbackUsed());
    verify(routingChatService, never()).callWithFirstPacketProbeSupplier(
            any(Supplier.class),
            nullable(String.class),
            any(ModelRouteType.class),
            any(TimeoutHint.class),
            anyString()
    );
}
```

- [ ] **Step 3: Add disabled web fallback characterization test**

Add this test near `shouldCallWebSearchWhenVectorStoreIsEmpty`:

```java
@Test
void shouldNotUseWebFallbackWhenCallerDisablesIt() {
    when(agentSkillService.resolveSkillBlock("query-optimizer")).thenReturn("");
    when(routingChatService.callWithFirstPacketProbeSupplier(any(Supplier.class), nullable(String.class), any(ModelRouteType.class), any(TimeoutHint.class), anyString()))
            .thenReturn("Java Concurrency");
    when(observabilitySwitchProperties.isRagTraceEnabled()).thenReturn(false);
    when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class))).thenReturn(List.of());

    RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket("什么是并发", "同时执行", false);

    assertFalse(packet.webFallbackUsed());
    assertEquals("[]", packet.retrievalEvidence());
    verify(webSearchTool, never()).run(any(WebSearchTool.Query.class));
    verify(mcpGatewayService, never()).invoke(anyString(), eq("web.search"), anyMap(), anyMap());
}
```

- [ ] **Step 4: Run the focused test**

Run:

```bash
mvn -q -Dtest=RAGServiceTest test
```

Expected: exit code `0`, or the known Mockito inline Byte Buddy self-attach environment failure. New tests must compile.

- [ ] **Step 5: Commit tests**

Run:

```bash
git add src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java
git commit -m "test: 固定知识包构建行为"
```

Expected: commit succeeds.

## Task 3: Extract RewrittenQuery Type

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/RewrittenQuery.java`
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`

- [ ] **Step 1: Create the record**

Create `RewrittenQuery.java`:

```java
package io.github.imzmq.interview.knowledge.application.retrieval;

/**
 * 结构化查询改写结果。
 *
 * <p>coreTerms 用于高精度检索通道，fullQuery 用于语义向量检索。</p>
 */
public record RewrittenQuery(String coreTerms, String expandTerms, String fullQuery) {
    public static RewrittenQuery fallback(String raw) {
        return new RewrittenQuery(raw == null ? "" : raw, "", raw == null ? "" : raw);
    }
}
```

- [ ] **Step 2: Update RAGService import**

Add:

```java
import io.github.imzmq.interview.knowledge.application.retrieval.RewrittenQuery;
```

- [ ] **Step 3: Remove nested record from RAGService**

Delete the nested `record RewrittenQuery(...)` block from `RAGService`.

- [ ] **Step 4: Compile**

Run:

```bash
mvn -q compile
```

Expected: exit code `0`.

- [ ] **Step 5: Commit**

Run:

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/RewrittenQuery.java src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java
git commit -m "refactor: 提取查询改写结果类型"
```

Expected: commit succeeds.

## Task 4: Extract QueryRewriteService

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/QueryRewriteService.java`
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`

- [ ] **Step 1: Create QueryRewriteService**

Create the class with the logic copied from `RAGService`:

```java
package io.github.imzmq.interview.knowledge.application.retrieval;

import io.github.imzmq.interview.agent.application.AgentSkillService;
import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.modelrouting.core.TimeoutHint;
import io.github.imzmq.interview.observability.core.RAGTraceContext;
import io.github.imzmq.interview.skill.core.SkillExecutionBudget;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);
    private static final Duration REWRITE_CACHE_TTL = Duration.ofMinutes(10);
    private static final int REWRITE_CACHE_MAX_SIZE = 256;
    private static final Set<String> REWRITE_TRIGGER_KEYWORDS = Set.of(
            "为什么", "怎么", "如何", "原理", "区别", "对比", "场景", "实现",
            "设计", "优化", "排查", "分析", "步骤", "问题", "异常", "报错",
            "以及", "并且", "但是", "不过", "是否", "还是", "包括", "比如"
    );

    private final RoutingChatService routingChatService;
    private final AgentSkillService agentSkillService;
    private final SkillOrchestrator skillOrchestrator;
    private final ConcurrentHashMap<String, CachedRewrite> rewriteCache = new ConcurrentHashMap<>();

    public QueryRewriteService(RoutingChatService routingChatService,
                               AgentSkillService agentSkillService,
                               SkillOrchestrator skillOrchestrator) {
        this.routingChatService = routingChatService;
        this.agentSkillService = agentSkillService;
        this.skillOrchestrator = skillOrchestrator;
    }

    public RewrittenQuery buildRewrittenQuery(String question, String userAnswer, SkillExecutionBudget skillBudget) {
        String fallbackRaw = normalizeRewriteSource(question, userAnswer);
        if (!shouldRewriteQuery(question, userAnswer)) {
            return RewrittenQuery.fallback(fallbackRaw);
        }
        String cacheKey = buildRewriteCacheKey(question, userAnswer);
        CachedRewrite cachedRewrite = rewriteCache.get(cacheKey);
        if (cachedRewrite != null && !cachedRewrite.isExpired()) {
            return cachedRewrite.query();
        }
        try {
            SkillExecutionResult result = skillOrchestrator.execute(
                    "query-optimizer",
                    new SkillExecutionContext(
                            RAGTraceContext.getTraceId(),
                            "rag-service",
                            Map.of(
                                    "question", question == null ? "" : question,
                                    "userAnswer", userAnswer == null ? "" : userAnswer
                            ),
                            skillBudget
                    )
            );
            RewrittenQuery rewrittenQuery = result.succeeded()
                    ? new RewrittenQuery(
                    result.textOutput("coreTerms"),
                    result.textOutput("expandTerms"),
                    result.textOutput("fullQuery").isBlank() ? fallbackRaw : result.textOutput("fullQuery")
            )
                    : callWithRetry(() -> rewriteQuery(question, userAnswer), 2, "关键词提取");
            putRewriteCache(cacheKey, rewrittenQuery);
            return rewrittenQuery;
        } catch (RuntimeException e) {
            logger.warn("关键词提取失败，使用原问答检索。原因: {}", summarizeError(e));
            return RewrittenQuery.fallback(fallbackRaw);
        }
    }

    private RewrittenQuery rewriteQuery(String question, String userAnswer) {
        String skillBlock = safeSkillText(agentSkillService.resolveSkillBlock("query-optimizer"));
        String prompt = skillBlock + "\n" +
                "请从下面的面试问答中提取用于知识检索的关键词。\n" +
                "严格按 CORE/EXPAND 两行格式输出。\n" +
                "问题：" + question + "\n" +
                "回答：" + userAnswer + "\n" +
                "只返回 CORE 和 EXPAND 两行，不要返回其他解释。";
        String raw = routingChatService.callWithFirstPacketProbeSupplier(
                () -> question,
                prompt,
                ModelRouteType.GENERAL,
                TimeoutHint.NORMAL,
                "关键词提取"
        );
        return parseRewrittenQuery(raw);
    }

    private boolean shouldRewriteQuery(String question, String userAnswer) {
        String normalizedQuestion = question == null ? "" : question.trim();
        String normalizedAnswer = userAnswer == null ? "" : userAnswer.trim();
        if (!normalizedAnswer.isBlank()) {
            return true;
        }
        if (normalizedQuestion.length() >= 28) {
            return true;
        }
        if (normalizedQuestion.contains("，")
                || normalizedQuestion.contains(",")
                || normalizedQuestion.contains("；")
                || normalizedQuestion.contains(";")
                || normalizedQuestion.contains("：")
                || normalizedQuestion.contains(":")
                || normalizedQuestion.contains("（")
                || normalizedQuestion.contains("(")) {
            return true;
        }
        for (String keyword : REWRITE_TRIGGER_KEYWORDS) {
            if (normalizedQuestion.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildRewriteCacheKey(String question, String userAnswer) {
        return normalizeRewriteSource(question, userAnswer).toLowerCase(Locale.ROOT);
    }

    private String normalizeRewriteSource(String question, String userAnswer) {
        String combined = ((question == null ? "" : question.trim()) + " " + (userAnswer == null ? "" : userAnswer.trim())).trim();
        return combined.replaceAll("\\s+", " ");
    }

    private void putRewriteCache(String cacheKey, RewrittenQuery query) {
        if (rewriteCache.size() >= REWRITE_CACHE_MAX_SIZE) {
            cleanupExpiredRewriteCache();
            if (rewriteCache.size() >= REWRITE_CACHE_MAX_SIZE) {
                String firstKey = rewriteCache.keys().hasMoreElements() ? rewriteCache.keys().nextElement() : null;
                if (firstKey != null) {
                    rewriteCache.remove(firstKey);
                }
            }
        }
        rewriteCache.put(cacheKey, new CachedRewrite(query, System.currentTimeMillis() + REWRITE_CACHE_TTL.toMillis()));
    }

    private void cleanupExpiredRewriteCache() {
        long now = System.currentTimeMillis();
        rewriteCache.entrySet().removeIf(entry -> entry.getValue().expireAtMs() <= now);
    }

    private RewrittenQuery parseRewrittenQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return RewrittenQuery.fallback("");
        }
        String core = "";
        String expand = "";
        for (String line : raw.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("CORE:")) {
                core = trimmed.substring(5).trim();
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("EXPAND:")) {
                expand = trimmed.substring(7).trim();
            }
        }
        if (core.isEmpty()) {
            return RewrittenQuery.fallback(raw.replaceAll("\\s+", " ").trim());
        }
        String full = expand.isEmpty() ? core : core + " " + expand;
        return new RewrittenQuery(core, expand, full);
    }

    private <T> T callWithRetry(Supplier<T> action, int maxAttempts, String stage) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                last = e;
                logger.warn("{}第{}次调用失败: {}", stage, attempt, summarizeError(e));
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(400L * attempt);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("线程中断", interruptedException);
                    }
                }
            }
        }
        throw last == null ? new IllegalStateException(stage + "失败") : last;
    }

    private String summarizeError(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private String safeSkillText(String content) {
        return content == null ? "" : content;
    }

    private record CachedRewrite(RewrittenQuery query, long expireAtMs) {
        private boolean isExpired() {
            return expireAtMs <= System.currentTimeMillis();
        }
    }
}
```

- [ ] **Step 2: Inject QueryRewriteService into RAGService**

In `RAGService`, add field:

```java
private final QueryRewriteService queryRewriteService;
```

Add constructor parameter after `SkillOrchestrator skillOrchestrator`:

```java
QueryRewriteService queryRewriteService,
```

Assign it:

```java
this.queryRewriteService = queryRewriteService;
```

- [ ] **Step 3: Delegate buildRewrittenQuery call**

Replace:

```java
RewrittenQuery rewrittenQuery = buildRewrittenQuery(question, userAnswer, skillBudget);
```

with:

```java
RewrittenQuery rewrittenQuery = queryRewriteService.buildRewrittenQuery(question, userAnswer, skillBudget);
```

- [ ] **Step 4: Remove migrated members from RAGService**

Delete from `RAGService`:

```java
private static final Duration REWRITE_CACHE_TTL = Duration.ofMinutes(10);
private static final int REWRITE_CACHE_MAX_SIZE = 256;
private static final Set<String> REWRITE_TRIGGER_KEYWORDS = Set.of(...);
private final ConcurrentHashMap<String, CachedRewrite> rewriteCache = new ConcurrentHashMap<>();
private RewrittenQuery rewriteQuery(...)
private RewrittenQuery buildRewrittenQuery(...)
private boolean shouldRewriteQuery(...)
private String buildRewriteCacheKey(...)
private String normalizeRewriteSource(...)
private void putRewriteCache(...)
private void cleanupExpiredRewriteCache(...)
private RewrittenQuery parseRewrittenQuery(...)
private <T> T callWithRetry(...)
private record CachedRewrite(...)
```

Then remove unused imports such as `java.time.Duration`, `java.util.concurrent.ConcurrentHashMap`, and `java.util.function.Supplier` only if no remaining code uses them.

- [ ] **Step 5: Update RAGServiceTest construction**

In `setUp`, create the service:

```java
QueryRewriteService queryRewriteService = new QueryRewriteService(
        routingChatService,
        agentSkillService,
        skillOrchestrator
);
```

Pass `queryRewriteService` into the `RAGService` constructor at the new parameter position. Add import:

```java
import io.github.imzmq.interview.knowledge.application.retrieval.QueryRewriteService;
```

- [ ] **Step 6: Compile and test**

Run:

```bash
mvn -q compile
mvn -q -Dtest=RAGServiceTest test
```

Expected: compile exits `0`; focused test exits `0` or hits only the known Mockito inline environment failure.

- [ ] **Step 7: Commit**

Run:

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/QueryRewriteService.java src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java
git commit -m "refactor: 提取查询改写服务"
```

Expected: commit succeeds.

## Task 5: Extract EvidenceEvaluationService

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/EvidenceEvaluationService.java`
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`

- [ ] **Step 1: Create EvidenceEvaluationService**

```java
package io.github.imzmq.interview.knowledge.application.retrieval;

import io.github.imzmq.interview.config.knowledge.RagRetrievalProperties;
import io.github.imzmq.interview.skill.core.SkillExecutionBudget;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EvidenceEvaluationService {

    private final SkillOrchestrator skillOrchestrator;
    private final RagRetrievalProperties ragRetrievalProperties;

    public EvidenceEvaluationService(SkillOrchestrator skillOrchestrator,
                                     RagRetrievalProperties ragRetrievalProperties) {
        this.skillOrchestrator = skillOrchestrator;
        this.ragRetrievalProperties = ragRetrievalProperties;
    }

    public EvidenceDecision decide(RewrittenQuery rewrittenQuery,
                                   List<Document> retrievedDocs,
                                   String context,
                                   double bestRetrievalScore,
                                   boolean allowWebFallback,
                                   String traceId,
                                   SkillExecutionBudget skillBudget) {
        SkillExecutionResult result = skillOrchestrator.execute(
                "evidence-evaluator",
                new SkillExecutionContext(
                        traceId,
                        "rag-service",
                        Map.of(
                                "retrievalQuery", rewrittenQuery == null ? "" : rewrittenQuery.fullQuery(),
                                "retrievedDocs", retrievedDocs == null ? List.of() : retrievedDocs,
                                "context", context == null ? "" : context,
                                "bestRetrievalScore", bestRetrievalScore,
                                "allowWebFallback", allowWebFallback
                        ),
                        skillBudget
                )
        );
        if (result.succeeded()) {
            return new EvidenceDecision(result.boolOutput("allowExternalLookup"), result.textOutput("reason"));
        }
        return new EvidenceDecision(
                shouldUseWebFallback(allowWebFallback, retrievedDocs, context, bestRetrievalScore),
                "LEGACY_WEB_FALLBACK"
        );
    }

    private boolean shouldUseWebFallback(boolean allowWebFallback,
                                         List<Document> retrievedDocs,
                                         String context,
                                         double bestRetrievalScore) {
        if (!allowWebFallback) {
            return false;
        }
        boolean groundedEvidencePresent = hasGroundedLocalEvidence(retrievedDocs);
        boolean graphOnlyContext = !groundedEvidencePresent && containsGraphHint(context);
        boolean emptyContext = !groundedEvidencePresent && ((context == null || context.isBlank()) || graphOnlyContext);
        RagRetrievalProperties.WebFallbackMode mode = ragRetrievalProperties.getWebFallbackMode();
        return switch (mode) {
            case NONE -> false;
            case ON_EMPTY -> emptyContext;
            case ON_LOW_QUALITY -> emptyContext || bestRetrievalScore < ragRetrievalProperties.getWebFallbackQualityThreshold();
        };
    }

    private boolean hasGroundedLocalEvidence(List<Document> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return false;
        }
        return retrievedDocs.stream().anyMatch(doc -> {
            if (doc == null) {
                return false;
            }
            String sourceType = String.valueOf(doc.getMetadata().getOrDefault("source_type", "")).trim().toLowerCase(Locale.ROOT);
            if ("graph_rag".equals(sourceType)) {
                return isSubstantiveGraphEvidence(doc.getText());
            }
            String text = doc.getText();
            return text != null && !text.isBlank();
        });
    }

    private boolean containsGraphHint(String context) {
        return context != null && context.contains("知识图谱关联提示");
    }

    private boolean isSubstantiveGraphEvidence(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        long conceptCount = text.chars().filter(ch -> ch == '（').count();
        return conceptCount >= 2 || text.contains("；");
    }

    public record EvidenceDecision(boolean allowExternalLookup, String reason) {
    }
}
```

- [ ] **Step 2: Inject EvidenceEvaluationService into RAGService**

Add field, constructor parameter, and assignment:

```java
private final EvidenceEvaluationService evidenceEvaluationService;
```

```java
EvidenceEvaluationService evidenceEvaluationService,
```

```java
this.evidenceEvaluationService = evidenceEvaluationService;
```

- [ ] **Step 3: Replace evidence decision logic**

Replace the `SkillExecutionResult evidenceDecision = ...` block in `buildKnowledgePacket` with:

```java
EvidenceEvaluationService.EvidenceDecision evidenceDecision = evidenceEvaluationService.decide(
        rewrittenQuery,
        retrievedDocs,
        context,
        bestRetrievalScore,
        allowWebFallback,
        traceId,
        skillBudget
);
boolean allowExternalLookup = evidenceDecision.allowExternalLookup();
String externalLookupReason = evidenceDecision.reason();
```

- [ ] **Step 4: Remove migrated methods from RAGService**

Delete:

```java
private SkillExecutionResult evaluateEvidenceSkill(...)
private boolean shouldUseWebFallback(...)
private boolean hasGroundedLocalEvidence(...)
private boolean containsGraphHint(...)
private boolean isSubstantiveGraphEvidence(...)
```

Remove unused `SkillExecutionResult` import if it becomes unused.

- [ ] **Step 5: Update RAGServiceTest construction**

Add import:

```java
import io.github.imzmq.interview.knowledge.application.retrieval.EvidenceEvaluationService;
```

Create and pass:

```java
EvidenceEvaluationService evidenceEvaluationService = new EvidenceEvaluationService(
        skillOrchestrator,
        ragRetrievalProperties
);
```

- [ ] **Step 6: Compile and focused test**

Run:

```bash
mvn -q compile
mvn -q -Dtest=RAGServiceTest test
```

Expected: compile exits `0`; focused test exits `0` or hits only the known Mockito inline environment failure.

- [ ] **Step 7: Commit**

Run:

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/EvidenceEvaluationService.java src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java
git commit -m "refactor: 提取证据评估服务"
```

Expected: commit succeeds.

## Task 6: Extract WebFallbackService

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/WebFallbackService.java`
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`

- [ ] **Step 1: Create WebFallbackService**

```java
package io.github.imzmq.interview.knowledge.application.retrieval;

import io.github.imzmq.interview.search.application.WebSearchTool;
import io.github.imzmq.interview.skill.client.SkillMcpClient;
import io.github.imzmq.interview.skill.core.SkillDefinition;
import io.github.imzmq.interview.skill.core.SkillExecutionBudget;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WebFallbackService {

    private final SkillOrchestrator skillOrchestrator;
    private final SkillMcpClient skillMcpClient;
    private final WebSearchTool webSearchTool;

    public WebFallbackService(SkillOrchestrator skillOrchestrator,
                              SkillMcpClient skillMcpClient,
                              WebSearchTool webSearchTool) {
        this.skillOrchestrator = skillOrchestrator;
        this.skillMcpClient = skillMcpClient;
        this.webSearchTool = webSearchTool;
    }

    public List<String> search(String query,
                               String traceId,
                               String reason,
                               SkillExecutionBudget skillBudget) {
        SkillDefinition definition = skillOrchestrator.definition("evidence-evaluator");
        SkillExecutionContext skillContext = new SkillExecutionContext(
                traceId,
                "rag-service",
                Map.of(
                        "query", query == null ? "" : query,
                        "reason", reason == null ? "" : reason
                ),
                skillBudget
        );
        Map<String, Object> mcpResult = skillMcpClient.invokeForSkill(
                "rag-service",
                definition,
                skillContext,
                "web.search",
                Map.of(
                        "query", query == null ? "" : query,
                        "limit", 3,
                        "reason", reason == null ? "" : reason
                )
        );
        List<String> snippets = extractSearchSnippets(mcpResult);
        if (!snippets.isEmpty()) {
            return snippets;
        }
        return webSearchTool.run(new WebSearchTool.Query(query, 3));
    }

    private List<String> extractSearchSnippets(Map<String, Object> mcpResult) {
        if (mcpResult == null || mcpResult.isEmpty()) {
            return List.of();
        }
        Object result = mcpResult.get("result");
        if (result instanceof List<?> list) {
            return stringifySearchList(list);
        }
        if (result instanceof Map<?, ?> map) {
            Object items = map.get("results");
            if (!(items instanceof List<?>)) {
                items = map.get("items");
            }
            if (!(items instanceof List<?>)) {
                items = map.get("snippets");
            }
            if (items instanceof List<?> list) {
                return stringifySearchList(list);
            }
            Object content = map.get("content");
            if (content instanceof String text && !text.isBlank()) {
                return List.of(text.trim());
            }
        }
        return List.of();
    }

    private List<String> stringifySearchList(List<?> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .map(this::searchSnippetOf)
                .filter(item -> item != null && !item.isBlank())
                .limit(3)
                .toList();
    }

    private String searchSnippetOf(Object item) {
        if (item instanceof String text) {
            return text.trim();
        }
        if (item instanceof Map<?, ?> map) {
            Object title = map.get("title");
            Object snippet = map.get("snippet");
            if (snippet == null) {
                snippet = map.get("content");
            }
            if (snippet == null) {
                snippet = map.get("summary");
            }
            String titleText = title == null ? "" : String.valueOf(title).trim();
            String snippetText = snippet == null ? "" : String.valueOf(snippet).trim();
            if (!titleText.isBlank() && !snippetText.isBlank()) {
                return titleText + " - " + snippetText;
            }
            return snippetText;
        }
        return item == null ? "" : String.valueOf(item).trim();
    }
}
```

- [ ] **Step 2: Inject WebFallbackService into RAGService**

Add field, constructor parameter, assignment:

```java
private final WebFallbackService webFallbackService;
```

```java
WebFallbackService webFallbackService,
```

```java
this.webFallbackService = webFallbackService;
```

- [ ] **Step 3: Delegate web search call**

Replace:

```java
List<String> webContext = runControlledWebSearch(rewrittenQuery.fullQuery(), traceId, externalLookupReason, skillBudget);
```

with:

```java
List<String> webContext = webFallbackService.search(rewrittenQuery.fullQuery(), traceId, externalLookupReason, skillBudget);
```

- [ ] **Step 4: Remove migrated methods from RAGService**

Delete:

```java
private List<String> runControlledWebSearch(...)
private List<String> extractSearchSnippets(...)
private List<String> stringifySearchList(...)
private String searchSnippetOf(...)
```

Remove unused imports for `SkillDefinition` and `SkillMcpClient` only if no remaining code uses them.

- [ ] **Step 5: Update RAGServiceTest construction**

Add import:

```java
import io.github.imzmq.interview.knowledge.application.retrieval.WebFallbackService;
```

Create and pass:

```java
SkillMcpClient skillMcpClient = new SkillMcpClient(mcpGatewayService);
WebFallbackService webFallbackService = new WebFallbackService(
        skillOrchestrator,
        skillMcpClient,
        webSearchTool
);
```

- [ ] **Step 6: Compile and focused test**

Run:

```bash
mvn -q compile
mvn -q -Dtest=RAGServiceTest test
```

Expected: compile exits `0`; focused test exits `0` or hits only the known Mockito inline environment failure.

- [ ] **Step 7: Commit**

Run:

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/retrieval/WebFallbackService.java src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java
git commit -m "refactor: 提取Web兜底检索服务"
```

Expected: commit succeeds.

## Task 7: Extract KnowledgePacketBuilder Skeleton

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/KnowledgePacketBuilder.java`
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`

- [ ] **Step 1: Create skeleton builder with callback interface**

Create `KnowledgePacketBuilder.java`:

```java
package io.github.imzmq.interview.knowledge.application;

import org.springframework.stereotype.Service;

@Service
public class KnowledgePacketBuilder {

    public RAGService.KnowledgePacket build(String question,
                                            String userAnswer,
                                            boolean allowWebFallback,
                                            BuildDelegate delegate) {
        return delegate.buildKnowledgePacketInternal(question, userAnswer, allowWebFallback);
    }

    @FunctionalInterface
    public interface BuildDelegate {
        RAGService.KnowledgePacket buildKnowledgePacketInternal(String question,
                                                                String userAnswer,
                                                                boolean allowWebFallback);
    }
}
```

- [ ] **Step 2: Inject builder into RAGService**

Add field, constructor parameter, assignment:

```java
private final KnowledgePacketBuilder knowledgePacketBuilder;
```

```java
KnowledgePacketBuilder knowledgePacketBuilder,
```

```java
this.knowledgePacketBuilder = knowledgePacketBuilder;
```

- [ ] **Step 3: Split internal method**

Change public method to delegate:

```java
public KnowledgePacket buildKnowledgePacket(String question, String userAnswer, boolean allowWebFallback) {
    return knowledgePacketBuilder.build(question, userAnswer, allowWebFallback, this::buildKnowledgePacketInternal);
}
```

Create private method containing the previous method body:

```java
private KnowledgePacket buildKnowledgePacketInternal(String question, String userAnswer, boolean allowWebFallback) {
    return executeWithinTraceRoot("KNOWLEDGE_PACKET", "Knowledge Packet Build", "Q: " + question, () -> {
        // existing body unchanged
    });
}
```

- [ ] **Step 4: Update RAGServiceTest construction**

Create and pass:

```java
KnowledgePacketBuilder knowledgePacketBuilder = new KnowledgePacketBuilder();
```

Add import:

```java
import io.github.imzmq.interview.knowledge.application.KnowledgePacketBuilder;
```

- [ ] **Step 5: Compile and focused test**

Run:

```bash
mvn -q compile
mvn -q -Dtest=RAGServiceTest test
```

Expected: compile exits `0`; focused test exits `0` or hits only the known Mockito inline environment failure.

- [ ] **Step 6: Commit**

Run:

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/KnowledgePacketBuilder.java src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java
git commit -m "refactor: 引入知识包构建器"
```

Expected: commit succeeds.

## Task 8: Move Main Build Flow into KnowledgePacketBuilder

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/KnowledgePacketBuilder.java`
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`

- [ ] **Step 1: Add dependencies to KnowledgePacketBuilder**

Move only dependencies needed by packet building into constructor fields:

```java
private final QueryRewriteService queryRewriteService;
private final EvidenceEvaluationService evidenceEvaluationService;
private final WebFallbackService webFallbackService;
private final SkillOrchestrator skillOrchestrator;
private final ObservabilitySwitchProperties observabilitySwitchProperties;
private final ImageService imageService;
```

Constructor:

```java
public KnowledgePacketBuilder(QueryRewriteService queryRewriteService,
                              EvidenceEvaluationService evidenceEvaluationService,
                              WebFallbackService webFallbackService,
                              SkillOrchestrator skillOrchestrator,
                              ObservabilitySwitchProperties observabilitySwitchProperties,
                              @org.springframework.lang.Nullable ImageService imageService) {
    this.queryRewriteService = queryRewriteService;
    this.evidenceEvaluationService = evidenceEvaluationService;
    this.webFallbackService = webFallbackService;
    this.skillOrchestrator = skillOrchestrator;
    this.observabilitySwitchProperties = observabilitySwitchProperties;
    this.imageService = imageService;
}
```

- [ ] **Step 2: Replace callback interface with helper interface**

Replace `BuildDelegate` with:

```java
public interface RetrievalDelegate {
    java.util.List<org.springframework.ai.document.Document> retrieveHybridDocuments(RewrittenQuery query, int topK);
    double bestRetrievalScore(String query, java.util.List<org.springframework.ai.document.Document> docs);
    String buildRetrievalEvidence(java.util.List<org.springframework.ai.document.Document> docs);
    String buildWebEvidence(java.util.List<String> webContext);
    java.util.List<ImageService.ImageResult> mergeImageResults(java.util.List<ImageService.ImageResult> associatedImages,
                                                               java.util.List<ImageService.ImageResult> semanticImages);
    boolean containsVisualIntent(String query);
    java.util.List<String> summarizeRetrievedDocuments(java.util.List<org.springframework.ai.document.Document> retrievedDocs);
    TraceNodeHandle startTraceChild(String traceId, String parentNodeId, TraceNodeDefinition definition, java.util.Map<String, Object> attributes);
    void completeTraceSuccess(TraceNodeHandle handle, java.util.Map<String, Object> attributes);
    void completeTraceSuccess(TraceNodeHandle handle,
                              java.util.Map<String, Object> attributes,
                              RAGObservabilityService.NodeMetrics metrics,
                              RAGObservabilityService.NodeDetails details);
}
```

- [ ] **Step 3: Move build body into builder**

Change builder method signature:

```java
public RAGService.KnowledgePacket build(String question,
                                        String userAnswer,
                                        boolean allowWebFallback,
                                        RetrievalDelegate delegate) {
    String traceId = RAGTraceContext.getTraceId();
    String parentNodeId = RAGTraceContext.getCurrentNodeId();
    SkillExecutionBudget skillBudget = skillOrchestrator.newBudget();
    TraceNodeHandle rewriteTrace = delegate.startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.QUERY_REWRITE, Map.of("status", "RUNNING"));
    RewrittenQuery rewrittenQuery = queryRewriteService.buildRewrittenQuery(question, userAnswer, skillBudget);
    delegate.completeTraceSuccess(rewriteTrace, Map.of("status", "COMPLETED"));
    if (observabilitySwitchProperties.isRagTraceEnabled()) {
        logger.info("Rewritten Query: CORE=[{}] EXPAND=[{}]", rewrittenQuery.coreTerms(), rewrittenQuery.expandTerms());
    }

    TraceNodeHandle docRetrieveTrace = delegate.startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.DOC_RETRIEVE, Map.of("status", "RUNNING"));
    List<Document> retrievedDocs = delegate.retrieveHybridDocuments(rewrittenQuery, 5);
    String context = retrievedDocs.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n"));
    double bestRetrievalScore = delegate.bestRetrievalScore(rewrittenQuery.fullQuery(), retrievedDocs);
    delegate.completeTraceSuccess(
            docRetrieveTrace,
            Map.of(
                    "docCount", retrievedDocs.size(),
                    "docRefs", delegate.summarizeRetrievedDocuments(retrievedDocs),
                    "status", "COMPLETED"
            ),
            new RAGObservabilityService.NodeMetrics(retrievedDocs.size(), false),
            new RAGObservabilityService.NodeDetails(
                    1,
                    KnowledgeRetrievalMode.RAG_ONLY.name(),
                    null,
                    false,
                    retrievedDocs.size(),
                    delegate.summarizeRetrievedDocuments(retrievedDocs),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            )
    );

    TraceNodeHandle associatedImageTrace = delegate.startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.IMAGE_ASSOC_RETRIEVE, Map.of("status", "RUNNING"));
    List<ImageService.ImageResult> associatedImages = imageService == null ? List.of() : imageService.findImagesForDocuments(retrievedDocs);
    delegate.completeTraceSuccess(associatedImageTrace, Map.of(
            "imageCount", associatedImages.size(),
            "status", "COMPLETED"
    ), null, new RAGObservabilityService.NodeDetails(
            1,
            KnowledgeRetrievalMode.RAG_ONLY.name(),
            null,
            false,
            null,
            List.of(),
            null,
            associatedImages.size(),
            null,
            null,
            null,
            null
    ));

    TraceNodeHandle semanticImageTrace = delegate.startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.IMAGE_SEMANTIC_RETRIEVE, Map.of("status", "RUNNING"));
    List<ImageService.ImageResult> semanticImages = imageService == null ? List.of() : imageService.searchRelevantImages(rewrittenQuery.fullQuery(), delegate.containsVisualIntent(rewrittenQuery.fullQuery()));
    delegate.completeTraceSuccess(semanticImageTrace, Map.of(
            "imageCount", semanticImages.size(),
            "status", "COMPLETED"
    ), null, new RAGObservabilityService.NodeDetails(
            1,
            KnowledgeRetrievalMode.RAG_ONLY.name(),
            null,
            false,
            null,
            List.of(),
            null,
            semanticImages.size(),
            null,
            null,
            null,
            null
    ));

    List<ImageService.ImageResult> retrievedImages = delegate.mergeImageResults(associatedImages, semanticImages);
    String imageContext = KnowledgeRetrievalCoordinator.buildImageContext(retrievedImages);
    String retrievalEvidence = delegate.buildRetrievalEvidence(retrievedDocs);
    boolean webFallbackUsed = false;
    EvidenceEvaluationService.EvidenceDecision evidenceDecision = evidenceEvaluationService.decide(
            rewrittenQuery,
            retrievedDocs,
            context,
            bestRetrievalScore,
            allowWebFallback,
            traceId,
            skillBudget
    );
    if (evidenceDecision.allowExternalLookup()) {
        TraceNodeHandle webFallbackTrace = delegate.startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.WEB_FALLBACK, Map.of("fallback", true, "status", "RUNNING"));
        List<String> webContext = webFallbackService.search(rewrittenQuery.fullQuery(), traceId, evidenceDecision.reason(), skillBudget);
        context = webContext.stream().collect(Collectors.joining("\n\n"));
        retrievalEvidence = delegate.buildWebEvidence(webContext);
        webFallbackUsed = true;
        delegate.completeTraceSuccess(
                webFallbackTrace,
                Map.of(
                        "fallback", true,
                        "fallbackReason", "LOW_RETRIEVAL_QUALITY",
                        "docCount", webContext.size(),
                        "status", "COMPLETED"
                ),
                new RAGObservabilityService.NodeMetrics(0, true),
                new RAGObservabilityService.NodeDetails(
                        1,
                        KnowledgeRetrievalMode.RAG_ONLY.name(),
                        null,
                        false,
                        webContext.size(),
                        List.of(),
                        webContext.size(),
                        null,
                        evidenceDecision.reason(),
                        null,
                        null,
                        null
                )
        );
    }

    return new RAGService.KnowledgePacket(rewrittenQuery.fullQuery(), retrievedDocs, retrievedImages, context, imageContext, retrievalEvidence, webFallbackUsed);
}
```

Also add required imports used by this method.

- [ ] **Step 4: Make RAGService implement the delegate**

Change class declaration:

```java
public class RAGService implements KnowledgePacketBuilder.RetrievalDelegate {
```

Change the public method to keep trace root and delegate:

```java
public KnowledgePacket buildKnowledgePacket(String question, String userAnswer, boolean allowWebFallback) {
    return executeWithinTraceRoot("KNOWLEDGE_PACKET", "Knowledge Packet Build", "Q: " + question,
            () -> knowledgePacketBuilder.build(question, userAnswer, allowWebFallback, this));
}
```

Delete `buildKnowledgePacketInternal`.

- [ ] **Step 5: Relax helper method visibility for interface implementation**

Change these methods from `private` to `public` in `RAGService` and add `@Override`:

```java
retrieveHybridDocuments(...)
bestRetrievalScore(...)
buildRetrievalEvidence(...)
buildWebEvidence(...)
mergeImageResults(...)
containsVisualIntent(...)
summarizeRetrievedDocuments(...)
startTraceChild(...)
completeTraceSuccess(TraceNodeHandle, Map<String,Object>)
completeTraceSuccess(TraceNodeHandle, Map<String,Object>, RAGObservabilityService.NodeMetrics, RAGObservabilityService.NodeDetails)
```

Expected: `RAGService` compiles as a concrete implementation of `KnowledgePacketBuilder.RetrievalDelegate`; no reflection or package scanning is introduced.

- [ ] **Step 6: Update RAGServiceTest construction**

Use the real constructor:

```java
KnowledgePacketBuilder knowledgePacketBuilder = new KnowledgePacketBuilder(
        queryRewriteService,
        evidenceEvaluationService,
        webFallbackService,
        skillOrchestrator,
        observabilitySwitchProperties,
        imageService
);
```

- [ ] **Step 7: Compile and focused test**

Run:

```bash
mvn -q compile
mvn -q -Dtest=RAGServiceTest test
```

Expected: compile exits `0`; focused test exits `0` or hits only the known Mockito inline environment failure.

- [ ] **Step 8: Commit**

Run:

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/KnowledgePacketBuilder.java src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java
git commit -m "refactor: 拆分知识包构建主流程"
```

Expected: commit succeeds.

## Task 9: Remove Obsolete RAGService Dependencies

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`

- [ ] **Step 1: Remove extracted-service fields from RAGService**

After Task 8, remove these fields from `RAGService` because `KnowledgePacketBuilder` owns them:

```java
private final WebSearchTool webSearchTool;
private final ImageService imageService;
private final QueryRewriteService queryRewriteService;
private final EvidenceEvaluationService evidenceEvaluationService;
private final WebFallbackService webFallbackService;
```

Keep `private final SkillOrchestrator skillOrchestrator;` because non-packet methods such as strategy/report/coding helpers still use it.

- [ ] **Step 2: Simplify constructor parameters**

Remove matching constructor parameters:

```java
WebSearchTool webSearchTool,
@org.springframework.lang.Nullable ImageService imageService,
QueryRewriteService queryRewriteService,
EvidenceEvaluationService evidenceEvaluationService,
WebFallbackService webFallbackService,
```

Keep:

```java
SkillOrchestrator skillOrchestrator,
KnowledgePacketBuilder knowledgePacketBuilder,
```

- [ ] **Step 3: Update tests for constructor signature**

Remove deleted arguments from `new RAGService(...)` in `RAGServiceTest`.

- [ ] **Step 4: Compile**

Run:

```bash
mvn -q compile
```

Expected: exit code `0`.

- [ ] **Step 5: Commit**

Run:

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java
git commit -m "refactor: 精简RAGService构造依赖"
```

Expected: commit succeeds.

## Task 10: Update Architecture Documentation

**Files:**
- Modify: `PACKAGE_CONVENTIONS.md`

- [ ] **Step 1: Update knowledge package breakdown**

Replace:

```markdown
- RAG core entry only: `knowledge.application` (currently only `RAGService`).
- Retrieval orchestration/fusion and RAG adapter: `knowledge.application.retrieval`.
```

with:

```markdown
- RAG compatibility entry and packet builder: `knowledge.application` (`RAGService` remains a facade; new code should prefer focused services where possible).
- Retrieval orchestration/fusion, query rewrite, evidence evaluation, Web fallback, and RAG adapters: `knowledge.application.retrieval`.
```

- [ ] **Step 2: Update forbidden placement note**

Replace:

```markdown
- Do not add new non-core classes into `knowledge.application` root.
```

with:

```markdown
- Do not add new non-core classes into `knowledge.application` root; do not add new responsibilities directly to `RAGService`.
```

- [ ] **Step 3: Compile docs-independent code**

Run:

```bash
mvn -q compile
```

Expected: exit code `0`.

- [ ] **Step 4: Commit**

Run:

```bash
git add PACKAGE_CONVENTIONS.md
git commit -m "docs: 更新知识模块拆分约定"
```

Expected: commit succeeds.

## Task 11: Final Verification

**Files:**
- Verify: repository-wide compile/test state

- [ ] **Step 1: Check formatting whitespace**

Run:

```bash
git diff --check
```

Expected: no trailing whitespace or conflict markers.

- [ ] **Step 2: Compile all main code**

Run:

```bash
mvn -q compile
```

Expected: exit code `0`.

- [ ] **Step 3: Run architecture rules**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: exit code `0`.

- [ ] **Step 4: Run focused RAG tests**

Run:

```bash
mvn -q -Dtest=RAGServiceTest,ParentChildRetrievalHydrationTest,KnowledgeRetrievalCoordinatorTest,RetrievalEvaluationServiceTest test
```

Expected: exit code `0`, or known Mockito inline Byte Buddy self-attach environment failure. If it fails for any other reason, fix before proceeding.

- [ ] **Step 5: Optional full verify without tests**

Run:

```bash
mvn -q verify -DskipTests
```

Expected: exit code `0`.

- [ ] **Step 6: Summarize result**

Record:

```text
Changed:
- Query rewrite extracted to QueryRewriteService.
- Evidence decision extracted to EvidenceEvaluationService.
- Web fallback extracted to WebFallbackService.
- buildKnowledgePacket now delegates to KnowledgePacketBuilder.

Verification:
- mvn -q compile: PASS
- mvn -q -Dtest=ArchitectureRulesTest test: PASS
- focused RAG tests: PASS or blocked by documented local Mockito attach issue
```

Do not claim full `mvn test` passed unless it actually ran successfully.
