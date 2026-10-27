package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.imzmq.interview.config.knowledge.RetrievalBenchmarkProperties;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetMetricsCalculator;
import io.github.imzmq.interview.knowledge.application.evaluation.goldset.GoldSetRunComparator;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RetrievalBenchmarkRunnerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RetrievalBenchmarkProperties.class, RetrievalBenchmarkRunner.class)
            .withBean(RetrievalBenchmarkFileService.class,
                    () -> mock(RetrievalBenchmarkFileService.class));

    @Test
    void validateConfigurationDoesNotRequireMilvusOrEmbeddingModel() {
        GoldSetMetricsCalculator metrics = new GoldSetMetricsCalculator();
        new ApplicationContextRunner()
                .withUserConfiguration(
                        RetrievalBenchmarkProperties.class,
                        RetrievalBenchmarkConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(RetrievalTokenizerService.class, RetrievalTokenizerService::new)
                .withBean(GoldSetMetricsCalculator.class, () -> metrics)
                .withBean(GoldSetRunComparator.class, () -> new GoldSetRunComparator(metrics))
                .withPropertyValues(
                        "app.knowledge.retrieval-benchmark.enabled=true",
                        "app.knowledge.retrieval-benchmark.operation=VALIDATE")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(RetrievalBenchmarkFileService.class));
    }

    @Test
    void runnerIsAbsentByDefault() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(RetrievalBenchmarkRunner.class));
    }

    @Test
    void runnerIsCreatedOnlyWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("app.knowledge.retrieval-benchmark.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(RetrievalBenchmarkRunner.class));
    }
}
