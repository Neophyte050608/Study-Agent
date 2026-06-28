package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.config.observability.AiObservationPublisherConfig;
import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.NoopAiObservationPublisher;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void contextCloseCallsOtelPublisherCloseWhenConfigured() {
        AtomicInteger closeCount = new AtomicInteger();
        ApplicationContextRunner closingContextRunner = new ApplicationContextRunner()
                .withUserConfiguration(RecordingOtelObservationConfig.class)
                .withBean(AtomicInteger.class, () -> closeCount);

        closingContextRunner
                .withPropertyValues(
                        "app.observability.external.enabled=true",
                        "app.observability.external.endpoint=http://localhost:4318/v1/traces",
                        "app.observability.external.public-key=public-key",
                        "app.observability.external.secret-key=secret-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiObservationPublisher.class);
                    assertThat(context).getBean(AiObservationPublisher.class)
                            .isInstanceOf(RecordingCloseablePublisher.class);
                    assertThat(closeCount).hasValue(0);
                });

        assertThat(closeCount).hasValue(1);
    }

    @Test
    void registersOtelAutoConfigurationBeforeNoopFallbackAutoConfiguration() {
        List<String> imports = autoConfigurationImports();

        assertThat(imports).contains(OtelObservationConfig.class.getName());
        assertThat(imports).contains(AiObservationPublisherConfig.class.getName());
        assertThat(imports.indexOf(OtelObservationConfig.class.getName()))
                .isLessThan(imports.indexOf(AiObservationPublisherConfig.class.getName()));
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingOtelObservationConfig extends OtelObservationConfig {

        private final AtomicInteger closeCount;

        RecordingOtelObservationConfig(AtomicInteger closeCount) {
            this.closeCount = closeCount;
        }

        @Override
        protected OtelAiObservationPublisher createPublisher(
                Tracer tracer,
                OtelObservationMapper mapper,
                SdkTracerProvider tracerProvider) {
            return new RecordingCloseablePublisher(closeCount, tracerProvider);
        }
    }

    static class RecordingCloseablePublisher extends OtelAiObservationPublisher {

        private final AtomicInteger closeCount;

        RecordingCloseablePublisher(AtomicInteger closeCount, SdkTracerProvider tracerProvider) {
            super(null, null, tracerProvider);
            this.closeCount = closeCount;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            super.close();
        }
    }

    private List<String> autoConfigurationImports() {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(input).as("AutoConfiguration.imports resource").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
