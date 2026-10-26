package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.DefaultOperationalObservationPublisher;
import io.github.imzmq.interview.observability.application.TraceAttributeSanitizer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ChatMemoryObservabilityTest {

    @Test
    void exportsOnlyBoundedOperationalAttributes() {
        CapturingPublisher sink = new CapturingPublisher();
        ChatMemoryObservationPublisher publisher = new ChatMemoryObservationPublisher(new DefaultOperationalObservationPublisher(
                sink, new TraceAttributeSanitizer()));

        publisher.extract("RETRY", Map.ofEntries(
                Map.entry("stage", "extract"),
                Map.entry("taskCount", 3),
                Map.entry("pendingCount", 2),
                Map.entry("deadCount", 1),
                Map.entry("latencyMs", 48),
                Map.entry("lagMs", 1200),
                Map.entry("fallback", true),
                Map.entry("errorCode", "MEMORY_SOURCE_INVALID"),
                Map.entry("content", "用户原文"),
                Map.entry("quote", "原始引文"),
                Map.entry("rawJson", "{\"secret\":true}"),
                Map.entry("prompt", "private prompt"),
                Map.entry("embedding", "[0.1, 0.2]"),
                Map.entry("contentHash", "sha256-secret"),
                Map.entry("userId", "user-1"),
                Map.entry("sessionId", "session-1"),
                Map.entry("memoryId", "memory-1"),
                Map.entry("taskId", "task-1")
        ));

        assertThat(sink.event.eventType()).isEqualTo("chat.memory.extract");
        assertThat(sink.event.traceId()).isEmpty();
        assertThat(sink.event.nodeId()).isEmpty();
        assertThat(sink.event.category()).isEqualTo("chat.memory");
        assertThat(sink.event.name()).isEqualTo("extract");
        assertThat(sink.event.status()).isEqualTo("RETRY");
        assertThat(sink.event.attributes())
                .containsEntry("stage", "extract")
                .containsEntry("taskCount", "3")
                .containsEntry("pendingCount", "2")
                .containsEntry("deadCount", "1")
                .containsEntry("latencyMs", "48")
                .containsEntry("lagMs", "1200")
                .containsEntry("fallback", "true")
                .containsEntry("errorCode", "MEMORY_SOURCE_INVALID")
                .doesNotContainKeys(
                        "content", "quote", "rawJson", "prompt", "embedding", "contentHash",
                        "userId", "sessionId", "memoryId", "taskId");
        assertThat(sink.event.attributes().toString())
                .doesNotContain("用户原文", "原始引文", "secret", "private prompt", "sha256-secret");
    }

    @Test
    void rejectsUncontrolledErrorValuesAndNeverBreaksMemoryWork() {
        ChatMemoryObservationPublisher publisher = new ChatMemoryObservationPublisher(new DefaultOperationalObservationPublisher(
                event -> { throw new IllegalStateException("external telemetry unavailable"); },
                new TraceAttributeSanitizer()));

        assertThatCode(() -> publisher.project("FAILED", Map.of(
                "errorCode", "database failed: token=secret",
                "resultCount", 0
        ))).doesNotThrowAnyException();
    }

    private static final class CapturingPublisher implements AiObservationPublisher {
        private AiObservationEvent event;

        @Override
        public void publish(AiObservationEvent event) {
            this.event = event;
        }
    }
}
