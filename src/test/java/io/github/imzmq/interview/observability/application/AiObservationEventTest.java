package io.github.imzmq.interview.observability.application;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiObservationEventTest {

    @Test
    void createsImmutableRagNodeEvent() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("model", "glm-4");
        attrs.put("docCount", 3);

        AiObservationEvent event = AiObservationEvent.ragNode(
                "trace-1",
                "node-1",
                "retrieval",
                "knowledge retrieval",
                "success",
                attrs
        );

        assertThat(event.eventType()).isEqualTo("rag.node");
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.nodeId()).isEqualTo("node-1");
        assertThat(event.category()).isEqualTo("retrieval");
        assertThat(event.name()).isEqualTo("knowledge retrieval");
        assertThat(event.status()).isEqualTo("success");
        assertThat(event.attributes()).containsEntry("model", "glm-4");
        assertThat(event.attributes()).containsEntry("docCount", 3);

        attrs.put("model", "changed");
        assertThat(event.attributes()).containsEntry("model", "glm-4");
        assertThatThrownBy(() -> event.attributes().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void normalizesBlankValues() {
        AiObservationEvent event = AiObservationEvent.ragNode(
                " trace-1 ",
                " node-1 ",
                " retrieval ",
                " knowledge retrieval ",
                " success ",
                null
        );

        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.nodeId()).isEqualTo("node-1");
        assertThat(event.category()).isEqualTo("retrieval");
        assertThat(event.name()).isEqualTo("knowledge retrieval");
        assertThat(event.status()).isEqualTo("success");
        assertThat(event.attributes()).isEmpty();
    }
}
