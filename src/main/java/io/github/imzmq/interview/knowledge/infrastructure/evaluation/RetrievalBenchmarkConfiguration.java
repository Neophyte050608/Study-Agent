package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.BenchmarkIndex;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetComparisonMarkdownRenderer;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetMetricsCalculator;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetRunComparator;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import io.github.imzmq.interview.knowledge.infrastructure.evaluation.milvus.MilvusBenchmarkIndex;
import io.github.imzmq.interview.knowledge.infrastructure.evaluation.milvus.MilvusBenchmarkSchema;
import io.milvus.client.MilvusServiceClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/** Runtime wiring loaded only when an operator explicitly enables retrieval benchmarks. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "app.knowledge.retrieval-benchmark.enabled",
        havingValue = "true"
)
public class RetrievalBenchmarkConfiguration {

    @Bean
    @Lazy
    BenchmarkIndex retrievalBenchmarkIndex(
            MilvusServiceClient client,
            EmbeddingModel embeddingModel,
            RetrievalBenchmarkProperties properties
    ) {
        MilvusBenchmarkSchema schema = new MilvusBenchmarkSchema(client, properties);
        return new MilvusBenchmarkIndex(client, embeddingModel, schema, properties);
    }

    @Bean
    RetrievalBenchmarkFileService retrievalBenchmarkFileService(
            RetrievalBenchmarkProperties properties,
            ObjectProvider<BenchmarkIndex> indexProvider,
            ObjectMapper objectMapper,
            RetrievalTokenizerService tokenizer,
            GoldSetMetricsCalculator metricsCalculator,
            GoldSetRunComparator runComparator
    ) {
        return new RetrievalBenchmarkFileService(
                properties,
                new BenchmarkDatasetFileLoader(objectMapper),
                indexProvider::getObject,
                objectMapper,
                tokenizer,
                metricsCalculator,
                runComparator,
                new GoldSetComparisonMarkdownRenderer(),
                new AtomicBenchmarkReportWriter());
    }
}
