package io.github.imzmq.interview.config.observability;

import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.NoopAiObservationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AiObservationPublisherConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiObservationPublisherConfig.class);

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
                .withUserConfiguration(CustomPublisherConfig.class, AiObservationPublisherConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiObservationPublisher.class);
                    assertThat(context).doesNotHaveBean(NoopAiObservationPublisher.class);
                    assertThat(context).getBean(AiObservationPublisher.class).isInstanceOf(CustomAiObservationPublisher.class);
                });
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
