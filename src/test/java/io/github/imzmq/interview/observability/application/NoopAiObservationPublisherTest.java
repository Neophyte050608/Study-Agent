package io.github.imzmq.interview.observability.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoopAiObservationPublisherTest {

    @Test
    void publishNeverThrowsForNullOrNormalEvent() {
        NoopAiObservationPublisher publisher = new NoopAiObservationPublisher();

        assertThatCode(() -> publisher.publish(null)).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(AiObservationEvent.ragNode(
                "trace-1",
                "node-1",
                "retrieval",
                "knowledge retrieval",
                "success",
                Map.of("model", "glm-4")
        ))).doesNotThrowAnyException();
    }
}
