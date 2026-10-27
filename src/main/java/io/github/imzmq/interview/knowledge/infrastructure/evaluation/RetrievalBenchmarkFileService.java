package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkCheckpointStore;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkDatasetIdentity;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndex;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndexCommand;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndexingResult;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndexingService;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperation;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkOperationException;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkRunMetadata;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetBm25Evaluator;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetComparisonMarkdownRenderer;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetMetricsCalculator;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetRunComparator;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.IsolatedBenchmarkMarkdownRenderer;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.IsolatedBenchmarkReport;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.IsolatedVectorBenchmarkService;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.IsolatedVectorEvaluator;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import io.github.imzmq.interview.knowledge.infrastructure.evaluation.milvus.MilvusBenchmarkSchema;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/** Dispatches exactly one validated, dataset-neutral benchmark operation. */
public final class RetrievalBenchmarkFileService {

    private static final int VECTOR_TOP_K = 20;

    private final RetrievalBenchmarkProperties properties;
    private final BenchmarkDatasetFileLoader datasetLoader;
    private final Supplier<BenchmarkIndex> indexSupplier;
    private final ObjectMapper objectMapper;
    private final RetrievalTokenizerService tokenizer;
    private final GoldSetMetricsCalculator metricsCalculator;
    private final GoldSetRunComparator runComparator;
    private final GoldSetComparisonMarkdownRenderer comparisonRenderer;
    private final AtomicBenchmarkReportWriter reportWriter;

    public RetrievalBenchmarkFileService(
            RetrievalBenchmarkProperties properties,
            BenchmarkDatasetFileLoader datasetLoader,
            BenchmarkIndex index,
            ObjectMapper objectMapper,
            RetrievalTokenizerService tokenizer,
            GoldSetMetricsCalculator metricsCalculator,
            GoldSetRunComparator runComparator,
            GoldSetComparisonMarkdownRenderer comparisonRenderer,
            AtomicBenchmarkReportWriter reportWriter
    ) {
        this(
                properties,
                datasetLoader,
                () -> Objects.requireNonNull(index, "index"),
                objectMapper,
                tokenizer,
                metricsCalculator,
                runComparator,
                comparisonRenderer,
                reportWriter);
    }

    public RetrievalBenchmarkFileService(
            RetrievalBenchmarkProperties properties,
            BenchmarkDatasetFileLoader datasetLoader,
            Supplier<BenchmarkIndex> indexSupplier,
            ObjectMapper objectMapper,
            RetrievalTokenizerService tokenizer,
            GoldSetMetricsCalculator metricsCalculator,
            GoldSetRunComparator runComparator,
            GoldSetComparisonMarkdownRenderer comparisonRenderer,
            AtomicBenchmarkReportWriter reportWriter
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.datasetLoader = Objects.requireNonNull(datasetLoader, "datasetLoader");
        this.indexSupplier = Objects.requireNonNull(indexSupplier, "indexSupplier");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.metricsCalculator = Objects.requireNonNull(metricsCalculator, "metricsCalculator");
        this.runComparator = Objects.requireNonNull(runComparator, "runComparator");
        this.comparisonRenderer = Objects.requireNonNull(comparisonRenderer, "comparisonRenderer");
        this.reportWriter = Objects.requireNonNull(reportWriter, "reportWriter");
    }

    public RetrievalBenchmarkFileResult execute(BenchmarkOperation operation) {
        BenchmarkOperation selected = Objects.requireNonNull(operation, "operation");
        if (selected == BenchmarkOperation.CLEAN) {
            return cleanConfirmedDataset();
        }
        ValidatedBenchmarkDataset dataset = datasetLoader.load(
                properties.requireGoldPath(),
                properties.requireCorpusPath());
        return switch (selected) {
            case VALIDATE -> validationResult(dataset);
            case INDEX -> index(dataset);
            case COMPARE -> compareAndWrite(dataset);
            case CLEAN -> throw new IllegalStateException("unreachable clean dispatch");
        };
    }

    private RetrievalBenchmarkFileResult validationResult(ValidatedBenchmarkDataset dataset) {
        return result(BenchmarkOperation.VALIDATE, dataset, null, null, null);
    }

    private RetrievalBenchmarkFileResult index(ValidatedBenchmarkDataset dataset) {
        BenchmarkDatasetIdentity identity = identity(dataset);
        BenchmarkIndex index = requireIndex();
        BenchmarkIndexingResult indexingResult = new BenchmarkIndexingService(index, checkpointStore())
                .index(new BenchmarkIndexCommand(
                        identity,
                        dataset.corpusFingerprint(),
                        dataset.chunks(),
                        properties.getBatchSize()));
        return result(BenchmarkOperation.INDEX, dataset, null, indexingResult, null);
    }

    private RetrievalBenchmarkFileResult compareAndWrite(ValidatedBenchmarkDataset dataset) {
        BenchmarkDatasetIdentity identity = identity(dataset);
        BenchmarkIndex index = requireIndex();
        IsolatedVectorBenchmarkService comparisonService = new IsolatedVectorBenchmarkService(
                index,
                checkpointStore(),
                new IsolatedVectorEvaluator(index, metricsCalculator),
                new GoldSetBm25Evaluator(tokenizer, metricsCalculator),
                runComparator);
        IsolatedBenchmarkReport report = comparisonService.compare(
                identity,
                dataset.cases(),
                dataset.chunks(),
                new BenchmarkRunMetadata(
                        dataset.queryFingerprint(),
                        properties.getCollectionName(),
                        MilvusBenchmarkSchema.SCHEMA_VERSION,
                        VECTOR_TOP_K,
                        dataset.cases().size(),
                        dataset.chunks().size()));
        String markdown = new IsolatedBenchmarkMarkdownRenderer(comparisonRenderer).render(report);
        Path output = reportWriter.write(properties.requireOutputPath(), markdown);
        return result(BenchmarkOperation.COMPARE, dataset, output, null, report);
    }

    private RetrievalBenchmarkFileResult cleanConfirmedDataset() {
        String datasetId = properties.getDatasetId();
        validateDatasetId(datasetId);
        if (!datasetId.equals(properties.getCleanupConfirmation())) {
            throw new BenchmarkOperationException("BENCHMARK_CLEAN_CONFIRMATION_MISMATCH");
        }
        requireIndex().deleteDataset(datasetId);
        return new RetrievalBenchmarkFileResult(
                BenchmarkOperation.CLEAN, 0, 0, null, null, null);
    }

    private BenchmarkIndex requireIndex() {
        BenchmarkIndex index = indexSupplier.get();
        if (index == null) {
            throw new BenchmarkOperationException("BENCHMARK_INDEX_UNAVAILABLE");
        }
        return index;
    }

    private BenchmarkCheckpointStore checkpointStore() {
        return new JsonBenchmarkCheckpointStore(properties.requireCheckpointPath(), objectMapper);
    }

    private BenchmarkDatasetIdentity identity(ValidatedBenchmarkDataset dataset) {
        return new BenchmarkDatasetIdentity(
                properties.getDatasetId(),
                dataset.corpusFingerprint(),
                properties.getEmbeddingModelId(),
                properties.getEmbeddingDimension());
    }

    private void validateDatasetId(String datasetId) {
        try {
            new BenchmarkDatasetIdentity(
                    datasetId,
                    "0".repeat(64),
                    "cleanup-validation",
                    1);
        } catch (IllegalArgumentException exception) {
            throw new BenchmarkOperationException("BENCHMARK_DATASET_ID_INVALID", exception);
        }
    }

    private RetrievalBenchmarkFileResult result(
            BenchmarkOperation operation,
            ValidatedBenchmarkDataset dataset,
            Path output,
            BenchmarkIndexingResult indexingResult,
            IsolatedBenchmarkReport report
    ) {
        return new RetrievalBenchmarkFileResult(
                operation,
                dataset.cases().size(),
                dataset.chunks().size(),
                output,
                indexingResult,
                report);
    }
}
