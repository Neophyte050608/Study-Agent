package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.NoopAiObservationPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OtelObservationConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OtelObservationConfig.class));

    @Test
    void registersNoopPublisherWhenExternalObservationIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "app.observability.external.enabled=false",
                        "app.observability.external.endpoint=http://localhost:4318/v1/traces",
                        "app.observability.external.public-key=public-key",
                        "app.observability.external.secret-key=secret-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiObservationPublisher.class);
                    assertThat(context).getBean(AiObservationPublisher.class)
                            .isInstanceOf(NoopAiObservationPublisher.class);
                });
    }

    @Test
    void registersNoopPublisherWhenExternalObservationIsEnabledButKeysAreMissing() {
        contextRunner
                .withPropertyValues(
                        "app.observability.external.enabled=true",
                        "app.observability.external.endpoint=http://localhost:4318/v1/traces")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiObservationPublisher.class);
                    assertThat(context).getBean(AiObservationPublisher.class)
                            .isInstanceOf(NoopAiObservationPublisher.class);
                });
    }

    @Test
    void registersOtelPublisherWhenExternalObservationConfigurationIsComplete() {
        contextRunner
                .withPropertyValues(
                        "app.observability.external.enabled=true",
                        "app.observability.external.endpoint=http://localhost:4318/v1/traces",
                        "app.observability.external.public-key=public-key",
                        "app.observability.external.secret-key=secret-key",
                        "app.observability.external.service-name=study-agent-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiObservationPublisher.class);
                    assertThat(context).getBean(AiObservationPublisher.class)
                            .isInstanceOf(OtelAiObservationPublisher.class);
                });
    }
}
