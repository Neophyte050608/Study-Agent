package io.github.imzmq.interview.observability.infrastructure.otel;

import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class OtelAiObservationPublisher implements AiObservationPublisher {

    private static final Logger log = LoggerFactory.getLogger(OtelAiObservationPublisher.class);

    private final Tracer tracer;
    private final OtelObservationMapper mapper;

    public OtelAiObservationPublisher(Tracer tracer, OtelObservationMapper mapper) {
        this.tracer = tracer;
        this.mapper = mapper;
    }

    @Override
    public void publish(AiObservationEvent event) {
        if (event == null || tracer == null || mapper == null) {
            return;
        }

        try {
            Span span = tracer.spanBuilder(mapper.spanName(event)).startSpan();
            try {
                Map<String, String> attributes = mapper.toAttributes(event);
                if (attributes != null) {
                    for (Map.Entry<String, String> entry : attributes.entrySet()) {
                        span.setAttribute(entry.getKey(), entry.getValue());
                    }
                }
                StatusCode statusCode = statusCode(event);
                if (statusCode != null) {
                    span.setStatus(statusCode);
                }
            } finally {
                span.end();
            }
        } catch (RuntimeException ex) {
            log.debug("Failed to publish AI observation to OpenTelemetry");
        }
    }

    private static StatusCode statusCode(AiObservationEvent event) {
        String status = event.status();
        if ("success".equalsIgnoreCase(status)) {
            return StatusCode.OK;
        }
        if ("failed".equalsIgnoreCase(status)
                || "error".equalsIgnoreCase(status)
                || "timeout".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)) {
            return StatusCode.ERROR;
        }
        return null;
    }
}
