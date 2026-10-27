package io.github.imzmq.interview.knowledge.infrastructure.evaluation;

import io.github.imzmq.interview.config.knowledge.GoldSetBenchmarkProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GoldSetBenchmarkRunnerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GoldSetBenchmarkProperties.class, GoldSetBenchmarkRunner.class)
            .withBean(
                    GoldSetBenchmarkFileService.class,
                    () -> new GoldSetBenchmarkFileService(null, null)
            );

    @Test
    void runnerIsAbsentByDefault() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(GoldSetBenchmarkRunner.class));
    }

    @Test
    void runnerIsCreatedOnlyWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("app.knowledge.retrieval-benchmark.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(GoldSetBenchmarkRunner.class));
    }
}
