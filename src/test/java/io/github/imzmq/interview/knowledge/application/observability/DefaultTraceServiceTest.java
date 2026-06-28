package io.github.imzmq.interview.knowledge.application.observability;

import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.RecordingAiObservationPublisher;
import io.github.imzmq.interview.observability.application.TraceAttributeSanitizer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class DefaultTraceServiceTest {

    @Test
    void successPublishesSanitizedRagNodeEvent() {
        RecordingAiObservationPublisher publisher = new RecordingAiObservationPublisher();
        DefaultTraceService traceService = new DefaultTraceService(
                new RAGObservabilityService(new ObservabilitySwitchProperties()),
                new TraceAttributeSanitizer(),
                publisher
        );
        TraceNodeHandle handle = traceService.startRoot(
                "trace-success",
                TraceNodeDefinitions.DOC_RETRIEVE,
                Map.of("ignored", "start attributes are not published")
        );

        traceService.success(handle, Map.of(
                "model", "glm-4",
                "docCount", 3,
                "secret", "must-not-leak"
        ));

        assertThat(publisher.events()).hasSize(1);
        AiObservationEvent event = publisher.events().get(0);
        assertThat(event.eventType()).isEqualTo("rag.node");
        assertThat(event.traceId()).isEqualTo("trace-success");
        assertThat(event.nodeId()).isEqualTo(handle.nodeId());
        assertThat(event.category()).isEqualTo(TraceNodeDefinitions.DOC_RETRIEVE.nodeType());
        assertThat(event.name()).isEqualTo(TraceNodeDefinitions.DOC_RETRIEVE.nodeName());
        assertThat(event.status()).isEqualTo("success");
        assertThat(event.attributes())
                .containsEntry("model", "glm-4")
                .containsEntry("docCount", "3")
                .doesNotContainKey("secret");
    }

    @Test
    void failPublishesSanitizedFailedRagNodeEventWithFallbackReasonAndError() {
        RecordingAiObservationPublisher publisher = new RecordingAiObservationPublisher();
        DefaultTraceService traceService = new DefaultTraceService(
                new RAGObservabilityService(new ObservabilitySwitchProperties()),
                new TraceAttributeSanitizer(),
                publisher
        );
        TraceNodeHandle handle = traceService.startRoot(
                "trace-failed",
                TraceNodeDefinitions.DOC_RETRIEVE,
                Map.of()
        );

        traceService.fail(handle, "remote vector store timeout", Map.of(
                "fallbackReason", "web fallback",
                "secret", "must-not-leak"
        ));

        assertThat(publisher.events()).hasSize(1);
        AiObservationEvent event = publisher.events().get(0);
        assertThat(event.eventType()).isEqualTo("rag.node");
        assertThat(event.traceId()).isEqualTo("trace-failed");
        assertThat(event.nodeId()).isEqualTo(handle.nodeId());
        assertThat(event.category()).isEqualTo(TraceNodeDefinitions.DOC_RETRIEVE.nodeType());
        assertThat(event.name()).isEqualTo(TraceNodeDefinitions.DOC_RETRIEVE.nodeName());
        assertThat(event.status()).isEqualTo("failed");
        assertThat(event.attributes())
                .containsEntry("fallbackReason", "web fallback")
                .containsEntry("error", "remote vector store timeout")
                .doesNotContainKey("secret");
    }

    @Test
    void successDoesNotThrowWhenObservationPublisherFails() {
        DefaultTraceService traceService = new DefaultTraceService(
                new RAGObservabilityService(new ObservabilitySwitchProperties()),
                new TraceAttributeSanitizer(),
                new ThrowingAiObservationPublisher()
        );
        TraceNodeHandle handle = traceService.startRoot(
                "trace-publisher-fails",
                TraceNodeDefinitions.DOC_RETRIEVE,
                Map.of()
        );

        assertThatNoException()
                .isThrownBy(() -> traceService.success(handle, Map.of("model", "glm-4")));
    }

    private static final class ThrowingAiObservationPublisher implements AiObservationPublisher {
        @Override
        public void publish(AiObservationEvent event) {
            throw new IllegalStateException("publisher unavailable");
        }
    }
}
