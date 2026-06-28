package io.github.imzmq.interview.observability.infrastructure.otel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(OutputCaptureExtension.class)
class OtelAiObservationPublisherTest {

    private final List<SdkTracerProvider> tracerProviders = new ArrayList<>();

    @AfterEach
    void shutdownTracerProviders() {
        for (SdkTracerProvider tracerProvider : tracerProviders) {
            tracerProvider.shutdown();
        }
    }

    @Test
    void publishIgnoresNullEvent() {
        OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(noopTracer(), new OtelObservationMapper());

        assertThatCode(() -> publisher.publish(null)).doesNotThrowAnyException();
    }

    @Test
    void publishExportsEndedSpanForSuccessWithSanitizedAttributes() {
        CapturingSpanExporter exporter = new CapturingSpanExporter();
        OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(testTracer(exporter), new OtelObservationMapper());

        publisher.publish(eventWithStatus("success"));

        assertThat(exporter.exportedSpans()).hasSize(1);
        SpanData span = exporter.exportedSpans().get(0);
        assertThat(span.getName()).isEqualTo("ai.rag_node.retrieval");
        assertThat(span.hasEnded()).isTrue();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("ai.model"))).isEqualTo("glm-4");
        assertThat(span.getAttributes().asMap().keySet())
                .extracting(AttributeKey::getKey)
                .contains("ai.model")
                .doesNotContain("prompt", "apiKey");
        assertThat(span.getAttributes().asMap().values())
                .doesNotContain("sensitive prompt", "secret-token");
    }

    @Test
    void publishMarksFailedErrorTimeoutAndCancelledAsErrorStatus() {
        assertStatusCode("failed", StatusCode.ERROR);
        assertStatusCode("error", StatusCode.ERROR);
        assertStatusCode("timeout", StatusCode.ERROR);
        assertStatusCode("cancelled", StatusCode.ERROR);
    }

    @Test
    void publishLeavesSkippedBlankAndUnknownStatusUnset() {
        assertStatusCode("skipped", StatusCode.UNSET);
        assertStatusCode(" ", StatusCode.UNSET);
        assertStatusCode("unknown", StatusCode.UNSET);
    }

    @Test
    void publishReturnsWhenDependenciesAreMissing() {
        assertThatCode(() -> new OtelAiObservationPublisher(null, new OtelObservationMapper())
                .publish(eventWithStatus("success")))
                .doesNotThrowAnyException();
        assertThatCode(() -> new OtelAiObservationPublisher(noopTracer(), null)
                .publish(eventWithStatus("success")))
                .doesNotThrowAnyException();
    }

    @Test
    void publishSwallowsMapperRuntimeExceptionAndEndsSpanWithoutLoggingSensitiveData(CapturedOutput output) {
        Logger logger = (Logger) LoggerFactory.getLogger(OtelAiObservationPublisher.class);
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        CapturingSpanExporter exporter = new CapturingSpanExporter();
        try {
            OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(testTracer(exporter), new ThrowingAttributesMapper());

            assertThatCode(() -> publisher.publish(eventWithStatus("failed"))).doesNotThrowAnyException();

            assertThat(exporter.exportedSpans()).hasSize(1);
            assertThat(exporter.exportedSpans().get(0).hasEnded()).isTrue();
            assertThat(output).doesNotContain(
                    "raw-secret-error-message",
                    "prompt",
                    "secret-token",
                    "sensitive prompt"
            );
        } finally {
            logger.setLevel(originalLevel);
        }
    }

    @Test
    void publishHandlesNullAttributesAndEndsSpan() {
        CapturingSpanExporter exporter = new CapturingSpanExporter();
        OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(testTracer(exporter), new NullAttributesMapper());

        assertThatCode(() -> publisher.publish(eventWithStatus("success"))).doesNotThrowAnyException();

        assertThat(exporter.exportedSpans()).hasSize(1);
        SpanData span = exporter.exportedSpans().get(0);
        assertThat(span.hasEnded()).isTrue();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    private void assertStatusCode(String status, StatusCode expectedStatusCode) {
        CapturingSpanExporter exporter = new CapturingSpanExporter();
        OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(testTracer(exporter), new OtelObservationMapper());

        publisher.publish(eventWithStatus(status));

        assertThat(exporter.exportedSpans()).hasSize(1);
        SpanData span = exporter.exportedSpans().get(0);
        assertThat(span.hasEnded()).isTrue();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(expectedStatusCode);
    }

    private Tracer testTracer(SpanExporter exporter) {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        tracerProviders.add(provider);
        return OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .build()
                .getTracer("test");
    }

    private static Tracer noopTracer() {
        return OpenTelemetry.noop().getTracer("test");
    }

    private static AiObservationEvent eventWithStatus(String status) {
        return AiObservationEvent.ragNode(
                "trace-1",
                "node-1",
                "retrieval",
                "safe internal node name",
                status,
                Map.of("model", "glm-4", "prompt", "sensitive prompt", "apiKey", "secret-token")
        );
    }

    private static final class ThrowingAttributesMapper extends OtelObservationMapper {

        @Override
        public Map<String, String> toAttributes(AiObservationEvent event) {
            throw new RuntimeException("raw-secret-error-message");
        }
    }

    private static final class NullAttributesMapper extends OtelObservationMapper {

        @Override
        public Map<String, String> toAttributes(AiObservationEvent event) {
            return null;
        }
    }

    private static final class CapturingSpanExporter implements SpanExporter {

        private final List<SpanData> exportedSpans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exportedSpans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        List<SpanData> exportedSpans() {
            return exportedSpans;
        }
    }
}
