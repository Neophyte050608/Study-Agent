# Controlled Dynamic Task Planning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add validated, request-scoped dynamic planning to Learning Plan Preview while retaining `FixedDagExecutor`, fixed-plan fallback, fixed durable workflows, and a non-executing LLM shadow path.

**Architecture:** Agent owns technology-neutral candidate/validated plan contracts, generic validation, an immutable capability registry, and compilation into existing `DagNode` objects. Learning owns the scenario policy, three read-only capabilities, deterministic plan selection, fixed fallback, and result presentation. For D5, Agent infrastructure calls a narrow ModelRouting-owned application contract, preserving the existing one-way `agent -> modelrouting` module edge; strict JSON parsing and the LLM planner remain isolated from execution contracts.

**Tech Stack:** Java 21, Spring Boot, Jackson, Spring AI through existing ModelRouting, JUnit 5, Mockito, AssertJ, ArchUnit, Maven.

---

## Delivery constraints

- Work on `/Users/bytedance/bits/StudyAgent/Study-Agent`.
- Before every task run `git status --short --branch`. Never stage or modify the existing user-owned `AGENTS.md` or `.DS_Store` files.
- Use TDD for every behavioral change: add a focused failing test, run it and inspect the expected failure, implement the minimum behavior, then rerun the focused tests.
- Do not use a real model or embedding service in automated tests.
- Do not add database migrations. `LearningPlanWorkflow` version 1 and durable HTTP behavior stay fixed.
- Keep `app.learning.plan-preview.dynamic-planning.mode=FIXED` as the repository and `local-lite` default.
- Do not run a real retrieval benchmark `INDEX` operation.
- Add ignored documentation with `git add -f`; stage source normally. Before every commit run `git diff --cached --check` and `git diff --cached --name-only`.

## Milestone-to-task map

| Confirmed milestone | Tasks in this plan |
| --- | --- |
| D1 Plan contract and validation | Tasks 1–2 |
| D2 Registry and DAG compilation | Task 3 |
| D3 Learning rule planner and business slice | Tasks 4–5 |
| D4 Architecture and observation guardrails | Tasks 6–7 |
| D5 LLM planner shadow mode | Task 8 |

## File responsibility map

### Agent contracts and generic implementation

- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/CandidateDynamicTaskPlan.java`: untrusted schema-versioned plan.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/CandidateDynamicTaskNode.java`: untrusted node definition with defensive copies.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/DynamicCapabilityDescriptor.java`: stable capability metadata and argument/dependency constraints.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/DynamicArgumentDefinition.java`: scalar argument schema.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/DynamicArgumentType.java`: supported scalar argument kinds.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/DynamicEffectType.java`: represent `READ_ONLY` and `WRITE`; phase one permits only `READ_ONLY` and rejects `WRITE`.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/DynamicPlanningLimits.java`: server-owned validation limits and budget.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/DynamicPlanIssue.java`: stable code and optional node ID.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/DynamicPlanValidationResult.java`: success or bounded issue list.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/ValidatedDynamicTaskPlan.java`: normalized immutable plan.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/ValidatedDynamicTaskNode.java`: normalized immutable node.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/DynamicPlanScenarioPolicy.java`: product-owned scenario validation extension used by the generic validator.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/DynamicExecutionContext.java`: trusted owner/topic/request input supplied outside planner output.
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/extension/DynamicNodeCapability.java`: narrow typed capability SPI.
- Create `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicTaskPlanValidator.java`: structure, descriptor, argument, budget, and scenario validation.
- Create `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicCapabilityRegistry.java`: immutable ID/version-to-capability registry.
- Create `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicTaskPlanCompiler.java`: validated-plan-to-`DagNode` compiler.
- Create `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicPlanFingerprint.java`: canonical SHA-256 plan digest for comparison and observation.
- Create `src/main/java/io/github/imzmq/interview/agent/infrastructure/planning/DynamicPlanParseResult.java`: bounded success/failure result for strict parsing and model planning.
- Create `src/main/java/io/github/imzmq/interview/agent/infrastructure/planning/StrictDynamicPlanJsonParser.java`: strict Jackson boundary for LLM text.
- Create `src/main/java/io/github/imzmq/interview/agent/infrastructure/planning/LlmDynamicTaskPlanner.java`: D5 prompt/model/parser adapter.
- Create `src/main/java/io/github/imzmq/interview/agent/infrastructure/planning/DynamicPlanningShadowRunner.java`: bounded asynchronous non-executing shadow path.

### Learning scenario

- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningProperties.java`: `FIXED`, `RULES`, and `SHADOW` plus validated limits.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanScenarioPolicy.java`: exact three-capability policy.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/RuleBasedLearningPlanPlanner.java`: deterministic request-characteristic selection.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanNodeOperations.java`: shared profile/evidence/compose business work.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanProfileMaterial.java`, `LearningPlanEvidenceMaterial.java`, and `LearningPlanComposeMaterial.java`: typed dynamic-node outputs with defensive invariants.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanFixedNodeFactory.java`: fixed three-node construction that unwraps typed operation results to workflow-v1 legacy output types.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanProfileCapability.java`: read-only profile capability.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanEvidenceCapability.java`: read-only evidence capability and typed `limit` argument.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanComposeCapability.java`: compose capability with optional upstream sources.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningService.java`: mode choice, validation, compilation, pre-execution fallback, and D5 shadow submission.
- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewService.java`: delegate fixed-node business work and present plans with omitted nodes correctly.
- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflow.java`: call an explicitly fixed node builder so durable workflow version 1 cannot become dynamic.

### ModelRouting, observation, configuration, architecture, and docs

- Create `src/main/java/io/github/imzmq/interview/modelrouting/application/contract/DynamicPlanningModelOperations.java`: narrow provider-owned raw-text model contract for planning.
- Create `src/main/java/io/github/imzmq/interview/modelrouting/infrastructure/planning/ModelRoutingDynamicPlanningModel.java`: ModelRouting implementation of its public contract using existing `RoutingChatService`.
- Create `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicPlanObservationPublisher.java`: sanitized operational events only.
- Modify `src/main/resources/application.yml`: add safe dynamic-planning defaults.
- Modify `src/main/resources/application-local-lite.yml`: force `FIXED`.
- Modify `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`: enforce API, core, SDK, persistence, Tool, and contract boundaries.
- Create focused architecture fixture classes in test-source packages that match the production rule selectors, and import them explicitly in fixture tests.
- Modify `PACKAGE_CONVENTIONS.md`: record dynamic-planning ownership.
- Modify `docs/development/observability-guidelines.md`: record event names and prohibited attributes.
- Modify `docs/development/learning-plan-preview.md`: record request-scoped modes and durable exclusion.

---

## Task 1: D1a — Define immutable dynamic-plan contracts and generic validation

**Files:**
- Create the twelve contract files listed under “Agent contracts and generic implementation,” from `CandidateDynamicTaskPlan.java` through `DynamicExecutionContext.java`, excluding the extension SPI file.
- Create `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicTaskPlanValidator.java`.
- Create `src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicTaskPlanValidatorTest.java`.

- [ ] **Step 1: Write tests for valid normalization and structural rejection**

Create `DynamicTaskPlanValidatorTest` with reusable descriptors and these explicit cases:

```java
class DynamicTaskPlanValidatorTest {
    private final DynamicCapabilityDescriptor source = descriptor(
            "source.read", Set.of(), Map.of(), 1, true);
    private final DynamicCapabilityDescriptor compose = descriptor(
            "result.compose", Set.of("source.read"), Map.of(), 2, false);
    private final DynamicPlanningLimits limits = new DynamicPlanningLimits(8, 4, 4, 4, 10, 20);
    private final DynamicPlanScenarioPolicy policy = new DynamicPlanScenarioPolicy() {
        public String scenarioId() { return "test"; }
        public Set<String> allowedCapabilities() { return Set.of("source.read", "result.compose"); }
        public List<DynamicPlanIssue> validate(ValidatedDynamicTaskPlan plan) { return List.of(); }
    };

    @Test
    void normalizesDefaultsAndProducesAnImmutableValidatedPlan() {
        CandidateDynamicTaskPlan candidate = new CandidateDynamicTaskPlan("1", List.of(
                node("source", "source.read", List.of(), Map.of()),
                node("compose", "result.compose", List.of("source"), Map.of())
        ));

        DynamicPlanValidationResult result = validator(source, compose).validate(candidate, policy, limits);

        assertThat(result.valid()).isTrue();
        assertThat(result.plan().nodes()).extracting(ValidatedDynamicTaskNode::id)
                .containsExactly("source", "compose");
        assertThatThrownBy(() -> result.plan().nodes().add(result.plan().nodes().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidPlans")
    void rejectsInvalidStructures(CandidateDynamicTaskPlan candidate, String code) {
        DynamicPlanValidationResult result = validator(source, compose).validate(candidate, policy, limits);
        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(DynamicPlanIssue::code).contains(code);
    }

    static Stream<Arguments> invalidPlans() {
        return Stream.of(
                Arguments.of(new CandidateDynamicTaskPlan("2", List.of(node("a", "source.read", List.of(), Map.of()))),
                        "UNSUPPORTED_SCHEMA_VERSION"),
                Arguments.of(new CandidateDynamicTaskPlan("1", List.of()), "PLAN_EMPTY"),
                Arguments.of(new CandidateDynamicTaskPlan("1", List.of(
                        node("A", "source.read", List.of(), Map.of()))), "INVALID_NODE_ID"),
                Arguments.of(new CandidateDynamicTaskPlan("1", List.of(
                        node("a", "source.read", List.of(), Map.of()),
                        node("a", "result.compose", List.of("a"), Map.of()))), "DUPLICATE_NODE_ID"),
                Arguments.of(new CandidateDynamicTaskPlan("1", List.of(
                        node("a", "missing", List.of(), Map.of()))), "UNKNOWN_CAPABILITY"),
                Arguments.of(new CandidateDynamicTaskPlan("1", List.of(
                        node("a", "source.read", List.of("missing"), Map.of()))), "UNKNOWN_DEPENDENCY"),
                Arguments.of(new CandidateDynamicTaskPlan("1", List.of(
                        node("a", "source.read", List.of("a"), Map.of()))), "CYCLIC_DEPENDENCY")
        );
    }
}
```

Add focused tests in the same class for duplicate dependencies, max nodes, max depth, max dependencies, max fan-out, disconnected nodes, issue truncation at 20, disallowed scenario capability, non-`READ_ONLY` descriptor, incompatible dependency capability, and total estimated cost over budget. Each test asserts the stable code from the design.

- [ ] **Step 2: Run the focused test and verify the expected compilation failure**

Run:

```bash
mvn -q -Dtest=DynamicTaskPlanValidatorTest test
```

Expected: compilation fails because the dynamic-plan contract and validator types do not exist.

- [ ] **Step 3: Implement defensive contract records**

Use records with canonical constructors that copy every collection. The central signatures must be exactly:

```java
public record CandidateDynamicTaskPlan(String schemaVersion, List<CandidateDynamicTaskNode> nodes) {
    public CandidateDynamicTaskPlan {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}

public record CandidateDynamicTaskNode(
        String id,
        String capability,
        List<String> dependsOn,
        Map<String, Object> arguments) {
    public CandidateDynamicTaskNode {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}

public enum DynamicEffectType { READ_ONLY, WRITE }

public enum DynamicArgumentType { STRING, INTEGER, BOOLEAN }

public record DynamicArgumentDefinition(
        String name,
        DynamicArgumentType type,
        boolean required,
        Object defaultValue,
        Long minimum,
        Long maximum) { }

public record DynamicCapabilityDescriptor(
        String capabilityId,
        int capabilityVersion,
        DynamicEffectType effectType,
        Map<String, DynamicArgumentDefinition> arguments,
        Set<String> allowedDependencyCapabilities,
        int estimatedCost,
        boolean requireSuccessfulDependencies) { }

public record DynamicPlanningLimits(
        int maxNodes,
        int maxDepth,
        int maxDependenciesPerNode,
        int maxFanOut,
        int maxEstimatedCost,
        int maxIssues) { }

public record DynamicPlanIssue(String code, String nodeId) { }

public record ValidatedDynamicTaskNode(
        String id,
        String capabilityId,
        int capabilityVersion,
        List<String> dependsOn,
        Map<String, Object> arguments) { }

public record ValidatedDynamicTaskPlan(
        String schemaVersion,
        String scenarioId,
        List<ValidatedDynamicTaskNode> nodes,
        int estimatedCost) { }

public interface DynamicPlanScenarioPolicy {
    String scenarioId();
    Set<String> allowedCapabilities();
    List<DynamicPlanIssue> validate(ValidatedDynamicTaskPlan plan);
}

public record DynamicExecutionContext(String owner, String topic, String request) { }
```

`DynamicPlanValidationResult` must enforce its invariant:

```java
public record DynamicPlanValidationResult(
        ValidatedDynamicTaskPlan plan,
        List<DynamicPlanIssue> issues) {
    public DynamicPlanValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        if ((plan == null) == issues.isEmpty()) {
            throw new IllegalArgumentException("Validation requires either a plan or issues");
        }
    }
    public boolean valid() { return plan != null; }
    public static DynamicPlanValidationResult valid(ValidatedDynamicTaskPlan plan) {
        return new DynamicPlanValidationResult(Objects.requireNonNull(plan), List.of());
    }
    public static DynamicPlanValidationResult invalid(List<DynamicPlanIssue> issues) {
        return new DynamicPlanValidationResult(null, issues);
    }
}
```

Validate descriptor constructors: nonblank ID, positive version/cost, nonnull effect, unique named argument definitions, and defensive copies. Validate positive planning limits and cap `maxIssues` at 20.

- [ ] **Step 4: Implement the validator in deterministic passes**

`DynamicTaskPlanValidator` should accept a `Collection<DynamicCapabilityDescriptor>` in its constructor and expose:

```java
public DynamicPlanValidationResult validate(
        CandidateDynamicTaskPlan candidate,
        DynamicPlanScenarioPolicy policy,
        DynamicPlanningLimits limits)
```

Implement passes in this order so error behavior remains stable:

1. schema and nonempty/size checks;
2. node ID, duplicate ID, capability existence, allowed capability, and `READ_ONLY` checks;
3. dependency existence, self/duplicate dependency, allowed upstream capability, indegree, and fan-out checks;
4. Kahn traversal for cycles and maximum depth;
5. find terminal nodes (nodes with no dependents), require exactly one terminal, then reverse-traverse dependencies from that terminal and require every node to be reachable; this is the phase-one generic representation of “every node contributes to the business final output”;
6. exact argument validation and default insertion;
7. estimated-cost sum;
8. create normalized nodes in candidate order and call `policy.validate`;
9. return at most `limits.maxIssues()` issues.

Use only stable codes from the spec. Never include exception text or raw arguments in `DynamicPlanIssue`.

- [ ] **Step 5: Run the focused tests**

Run:

```bash
mvn -q -Dtest=DynamicTaskPlanValidatorTest test
```

Expected: all tests in `DynamicTaskPlanValidatorTest` pass.

- [ ] **Step 6: Commit Task 1**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic \
  src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicTaskPlanValidator.java \
  src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicTaskPlanValidatorTest.java
git diff --cached --check
git commit -m "feat(agent): define controlled dynamic plan validation"
```

---

## Task 2: D1b — Add strict JSON parsing and the Learning scenario policy

**Files:**
- Create `src/main/java/io/github/imzmq/interview/agent/infrastructure/planning/DynamicPlanParseResult.java`.
- Create `src/main/java/io/github/imzmq/interview/agent/infrastructure/planning/StrictDynamicPlanJsonParser.java`.
- Create `src/test/java/io/github/imzmq/interview/agent/infrastructure/planning/StrictDynamicPlanJsonParserTest.java`.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanScenarioPolicy.java`.
- Create `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanScenarioPolicyTest.java`.

- [ ] **Step 1: Write strict parser tests**

Use a fresh Jackson mapper in the test and assert the public result rather than Jackson exception messages:

```java
class StrictDynamicPlanJsonParserTest {
    private final StrictDynamicPlanJsonParser parser =
            new StrictDynamicPlanJsonParser(new ObjectMapper(), 32 * 1024, 4 * 1024);

    @Test
    void parsesOnlyTheExactPlanObject() {
        DynamicPlanParseResult result = parser.parse("""
                {"schemaVersion":"1","nodes":[
                  {"id":"evidence","capability":"knowledge.evidence.retrieve","dependsOn":[],"arguments":{"limit":4}},
                  {"id":"compose","capability":"learning.plan.compose","dependsOn":["evidence"],"arguments":{}}
                ]}
                """);
        assertThat(result.success()).isTrue();
        assertThat(result.plan().nodes()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "```json\n{\"schemaVersion\":\"1\",\"nodes\":[]}\n```",
            "prefix {\"schemaVersion\":\"1\",\"nodes\":[]}",
            "{\"schemaVersion\":\"1\",\"schemaVersion\":\"1\",\"nodes\":[]}",
            "{\"schemaVersion\":\"1\",\"nodes\":[],\"extra\":true}",
            "[]"
    })
    void rejectsNonExactJson(String raw) {
        assertThat(parser.parse(raw)).isEqualTo(DynamicPlanParseResult.failure("INVALID_PLAN_JSON"));
    }
}
```

Add tests for UTF-8 payload over 32 KiB, arguments over 4 KiB, wrong field types, trailing JSON, and null input.

- [ ] **Step 2: Write Learning scenario tests**

Create validated plans directly and assert:

```java
@Test
void acceptsOnlyTheThreeApprovedShapes() {
    assertThat(policy.validate(plan("profile", "evidence", "compose"))).isEmpty();
    assertThat(policy.validate(plan("profile", "compose"))).isEmpty();
    assertThat(policy.validate(plan("evidence", "compose"))).isEmpty();
}

@Test
void rejectsComposeOnlyDuplicateSourcesAndNonTerminalCompose() {
    assertThat(policy.validate(plan("compose")))
            .extracting(DynamicPlanIssue::code).contains("SCENARIO_POLICY_VIOLATION");
    assertThat(policy.validate(planWithDuplicateProfile()))
            .extracting(DynamicPlanIssue::code).contains("SCENARIO_POLICY_VIOLATION");
    assertThat(policy.validate(planWithComposeBeforeEvidence()))
            .extracting(DynamicPlanIssue::code).contains("SCENARIO_POLICY_VIOLATION");
}
```

The helper must build explicit `ValidatedDynamicTaskNode` instances for capability IDs `learning.profile.lookup`, `knowledge.evidence.retrieve`, and `learning.plan.compose`; it must not infer policy behavior inside the test helper.

- [ ] **Step 3: Run tests to verify they fail for missing implementations**

```bash
mvn -q -Dtest=StrictDynamicPlanJsonParserTest,LearningPlanScenarioPolicyTest test
```

Expected: compilation fails for missing parser, parse-result, and scenario-policy classes.

- [ ] **Step 4: Implement exact JSON parsing without repair or retry**

`DynamicPlanParseResult`:

```java
public record DynamicPlanParseResult(CandidateDynamicTaskPlan plan, String errorCode) {
    public boolean success() { return plan != null; }
    public static DynamicPlanParseResult success(CandidateDynamicTaskPlan plan) {
        return new DynamicPlanParseResult(Objects.requireNonNull(plan), "");
    }
    public static DynamicPlanParseResult failure(String code) {
        return new DynamicPlanParseResult(null, code);
    }
}
```

`StrictDynamicPlanJsonParser` must copy the injected `ObjectMapper` so registered project modules are preserved, then enable strict parsing with APIs available in the project’s Jackson version:

```java
ObjectMapper mapper = injectedObjectMapper.copy();
mapper.getFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
```

Before deserializing, require nonblank raw text and enforce UTF-8 byte length. Deserialize directly to `CandidateDynamicTaskPlan`, require the root to start with `{` after trimming, and serialize each node’s `arguments()` with the same mapper to enforce the 4 KiB bound. Catch parsing and mapping exceptions and return only `INVALID_PLAN_JSON` or `PLAN_TOO_LARGE`; do not expose exception messages.

- [ ] **Step 5: Implement the exact Learning policy**

`LearningPlanScenarioPolicy` must return:

```java
public String scenarioId() { return "learning-plan-preview"; }

public Set<String> allowedCapabilities() {
    return Set.of(
            "learning.profile.lookup",
            "knowledge.evidence.retrieve",
            "learning.plan.compose");
}
```

Its `validate` method counts capability IDs, requires exactly one compose, at most one profile/evidence, two or three total nodes, compose as the sole terminal, no dependency between profile and evidence, and compose dependencies exactly matching the selected source node IDs. Every violation returns `SCENARIO_POLICY_VIOLATION` with the closest node ID or an empty node ID for a plan-wide error.

- [ ] **Step 6: Run D1 focused tests**

```bash
mvn -q -Dtest=DynamicTaskPlanValidatorTest,StrictDynamicPlanJsonParserTest,LearningPlanScenarioPolicyTest test
```

Expected: all three test classes pass.

- [ ] **Step 7: Commit Task 2**

```bash
git add src/main/java/io/github/imzmq/interview/agent/infrastructure/planning \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanScenarioPolicy.java \
  src/test/java/io/github/imzmq/interview/agent/infrastructure/planning/StrictDynamicPlanJsonParserTest.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanScenarioPolicyTest.java
git diff --cached --check
git commit -m "feat(agent): parse and constrain dynamic plans"
```

---

## Task 3: D2 — Register typed capabilities and compile validated plans into the existing DAG

**Files:**
- Create `src/main/java/io/github/imzmq/interview/agent/application/contract/extension/DynamicNodeCapability.java`.
- Create `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicCapabilityRegistry.java`.
- Create `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicTaskPlanCompiler.java`.
- Create `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicPlanFingerprint.java`.
- Create `src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicCapabilityRegistryTest.java`.
- Create `src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicTaskPlanCompilerTest.java`.
- Create `src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicPlanFingerprintTest.java`.

- [ ] **Step 1: Write registry collision and typed compilation tests**

Define a private typed test capability:

```java
private record LimitArguments(int limit) { }

private static final class TestCapability implements DynamicNodeCapability<LimitArguments> {
    private final DynamicCapabilityDescriptor descriptor;
    private final AtomicInteger calls;

    TestCapability(DynamicCapabilityDescriptor descriptor, AtomicInteger calls) {
        this.descriptor = descriptor;
        this.calls = calls;
    }
    public DynamicCapabilityDescriptor descriptor() { return descriptor; }
    public LimitArguments decodeArguments(Map<String, Object> normalized) {
        return new LimitArguments((Integer) normalized.getOrDefault("limit", 1));
    }
    public Object execute(DynamicExecutionContext context,
                          Map<String, DagNodeResult> upstream,
                          LimitArguments arguments) {
        calls.incrementAndGet();
        return context.topic() + ":" + arguments.limit();
    }
}
```

Tests must prove:

- duplicate `(capabilityId, capabilityVersion)` fails registry construction;
- unknown ID/version lookup fails with `UNKNOWN_CAPABILITY` and no class name;
- argument decoding happens during compile, before any DAG node runs;
- compile produces the validated IDs and dependencies;
- executing compiled nodes with a real `FixedDagExecutor` invokes only selected capabilities;
- two independent sources can run before compose;
- a capability exception becomes the existing `NODE_FAILED` result;
- compile never invokes a capability’s `execute` method.

- [ ] **Step 2: Write canonical fingerprint tests**

```java
@Test
void fingerprintIgnoresCandidateMapInsertionOrderButIncludesCapabilityVersion() {
    String first = fingerprint.digest(planWithArguments(linkedMap("limit", 4, "enabled", true), 1));
    String reordered = fingerprint.digest(planWithArguments(linkedMap("enabled", true, "limit", 4), 1));
    String upgraded = fingerprint.digest(planWithArguments(linkedMap("limit", 4, "enabled", true), 2));

    assertThat(first).isEqualTo(reordered).matches("[0-9a-f]{64}");
    assertThat(upgraded).isNotEqualTo(first);
}
```

Also assert node/dependency ordering is canonical and that digest input never includes owner, topic, request, timestamp, handler class, or random IDs.

- [ ] **Step 3: Run tests and verify missing-type failures**

```bash
mvn -q -Dtest=DynamicCapabilityRegistryTest,DynamicTaskPlanCompilerTest,DynamicPlanFingerprintTest test
```

Expected: compilation fails because the SPI, registry, compiler, and fingerprint classes do not exist.

- [ ] **Step 4: Implement the typed capability SPI and immutable registry**

Use this exact public SPI:

```java
public interface DynamicNodeCapability<A> {
    DynamicCapabilityDescriptor descriptor();
    A decodeArguments(Map<String, Object> normalizedArguments);
    Object execute(DynamicExecutionContext context,
                   Map<String, DagNodeResult> upstream,
                   A arguments);
}
```

`DynamicCapabilityRegistry` receives `Collection<DynamicNodeCapability<?>>`, builds an unmodifiable map keyed by a private `CapabilityKey(id, version)`, and rejects duplicate keys. It exposes descriptor snapshots and a package-private typed-erased lookup for the compiler. It must not use `ApplicationContext`, reflection, or Bean names.

- [ ] **Step 5: Implement compiler prebinding**

`DynamicTaskPlanCompiler.compile(plan, context)` must:

1. iterate all validated nodes before returning any executable node;
2. resolve exact capability version;
3. call `decodeArguments` and retain the typed value;
4. build `DagNode` in validated order;
5. set dependencies from `dependsOn`;
6. set `requireSuccessfulDependencies` from the descriptor;
7. bind a lambda that calls only the already-resolved capability with the trusted context and immutable upstream map.

Use one private generic helper to contain the unavoidable type erasure cast. Convert any lookup or decoding failure into `IllegalArgumentException("PLAN_COMPILE_FAILED")` without the original message. Never call `execute` during compilation.

- [ ] **Step 6: Implement canonical SHA-256 fingerprinting**

Build canonical text from schema, scenario, sorted nodes, capability ID/version, sorted dependency IDs, and recursively key-sorted scalar argument maps. Hash UTF-8 bytes with `MessageDigest.getInstance("SHA-256")` and format lowercase hexadecimal. Reject unsupported argument value types with `IllegalArgumentException("PLAN_COMPILE_FAILED")`.

- [ ] **Step 7: Run D2 and executor regression tests**

```bash
mvn -q -Dtest=DynamicCapabilityRegistryTest,DynamicTaskPlanCompilerTest,DynamicPlanFingerprintTest,FixedDagExecutorTest test
```

Expected: all four test classes pass.

- [ ] **Step 8: Commit Task 3**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/contract/extension/DynamicNodeCapability.java \
  src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic \
  src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic
git diff --cached --check
git commit -m "feat(agent): compile validated plans into dag execution"
```

---

## Task 4: D3a — Extract reusable Learning node operations without changing fixed behavior

**Files:**
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanNodeOperations.java`.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanProfileMaterial.java`.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanEvidenceMaterial.java`.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanComposeMaterial.java`.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanFixedNodeFactory.java`.
- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewService.java`.
- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanWorkflow.java`.
- Modify `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewServiceTest.java`.
- Modify `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewWiringTest.java`.
- Create `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanNodeOperationsTest.java`.

- [ ] **Step 1: Add characterization tests for fixed output and absent-node presentation**

Keep all existing fixed-plan tests. Add a new `LearningPlanNodeOperationsTest` proving profile formatting, evidence defensive copy/limit, and blank compose rejection. Add this regression to `LearningPlanPreviewServiceTest`:

```java
@Test
void presentationDoesNotReportAnUnselectedSourceAsUnavailable() {
    DagExecutionResult result = new DagExecutionResult("COMPLETED", Map.of(
            "evidence", new DagNodeResult(DagNodeStatus.SUCCEEDED,
                    new LearningPlanEvidenceMaterial(List.of(EVIDENCE)), "OK"),
            "compose", new DagNodeResult(DagNodeStatus.SUCCEEDED,
                    new LearningPlanComposeMaterial("plan"), "OK")
    ));

    LearningPlanPreview preview = service.present(result);

    assertThat(preview.status()).isEqualTo("COMPLETED");
    assertThat(preview.personalized()).isFalse();
    assertThat(preview.degradations()).doesNotContain("PROFILE_UNAVAILABLE", "PROFILE_EMPTY");
    assertThat(preview.nodes()).containsOnlyKeys("evidence", "compose");
}
```

- [ ] **Step 2: Run the focused tests and verify the new regression fails**

```bash
mvn -q -Dtest=LearningPlanNodeOperationsTest,LearningPlanPreviewServiceTest,LearningPlanPreviewWiringTest test
```

Expected: compilation fails for missing material/operations types; after temporary test compilation is possible, the absent-node test fails against `materials()` because the current service assumes profile and evidence always exist.

- [ ] **Step 3: Extract typed node operations**

Use records:

```java
record LearningPlanProfileMaterial(String text) {
    LearningPlanProfileMaterial { text = text == null ? "" : text; }
}

record LearningPlanEvidenceMaterial(List<LearningPlanEvidence> evidence) {
    LearningPlanEvidenceMaterial { evidence = List.copyOf(evidence); }
}

record LearningPlanComposeMaterial(String plan) {
    LearningPlanComposeMaterial {
        if (plan == null || plan.isBlank()) throw new IllegalArgumentException("EMPTY_PLAN");
    }
}
```

`LearningPlanNodeOperations` owns the existing behavior through these methods:

```java
LearningPlanProfileMaterial readProfile(String owner)
LearningPlanEvidenceMaterial readEvidence(String topic, int limit)
LearningPlanComposeMaterial compose(
        String topic,
        String request,
        Optional<LearningPlanProfileMaterial> profile,
        Optional<LearningPlanEvidenceMaterial> evidence,
        List<String> degradations)
```

Move the existing profile formatting into `readProfile`. `readEvidence` calls `LearningPlanEvidenceReader.read(topic)`, defensively copies, and applies `limit` in the range `1..5`. `compose` creates the existing `LearningPlanPreviewInput` and invokes the existing generator. No method writes memory, notes, profile, or persistence.

- [ ] **Step 4: Make fixed nodes use the extracted operations**

Create package-private `LearningPlanFixedNodeFactory` with:

```java
List<DagNode> nodes(String owner, String subject, String instruction)
```

It calls `LearningPlanNodeOperations`, retains IDs/dependencies `profile`, `evidence`, `compose`, and deliberately unwraps each typed material in the node action:

```text
profile action  -> operations.readProfile(owner).text()
evidence action -> operations.readEvidence(subject, 4).evidence()
compose action  -> wrap the successful raw profile/evidence dependency values,
                   call operations.compose(...), then return .plan()
```

Therefore fixed preview and durable workflow continue producing exactly the workflow-v1 output types `String`, `List<LearningPlanEvidence>`, and `String`; `LearningPlanWorkflow.encodeOutput/decodeOutput` and workflow version stay unchanged. `LearningPlanWorkflow` calls `fixedNodeFactory.nodes(...)` directly and never calls a mode-dependent service.

Dynamic capabilities return the typed material records. Update `LearningPlanPreviewService.present` with three closed extraction helpers that accept only the known fixed or dynamic form:

```text
profile: String or LearningPlanProfileMaterial
evidence: List<LearningPlanEvidence> or LearningPlanEvidenceMaterial
compose: String or LearningPlanComposeMaterial
```

An unknown successful output type is treated as that selected node being unavailable and is never reflectively decoded. Missing node means “not selected” and adds no degradation; selected failed node adds `PROFILE_UNAVAILABLE` or `EVIDENCE_UNAVAILABLE`; selected successful empty node adds `PROFILE_EMPTY` or `EVIDENCE_EMPTY`; compose success returns its string value or unwraps `.plan()`. Add tests for both legacy fixed outputs and typed dynamic outputs.

- [ ] **Step 5: Run Learning and durable regressions**

```bash
mvn -q -Dtest=LearningPlanNodeOperationsTest,LearningPlanPreviewServiceTest,LearningPlanPreviewWiringTest,LearningPlanWorkflowTest,DurableLearningPlanServiceTest test
```

Expected: all named tests pass and `LearningPlanWorkflowTest` proves existing durable output fixtures and workflow-v1 JSON remain unchanged.

- [ ] **Step 6: Commit Task 4**

```bash
git add src/main/java/io/github/imzmq/interview/learning/application/planning \
  src/test/java/io/github/imzmq/interview/learning/application/planning
git diff --cached --check
git commit -m "refactor(learning): share plan preview node operations"
```

---

## Task 5: D3b — Add Learning capabilities, deterministic planning, modes, and fixed fallback

**Files:**
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningProperties.java`.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/RuleBasedLearningPlanPlanner.java`.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanProfileCapability.java`.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanEvidenceCapability.java`.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanComposeCapability.java`.
- Create `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningService.java`.
- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewService.java`.
- Modify `src/main/resources/application.yml`.
- Modify `src/main/resources/application-local-lite.yml`.
- Create `src/test/java/io/github/imzmq/interview/learning/application/planning/RuleBasedLearningPlanPlannerTest.java`.
- Create `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningServiceTest.java`.
- Create `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningPropertiesTest.java`.
- Modify `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanPreviewWiringTest.java`.

- [ ] **Step 1: Write deterministic planner tests**

Use exact request characteristics:

```java
@ParameterizedTest
@CsvSource({
        "Java并发,结合我的薄弱项安排训练,profile|compose",
        "Java并发,参考知识库资料安排训练,evidence|compose",
        "Java并发,结合历史表现和参考证据安排训练,profile|evidence|compose",
        "Java并发,安排三天训练,profile|evidence|compose",
        "Java concurrency,personalized from my history,profile|compose",
        "Java concurrency,use knowledge references,evidence|compose"
})
void selectsAStableConservativeShape(String topic, String request, String expected) {
    CandidateDynamicTaskPlan first = planner.plan(topic, request);
    CandidateDynamicTaskPlan second = planner.plan(topic, request);
    assertThat(shape(first)).isEqualTo(expected);
    assertThat(second).isEqualTo(first);
}
```

Use explicit keyword sets in the production class:

```text
profile: 薄弱, 历史, 表现, 个性化, 画像, 掌握, history, profile, personalized, weakness

evidence: 资料, 知识库, 证据, 参考, 题目, 文档, evidence, reference, knowledge, document
```

If both sets are absent, select both sources. Normalize with `Locale.ROOT` only; do not call a model.

- [ ] **Step 2: Write mode and fallback tests**

`LearningPlanDynamicPlanningServiceTest` must prove:

1. `FIXED` returns the fixed three-node graph and never invokes the rule planner, validator, or compiler;
2. `RULES` compiles a valid two-node plan and does not execute the omitted capability;
3. planner exception falls back to the fixed graph before execution;
4. invalid validation result falls back before execution;
5. compiler exception falls back before execution;
6. once returned dynamic nodes begin executing, a node failure does not invoke fixed fallback;
7. fixed fallback itself is attempted once, not recursively;
8. selected-node result presentation omits degradation for an unselected source;
9. durable `LearningPlanWorkflow` remains three nodes under `RULES` configuration.

Use mocks/counters around `DagExecutionOperations` and capabilities. Assert graph IDs before calling the executor so fallback timing is explicit.

- [ ] **Step 3: Write properties validation tests**

Instantiate properties directly and call `validate()`:

```java
@Test
void defaultsAreFixedAndBounded() {
    LearningPlanDynamicPlanningProperties properties = new LearningPlanDynamicPlanningProperties();
    properties.validate();
    assertThat(properties.getMode()).isEqualTo(Mode.FIXED);
    assertThat(properties.toLimits()).isEqualTo(new DynamicPlanningLimits(8, 4, 4, 4, 10, 20));
}

@Test
void rejectsLimitsOutsideThePhaseOneEnvelope() {
    LearningPlanDynamicPlanningProperties properties = new LearningPlanDynamicPlanningProperties();
    properties.setMaxNodes(9);
    assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("INVALID_DYNAMIC_PLANNING_LIMITS");
}
```

Validate: max nodes `2..8`, depth `1..4`, dependencies/fan-out `1..4`, cost budget `1..10`, max issues `1..20`, planner timeout `100..3000` ms, and max plan bytes `1024..32768`.

- [ ] **Step 4: Run tests and verify missing implementations**

```bash
mvn -q -Dtest=RuleBasedLearningPlanPlannerTest,LearningPlanDynamicPlanningServiceTest,LearningPlanDynamicPlanningPropertiesTest test
```

Expected: compilation fails for the new properties, capabilities, planner, and service.

- [ ] **Step 5: Implement Learning capability descriptors and typed arguments**

Descriptor definitions must be exact:

```text
learning.profile.lookup v1
  READ_ONLY, no args, no deps, cost 1, requireSuccessfulDependencies true

knowledge.evidence.retrieve v1
  READ_ONLY, optional INTEGER limit default 4 range 1..5, no deps, cost 2,
  requireSuccessfulDependencies true

learning.plan.compose v1
  READ_ONLY, no args, allowed deps profile/evidence, cost 3,
  requireSuccessfulDependencies false
```

Use typed argument records `NoArguments` and `EvidenceArguments(int limit)` inside the capability classes. Profile and evidence return their typed material records. Compose inspects only selected upstream results and passes `Optional.empty()` for an omitted source. It reports an unavailable degradation only when a selected upstream node did not succeed.

- [ ] **Step 6: Implement properties and deterministic planner**

Bind:

```java
@Component
@ConfigurationProperties(prefix = "app.learning.plan-preview.dynamic-planning")
public final class LearningPlanDynamicPlanningProperties {
    public enum Mode { FIXED, RULES, SHADOW }
}
```

Include fields `mode`, `plannerTimeoutMillis`, `maxPlanBytes`, `maxNodes`, `maxDepth`, `maxDependenciesPerNode`, `maxFanOut`, `maxEstimatedCost`, and `maxIssues`. Add `@PostConstruct validate()` and `toLimits()`.

`RuleBasedLearningPlanPlanner.plan(topic, request)` returns schema `"1"`, stable IDs `profile`, `evidence`, and `compose`, and `limit=4` for evidence. It never emits user identity, timeout, prompt, or implementation names.

- [ ] **Step 7: Implement pre-execution selection and fallback**

`LearningPlanDynamicPlanningService` receives fixed-node factory access, the rule planner, validator, Learning policy, compiler, properties, and descriptor snapshot. Expose:

```java
List<DagNode> nodes(DynamicExecutionContext context)
```

Behavior:

```java
if (properties.getMode() == Mode.FIXED) return fixedNodes(context);
try {
    CandidateDynamicTaskPlan candidate = planner.plan(context.topic(), context.request());
    DynamicPlanValidationResult result = validator.validate(candidate, policy, properties.toLimits());
    if (!result.valid()) return fixedNodes(context);
    return compiler.compile(result.plan(), context);
} catch (RuntimeException error) {
    return fixedNodes(context);
}
```

Do not catch errors from `executor.execute`; `LearningPlanPreviewService.preview` first obtains nodes, then calls the executor exactly once. This enforces pre-execution-only fallback.

- [ ] **Step 8: Wire sync preview to dynamic selection and durable workflow to fixed selection**

`LearningPlanPreviewService.preview` validates owner/topic/request, builds `DynamicExecutionContext`, calls `dynamicPlanning.nodes(context)`, then invokes `executor.execute` once. Fixed fallback and `LearningPlanWorkflow` both call `LearningPlanFixedNodeFactory`; no fixed-node builder remains on `LearningPlanPreviewService`.

Use the package-private `LearningPlanFixedNodeFactory` created in Task 4. The final dependency direction is:

```text
LearningPlanPreviewService -> LearningPlanDynamicPlanningService
LearningPlanDynamicPlanningService -> LearningPlanFixedNodeFactory
LearningPlanWorkflow -> LearningPlanFixedNodeFactory
```

Do not make `LearningPlanDynamicPlanningService` call back into `LearningPlanPreviewService`.

- [ ] **Step 9: Add safe configuration defaults**

Add to `application.yml`:

```yaml
  learning:
    notes:
      dir: ${APP_LEARNING_NOTES_DIR:learning-notes}
    plan-preview:
      dynamic-planning:
        mode: ${APP_LEARNING_PLAN_DYNAMIC_MODE:FIXED}
        planner-timeout-millis: ${APP_LEARNING_PLAN_DYNAMIC_PLANNER_TIMEOUT_MILLIS:2000}
        max-plan-bytes: ${APP_LEARNING_PLAN_DYNAMIC_MAX_PLAN_BYTES:32768}
        max-nodes: ${APP_LEARNING_PLAN_DYNAMIC_MAX_NODES:8}
        max-depth: ${APP_LEARNING_PLAN_DYNAMIC_MAX_DEPTH:4}
        max-dependencies-per-node: ${APP_LEARNING_PLAN_DYNAMIC_MAX_DEPENDENCIES:4}
        max-fan-out: ${APP_LEARNING_PLAN_DYNAMIC_MAX_FAN_OUT:4}
        max-estimated-cost: ${APP_LEARNING_PLAN_DYNAMIC_MAX_COST:10}
        max-issues: ${APP_LEARNING_PLAN_DYNAMIC_MAX_ISSUES:20}
```

Add to `application-local-lite.yml`:

```yaml
  learning:
    plan-preview:
      dynamic-planning:
        mode: FIXED
```

Do not add an API Key or active/shadow-model toggle.

- [ ] **Step 10: Run D3 tests and architecture baseline**

```bash
mvn -q -Dtest=RuleBasedLearningPlanPlannerTest,LearningPlanDynamicPlanningServiceTest,LearningPlanDynamicPlanningPropertiesTest,LearningPlanPreviewServiceTest,LearningPlanPreviewWiringTest,DurableLearningPlanServiceTest,ArchitectureRulesTest test
```

Expected: all named tests pass. Durable tests still see fixed profile/evidence/compose nodes.

- [ ] **Step 11: Commit Task 5**

```bash
git add src/main/java/io/github/imzmq/interview/learning/application/planning \
  src/test/java/io/github/imzmq/interview/learning/application/planning \
  src/main/resources/application.yml src/main/resources/application-local-lite.yml
git diff --cached --check
git commit -m "feat(learning): add rule-based dynamic plan preview"
```

---

## Task 6: D4a — Publish sanitized dynamic-planning observations

**Files:**
- Create `src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicPlanObservationPublisher.java`.
- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningService.java`.
- Create `src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicPlanObservationPublisherTest.java`.
- Modify `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningServiceTest.java`.
- Modify `docs/development/observability-guidelines.md`.

- [ ] **Step 1: Write sanitization tests against the public observation contract**

Use a recording `OperationalObservationPublisher` and assert exact keys:

```java
@Test
void publishesOnlyBoundedStructuralAttributes() {
    observer.compiled("rules", "v1", plan, "digest", 12L);

    RecordedEvent event = recorder.single();
    assertThat(event.eventType()).isEqualTo("dynamic_plan.compiled");
    assertThat(event.attributes()).containsOnlyKeys(
            "scenario", "plannerType", "plannerVersion", "schemaVersion",
            "nodeCount", "depth", "capabilities", "planDigest", "durationMs");
    assertThat(event.attributes().toString())
            .doesNotContain("secret topic", "raw request", "prompt", "owner", "arguments");
}
```

Add tests for `requested`, `generated`, `rejected`, `fallback`, and later `shadowCompared`. Capability IDs must be sorted and joined into one bounded string. Error codes must match an allowlist; unknown codes become `PLANNING_FAILED`.

- [ ] **Step 2: Run the observation test and verify missing-class failure**

```bash
mvn -q -Dtest=DynamicPlanObservationPublisherTest test
```

Expected: compilation fails because the publisher does not exist.

- [ ] **Step 3: Implement the sanitized publisher**

Depend only on `observability.application.contract.OperationalObservationPublisher`. Publish category `agent-planning`, name `learning-plan-preview`, and event names from the spec. Construct a fresh `LinkedHashMap<String,Object>` per event. Never accept raw topic, request, prompt, response, arguments, exception, owner, or evidence as method parameters.

Expose methods whose parameters make unsafe logging difficult:

```java
void requested(String plannerType, String plannerVersion)
void generated(String plannerType, String plannerVersion, ValidatedDynamicTaskPlan plan, String digest, long durationMs)
void rejected(String plannerType, String plannerVersion, String errorCode, long durationMs)
void compiled(String plannerType, String plannerVersion, ValidatedDynamicTaskPlan plan, String digest, long durationMs)
void fallback(String plannerType, String plannerVersion, String errorCode)
void shadowCompared(String ruleDigest, String shadowDigest, boolean same, int nodeDelta, long durationMs)
```

- [ ] **Step 4: Instrument pre-execution planning only**

Update `LearningPlanDynamicPlanningService` to publish requested/generated/rejected/compiled/fallback events. Measure with `System.nanoTime()`. Convert thrown exceptions to stable codes without publishing `error.getMessage()`.

`FIXED` mode may publish a bounded requested/compiled baseline event or publish nothing; choose one behavior and lock it with the test. Use “publish nothing” to keep the disabled path zero-cost.

- [ ] **Step 5: Document event contract**

In `docs/development/observability-guidelines.md`, add the six event names, allowed structural fields, and the prohibited raw fields from the spec. State that the plan digest is an audit/comparison value, not an authentication token.

- [ ] **Step 6: Run observation and planning tests**

```bash
mvn -q -Dtest=DynamicPlanObservationPublisherTest,LearningPlanDynamicPlanningServiceTest test
```

Expected: both test classes pass and recording assertions contain no sensitive content.

- [ ] **Step 7: Commit Task 6**

```bash
git add src/main/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicPlanObservationPublisher.java \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningService.java \
  src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/DynamicPlanObservationPublisherTest.java \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningServiceTest.java
git add -f docs/development/observability-guidelines.md
git diff --cached --check
git commit -m "feat(agent): observe controlled dynamic planning"
```

---

## Task 7: D4b — Enforce architecture boundaries and update ownership documentation

**Files:**
- Modify `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`.
- Create `src/test/java/io/github/imzmq/interview/learning/api/dynamicfixture/ControllerDependingOnPlanner.java`.
- Create `src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/fixture/CoreDependingOnSpringAi.java`.
- Create `src/test/java/io/github/imzmq/interview/learning/application/planning/fixture/LearningPlanPersistenceCapability.java`.
- Create `src/test/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/fixture/ContractExposingJsonNode.java`.
- Modify `PACKAGE_CONVENTIONS.md`.
- Modify `docs/development/learning-plan-preview.md`.

- [ ] **Step 1: Add negative fixtures and tests that prove each rule catches a violation**

Place every fixture under a test-source package that matches the corresponding production rule selector. The production `@AnalyzeClasses` scan continues to exclude test classes; each fixture is imported explicitly only by its negative test. For example:

```java
package io.github.imzmq.interview.agent.application.execution.dynamic.fixture;

import org.springframework.ai.chat.model.ChatModel;

public final class CoreDependingOnSpringAi {
    private ChatModel model;
}
```

In `ArchitectureRulesTest`, import each fixture explicitly and assert `hasViolation()` before relying on the production scan:

```java
@Test
void dynamic_planning_core_rule_catches_spring_ai_dependency() {
    JavaClasses fixture = new ClassFileImporter().importClasses(CoreDependingOnSpringAi.class);
    assertThat(dynamic_planning_core_should_be_sdk_neutral.evaluate(fixture).hasViolation()).isTrue();
}
```

The other fixtures must likewise match their selectors: `..learning.api..`, a `LearningPlan*Capability` class under `..learning.application.planning..`, and `..agent.application.contract.execution.dynamic..`. Add equivalent negative tests for Learning API → planner internals, Learning capability → MyBatis Mapper/persistence, and public contract → Jackson `JsonNode`.

- [ ] **Step 2: Run the negative fixture tests against not-yet-added rules**

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: compilation fails because the new ArchUnit rule fields referenced by tests do not exist.

- [ ] **Step 3: Add production ArchUnit rules**

Add rules with these scopes:

```java
@ArchTest
static final ArchRule learning_api_should_not_access_dynamic_planning_internals =
        noClasses().that().resideInAPackage("..learning.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..agent.application.execution.dynamic..",
                        "..agent.infrastructure.planning..",
                        "..learning.application.planning..");

@ArchTest
static final ArchRule dynamic_planning_core_should_be_sdk_neutral =
        noClasses().that().resideInAnyPackage(
                        "..agent.application.contract.execution.dynamic..",
                        "..agent.application.execution.dynamic..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..", "org.springframework.ai..",
                        "com.baomidou.mybatisplus..", "org.apache.ibatis..",
                        "io.milvus..", "org.neo4j..", "dev.langchain4j..",
                        "io.github.imzmq.interview.learning..",
                        "io.github.imzmq.interview.knowledge..");
```

Add separate rules that:

- `..agent.infrastructure.planning..` also remains free of direct Spring AI types and calls only `modelrouting.application.contract.DynamicPlanningModelOperations`;
- Learning capability classes (`LearningPlan*Capability`) do not depend on Mapper, `..infrastructure.persistence..`, `..tool..`, `..mcp..`, or `..agent.runtime..`;
- `..application.contract.execution.dynamic..`, `DynamicNodeCapability`, and `modelrouting.application.contract.DynamicPlanningModelOperations` do not depend on Jackson, Spring AI, Web, persistence, or infrastructure types.

Use the existing custom `areMyBatisMappers()` predicate for Mapper detection. Do not add allowlists or exclude production packages to make rules pass.

- [ ] **Step 4: Run architecture and module boundary tests**

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected: all ArchUnit production scans and all negative-fixture assertions pass.

- [ ] **Step 5: Update package and behavior documentation**

Add to `PACKAGE_CONVENTIONS.md`:

```text
- Generic candidate/validated plan contracts and DAG compilation belong to Agent execution.
- Business capability implementations and scenario policy belong to the business owner.
- LLM planning calls enter ModelRouting through the provider-owned `modelrouting.application.contract.DynamicPlanningModelOperations`; ModelRouting infrastructure implements it, preserving the one-way `agent -> modelrouting` dependency and avoiding a module cycle.
- Dynamic planning must not reference Bean names, implementation class names, Tool executors, or persistence adapters.
```

Update `docs/development/learning-plan-preview.md` with `FIXED`, `RULES`, `SHADOW`, default `FIXED`, request-scoped-only behavior, durable fixed workflow, no frontend/API change, and the `application-private.yml` Key rule for later real shadow testing.

- [ ] **Step 6: Run focused boundary and Learning regression tests**

```bash
mvn -q -Dtest=ArchitectureRulesTest,LearningPlanPreviewServiceTest,LearningPlanDynamicPlanningServiceTest,DurableLearningPlanServiceTest test
```

Expected: all named tests pass.

- [ ] **Step 7: Commit Task 7**

```bash
git add src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java \
  src/test/java/io/github/imzmq/interview/learning/api/dynamicfixture \
  src/test/java/io/github/imzmq/interview/agent/application/execution/dynamic/fixture \
  src/test/java/io/github/imzmq/interview/learning/application/planning/fixture \
  src/test/java/io/github/imzmq/interview/agent/application/contract/execution/dynamic/fixture \
  PACKAGE_CONVENTIONS.md
git add -f docs/development/learning-plan-preview.md
git diff --cached --check
git commit -m "test(architecture): enforce dynamic planning boundaries"
```

---

## Task 8: D5 — Add bounded, non-executing LLM planner shadow mode

**Files:**
- Create `src/main/java/io/github/imzmq/interview/modelrouting/application/contract/DynamicPlanningModelOperations.java`.
- Create `src/main/java/io/github/imzmq/interview/modelrouting/infrastructure/planning/ModelRoutingDynamicPlanningModel.java`.
- Create `src/main/java/io/github/imzmq/interview/agent/infrastructure/planning/LlmDynamicTaskPlanner.java`.
- Create `src/main/java/io/github/imzmq/interview/agent/infrastructure/planning/DynamicPlanningShadowRunner.java`.
- Modify `src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningService.java`.
- Create `src/test/java/io/github/imzmq/interview/agent/infrastructure/planning/LlmDynamicTaskPlannerTest.java`.
- Create `src/test/java/io/github/imzmq/interview/agent/infrastructure/planning/DynamicPlanningShadowRunnerTest.java`.
- Create `src/test/java/io/github/imzmq/interview/modelrouting/infrastructure/planning/ModelRoutingDynamicPlanningModelTest.java`.
- Modify `src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningServiceTest.java`.

- [ ] **Step 1: Write LLM planner adapter tests with a fake ModelRouting contract**

Use the provider-owned narrow contract:

```java
@FunctionalInterface
public interface DynamicPlanningModelOperations {
    String generatePlan(String systemPrompt, String userPrompt);
}
```

Test that `LlmDynamicTaskPlanner`:

- sends only topic, request, schema, allowed capability descriptors, and explicit JSON-only instructions;
- does not send owner, Bean names, handler classes, API keys, profile content, or evidence content;
- parses exact valid JSON through `StrictDynamicPlanJsonParser`;
- returns `INVALID_PLAN_JSON` for fenced/explanatory/malformed output;
- does not retry or repair malformed output;
- never executes or compiles a plan.

A representative assertion:

```java
@Test
void plannerSendsOnlyTheBoundedPlanningView() {
    AtomicReference<String> userPrompt = new AtomicReference<>();
    DynamicPlanningModelOperations model = (system, user) -> {
        userPrompt.set(user);
        return VALID_PLAN_JSON;
    };

    DynamicPlanParseResult result = planner(model).plan(
            new DynamicExecutionContext("private-owner", "Java", "参考资料"), descriptors);

    assertThat(result.success()).isTrue();
    assertThat(userPrompt.get()).contains("Java", "参考资料", "knowledge.evidence.retrieve");
    assertThat(userPrompt.get()).doesNotContain("private-owner", "Bean", "API_KEY");
}
```

- [ ] **Step 2: Write bounded shadow-runner tests**

Use latches and counters to prove:

1. `submit` returns before a blocked fake model completes;
2. a valid shadow candidate is parsed, validated, fingerprinted, and compared but never compiled or executed;
3. invalid output publishes rejected and does not affect the rule result;
4. model exception is contained;
5. timeout cancellation interrupts a cooperative blocked model and publishes `SHADOW_TIMEOUT`;
6. when a fake model ignores interruption, its one physical worker stays occupied and a saturated queue publishes `SHADOW_OVERLOADED` rather than creating replacement capacity;
7. `close()` shuts down both the worker executor and timeout scheduler;
8. no submitted runnable uses the `FixedDagExecutor` worker pool.

Construct the runner with testable limits:

```java
DynamicPlanningShadowRunner runner = new DynamicPlanningShadowRunner(
        llmPlanner, validator, fingerprint, observer,
        Duration.ofMillis(100), 1, 1);
runner.submit(CONTEXT, RULE_PLAN, policy, descriptors, limits);
```

- [ ] **Step 3: Write SHADOW mode integration tests**

Add to `LearningPlanDynamicPlanningServiceTest`:

```java
@Test
void shadowModeExecutesRulesAndSubmitsExactlyOneNonBlockingComparison() {
    properties.setMode(Mode.SHADOW);
    List<DagNode> nodes = service.nodes(CONTEXT);
    assertThat(nodes).extracting(DagNode::id).containsExactly("evidence", "compose");
    verify(shadowRunner).submit(
            eq(CONTEXT), any(ValidatedDynamicTaskPlan.class), eq(policy), eq(descriptors), eq(limits));
    verifyNoInteractions(fixedFallbackAfterPlanningSucceeded);
}
```

Also prove `FIXED` and `RULES` never submit shadow work. Missing/unavailable shadow infrastructure must leave rule execution unchanged.

- [ ] **Step 4: Run tests and verify missing implementation failures**

```bash
mvn -q -Dtest=LlmDynamicTaskPlannerTest,DynamicPlanningShadowRunnerTest,ModelRoutingDynamicPlanningModelTest,LearningPlanDynamicPlanningServiceTest test
```

Expected: compilation fails for the ModelRouting contract, adapter, LLM planner, and shadow runner.

- [ ] **Step 5: Implement the ModelRouting-owned public contract and internal adapter**

`DynamicPlanningModelOperations` belongs to `modelrouting.application.contract`; Agent infrastructure may call it without creating a reverse `modelrouting -> agent` edge. `ModelRoutingDynamicPlanningModel` belongs to ModelRouting infrastructure and is the only new class allowed to depend on `RoutingChatService` and `ModelRouteType`:

```java
@Component
public final class ModelRoutingDynamicPlanningModel implements DynamicPlanningModelOperations {
    private final RoutingChatService routing;

    public ModelRoutingDynamicPlanningModel(RoutingChatService routing) {
        this.routing = routing;
    }

    @Override
    public String generatePlan(String systemPrompt, String userPrompt) {
        return routing.call(systemPrompt, userPrompt, ModelRouteType.GENERAL, "受控动态计划影子");
    }
}
```

Keep the public contract JDK-only. Do not expose `RoutingChatService`, `ChatModel`, route/provider names, or Spring AI types. This deliberately uses the normal provider-contract rule from `PACKAGE_CONVENTIONS.md`; an Agent-owned SPI would introduce `agent -> modelrouting` and `modelrouting -> agent` edges because Agent already consumes ModelRouting.

- [ ] **Step 6: Implement the strict LLM planner**

`LlmDynamicTaskPlanner` receives `DynamicPlanningModelOperations` and `StrictDynamicPlanJsonParser`. Build a fixed system prompt containing:

```text
Return one JSON object only. Do not use markdown.
Use schemaVersion "1".
Use only the supplied capability IDs and allowed arguments.
Do not emit user identity, URL, file path, class, Bean, method, SQL, shell, tool, timeout, retry, token, or infrastructure settings.
```

The user prompt contains the bounded topic/request and sorted descriptor summaries. It does not contain owner. Call `generatePlan` once and pass the raw output directly to the strict parser. Catch model exceptions and return `DynamicPlanParseResult.failure("PLANNER_UNAVAILABLE")` without logging the exception message.

- [ ] **Step 7: Implement one bounded worker executor plus one timeout scheduler**

`DynamicPlanningShadowRunner` owns exactly:

```text
shadowWorkers   = fixed-size ThreadPoolExecutor, daemon thread dynamic-plan-shadow-1,
                  ArrayBlockingQueue, AbortPolicy
 timeoutScheduler = one-thread daemon ScheduledExecutorService,
                    thread dynamic-plan-shadow-timeout-1
```

Expose the generic method:

```java
void submit(
        DynamicExecutionContext context,
        ValidatedDynamicTaskPlan rulePlan,
        DynamicPlanScenarioPolicy policy,
        List<DynamicCapabilityDescriptor> descriptors,
        DynamicPlanningLimits limits)
```

`submit` copies the descriptor list, submits the entire shadow job once to `shadowWorkers`, and returns immediately. Store the returned worker `Future<?>`; schedule one timeout task that calls `future.cancel(true)` after the configured duration. Each job owns an `AtomicBoolean terminalObservation`: the timeout task publishes `SHADOW_TIMEOUT` only when cancellation succeeds and it wins `compareAndSet(false, true)`; normal/rejected/comparison publication uses the same guard. In the worker’s `finally`, cancel the scheduled timeout task when the job completed first. After the model call, the worker exits without validation or publication if timeout already won. Do not nest another future in the worker pool and do not use the common pool.

Inside the worker job:

1. call the LLM planner once;
2. on parse/model failure, publish a stable rejection;
3. validate with the same Learning policy and limits;
4. never call compiler, capability, or `DagExecutionOperations`;
5. fingerprint valid shadow and rule plans;
6. publish `shadowCompared` with digest equality and node delta.

If `shadowWorkers.submit` throws `RejectedExecutionException`, publish `SHADOW_OVERLOADED` and return. If a model ignores interruption after timeout, that physical worker remains occupied until the call exits; cancellation must not decrement separate capacity counters or spawn a replacement. `close()` invokes `shutdownNow()` on both executors. Tests must close every runner.

- [ ] **Step 8: Trigger shadow only after a valid rule plan exists**

In `LearningPlanDynamicPlanningService`:

```text
FIXED  -> fixed nodes, no shadow
RULES  -> validate/compile rule plan, no shadow
SHADOW -> validate/compile rule plan, submit shadow comparison, return compiled rule nodes immediately
```

If rule planning fails, use fixed fallback and do not submit shadow. If shadow submission fails, return the compiled rule nodes unchanged.

Inject `ObjectProvider<DynamicPlanningShadowRunner>` so contexts that intentionally omit model wiring remain bootable. An absent runner means a safe shadow skip; it must not change `FIXED` or `RULES` startup. In `SHADOW`, pass the same policy, immutable descriptor snapshot, and `properties.toLimits()` used for the rule candidate into `submit`; the runner must not discover scenario beans or configuration by name.

- [ ] **Step 9: Run D5 focused tests**

```bash
mvn -q -Dtest=LlmDynamicTaskPlannerTest,DynamicPlanningShadowRunnerTest,ModelRoutingDynamicPlanningModelTest,LearningPlanDynamicPlanningServiceTest,LearningPlanPreviewWiringTest test
```

Expected: all named tests pass. No test accesses a real model or requires an API Key.

- [ ] **Step 10: Run architecture, compile, and full regression verification**

```bash
mvn -q -Dtest=ArchitectureRulesTest test
mvn -q compile
mvn -q -DskipTests test-compile
mvn test
```

Expected: every command exits 0. Record the final Maven test counts from the output; do not reuse the earlier 735-test count because this phase adds tests.

- [ ] **Step 11: Verify repository scope and secret hygiene**

```bash
git status --short --branch
git diff --check
git diff --name-only
git grep -nE '(api[_-]?key|token|secret)[[:space:]]*[:=][[:space:]]*[^$<{[:space:]]+' -- \
  src/main src/test application.yml application-private.yml 2>/dev/null || true
```

Expected: only planned D5 files and pre-existing user-owned files appear; no committed credential value appears. Do not stage `application-private.yml`, `AGENTS.md`, or `.DS_Store`.

- [ ] **Step 12: Commit Task 8**

```bash
git add src/main/java/io/github/imzmq/interview/modelrouting/application/contract/DynamicPlanningModelOperations.java \
  src/main/java/io/github/imzmq/interview/modelrouting/infrastructure/planning \
  src/main/java/io/github/imzmq/interview/agent/infrastructure/planning \
  src/main/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningService.java \
  src/test/java/io/github/imzmq/interview/modelrouting/infrastructure/planning \
  src/test/java/io/github/imzmq/interview/agent/infrastructure/planning \
  src/test/java/io/github/imzmq/interview/learning/application/planning/LearningPlanDynamicPlanningServiceTest.java
git diff --cached --check
git commit -m "feat(agent): observe llm-generated plans in shadow mode"
```

---

## Final acceptance checklist

- [ ] Dynamic plans reference only registered `READ_ONLY` capabilities.
- [ ] Candidate plans cannot reach `FixedDagExecutor` without strict validation.
- [ ] The compiler resolves capability versions and typed arguments before any business node executes.
- [ ] Rule planning can produce exactly the three approved Learning graph shapes.
- [ ] Omitted sources do not appear as failed/unavailable degradations.
- [ ] Planning/parser/validation/compiler failures fall back before execution.
- [ ] Runtime node failures never trigger graph replacement or replan.
- [ ] Fixed and dynamic paths share Learning node operations.
- [ ] Durable workflow version 1, persistence, HTTP, and frontend remain fixed and unchanged.
- [ ] `FIXED` is the default in both repository and `local-lite` configuration.
- [ ] `SHADOW` executes the rule plan and never executes the LLM plan.
- [ ] Shadow execution uses separate bounded resources and contains timeouts/overload.
- [ ] Observations contain structural fields only, not request, prompt, response, arguments, identity, profile, evidence, credentials, or exception messages.
- [ ] ArchUnit negative fixtures prove the new boundaries are enforced.
- [ ] No Saber-specific class, property, collection, or dataset contract exists.
- [ ] No database migration, empty abstraction, reflection dispatch, Bean-name dispatch, or second DAG executor exists.
- [ ] `mvn -q -Dtest=ArchitectureRulesTest test`, compile, test-compile, and `mvn test` all exit 0 in the implementation session.
- [ ] Each task commit contains only its listed files plus necessary adjacent test/document updates.

## Real shadow-model smoke test (separate operator-approved follow-up)

Do not perform this during automated implementation. After D5 is merged and the user chooses to run a real shadow comparison:

1. Put the already-supported provider credential only in `/Users/bytedance/bits/StudyAgent/Study-Agent/application-private.yml`.
2. Set `APP_LEARNING_PLAN_DYNAMIC_MODE=SHADOW` locally or override the corresponding property in that untracked file.
3. Start the project with the existing local startup instructions.
4. Invoke one request-scoped Learning Plan Preview task, not a durable HTTP task.
5. Confirm the user receives the rule-plan result even if the shadow model fails.
6. Inspect sanitized operational observations for `dynamic_plan.shadow_compared` or a stable rejection code.
7. Return the mode to `FIXED` after the smoke test.
8. Never add or stage `application-private.yml`.
