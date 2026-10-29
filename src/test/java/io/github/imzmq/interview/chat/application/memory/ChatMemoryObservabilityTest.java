package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.observability.application.AiObservationEvent;
import io.github.imzmq.interview.observability.application.AiObservationPublisher;
import io.github.imzmq.interview.observability.application.DefaultOperationalObservationPublisher;
import io.github.imzmq.interview.observability.application.TraceAttributeSanitizer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
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
    void lifecycleExportsOnlyBoundedContentFreeAttributes() {
        CapturingPublisher sink = new CapturingPublisher();
        ChatMemoryObservationPublisher publisher = new ChatMemoryObservationPublisher(new DefaultOperationalObservationPublisher(
                sink, new TraceAttributeSanitizer()));

        publisher.lifecycle("QUARANTINED", Map.ofEntries(
                Map.entry("stage", "management"),
                Map.entry("resultCount", 2),
                Map.entry("reasonCode", "AMBIGUOUS_SINGLETON"),
                Map.entry("previousStatus", "QUARANTINED"),
                Map.entry("nextStatus", "ACTIVE"),
                Map.entry("errorCode", "MEMORY_STATE_CONFLICT"),
                Map.entry("latencyMs", 7),
                Map.entry("content", "用户原文"),
                Map.entry("quote", "原始引文"),
                Map.entry("subjectKey", "preference.editor"),
                Map.entry("userId", "user-1"),
                Map.entry("sessionId", "session-1"),
                Map.entry("messageId", "message-1"),
                Map.entry("memoryId", 42L),
                Map.entry("prompt", "private prompt"),
                Map.entry("rawJson", "{\"content\":\"secret\"}"),
                Map.entry("embedding", "[0.1, 0.2]"),
                Map.entry("exceptionText", "database failed: token=secret")
        ));

        assertThat(sink.event.eventType()).isEqualTo("chat.memory.lifecycle");
        assertThat(sink.event.category()).isEqualTo("chat.memory");
        assertThat(sink.event.name()).isEqualTo("lifecycle");
        assertThat(sink.event.status()).isEqualTo("QUARANTINED");
        assertThat(sink.event.attributes())
                .containsEntry("stage", "management")
                .containsEntry("resultCount", "2")
                .containsEntry("reasonCode", "AMBIGUOUS_SINGLETON")
                .containsEntry("previousStatus", "QUARANTINED")
                .containsEntry("nextStatus", "ACTIVE")
                .containsEntry("errorCode", "MEMORY_STATE_CONFLICT")
                .containsEntry("latencyMs", "7")
                .doesNotContainKeys(
                        "content", "quote", "subjectKey", "userId", "sessionId", "messageId",
                        "memoryId", "prompt", "rawJson", "embedding", "exceptionText");
        assertThat(sink.event.attributes().toString())
                .doesNotContain("用户原文", "原始引文", "preference.editor", "user-1",
                        "message-1", "private prompt", "secret", "token");
    }

    @Test
    void lifecycleRejectsUnboundedLifecycleAttributeValues() {
        CapturingPublisher sink = new CapturingPublisher();
        ChatMemoryObservationPublisher publisher = new ChatMemoryObservationPublisher(new DefaultOperationalObservationPublisher(
                sink, new TraceAttributeSanitizer()));

        publisher.lifecycle("DELETED", Map.of(
                "stage", "用户原文",
                "resultCount", "result contains secret",
                "latencyMs", "database failed: token=secret",
                "reasonCode", "invalid reason"
        ));

        assertThat(sink.event.attributes()).isEmpty();
        assertThat(sink.event.attributes().toString())
                .doesNotContain("用户原文", "secret", "token", "invalid reason");
    }

    @Test
    void lifecycleAcceptsOnlyKnownLifecycleStatuses() {
        CapturingPublisher sink = new CapturingPublisher();
        ChatMemoryObservationPublisher publisher = new ChatMemoryObservationPublisher(new DefaultOperationalObservationPublisher(
                sink, new TraceAttributeSanitizer()));

        for (String status : List.of(
                "AUTO_SUPERSEDED",
                "QUARANTINED",
                "CONFIRMED_REPLACEMENT",
                "CONFIRMED_COEXISTENCE",
                "DELETED",
                "STATE_CONFLICT")) {
            publisher.lifecycle(status, Map.of("stage", "management", "resultCount", 1));
        }

        assertThat(sink.events)
                .extracting(AiObservationEvent::status)
                .containsExactly(
                        "AUTO_SUPERSEDED",
                        "QUARANTINED",
                        "CONFIRMED_REPLACEMENT",
                        "CONFIRMED_COEXISTENCE",
                        "DELETED",
                        "STATE_CONFLICT");
    }

    @Test
    void lifecycleAfterCommitWaitsForCommitAndDropsRollbackEvents() {
        CapturingPublisher sink = new CapturingPublisher();
        ChatMemoryObservationPublisher publisher = new ChatMemoryObservationPublisher(new DefaultOperationalObservationPublisher(
                sink, new TraceAttributeSanitizer()));

        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.lifecycleAfterCommit("DELETED", Map.of("stage", "management", "resultCount", 1));
            assertThat(sink.events).isEmpty();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(sink.events).hasSize(1);
            assertThat(sink.event.status()).isEqualTo("DELETED");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        sink.events.clear();
        sink.event = null;
        TransactionSynchronizationManager.initSynchronization();
        try {
            publisher.lifecycleAfterCommit("QUARANTINED", Map.of("stage", "write", "resultCount", 1));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertThat(sink.events).isEmpty();
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
        private final List<AiObservationEvent> events = new ArrayList<>();
        private AiObservationEvent event;

        @Override
        public void publish(AiObservationEvent event) {
            this.event = event;
            this.events.add(event);
        }
    }
}
