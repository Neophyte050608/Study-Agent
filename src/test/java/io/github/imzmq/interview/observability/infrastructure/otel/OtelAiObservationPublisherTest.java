package io.github.imzmq.interview.observability.infrastructure.otel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(OutputCaptureExtension.class)
class OtelAiObservationPublisherTest {

    @Test
    void publishIgnoresNullEvent() {
        OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(noopTracer(), new OtelObservationMapper());

        assertThatCode(() -> publisher.publish(null)).doesNotThrowAnyException();
    }

    @Test
    void publishDoesNotThrowForSuccessAndFailedEvents() {
        OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(noopTracer(), new OtelObservationMapper());

        assertThatCode(() -> publisher.publish(eventWithStatus("success"))).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(eventWithStatus("failed"))).doesNotThrowAnyException();
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
    void publishSwallowsMapperRuntimeExceptionWithoutLoggingSensitiveData(CapturedOutput output) {
        Logger logger = (Logger) LoggerFactory.getLogger(OtelAiObservationPublisher.class);
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            OtelAiObservationPublisher publisher = new OtelAiObservationPublisher(noopTracer(), new ThrowingMapper());

            assertThatCode(() -> publisher.publish(eventWithStatus("failed"))).doesNotThrowAnyException();

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

    private static final class ThrowingMapper extends OtelObservationMapper {

        @Override
        public String spanName(AiObservationEvent event) {
            throw new RuntimeException("raw-secret-error-message");
        }
    }
}
