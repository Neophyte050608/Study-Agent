# Durable Dynamic Plan Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the submission-time Learning Plan Preview capability graph as an immutable durable snapshot and make worker recovery and partial retry execute that snapshot without replanning.

**Architecture:** Replace the durable workflow's repeatable `nodes(...)` contract with explicit `prepare(...)` and `restore(...)` phases. Keep workflow v1 for historical fixed definitions, submit all new tasks as v2, store the v2 capability plan in `definition_json`, and compile it through the existing dynamic capability registry on every recovery without invoking a planner.

**Tech Stack:** Java 21, Spring Boot, Jackson strict JSON parsing, MyBatis, MySQL JSON, JUnit 5, Mockito, AssertJ, ArchUnit, Maven.

---

## File and responsibility map

### Agent contract and runtime

- Modify `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/durable/DurableWorkflow.java`
  - Define the `prepare`/`restore` lifecycle and immutable prepared-workflow result.
- Modify `src/main/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionService.java`
  - Persist the prepared definition and recover dependencies from the persisted definition during retry.
- Modify `src/main/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionWorker.java`
  - Restore the persisted graph instead of regenerating it.
- Modify `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicCapabilityRegistry.java`
  - Retain exact capability versions while exposing only the latest version for new planning.
- Modify `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DefaultDynamicPlanningOperations.java`
  - Compile persisted plans by exact capability ID/version through the updated registry.

### Learning planning and workflow ownership

- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/RuleBasedLearningPlanPlanner.java`
  - Produce the canonical full fallback candidate.
- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningService.java`
  - Return the final validated plan, digest, compiled nodes, source, and stable fallback code.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreparedGraph.java`
  - Hold the immutable submission-time plan result used by sync execution and durable snapshot creation.
- Rename `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflow.java`
  - To `LearningPlanWorkflowV1.java`; retain strict historical fixed-definition recovery.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV2.java`
  - Prepare and restore the capability snapshot for new durable tasks.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowSnapshotCodec.java`
  - Strictly encode and decode the workflow-specific v2 snapshot schema.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowOutputCodec.java`
  - Share output/result encoding between workflow v1 and v2 without coupling v2 to v1.
- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/DurableLearningPlanService.java`
  - Submit v2 and validate retry node IDs from the owner-scoped task view.
- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanTaskPresenter.java`
  - Accept v1/v2 and project the actual v2 node set.

### Tests and documentation

- Modify the existing Agent durable, dynamic planning, and Learning planning tests named in each task.
- Create focused codec/v2 workflow tests rather than hiding protocol coverage in a broad Spring test.
- Modify `PACKAGE_CONVENTIONS.md` and `docs/development/learning-plan-preview.md` so current documentation no longer describes durable execution as fixed-only.
- No Flyway file is created or modified.

## Implementation constraints

- Do not alter or stage `AGENTS.md`, `.DS_Store`, `.dev/`, credentials, build output, or local private configuration.
- Do not add old/new runtime mode switches, compatibility aliases, optional production dependencies, no-op adapters, or test-only production constructors.
- Do not call a real model or external API in tests. No API Key is needed.
- Use the existing Byte Buddy agent argument for Mockito tests under JDK 21:

```bash
-DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar'
```

---

### Task 1: Replace repeatable graph generation with prepare/restore lifecycle

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/durable/DurableWorkflow.java`
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionService.java`
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionWorker.java`
- Rename: `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflow.java` to `LearningPlanWorkflowV1.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionWorkerTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionMySqlIntegrationTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningServiceTest.java`
- Rename: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowTest.java` to `LearningPlanWorkflowV1Test.java`

- [ ] **Step 1: Add failing service and worker tests for the new lifecycle**

Replace workflow stubs that use `nodes(...)` with assertions that submission calls `prepare(...)` once and retry/worker call `restore(...)` with the persisted definition:

```java
@Test
void submissionPersistsPreparedDefinitionAndRetryRestoresItWithoutPreparingAgain() {
    DurableWorkflow workflow = mock(DurableWorkflow.class);
    DagNode source = new DagNode("source", Set.of(), true, ignored -> "ok");
    when(registry.require("TEST", 2)).thenReturn(workflow);
    when(workflow.prepare("owner", "{}"))
            .thenReturn(new DurableWorkflow.Prepared("{\"schemaVersion\":\"2\"}", List.of(source)));
    when(workflow.restore("owner", "{}", "{\"schemaVersion\":\"2\"}"))
            .thenReturn(List.of(source));

    service.submit("owner", "TEST", 2, "key", "{}");
    service.retry("owner", "task", 0, Set.of("source"));

    verify(workflow).prepare("owner", "{}");
    verify(workflow).restore("owner", "{}", "{\"schemaVersion\":\"2\"}");
    verifyNoMoreInteractions(workflow);
}
```

Add a worker assertion with a planner-like failure on `prepare` to prove recovery never reaches it:

```java
when(workflow.prepare(anyString(), anyString()))
        .thenThrow(new AssertionError("worker must not prepare"));
when(workflow.restore("owner", "{}", persistedDefinition))
        .thenReturn(nodes);
worker.run(task);
verify(workflow, never()).prepare(anyString(), anyString());
verify(workflow).restore("owner", "{}", persistedDefinition);
```

- [ ] **Step 2: Run the focused tests and verify they fail against `nodes(...)`**

Run:

```bash
mvn -q -Dtest=DurableExecutionServiceTest,DurableExecutionWorkerTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: test compilation fails because `DurableWorkflow.Prepared`, `prepare`, and `restore` do not exist.

- [ ] **Step 3: Define the final workflow lifecycle contract**

Replace `nodes(...)` in `DurableWorkflow` with:

```java
public interface DurableWorkflow {
    String type();
    int version();
    Prepared prepare(String owner, String inputJson);
    List<DagNode> restore(String owner, String inputJson, String definitionJson);
    String encodeOutput(String nodeId, Object output);
    Object decodeOutput(String nodeId, String outputJson);
    WorkflowOutcome outcome(DagExecutionResult result);

    record Prepared(String definitionJson, List<DagNode> nodes) {
        public Prepared {
            if (definitionJson == null || definitionJson.isBlank()) {
                throw new IllegalArgumentException("WORKFLOW_DEFINITION_INVALID");
            }
            nodes = List.copyOf(nodes);
        }
    }

    record WorkflowOutcome(String status, String resultJson) { }
}
```

This is the only lifecycle contract; do not keep a default `nodes(...)` compatibility method.

- [ ] **Step 4: Make Agent persist prepared definitions and restore persisted definitions**

In `DurableExecutionService.submit`, use:

```java
var workflow = registry.require(type, version);
var prepared = workflow.prepare(owner, input);
String definition = json.check(prepared.definitionJson(), 65536);
List<DagNode> nodes = validateNodes(prepared.nodes());
```

Move generic DAG/node-ID validation from the old `definition(nodes)` method into `validateNodes(...)`. Do not regenerate or serialize a definition in Agent.

In `retry`, use:

```java
String definition = json.check(identity.getDefinitionJson(), 65536);
var nodes = validateNodes(workflow.restore(owner, identity.getInputJson(), definition));
Map<String, Set<String>> dependencies = dependencies(nodes);
tx.retry(owner, id, revision, Set.copyOf(selected), dependencies);
```

In `DurableExecutionWorker.run`, use the same `restore(...)` call and retain the existing snapshot-node equality check before execution.

- [ ] **Step 5: Convert the existing Learning workflow into strict v1 preparation and recovery**

Rename the component class to `LearningPlanWorkflowV1`. Keep `version() == 1` and the fixed node factory. Add a private v1 definition record:

```java
private record NodeDefinition(
        String id,
        List<String> dependencies,
        boolean requireSuccess) { }
```

`prepare(...)` must build fixed nodes and encode the same sorted JSON array produced by the old Agent serializer. `restore(...)` must strictly parse the persisted array, compare it semantically with the expected fixed graph, and throw `WORKFLOW_DEFINITION_MISMATCH` for changed IDs, dependencies, or `requireSuccess`. Strict parsing must reject duplicate fields, trailing tokens, and unknown fields.

Update the anonymous workflow in `DurableExecutionMySqlIntegrationTest` to return its existing fixed definition from `prepare(...)` and reconstruct the same nodes from `restore(...)`. Update the durable-fixed assertion in `LearningPlanDynamicPlanningServiceTest` to instantiate `LearningPlanWorkflowV1` and assert `prepare(...).nodes()`. These are compile-required lifecycle migrations, not v2 behavior changes.

- [ ] **Step 6: Run lifecycle and v1 compatibility tests**

Run:

```bash
mvn -q -Dtest=DurableExecutionServiceTest,DurableExecutionWorkerTest,LearningPlanWorkflowV1Test \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: PASS; the worker/retry tests verify `restore`, and the v1 test accepts a historical fixed definition but rejects altered topology.

- [ ] **Step 7: Commit the lifecycle change**

```bash
git add -A -- \
  src/main/java/io/github/imzmq/interview/agent/application/contract/execution/durable/DurableWorkflow.java \
  src/main/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionService.java \
  src/main/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionWorker.java \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflow.java \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV1.java \
  src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionServiceTest.java \
  src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionWorkerTest.java \
  src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionMySqlIntegrationTest.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningServiceTest.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowTest.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV1Test.java
git commit -m "refactor(execution): restore durable graphs from definitions"
```

---

### Task 2: Make capability versions durable-compatible

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicCapabilityRegistry.java`
- Modify: `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DefaultDynamicPlanningOperations.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicCapabilityRegistryTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DefaultDynamicPlanningOperationsTest.java`

- [ ] **Step 1: Add failing tests for current-version planning and exact-version compilation**

Register two test capabilities with the same ID and versions 1 and 2. Assert:

```java
assertThat(registry.descriptors())
        .extracting(DynamicCapabilityDescriptor::capabilityVersion)
        .containsExactly(2);
assertThat(registry.lookup("test.capability", 1)).isSameAs(versionOne);
assertThat(registry.lookup("test.capability", 2)).isSameAs(versionTwo);
```

Add an operations test proving a new candidate validates to version 2 while a persisted `ValidatedDynamicTaskPlan` containing version 1 compiles through version 1.

- [ ] **Step 2: Run tests and verify duplicate IDs fail today**

Run:

```bash
mvn -q -Dtest=DynamicCapabilityRegistryTest,DefaultDynamicPlanningOperationsTest test
```

Expected: FAIL because registry descriptors currently expose both versions and `DynamicTaskPlanValidator` rejects duplicate capability IDs.

- [ ] **Step 3: Retain exact versions and expose only the latest descriptor for planning**

Change `DynamicCapabilityRegistry` to maintain:

```java
private final Map<CapabilityKey, DynamicNodeCapability<?>> capabilities;
private final List<DynamicCapabilityDescriptor> currentDescriptors;
```

Registration rules:

```java
CapabilityKey key = new CapabilityKey(id, version);
if (registered.putIfAbsent(key, capability) != null) {
    throw new IllegalArgumentException("Duplicate dynamic capability version");
}
currentById.merge(id, descriptor,
        (left, right) -> left.capabilityVersion() > right.capabilityVersion() ? left : right);
```

`descriptors()` returns one highest-version descriptor per ID in deterministic capability-ID order. `lookup(id, version)` remains exact and never falls forward to a newer version. `DefaultDynamicPlanningOperations` continues constructing the new-plan validator from `registry.descriptors()` and the compiler from the exact registry.

- [ ] **Step 4: Re-run capability version tests**

Run:

```bash
mvn -q -Dtest=DynamicCapabilityRegistryTest,DefaultDynamicPlanningOperationsTest,DynamicTaskPlanCompilerTest,DynamicTaskPlanValidatorTest test
```

Expected: PASS; new planning selects the latest descriptor and persisted compilation honors the exact version.

- [ ] **Step 5: Commit capability version semantics**

```bash
git add \
  src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicCapabilityRegistry.java \
  src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DefaultDynamicPlanningOperations.java \
  src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicCapabilityRegistryTest.java \
  src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DefaultDynamicPlanningOperationsTest.java
git commit -m "feat(planning): preserve dynamic capability versions"
```

---

### Task 3: Produce one final prepared plan for sync and durable execution

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreparedGraph.java`
- Modify: `src/main/java/io/github/imzmq/interview/learning/application/planning/RuleBasedLearningPlanPlanner.java`
- Modify: `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningService.java`
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/RuleBasedLearningPlanPlannerTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewTestFixtures.java`

- [ ] **Step 1: Add failing tests for prepared rule and fallback plans**

Test the normal case:

```java
LearningPlanPreparedGraph prepared = service.prepare(context);
assertThat(prepared.source()).isEqualTo(LearningPlanPreparedGraph.Source.RULES);
assertThat(prepared.fallbackCode()).isNull();
assertThat(prepared.plan().scenarioId()).isEqualTo("learning-plan-preview");
assertThat(prepared.digest()).hasSize(64);
assertThat(prepared.nodes()).extracting(DagNode::id)
        .containsExactlyInAnyOrder("evidence", "compose");
```

Test invalid rule output:

```java
when(dynamicPlanning.validate(ruleCandidate, policy, limits))
        .thenReturn(DynamicPlanValidationResult.invalid(
                List.of(new DynamicPlanIssue("INVALID_ARGUMENTS", "evidence"))));
when(dynamicPlanning.validate(fallbackCandidate, policy, limits))
        .thenReturn(DynamicPlanValidationResult.valid(fallbackPlan));

LearningPlanPreparedGraph prepared = service.prepare(context);
assertThat(prepared.source()).isEqualTo(LearningPlanPreparedGraph.Source.FIXED_FALLBACK);
assertThat(prepared.fallbackCode()).isEqualTo("INVALID_ARGUMENTS");
assertThat(prepared.plan()).isEqualTo(fallbackPlan);
```

Also prove fallback validation/compile failure propagates and is not replaced by direct fixed nodes.

- [ ] **Step 2: Run tests and verify no prepared result exists**

Run:

```bash
mvn -q -Dtest=RuleBasedLearningPlanPlannerTest,LearningPlanDynamicPlanningServiceTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: test compilation fails because `LearningPlanPreparedGraph` and `prepare(...)` do not exist.

- [ ] **Step 3: Add the immutable prepared-plan type**

Create:

```java
record LearningPlanPreparedGraph(
        ValidatedDynamicTaskPlan plan,
        String digest,
        List<DagNode> nodes,
        Source source,
        String fallbackCode) {

    enum Source { RULES, FIXED_FALLBACK }

    LearningPlanPreparedGraph {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(digest);
        nodes = List.copyOf(nodes);
        Objects.requireNonNull(source);
        if ((source == Source.RULES) != (fallbackCode == null)) {
            throw new IllegalArgumentException("Invalid prepared plan source");
        }
    }
}
```

Keep it package-private because it is an internal Learning preparation result, not a cross-module contract.

- [ ] **Step 4: Make the rule planner produce a canonical fallback candidate**

Add `fixedFallback()` that always returns the full three-node candidate using the same stable capability IDs and evidence limit as normal rule planning:

```java
CandidateDynamicTaskPlan fixedFallback() {
    return new CandidateDynamicTaskPlan("1", List.of(
            node("profile", "learning.profile.lookup", List.of(), Map.of()),
            node("evidence", "knowledge.evidence.retrieve", List.of(), Map.of("limit", 4)),
            node("compose", "learning.plan.compose",
                    List.of("profile", "evidence"), Map.of())));
}
```

Do not add a second fallback factory or continue compiling fallback through `LearningPlanFixedNodeFactory`.

- [ ] **Step 5: Refactor dynamic planning around the final prepared plan**

Implement:

```java
LearningPlanPreparedGraph prepare(DynamicExecutionContext context)
```

Normal flow validates, digests, compiles, records observations, submits optional shadow comparison, and returns `Source.RULES`. On a stable planning-stage failure, record rejection/fallback, validate/digest/compile `planner.fixedFallback()`, and return `Source.FIXED_FALLBACK` with the stable code. Fallback failure propagates as `PLANNING_FAILED` or the existing stable code; it must not call `LearningPlanFixedNodeFactory`.

Remove `LearningPlanFixedNodeFactory` from this service's fields and constructor. Update `LearningPlanPreviewTestFixtures` and direct test construction accordingly. The fixed node factory remains a real implementation only for historical workflow v1; no new planning path may inject or call it.

Keep sync behavior through:

```java
public List<DagNode> nodes(DynamicExecutionContext context) {
    return prepare(context).nodes();
}
```

This method is the sync execution API, not a deprecated durable compatibility path.

- [ ] **Step 6: Run prepared-plan tests and existing sync planning tests**

Run:

```bash
mvn -q -Dtest=RuleBasedLearningPlanPlannerTest,LearningPlanDynamicPlanningServiceTest,LearningPlanPreviewServiceTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: PASS; sync execution still receives compiled nodes, while durable callers can retain the validated plan and digest.

- [ ] **Step 7: Commit prepared planning**

```bash
git add \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreparedGraph.java \
  src/main/java/io/github/imzmq/interview/learning/application/planning/RuleBasedLearningPlanPlanner.java \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningService.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/RuleBasedLearningPlanPlannerTest.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningServiceTest.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewTestFixtures.java
git commit -m "refactor(planning): expose final prepared learning graph"
```

---

### Task 4: Add the strict v2 snapshot codec and workflow

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowSnapshotCodec.java`
- Create: `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowOutputCodec.java`
- Create: `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV2.java`
- Modify: `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV1.java`
- Create: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowSnapshotCodecTest.java`
- Create: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV2Test.java`
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV1Test.java`

- [ ] **Step 1: Add failing codec tests for canonical round-trip and strict rejection**

Build a prepared two-node rule plan and assert the encoded tree contains exactly:

```java
Set.of(
        "schemaVersion", "workflowType", "workflowVersion", "scenarioId",
        "planSource", "fallbackCode", "planDigest", "nodes")
```

Assert node objects contain exactly:

```java
Set.of(
        "id", "capabilityId", "capabilityVersion",
        "dependsOn", "arguments", "requireSuccess")
```

Add parameterized malformed definitions for:

- duplicate `planDigest`;
- trailing JSON content;
- unknown root/node fields;
- schema version other than `2`;
- wrong workflow type/version/scenario;
- invalid `planSource/fallbackCode` pairing;
- duplicate node IDs;
- invalid node ID or dependency;
- arguments containing arrays, floating-point values, nulls, or nested depth beyond the supported scalar/map format;
- definition larger than the existing 65,536-byte Agent boundary.

Each malformed case must throw `IllegalArgumentException` with `WORKFLOW_DEFINITION_INVALID` rather than exposing a Jackson message.

- [ ] **Step 2: Run codec tests and verify the codec is absent**

Run:

```bash
mvn -q -Dtest=LearningPlanWorkflowSnapshotCodecTest test
```

Expected: test compilation fails because the codec and snapshot records do not exist.

- [ ] **Step 3: Implement canonical encode and strict decode**

`LearningPlanWorkflowSnapshotCodec` must expose package-private operations:

```java
String encode(LearningPlanPreparedGraph prepared);
Decoded decode(String definitionJson);
```

Use private records for the persisted shape and return only the semantic restore data:

```java
record Decoded(
        ValidatedDynamicTaskPlan plan,
        String digest,
        LearningPlanPreparedGraph.Source source,
        String fallbackCode,
        Map<String, Boolean> requireSuccess) { }
```

Encoding rules:

- sort nodes by ID;
- sort dependencies and argument map keys;
- copy only string, integer, boolean, and nested map values accepted by `DynamicPlanFingerprint`;
- derive `requireSuccess` from the compiled `DagNode` with the same ID;
- write `fallbackCode=null` for `RULES` and a stable code for `FIXED_FALLBACK`.

Decoding rules:

- enable `STRICT_DUPLICATE_DETECTION` and `FAIL_ON_TRAILING_TOKENS`;
- compare exact field sets at every object level;
- apply explicit length/count/depth limits before constructing plan records;
- for snapshot schema `2`, reconstruct dynamic-plan schema as `"1"`; these are separate version domains, and dynamic-plan schema `"1"` is part of the existing fingerprint;
- construct `ValidatedDynamicTaskPlan` with `estimatedCost=0`, because cost is a submission-time budget result and is not part of the execution fingerprint;
- preserve only the definition data needed for semantic validation and exact compilation.

- [ ] **Step 4: Add failing v2 workflow tests proving prepare-once and no-replan restore**

Prepare test:

```java
when(planning.prepare(context)).thenReturn(prepared);
DurableWorkflow.Prepared result = workflow.prepare("owner", inputJson);
assertThat(result.nodes()).isEqualTo(prepared.nodes());
assertThat(result.definitionJson()).contains("\"workflowVersion\":2");
verify(planning).prepare(context);
```

Restore test:

```java
when(dynamicPlanning.digest(decoded.plan())).thenReturn(decoded.digest());
when(dynamicPlanning.compile(decoded.plan(), context)).thenReturn(compiledNodes);
List<DagNode> restored = workflow.restore("owner", inputJson, definitionJson);
assertThat(restored).isEqualTo(compiledNodes);
verifyNoInteractions(rulePlanner);
```

Also test digest mismatch, scenario-policy rejection, missing exact capability version/compile failure, and `requireSuccess` mismatch. None may trigger fallback or planner invocation.

- [ ] **Step 5: Implement shared output codec and workflow v2**

Move v1's `encodeOutput`, `decodeOutput`, and `outcome` implementation into `LearningPlanWorkflowOutputCodec`. Both workflows delegate to it; do not make v2 depend on the v1 component.

`LearningPlanWorkflowV2` must:

```java
public String type() { return "LEARNING_PLAN_PREVIEW"; }
public int version() { return 2; }
```

`prepare(...)` parses only `{topic, request}`, creates a trusted `DynamicExecutionContext(owner, topic, request)`, calls `LearningPlanDynamicPlanningService.prepare`, encodes the definition, and returns its compiled nodes.

`restore(...)`:

1. parses trusted input;
2. strictly decodes the snapshot;
3. re-runs `LearningPlanScenarioPolicy.validate` on the reconstructed plan;
4. recomputes and compares digest;
5. calls `DynamicPlanningOperations.compile` once with the exact persisted versions;
6. compares compiled IDs, dependencies, and `requireSuccess` with the snapshot;
7. returns compiled nodes without calling rule or shadow planning.

Map all malformed snapshot failures to stable workflow errors; never include raw JSON or parser messages.

- [ ] **Step 6: Run codec and both workflow version tests**

Run:

```bash
mvn -q -Dtest=LearningPlanWorkflowSnapshotCodecTest,LearningPlanWorkflowV1Test,LearningPlanWorkflowV2Test \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: PASS; v1 restores historical topology, v2 restores the persisted capability graph, and no v2 restore test invokes a planner.

- [ ] **Step 7: Commit the v2 snapshot protocol**

```bash
git add \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowSnapshotCodec.java \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowOutputCodec.java \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV1.java \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV2.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowSnapshotCodecTest.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV1Test.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflowV2Test.java
git commit -m "feat(planning): persist durable learning plan snapshots"
```

---

### Task 5: Switch new submissions to v2 and project actual task nodes

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/learning/application/planning/DurableLearningPlanService.java`
- Modify: `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanTaskPresenter.java`
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/DurableLearningPlanServiceTest.java`
- Create: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanTaskPresenterTest.java`

- [ ] **Step 1: Add failing service tests for v2 submit and task-scoped retry validation**

Update the submit assertion:

```java
verify(execution).submit(
        "trusted", "LEARNING_PLAN_PREVIEW", 2, "key",
        "{\"topic\":\"Java\",\"request\":\"three days\"}");
```

Add task views with node sets `{profile, compose}` and `{evidence, compose}`. Assert retry accepts IDs present in the owner-scoped view, rejects an absent ID before mutation, and still permits an empty selection for “all failed roots”:

```java
when(execution.query("trusted", ID)).thenReturn(taskV2("FAILED", "profile", "compose"));
service.retry("trusted", ID, 2, Set.of("profile"));
assertCode(() -> service.retry("trusted", ID, 2, Set.of("evidence")), "INVALID_INPUT");
service.retry("trusted", ID, 2, Set.of());
```

- [ ] **Step 2: Add failing presenter tests for v1 and dynamic v2 node sets**

Cover:

- v1 accepts exactly three fixed nodes;
- v2 accepts profile+compose, evidence+compose, or all three;
- v2 rejects compose-only, unknown nodes, and missing compose;
- v2 result JSON node keys must exactly equal task node keys;
- absent profile/evidence is not converted into a degradation;
- included failed/empty material retains existing degradation behavior.

- [ ] **Step 3: Run service/presenter tests and verify fixed assumptions fail**

Run:

```bash
mvn -q -Dtest=DurableLearningPlanServiceTest,LearningPlanTaskPresenterTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: FAIL because submit still uses v1 and presenter/retry still require the fixed set.

- [ ] **Step 4: Submit v2 and validate retries from the task view**

Change submit version to `2`. In retry:

```java
DurableTaskView task = execution.query(owner, id);
presenter.requireWorkflow(task);
if (nodes == null || nodes.size() > task.nodes().size()
        || nodes.stream().anyMatch(node -> node == null || !task.nodes().containsKey(node))) {
    throw error(INVALID_INPUT);
}
return presenter.present(execution.retry(owner, id, revision, Set.copyOf(nodes)));
```

Keep query owner-scoped and workflow validation before inspecting node membership. Remove the static three-node retry whitelist.

- [ ] **Step 5: Make presenter version-aware without relaxing its strictness**

`requireWorkflow` accepts only type `LEARNING_PLAN_PREVIEW` and versions 1 or 2. Determine expected nodes as:

```java
Set<String> expected = task.workflowVersion() == 1
        ? Set.of("profile", "evidence", "compose")
        : Set.copyOf(task.nodes().keySet());
```

For v2, explicitly require compose plus profile and/or evidence and reject any other ID. Pass `expected` into result parsing and require result node keys to match it exactly. Keep all current task state, safe error-code, output-size, evidence, degradation, and attempt-count checks.

- [ ] **Step 6: Run Learning durable service and presenter tests**

Run:

```bash
mvn -q -Dtest=DurableLearningPlanServiceTest,LearningPlanTaskPresenterTest,LearningPlanWorkflowV1Test,LearningPlanWorkflowV2Test \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: PASS for both historical v1 and new dynamic v2 views.

- [ ] **Step 7: Commit the v2 public application behavior**

```bash
git add \
  src/main/java/io/github/imzmq/interview/learning/application/planning/DurableLearningPlanService.java \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanTaskPresenter.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/DurableLearningPlanServiceTest.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanTaskPresenterTest.java
git commit -m "feat(learning): submit durable previews with workflow v2"
```

---

### Task 6: Prove recovery, idempotency, retry, and fencing with the real durable runtime

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionServiceTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionWorkerTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionMySqlIntegrationTest.java`
- Modify: `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewWiringTest.java`

`DurableCheckpointTest` remains unchanged: it owns executor checkpoint/seed semantics, not workflow preparation or snapshot recovery. `DurableExecutionConfigurationTest` also remains unchanged because the feature adds no property and does not alter worker enablement.

- [ ] **Step 1: Add focused runtime tests for prepare-once and restore-only execution**

In `DurableExecutionServiceTest`, construct the service with mocked `ExecutionTransactions` and a workflow whose `prepare(...)` returns a two-node graph plus a recognizable v2 definition. Capture the arguments sent to `tx.submit(...)` and assert the saved definition is exactly the workflow output and the saved node rows are exactly `{evidence, compose}`. Call retry against an owner-scoped identity containing the same definition and assert only `restore(...)` is invoked for dependency recovery.

In `DurableExecutionWorkerTest`, return a claimed task containing the persisted definition and a matching two-node node snapshot. Configure:

```java
when(workflow.prepare(anyString(), anyString()))
        .thenThrow(new AssertionError("worker must not prepare"));
when(workflow.restore("owner", inputJson, persistedDefinition))
        .thenReturn(List.of(evidence, compose));
```

Run the worker and assert it completes through the restored graph, invokes `restore(...)` once, and never invokes `prepare(...)`. Add a mismatch case where the restored graph contains a node absent from the database snapshot; assert the task finishes with the existing stable execution failure and no node action runs.

- [ ] **Step 2: Register both Learning workflow versions in the Spring wiring test**

Extend `LearningPlanPreviewWiringTest` with a focused context that registers the real `LearningPlanWorkflowV1`, `LearningPlanWorkflowV2`, `LearningPlanWorkflowSnapshotCodec`, `LearningPlanWorkflowOutputCodec`, and `DurableWorkflowRegistry`, while providing mocks for their execution dependencies. Assert:

```java
assertThat(registry.require("LEARNING_PLAN_PREVIEW", 1))
        .isInstanceOf(LearningPlanWorkflowV1.class);
assertThat(registry.require("LEARNING_PLAN_PREVIEW", 2))
        .isInstanceOf(LearningPlanWorkflowV2.class);
```

This test proves coexistence for historical recovery and new submission without starting a worker, database, model, or external API.

- [ ] **Step 3: Extend the opt-in MySQL integration test**

Convert its anonymous workflow to the final `prepare/restore` contract and let its prepared definition describe a mutable two- or three-node test graph. Add assertions that:

- repeating the same submission returns the original task and does not replace `definition_json`;
- the same key with changed input or workflow version conflicts;
- after submission, changing the workflow's preparation behavior cannot change worker recovery because `restore(...)` receives the original definition;
- process/lease recovery executes the original graph;
- retrying failed `evidence` resets `evidence` and `compose` only;
- successful unrelated nodes remain seeded in a three-node graph;
- stale epoch writes remain rejected;
- definition/node-row mismatch closes the task as failed instead of preparing or replanning.

Use SQL only to inspect persisted JSON or simulate crashes, stale leases, and corruption. Successful task creation must still go through `DurableExecutionService.submit(...)`.

- [ ] **Step 4: Run the non-database durable runtime slice**

Run:

```bash
mvn -q -Dtest=DurableExecutionServiceTest,DurableExecutionWorkerTest,LearningPlanPreviewWiringTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: PASS with no external model call; the service prepares only on submission, the worker restores only from the persisted definition, and both workflow versions are registered.

- [ ] **Step 5: Run the real MySQL evidence only with an isolated configured database**

`DurableExecutionMySqlIntegrationTest` is opt-in. It does not create MySQL through Testcontainers. Run it only when an isolated disposable MySQL URL is available:

```bash
mvn -q -Dtest=DurableExecutionMySqlIntegrationTest \
  -Dtest.mysql.execution-jdbc-url='<isolated-jdbc-url>' \
  -Dtest.mysql.username='<username>' \
  -Dtest.mysql.password='<password>' \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: PASS with tests executed, not skipped. Do not put credentials in the plan, shell history committed to the repository, logs, or source files. If no isolated URL is configured, the test is skipped by its JUnit assumption; a skipped run is not evidence that MySQL recovery, fencing, or retry passed and must be reported as unverified.

- [ ] **Step 6: Run the complete durable and dynamic planning test slice**

Run:

```bash
mvn -q \
  -Dtest='io.github.imzmq.interview.agent.application.execution.*Test,io.github.imzmq.interview.agent.application.execution.dynamic.*Test,io.github.imzmq.interview.learning.application.planning.*Test' \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: PASS with no planner invocation during restore/retry paths. Inspect the Surefire summary: if `DurableExecutionMySqlIntegrationTest` is skipped because the system property is absent, report that separately rather than counting it as executed MySQL evidence.

- [ ] **Step 7: Commit durable runtime evidence**

```bash
git add \
  src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionServiceTest.java \
  src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionWorkerTest.java \
  src/test/java/io/github/imzmq/interview/agent/application/execution/DurableExecutionMySqlIntegrationTest.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewWiringTest.java
git commit -m "test(execution): prove durable snapshot recovery"
```

---

### Task 7: Update architecture documentation and run repository gates

**Files:**
- Modify: `PACKAGE_CONVENTIONS.md`
- Modify: `docs/development/learning-plan-preview.md`
- Modify: `docs/development/verification.md`
- Verify: all files changed by Tasks 1–6

- [ ] **Step 1: Replace fixed-only durable documentation with the v1/v2 truth**

Document:

- v1 exists only for historical fixed tasks;
- all new durable submissions use v2;
- submission plans once and persists either rules or fixed-fallback capability snapshot;
- worker/retry compile the saved snapshot and never plan again;
- `definition_json` is the only execution-definition truth;
- capability ID/version is a persistence compatibility contract;
- no new table, startup dependency, API Key, or environment variable was added;
- sync preview and durable v2 use the same rule/fallback planning result but different execution lifecycles.

Remove current statements that durable preview always uses the fixed three-node workflow. Keep dated historical specs unchanged.

- [ ] **Step 2: Update verification commands**

Add the focused workflow snapshot, service, worker, presenter, MySQL integration, and ArchUnit commands to `docs/development/verification.md`. State that the MySQL integration result may only be claimed when it actually runs successfully.

- [ ] **Step 3: Run format and stale-assumption checks**

Run:

```bash
git diff --check
grep -RInE 'durable workflow remains the fixed|持久化预览继续使用版本化固定三节点|versioned fixed graph' \
  PACKAGE_CONVENTIONS.md docs/development src/main/java src/test/java
```

Expected: `git diff --check` exits 0; grep returns no current fixed-only durable statement or old interface comment.

- [ ] **Step 4: Run architecture rules**

Run:

```bash
mvn -q -Dtest=ArchitectureRulesTest \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
```

Expected: PASS; Learning depends only on Agent application contracts and no infrastructure edge is introduced.

- [ ] **Step 5: Run the full test suite and verification lifecycle**

Run:

```bash
mvn -q \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar' test
mvn -q verify -DskipTests
git diff --check
git status --short --branch
```

Expected: all Maven commands and `git diff --check` exit 0. Status may still show the protected user files, but no task commit may contain them.

- [ ] **Step 6: Review the final diff for transitional residue**

Confirm all of the following before the final commit:

```text
- DurableWorkflow has no nodes(...) compatibility method.
- v2 has one snapshot format for RULES and FIXED_FALLBACK.
- Worker and retry contain no planner call.
- DurableLearningPlanService has no static three-node retry whitelist.
- LearningPlanFixedNodeFactory is referenced only by the historical v1 workflow.
- No plan_json/digest column, Flyway migration, mode switch, facade, no-op adapter, or test-only constructor was added.
- No credential or local configuration is staged.
```

- [ ] **Step 7: Commit documentation and final guardrail updates**

```bash
git add PACKAGE_CONVENTIONS.md docs/development/learning-plan-preview.md docs/development/verification.md
git commit -m "docs(planning): document durable snapshot execution"
```

- [ ] **Step 8: Inspect final branch state without pushing automatically**

```bash
git log --oneline --decorate -8
git status --short --branch
git diff origin/main...HEAD --stat
```

Expected: only intended commits are ahead of `origin/main`; protected user files remain unstaged. Push to remote `main` only after the user requests it or confirms the completed implementation.
