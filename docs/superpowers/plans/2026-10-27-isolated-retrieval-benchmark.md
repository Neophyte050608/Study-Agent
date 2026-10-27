# Isolated Retrieval Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a dataset-neutral, resumable Milvus vector benchmark that compares an isolated vector index with local BM25 without contaminating the normal knowledge index or automatically issuing bulk embedding calls.

**Architecture:** Keep gold-set parsing, validation, metrics, and comparison in `knowledge.application.evaluation.goldset`. Add narrow application ports for the isolated index and checkpoint state; implement files and Milvus under `knowledge.infrastructure.evaluation`. The startup runner remains disabled by default and dispatches exactly one explicit operation: `VALIDATE`, `INDEX`, `COMPARE`, or confirmed `CLEAN`.

**Tech Stack:** Java 21, Spring Boot, Spring AI `EmbeddingModel`, Milvus Java SDK, Jackson, JUnit 5, AssertJ, Mockito, ArchUnit, Maven.

---

## Delivery constraints

- Work on `/Users/bytedance/bits/StudyAgent/Study-Agent` `main`; inspect `git status --short --branch` before every commit.
- Never stage the user-owned `AGENTS.md` or any `.DS_Store` file.
- Use TDD for every behavior change: write one focused test, observe the expected failure, add minimal production code, rerun it.
- S1–S3 development and unit tests must not call a real embedding provider or perform a real 10,000-chunk import.
- Do not run a real `INDEX` operation until the user separately approves the call count, expected duration, and potential cost.
- Commit each task separately using the repository commit convention. Push completed increments to `origin/main` only after their verification passes.

## File map

### Existing files to modify

- `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetDatasetValidator.java` — require nonblank chunk content.
- `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetBenchmarkComparisonService.java` — retain only the existing business-index comparison; external file flow stops using it.
- `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/GoldSetBenchmarkFileService.java` — replace production-RAG file orchestration with operation-neutral validated dataset loading, then rename to `RetrievalBenchmarkFileService`.
- `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/GoldSetBenchmarkRunner.java` — dispatch one safe operation; rename to `RetrievalBenchmarkRunner`.
- `src/main/java/io/github/imzmq/interview/config/knowledge/GoldSetBenchmarkProperties.java` — expand and rename to `RetrievalBenchmarkProperties`.
- `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetComparisonMarkdownRenderer.java` — remain the comparison-body renderer; disclaimer and run metadata belong to a new report renderer.
- `src/main/resources/application.yml` — add safe benchmark defaults and environment-variable mappings.
- `src/main/resources/eval/README.md` — document the generic contract and four explicit operations.
- `docs/development/verification.md` — document focused tests and the external-call gate.
- `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java` — prohibit Milvus and Spring AI types in the evaluation application package.

### New application files

All under `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/`:

- `BenchmarkOperation.java` — `VALIDATE`, `INDEX`, `COMPARE`, `CLEAN`.
- `BenchmarkDatasetIdentity.java` — validated dataset/model identity.
- `BenchmarkFingerprint.java` — SHA-256 helper for deterministic identity.
- `BenchmarkIndex.java` — narrow vector index port.
- `BenchmarkIndexMatch.java` — chunk ID and score returned by the port.
- `BenchmarkCheckpoint.java`, `BenchmarkCheckpointStatus.java`, `BenchmarkCheckpointStore.java` — resumable progress contract.
- `BenchmarkIndexCommand.java`, `BenchmarkIndexingResult.java`, `BenchmarkIndexingService.java` — batch and resume orchestration.
- `IsolatedVectorEvaluator.java` — converts vector search results into an evaluation run.
- `IsolatedVectorBenchmarkService.java` — compares isolated vector results with BM25.
- `BenchmarkRunMetadata.java`, `IsolatedBenchmarkReport.java`, `IsolatedBenchmarkMarkdownRenderer.java` — honest report identity and disclaimer.
- `BenchmarkOperationException.java` — stable, content-free application error codes.

### New infrastructure files

All under `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/` unless noted:

- `ValidatedBenchmarkDataset.java` — validated cases, chunks, and two fingerprints.
- `BenchmarkDatasetFileLoader.java` — UTF-8 JSONL input adapter.
- `JsonBenchmarkCheckpointStore.java` — atomic JSON checkpoint adapter.
- `AtomicBenchmarkReportWriter.java` — temporary-file then atomic-move output.
- `RetrievalBenchmarkFileService.java` — `VALIDATE`, `INDEX`, `COMPARE`, `CLEAN` orchestration.
- `RetrievalBenchmarkRunner.java` — explicitly enabled startup entry.
- `milvus/MilvusBenchmarkSchema.java` — lazy schema creation/compatibility check.
- `milvus/MilvusBenchmarkIndex.java` — batch embedding, upsert, filtered search, count, and scoped delete.

Tests mirror these paths under `src/test/java`.

---

### Task 1: Make the JSONL contract genuinely dataset-neutral

**Files:**
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetDatasetValidator.java`
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetDataLoader.java`
- Modify: `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/GoldSetBenchmarkFileService.java`
- Test: `src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetDatasetValidatorTest.java`
- Test: `src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetDataLoaderTest.java`

- [ ] **Step 1: Write failing validation tests**

Add cases proving blank chunk content is rejected and unknown dataset fields remain harmless:

```java
@Test
void rejectsChunkWithoutContent() {
    List<GoldSetCase> cases = List.of(goldCase("q1", "Redis", List.of("c1")));
    GoldSetChunk blank = new GoldSetChunk("c1", "doc", 0, "  ", "", "", "");

    assertThatThrownBy(() -> validator.validate(cases, List.of(blank)))
            .isInstanceOf(GoldSetDatasetValidationException.class)
            .hasMessageContaining("blank content for chunk_id: c1");
}

@Test
void ignoresUnknownFieldsInGenericCorpusRows() throws Exception {
    List<GoldSetChunk> chunks = loader.loadChunks(new StringReader(
            "{\"chunk_id\":\"c1\",\"content\":\"Redis\",\"vendor_field\":42}\n"));

    assertThat(chunks).singleElement().extracting(GoldSetChunk::chunkId).isEqualTo("c1");
}
```

- [ ] **Step 2: Run the focused tests and confirm RED**

Run:

```bash
mvn -q -Dtest=GoldSetDatasetValidatorTest,GoldSetDataLoaderTest test
```

Expected: `rejectsChunkWithoutContent` fails because the validator currently accepts blank content; the unknown-field test passes.

- [ ] **Step 3: Add the minimum validator rule and remove dataset-specific wording**

In the chunk loop add:

```java
if (chunk.content() == null || chunk.content().isBlank()) {
    violations.add("blank content for chunk_id: " + normalizedId);
}
```

Change comments and Javadoc from “Saber-style” to “chunk-level benchmark JSONL”. Do not add alternate field aliases or dataset-name switches.

- [ ] **Step 4: Verify GREEN**

Run the same Maven command. Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetDatasetValidator.java \
  src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetDataLoader.java \
  src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/GoldSetBenchmarkFileService.java \
  src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetDatasetValidatorTest.java \
  src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/GoldSetDataLoaderTest.java
git commit -m "fix(knowledge): enforce generic benchmark corpus contract"
```

### Task 2: Add safe operation configuration and stable dataset identity

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkOperation.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkDatasetIdentity.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkFingerprint.java`
- Modify/Rename: `src/main/java/io/github/imzmq/interview/config/knowledge/GoldSetBenchmarkProperties.java` → `RetrievalBenchmarkProperties.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkDatasetIdentityTest.java`
- Rename/Modify: `src/test/java/io/github/imzmq/interview/config/knowledge/GoldSetBenchmarkPropertiesTest.java` → `RetrievalBenchmarkPropertiesTest.java` if present; otherwise create the target test.

- [ ] **Step 1: Write failing identity and property tests**

```java
@Test
void acceptsPortableDatasetIdentity() {
    BenchmarkDatasetIdentity identity = new BenchmarkDatasetIdentity(
            "rag.regression-2026_10", "a".repeat(64), "embedding-3", 2048);
    assertThat(identity.datasetId()).isEqualTo("rag.regression-2026_10");
}

@Test
void rejectsUnsafeDatasetId() {
    assertThatThrownBy(() -> new BenchmarkDatasetIdentity(
            "rag data/*", "a".repeat(64), "embedding-3", 2048))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("BENCHMARK_DATASET_ID_INVALID");
}

@Test
void defaultsToDisabledValidationWithoutDestructiveConfirmation() {
    RetrievalBenchmarkProperties properties = new RetrievalBenchmarkProperties();
    assertThat(properties.isEnabled()).isFalse();
    assertThat(properties.getOperation()).isEqualTo(BenchmarkOperation.VALIDATE);
    assertThat(properties.getCleanupConfirmation()).isEmpty();
}
```

- [ ] **Step 2: Run tests and confirm RED**

```bash
mvn -q -Dtest=BenchmarkDatasetIdentityTest,RetrievalBenchmarkPropertiesTest test
```

Expected: test compilation fails because the new types do not exist.

- [ ] **Step 3: Implement the pure types and safe configuration**

Use these public shapes:

```java
public enum BenchmarkOperation { VALIDATE, INDEX, COMPARE, CLEAN }

public record BenchmarkDatasetIdentity(
        String datasetId,
        String datasetVersion,
        String embeddingModelId,
        int embeddingDimension) {
    private static final Pattern DATASET_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    public BenchmarkDatasetIdentity {
        datasetId = datasetId == null ? "" : datasetId.trim();
        datasetVersion = datasetVersion == null ? "" : datasetVersion.trim().toLowerCase(Locale.ROOT);
        embeddingModelId = embeddingModelId == null ? "" : embeddingModelId.trim();
        if (!DATASET_ID.matcher(datasetId).matches()) throw new IllegalArgumentException("BENCHMARK_DATASET_ID_INVALID");
        if (!SHA256.matcher(datasetVersion).matches()) throw new IllegalArgumentException("BENCHMARK_DATASET_VERSION_INVALID");
        if (embeddingModelId.isEmpty()) throw new IllegalArgumentException("BENCHMARK_EMBEDDING_MODEL_INVALID");
        if (embeddingDimension < 1 || embeddingDimension > 65_536) throw new IllegalArgumentException("BENCHMARK_EMBEDDING_DIMENSION_INVALID");
    }
}
```

`BenchmarkFingerprint.sha256(byte[])` returns lowercase hexadecimal SHA-256. `RetrievalBenchmarkProperties` keeps `enabled=false`, `operation=VALIDATE`, generic collection `study_agent_retrieval_benchmark`, batch size `64`, dimension `2048`, model `embedding-3`, and blank paths/confirmation.

Map every value under `app.knowledge.retrieval-benchmark` in `application.yml`; use environment variables prefixed `APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_`.

- [ ] **Step 4: Verify GREEN and configuration binding**

```bash
mvn -q -Dtest=BenchmarkDatasetIdentityTest,RetrievalBenchmarkPropertiesTest test
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkOperation.java \
  src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkDatasetIdentity.java \
  src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkFingerprint.java \
  src/main/java/io/github/imzmq/interview/config/knowledge/RetrievalBenchmarkProperties.java \
  src/main/resources/application.yml \
  src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkDatasetIdentityTest.java \
  src/test/java/io/github/imzmq/interview/config/knowledge/RetrievalBenchmarkPropertiesTest.java
git commit -m "feat(knowledge): define isolated benchmark identity"
```

### Task 3: Build resumable batch indexing at the application boundary

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkIndex.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkIndexMatch.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkCheckpointStatus.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkCheckpoint.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkCheckpointStore.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkIndexCommand.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkIndexingResult.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkOperationException.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkIndexingService.java`
- Test: `src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkIndexingServiceTest.java`

- [ ] **Step 1: Write the failing batch/resume tests with in-memory fakes**

The tests must prove exact batch boundaries, checkpoint after each successful batch, completed-count verification, and resume from `completedChunks`:

```java
@Test
void indexesStableBatchesAndCompletesAfterCountMatches() {
    FakeIndex index = new FakeIndex(5);
    FakeCheckpointStore checkpoints = new FakeCheckpointStore();
    BenchmarkIndexingService service = new BenchmarkIndexingService(index, checkpoints);

    BenchmarkIndexingResult result = service.index(command(5, 2));

    assertThat(index.batchIds()).containsExactly(
            List.of("c0", "c1"), List.of("c2", "c3"), List.of("c4"));
    assertThat(checkpoints.saved()).extracting(BenchmarkCheckpoint::completedChunks)
            .containsExactly(2, 4, 5, 5);
    assertThat(result.status()).isEqualTo(BenchmarkCheckpointStatus.COMPLETED);
}

@Test
void resumesAtCheckpointWithoutReembeddingEarlierChunks() {
    FakeCheckpointStore checkpoints = new FakeCheckpointStore(checkpoint(2, IN_PROGRESS));
    FakeIndex index = new FakeIndex(5);
    BenchmarkIndexingService service = new BenchmarkIndexingService(index, checkpoints);

    service.index(command(5, 2));

    assertThat(index.batchIds()).containsExactly(List.of("c2", "c3"), List.of("c4"));
}
```

Add tests for identity mismatch, total-count mismatch, invalid batch size, and a second concurrent call rejected with `BENCHMARK_INDEX_ALREADY_RUNNING`.

- [ ] **Step 2: Run the focused test and confirm RED**

```bash
mvn -q -Dtest=BenchmarkIndexingServiceTest test
```

Expected: test compilation fails because the index contracts and service do not exist.

- [ ] **Step 3: Implement narrow ports and the indexing state machine**

Use these signatures:

```java
public interface BenchmarkIndex {
    void upsertBatch(BenchmarkDatasetIdentity identity, List<GoldSetChunk> chunks);
    List<BenchmarkIndexMatch> search(BenchmarkDatasetIdentity identity, String query, int topK);
    long count(BenchmarkDatasetIdentity identity);
    void deleteDataset(String datasetId);
}

public interface BenchmarkCheckpointStore {
    Optional<BenchmarkCheckpoint> load();
    void save(BenchmarkCheckpoint checkpoint);
}

public record BenchmarkIndexCommand(
        BenchmarkDatasetIdentity identity,
        String corpusSource,
        List<GoldSetChunk> chunks,
        int batchSize) {}
```

`BenchmarkIndexingService.index` acquires an `AtomicBoolean` guard, validates command/checkpoint identity before calling `BenchmarkIndex`, slices immutable batches, saves `IN_PROGRESS` after each successful upsert, verifies `count == chunks.size()`, saves `COMPLETED`, and releases the guard in `finally`. Throw `BenchmarkOperationException` with stable codes only.

- [ ] **Step 4: Verify GREEN**

```bash
mvn -q -Dtest=BenchmarkIndexingServiceTest test
```

Expected: all selected tests pass and no Spring context starts.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/Benchmark*.java \
  src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkIndexingServiceTest.java
git commit -m "feat(knowledge): add resumable benchmark indexing"
```

### Task 4: Add generic file loading, fingerprints, and atomic checkpoints

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/ValidatedBenchmarkDataset.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/BenchmarkDatasetFileLoader.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/JsonBenchmarkCheckpointStore.java`
- Test: `src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/BenchmarkDatasetFileLoaderTest.java`
- Test: `src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/JsonBenchmarkCheckpointStoreTest.java`

- [ ] **Step 1: Write failing adapter tests**

```java
@Test
void loadsValidatedDatasetAndHashesOriginalBytes() throws Exception {
    Files.writeString(gold, validGoldJsonl());
    Files.writeString(corpus, validCorpusJsonl());

    ValidatedBenchmarkDataset dataset = loader.load(gold, corpus);

    assertThat(dataset.cases()).hasSize(1);
    assertThat(dataset.chunks()).hasSize(1);
    assertThat(dataset.queryFingerprint()).matches("[a-f0-9]{64}");
    assertThat(dataset.corpusFingerprint()).isEqualTo(
            BenchmarkFingerprint.sha256(Files.readAllBytes(corpus)));
}

@Test
void atomicallyRoundTripsCheckpointWithoutCorpusContent() throws Exception {
    JsonBenchmarkCheckpointStore store = new JsonBenchmarkCheckpointStore(path, objectMapper);
    store.save(checkpoint);

    assertThat(store.load()).contains(checkpoint);
    assertThat(Files.readString(path)).doesNotContain("chunk body", "api-key");
    assertThat(tempSiblingFiles(path)).isEmpty();
}
```

Also prove invalid data writes neither checkpoint nor output and malformed checkpoint becomes `BENCHMARK_CHECKPOINT_INVALID`.

- [ ] **Step 2: Run tests and confirm RED**

```bash
mvn -q -Dtest=BenchmarkDatasetFileLoaderTest,JsonBenchmarkCheckpointStoreTest test
```

Expected: test compilation fails because the adapters do not exist.

- [ ] **Step 3: Implement adapters**

`BenchmarkDatasetFileLoader` reads raw bytes once, hashes them, parses UTF-8 through `GoldSetDataLoader`, then calls `GoldSetDatasetValidator` before returning the immutable record.

`JsonBenchmarkCheckpointStore.save` writes Jackson JSON to `<checkpoint>.tmp`, then uses:

```java
try {
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
} catch (AtomicMoveNotSupportedException ignored) {
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
}
```

Delete the temporary file in `finally` when it still exists. Map parse and I/O failures to stable `BenchmarkOperationException` codes without including file contents.

- [ ] **Step 4: Verify GREEN**

```bash
mvn -q -Dtest=BenchmarkDatasetFileLoaderTest,JsonBenchmarkCheckpointStoreTest test
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/ValidatedBenchmarkDataset.java \
  src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/BenchmarkDatasetFileLoader.java \
  src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/JsonBenchmarkCheckpointStore.java \
  src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/BenchmarkDatasetFileLoaderTest.java \
  src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/JsonBenchmarkCheckpointStoreTest.java
git commit -m "feat(knowledge): persist benchmark import progress"
```

### Task 5: Compare isolated vector retrieval with BM25 and render honest metadata

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/IsolatedVectorEvaluator.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/IsolatedVectorBenchmarkService.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkRunMetadata.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/IsolatedBenchmarkReport.java`
- Create: `src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/IsolatedBenchmarkMarkdownRenderer.java`
- Test: `src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/IsolatedVectorBenchmarkServiceTest.java`
- Test: `src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/IsolatedBenchmarkMarkdownRendererTest.java`

- [ ] **Step 1: Write failing evaluator and report tests**

```java
@Test
void comparesIsolatedVectorWithBm25UsingExactChunkIds() {
    FakeIndex index = new FakeIndex(Map.of("Redis?", List.of(
            new BenchmarkIndexMatch("c-vector", 0.91),
            new BenchmarkIndexMatch("duplicate", 0.80),
            new BenchmarkIndexMatch("duplicate", 0.70))));
    IsolatedVectorBenchmarkService service = service(index);

    IsolatedBenchmarkReport report = service.compare(identity, cases(), corpus(), metadata());

    assertThat(report.comparison().candidateLabel()).isEqualTo("isolated-vector/embedding-3");
    assertThat(report.comparison().baselineLabel()).isEqualTo("bm25");
    assertThat(report.comparison().cases().getFirst().candidateMetrics().mrr()).isEqualTo(1.0);
}

@Test
void rendersDisclaimerAndReproducibilityMetadata() {
    String markdown = renderer.render(report);
    assertThat(markdown)
            .contains("不代表完整生产混合检索链路")
            .contains("Dataset: `generic-rag`")
            .contains("Corpus SHA-256: `" + "a".repeat(64) + "`")
            .contains("Candidate: `isolated-vector/embedding-3`");
}
```

Also test that `COMPARE` refuses a missing/non-completed checkpoint and an index count mismatch before issuing any search.

- [ ] **Step 2: Run tests and confirm RED**

```bash
mvn -q -Dtest=IsolatedVectorBenchmarkServiceTest,IsolatedBenchmarkMarkdownRendererTest test
```

Expected: test compilation fails because the isolated evaluator/report types do not exist.

- [ ] **Step 3: Implement isolated evaluation and rendering**

`IsolatedVectorEvaluator` requests Top-20, removes blank/duplicate IDs while preserving first rank, builds `GoldSetRetrievalResult`, and delegates metrics to `GoldSetMetricsCalculator`.

`IsolatedVectorBenchmarkService.compare` validates `COMPLETED` checkpoint identity and `index.count`, then executes candidate and BM25 runs and calls:

```java
runComparator.compare(
        "isolated-vector/" + identity.embeddingModelId(),
        vectorRun,
        "bm25",
        bm25Run);
```

`IsolatedBenchmarkMarkdownRenderer` writes the mandatory disclaimer and metadata before appending `GoldSetComparisonMarkdownRenderer.render(comparison)`.

- [ ] **Step 4: Verify GREEN**

```bash
mvn -q -Dtest=IsolatedVectorBenchmarkServiceTest,IsolatedBenchmarkMarkdownRendererTest test
```

Expected: all selected tests pass; no model or Milvus bean is involved.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/Isolated*.java \
  src/main/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/BenchmarkRunMetadata.java \
  src/test/java/io/github/imzmq/interview/knowledge/application/evaluation/goldset/Isolated*.java
git commit -m "feat(knowledge): compare isolated vector retrieval with bm25"
```

### Task 6: Implement the lazy, isolated Milvus schema

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/milvus/MilvusBenchmarkSchema.java`
- Test: `src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/milvus/MilvusBenchmarkSchemaTest.java`

- [ ] **Step 1: Write failing schema tests**

Mock `MilvusServiceClient` and prove:

```java
@Test
void doesNotTouchMilvusUntilRequired() {
    new MilvusBenchmarkSchema(client, properties);
    verifyNoInteractions(client);
}

@Test
void createsExpectedGenericSchemaAndLoadsCollection() {
    when(client.hasCollection(any())).thenReturn(R.success(false));
    stubSuccessfulCreateIndexAndLoad(client);

    schema.requireReady();

    ArgumentCaptor<CreateCollectionParam> request = ArgumentCaptor.forClass(CreateCollectionParam.class);
    verify(client).createCollection(request.capture());
    assertThat(request.getValue().getCollectionName()).isEqualTo("study_agent_retrieval_benchmark");
    assertThat(fieldNames(request.getValue())).containsExactly(
            "benchmark_vector_id", "dataset_id", "dataset_version", "chunk_id",
            "content_hash", "embedding_model_id", "embedding");
}
```

Add tests that incompatible descriptions, dimensions, primary-key settings, or varchar lengths fail with `BENCHMARK_SCHEMA_INCOMPATIBLE` and never drop the collection.

- [ ] **Step 2: Run the focused test and confirm RED**

```bash
mvn -q -Dtest=MilvusBenchmarkSchemaTest test
```

Expected: test compilation fails because the schema component does not exist.

- [ ] **Step 3: Implement lazy schema initialization**

Follow the established Milvus request/error pattern from `ChatMemoryMilvusSchemaInitializer`, but do not use `@PostConstruct`. `requireReady()` performs synchronized initialize-once behavior. The collection description is:

```text
study-agent-retrieval-benchmark;schema=1;dimension=<configured dimension>
```

Create COSINE/HNSW index `idx_benchmark_embedding` with `{"M":16,"efConstruction":200}`. If an existing schema differs, throw `BenchmarkOperationException("BENCHMARK_SCHEMA_INCOMPATIBLE")`; never call `dropCollection`.

- [ ] **Step 4: Verify GREEN**

```bash
mvn -q -Dtest=MilvusBenchmarkSchemaTest test
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/milvus/MilvusBenchmarkSchema.java \
  src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/milvus/MilvusBenchmarkSchemaTest.java
git commit -m "feat(knowledge): isolate benchmark milvus schema"
```

### Task 7: Implement batch embedding and dataset-scoped Milvus operations

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/milvus/MilvusBenchmarkIndex.java`
- Test: `src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/milvus/MilvusBenchmarkIndexTest.java`
- Test: `src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/milvus/MilvusBenchmarkIndexIntegrationTest.java`

- [ ] **Step 1: Write failing adapter unit tests**

Mock `EmbeddingModel`, `MilvusServiceClient`, and schema. Assert that one batch embedding call yields one upsert, deterministic IDs are stable, search uses all three identity filters, and cleanup is scoped:

```java
verify(embeddingModel).embed(List.of("first", "second"));
assertThat(upsertFields.get("chunk_id")).isEqualTo(List.of("c1", "c2"));
assertThat(searchRequest.getExpr())
        .contains("dataset_id == \"generic-rag\"")
        .contains("dataset_version == \"" + version + "\"")
        .contains("embedding_model_id == \"embedding-3\"");
assertThat(deleteRequest.getExpr()).isEqualTo("dataset_id == \"generic-rag\"");
```

Add tests for embedding result count mismatch, wrong dimensions, blank search query, invalid Top-K, null SDK response, timeout mapping, and response rows without chunk IDs. Verify exception messages never contain chunk content.

- [ ] **Step 2: Run the unit test and confirm RED**

```bash
mvn -q -Dtest=MilvusBenchmarkIndexTest test
```

Expected: test compilation fails because the adapter does not exist.

- [ ] **Step 3: Implement the adapter**

`upsertBatch` calls `schema.requireReady()`, invokes `embeddingModel.embed(contents)`, validates count and every vector dimension, then sends one `UpsertParam`. Generate the primary key with:

```java
BenchmarkFingerprint.sha256((identity.datasetId() + "\n"
        + identity.datasetVersion() + "\n"
        + identity.embeddingModelId() + "\n"
        + chunk.chunkId()).getBytes(StandardCharsets.UTF_8));
```

`search` embeds the query, performs COSINE search with `ef=64`, Top-K ≤ 200, identity filter, and `chunk_id` output. `count` uses an aggregate query scoped by all identity fields. `deleteDataset` rejects blank IDs and issues only an equality expression.

- [ ] **Step 4: Verify GREEN**

```bash
mvn -q -Dtest=MilvusBenchmarkIndexTest test
```

Expected: all selected tests pass and the embedding model is a mock.

- [ ] **Step 5: Add an opt-in integration test without an external API**

Use `@EnabledIfEnvironmentVariable(named = "RUN_MILVUS_INTEGRATION_TESTS", matches = "true")`, the local Milvus client, and a deterministic fake `EmbeddingModel` that returns configured-dimension vectors. Cover upsert, count, filtered search, and dataset-scoped delete. The default Maven run must skip this test.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/milvus/MilvusBenchmarkIndex.java \
  src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/milvus/MilvusBenchmarkIndexTest.java \
  src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/milvus/MilvusBenchmarkIndexIntegrationTest.java
git commit -m "feat(knowledge): add isolated milvus benchmark index"
```

### Task 8: Replace the external-file runner with explicit safe operations

**Files:**
- Create: `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/AtomicBenchmarkReportWriter.java`
- Rename/Modify: `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/GoldSetBenchmarkFileService.java` → `RetrievalBenchmarkFileService.java`
- Rename/Modify: `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/GoldSetBenchmarkRunner.java` → `RetrievalBenchmarkRunner.java`
- Rename/Modify: `src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation/GoldSetBenchmarkFileResult.java` → `RetrievalBenchmarkFileResult.java`
- Rename/Modify: corresponding three test classes.

- [ ] **Step 1: Write failing operation-isolation tests**

Use mocks/fakes to prove:

```java
@Test
void validateNeverTouchesIndexCheckpointOrEmbeddingBackedService() {
    service.execute(BenchmarkOperation.VALIDATE);
    verifyNoInteractions(index, indexingService, comparisonService, checkpointStore);
}

@Test
void cleanRequiresExactDatasetConfirmation() {
    properties.setDatasetId("generic-rag");
    properties.setCleanupConfirmation("wrong");
    assertThatThrownBy(() -> service.execute(BenchmarkOperation.CLEAN))
            .hasMessage("BENCHMARK_CLEAN_CONFIRMATION_MISMATCH");
    verify(index, never()).deleteDataset(anyString());
}

@Test
void failedComparisonDoesNotOverwriteExistingReport() {
    Files.writeString(output, "known-good");
    when(comparisonService.compare(any(), any(), any(), any()))
            .thenThrow(new BenchmarkOperationException("BENCHMARK_INDEX_INCOMPLETE"));
    assertThatThrownBy(() -> service.execute(BenchmarkOperation.COMPARE));
    assertThat(Files.readString(output)).isEqualTo("known-good");
}
```

Condition tests must prove the runner is absent by default and present only when `enabled=true`. Runner tests must prove it dispatches exactly the configured operation.

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
mvn -q -Dtest=RetrievalBenchmarkFileServiceTest,RetrievalBenchmarkRunnerTest,RetrievalBenchmarkRunnerConditionTest test
```

Expected: test compilation fails because the renamed explicit-operation entry does not exist.

- [ ] **Step 3: Implement the operation dispatcher and atomic report writer**

`RetrievalBenchmarkFileService.execute` always loads and validates the required files before side effects, except `CLEAN`, which requires only validated dataset ID and exact confirmation. Dispatch:

```java
return switch (operation) {
    case VALIDATE -> validationResult(dataset);
    case INDEX -> index(dataset);
    case COMPARE -> compareAndWrite(dataset);
    case CLEAN -> cleanConfirmedDataset();
};
```

`INDEX` constructs identity from corpus fingerprint and configured model fields. `COMPARE` loads checkpoint through the port, builds `BenchmarkRunMetadata`, renders the isolated report, then uses `AtomicBenchmarkReportWriter`. It must never call `GoldSetBenchmarkComparisonService` or `RAGService`.

- [ ] **Step 4: Verify GREEN**

```bash
mvn -q -Dtest=RetrievalBenchmarkFileServiceTest,RetrievalBenchmarkRunnerTest,RetrievalBenchmarkRunnerConditionTest test
```

Expected: all selected tests pass.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation \
  src/test/java/io/github/imzmq/interview/knowledge/infrastructure/evaluation
git commit -m "feat(knowledge): add explicit retrieval benchmark operations"
```

### Task 9: Add architecture gates, operator documentation, and full verification

**Files:**
- Modify: `src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java`
- Modify: `src/main/resources/eval/README.md`
- Modify: `docs/development/verification.md`
- Modify: `PACKAGE_CONVENTIONS.md`

- [ ] **Step 1: Write the failing ArchUnit rule**

```java
@ArchTest
static final ArchRule benchmark_application_should_not_depend_on_infrastructure_sdks =
        noClasses().that().resideInAnyPackage(
                "..knowledge.application.evaluation.goldset..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.milvus..",
                        "org.springframework.ai.embedding..",
                        "..knowledge.infrastructure.evaluation..");
```

Temporarily add a test fixture violating the rule, assert that the evaluation reports it, observe RED, then remove the fixture and retain the rule.

- [ ] **Step 2: Run architecture tests**

```bash
mvn -q -Dtest=ArchitectureRulesTest test
```

Expected after removing the fixture: all architecture tests pass.

- [ ] **Step 3: Document exact operations and the key location**

Document these commands with absolute paths supplied by the operator:

```bash
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_ENABLED=true \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_OPERATION=VALIDATE \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_DATASET_ID=generic-rag \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_GOLD_FILE=/absolute/path/gold.jsonl \
APP_KNOWLEDGE_RETRIEVAL_BENCHMARK_CORPUS_FILE=/absolute/path/corpus.jsonl \
mvn spring-boot:run
```

State explicitly:

- `application-private.yml` is the only local location for the Zhipu embedding API key;
- `VALIDATE` and BM25 do not need an API key;
- `INDEX` can incur external API calls and requires separate user approval;
- `COMPARE` embeds the query set and therefore can also call the provider;
- `CLEAN` requires `cleanup-confirmation == dataset-id`;
- the result is isolated-vector vs BM25, not full production hybrid retrieval.

Update `PACKAGE_CONVENTIONS.md` with the evaluation application/infrastructure ownership and the ban on dataset-specific adapters.

- [ ] **Step 4: Run the focused and static verification suite**

```bash
mvn -q -Dtest='GoldSet*Test,Benchmark*Test,Isolated*Test,MilvusBenchmark*Test,RetrievalBenchmark*Test,ArchitectureRulesTest' test
mvn -q compile
mvn -q -DskipTests test-compile
mvn -q verify -DskipTests
```

Expected: exit code 0 for every command. The opt-in Milvus integration test reports skipped unless its environment variable is set.

- [ ] **Step 5: Run the full unit/integration suite with the required Byte Buddy agent**

```bash
mvn -q \
  -DargLine='-javaagent:/Users/bytedance/.m2/repository/net/bytebuddy/byte-buddy-agent/1.14.19/byte-buddy-agent-1.14.19.jar -XX:+EnableDynamicAgentLoading' \
  test
```

Expected: exit code 0. Record the exact test/skip counts from Surefire; do not claim a count before this run.

- [ ] **Step 6: Verify no secret or user-owned file is staged**

```bash
git diff --check
git status --short
git diff --cached --name-only
```

Expected: no `application-private.yml`, `AGENTS.md`, `.DS_Store`, `target/`, or logs are staged.

- [ ] **Step 7: Commit documentation and gates**

```bash
git add src/test/java/io/github/imzmq/interview/architecture/ArchitectureRulesTest.java \
  src/main/resources/eval/README.md docs/development/verification.md PACKAGE_CONVENTIONS.md
git commit -m "docs(knowledge): document isolated retrieval benchmarks"
```

- [ ] **Step 8: Push verified commits**

```bash
git push origin main
```

Expected: `origin/main` advances to the local verified HEAD. Do not run `INDEX`; stop and report that real corpus indexing awaits explicit user approval.

---

## Plan self-review

- **Spec coverage:** Tasks 1–2 cover the generic contract and identity; Tasks 3–4 cover idempotent resumable indexing and atomic checkpoint files; Task 5 covers honest isolated-vector vs BM25 reporting; Tasks 6–7 cover the isolated Milvus schema and adapter; Task 8 covers operation safety and atomic report output; Task 9 covers architecture, docs, verification, and the external-call gate.
- **Scope:** The plan does not add dataset-specific adapters, normal knowledge ingestion, GraphRAG, parent-child hydration, UI/API, parameter tuning, or a plugin framework.
- **Type consistency:** `BenchmarkDatasetIdentity`, `BenchmarkIndex`, `BenchmarkCheckpointStore`, `BenchmarkIndexingService`, `IsolatedVectorBenchmarkService`, and `RetrievalBenchmarkFileService` retain the same names and responsibilities across tasks.
- **Safety:** No task executes a real corpus import. All embedding behavior before explicit approval uses mocks or a deterministic local fake.
