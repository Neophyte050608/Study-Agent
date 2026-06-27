# Coding Practice Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the coding-practice generation/evaluation chain from `RAGService` into a focused `CodingPracticeService` while preserving existing public APIs and behavior.

**Architecture:** `CodingPracticeService` owns coding question generation, batch quiz generation, coding answer evaluation, next-question generation, and their fallback/helper logic. `RAGService` remains a compatibility facade and delegates existing public coding methods to the new service; `CodingPracticeAgent` keeps using `RAGService` in this phase.

**Tech Stack:** Java 17, Spring Boot, Spring AI, Jackson, Maven, JUnit 5, Mockito, ArchUnit.

---

## File Structure

- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeService.java` — coding-practice orchestration and helper logic.
- Create: `src/test/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeServiceTest.java` — focused characterization tests for the extracted service.
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java` — inject `CodingPracticeService`, delegate coding public methods, remove migrated private helpers.
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java` — update constructor wiring and keep facade compatibility tests.
- Modify: `src/test/java/io/github/imzmq/interview/service/ParentChildRetrievalHydrationTest.java` — update constructor wiring.
- Modify: `PACKAGE_CONVENTIONS.md` — document `knowledge.application.coding` ownership.

## Task 1: Baseline and Characterization Tests

**Files:**
- Create: `src/test/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`

- [ ] **Step 1: Run baseline gates**

Run:
```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
```
Expected: all commands exit 0. If Maven cannot write `~/.m2`, rerun the exact command with sandbox escalation.

- [ ] **Step 2: Add service-level tests with current behavior expectations**

Create `CodingPracticeServiceTest` with tests for:
```java
// shouldInjectCodingCoachSkillIntoQuestionPrompt
// shouldInjectCodingCoachSkillIntoEvaluationPrompt
// shouldReturnFallbackAssessmentForBlankAnswer
// shouldReturnFallbackAssessmentWhenEvaluationJsonCannotBeParsed
```
Use lightweight test doubles where possible. If Mockito inline self-attach fails in this environment, prefer hand-written stubs for final classes/services so `CodingPracticeServiceTest` can run without Mockito.

- [ ] **Step 3: Keep facade coverage in `RAGServiceTest`**

Ensure existing tests still cover:
```java
ragService.generateCodingQuestion("数组与字符串", "medium", "高级后端开发", List.of());
ragService.evaluateCodingAnswer("算法", "medium", "Two Sum", "使用 HashMap 一次遍历");
```
These tests prove current external callers can keep using `RAGService` after delegation.

- [ ] **Step 4: Run focused tests**

Run:
```bash
mvn -q -Dtest=CodingPracticeServiceTest test
mvn -q -Dtest=RAGServiceTest test
```
Expected before implementation: `CodingPracticeServiceTest` may fail to compile until `CodingPracticeService` exists; after implementation both should pass.

## Task 2: Create CodingPracticeService

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeService.java`

- [ ] **Step 1: Add service skeleton**

Create a Spring `@Service` with constructor dependencies:
```java
RoutingChatService routingChatService;
AgentSkillService agentSkillService;
PromptManager promptManager;
SkillOrchestrator skillOrchestrator;
LlmJsonParser llmJsonParser;
```
Do not inject `RAGService`.

- [ ] **Step 2: Move public coding methods**

Move or copy these methods from `RAGService` into `CodingPracticeService`:
```java
public String generateCodingQuestion(String topic, String difficulty, String profileSnapshot, List<String> excludedTopics)
public List<CodingPracticeAgent.QuizQuestion> generateBatchQuiz(String topic, String difficulty, int count, String profileSnapshot, List<String> excludedTopics)
public RAGService.CodingAssessment evaluateCodingAnswer(String topic, String difficulty, String question, String answer)
public String generateNextCodingQuestion(String topic, String difficulty, String question, String answer, int score)
```
Keep method signatures and return values unchanged except for class ownership.

- [ ] **Step 3: Move coding-only helpers**

Move these helper methods from `RAGService` into `CodingPracticeService`:
```java
buildFallbackCodingQuestion(...)
fallbackBatchQuiz(...)
buildInlineBatchQuizPrompt(...)
fallbackNextCodingQuestion(...)
fallbackCodingAssessment(...)
normalizeCodingQuestionType(...)
resolveCodingSkillBlock(...)
resolveCodingCoachSummary(...)
```
Also add local copies of shared helpers needed by the service:
```java
buildTopicWithExclusion(...)
callWithRetry(...)
truncate(...)
safeSkillText(...)
summarizeError(...) and upstream sanitization helpers, if logging needs identical safe messages
mergeSkillGuidance(...), if coding chain uses it
```
Do not remove shared helpers from `RAGService` until grep confirms no other non-coding methods use them.

## Task 3: Convert RAGService Coding Methods to Delegates

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java`

- [ ] **Step 1: Inject new service**

Add:
```java
private final CodingPracticeService codingPracticeService;
```
Add `CodingPracticeService codingPracticeService` to the constructor and assign it.

- [ ] **Step 2: Delegate public coding methods**

Replace coding method bodies with direct delegation:
```java
public String generateCodingQuestion(String topic, String difficulty, String profileSnapshot, List<String> excludedTopics) {
    return codingPracticeService.generateCodingQuestion(topic, difficulty, profileSnapshot, excludedTopics);
}

public List<CodingPracticeAgent.QuizQuestion> generateBatchQuiz(String topic, String difficulty, int count, String profileSnapshot, List<String> excludedTopics) {
    return codingPracticeService.generateBatchQuiz(topic, difficulty, count, profileSnapshot, excludedTopics);
}

public CodingAssessment evaluateCodingAnswer(String topic, String difficulty, String question, String answer) {
    return codingPracticeService.evaluateCodingAnswer(topic, difficulty, question, answer);
}

public String generateNextCodingQuestion(String topic, String difficulty, String question, String answer, int score) {
    return codingPracticeService.generateNextCodingQuestion(topic, difficulty, question, answer, score);
}
```

- [ ] **Step 3: Remove migrated private helpers only when unused**

Check usage before deleting:
```bash
grep -R "normalizeCodingQuestionType\|resolveCodingSkillBlock\|resolveCodingCoachSummary\|fallbackCodingAssessment\|fallbackNextCodingQuestion\|buildFallbackCodingQuestion\|buildInlineBatchQuizPrompt" -n src/main/java src/test/java
```
Remove migrated helpers from `RAGService` only if remaining references are inside `CodingPracticeService` or tests.

## Task 4: Update Tests and Package Conventions

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/service/ParentChildRetrievalHydrationTest.java`
- Modify: `PACKAGE_CONVENTIONS.md`

- [ ] **Step 1: Update test constructors**

Where tests call `new RAGService(...)`, instantiate and pass a `CodingPracticeService` using the same routing/prompt/skill/parser dependencies already available in the test setup.

- [ ] **Step 2: Update package convention document**

Add to Knowledge package breakdown:
```markdown
- Coding practice question generation/evaluation/quiz fallback: `knowledge.application.coding`.
```
Update forbidden placement to say new coding-practice logic should not be added to `RAGService`.

## Task 5: Verification, Self-Review, and Commit

**Files:**
- All modified production, test, and docs files.

- [ ] **Step 1: Run final gates**

Run:
```bash
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q -Dtest=CodingPracticeServiceTest test
mvn -q -Dtest=RAGServiceTest test
mvn -q verify -DskipTests
git diff --check
```
Expected: all commands exit 0.

- [ ] **Step 2: Self-review diff**

Run:
```bash
git diff --stat
git diff -- src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java
git diff -- src/main/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeService.java
```
Confirm:
```text
- No CodingPracticeService -> RAGService injection.
- RAGService.CodingAssessment remains available.
- CodingPracticeAgent can still call RAGService methods.
- Fallback text and score heuristics are copied unchanged.
- Batch quiz fallback calls CodingPracticeService.generateCodingQuestion, not RAGService.
```

- [ ] **Step 3: Commit implementation**

Run:
```bash
git add PACKAGE_CONVENTIONS.md \
        src/main/java/io/github/imzmq/interview/knowledge/application/RAGService.java \
        src/main/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeService.java \
        src/test/java/io/github/imzmq/interview/knowledge/application/coding/CodingPracticeServiceTest.java \
        src/test/java/io/github/imzmq/interview/service/RAGServiceTest.java \
        src/test/java/io/github/imzmq/interview/service/ParentChildRetrievalHydrationTest.java

git commit -m "refactor: 拆分编程练习链路"
```
