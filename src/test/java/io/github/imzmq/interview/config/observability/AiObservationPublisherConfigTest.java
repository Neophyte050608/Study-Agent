package io.github.imzmq.interview.config.observability;

import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.NoopAiObservationPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AiObservationPublisherConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiObservationPublisherConfig.class));

    @Test
    void registersNoopPublisherWhenNoCustomPublisherExists() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiObservationPublisher.class);
            assertThat(context).hasSingleBean(NoopAiObservationPublisher.class);
            assertThat(context).getBean(AiObservationPublisher.class).isInstanceOf(NoopAiObservationPublisher.class);
        });
    }

    @Test
    void backsOffWhenCustomPublisherExists() {
        new ApplicationContextRunner()
                .withUserConfiguration(CustomPublisherConfig.class)
                .withConfiguration(AutoConfigurations.of(AiObservationPublisherConfig.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiObservationPublisher.class);
                    assertThat(context).doesNotHaveBean(NoopAiObservationPublisher.class);
                    assertThat(context).getBean(AiObservationPublisher.class).isInstanceOf(CustomAiObservationPublisher.class);
                });
    }

    @Test
    void backsOffWhenAutoConfigurationIsDeclaredBeforeUserConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AiObservationPublisherConfig.class))
                .withUserConfiguration(CustomPublisherConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiObservationPublisher.class);
                    assertThat(context).doesNotHaveBean(NoopAiObservationPublisher.class);
                    assertThat(context).getBean(AiObservationPublisher.class).isInstanceOf(CustomAiObservationPublisher.class);
                });
    }

    @Test
    void isRegisteredAsSpringBootAutoConfiguration() {
        assertThat(AiObservationPublisherConfig.class).hasAnnotation(AutoConfiguration.class);
    }

    @Configuration
    static class CustomPublisherConfig {

        @Bean
        AiObservationPublisher customAiObservationPublisher() {
            return new CustomAiObservationPublisher();
        }
    }

    static class CustomAiObservationPublisher implements AiObservationPublisher {

        @Override
        public void publish(AiObservationEvent event) {
            // Test publisher intentionally does nothing.
        }
    }
}
