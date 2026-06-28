package io.github.imzmq.interview.observability.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(OutputCaptureExtension.class)
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

    @Test
    void publishDoesNotOutputEventNameOrAttributes(CapturedOutput output) {
        Logger logger = (Logger) LoggerFactory.getLogger(NoopAiObservationPublisher.class);
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            NoopAiObservationPublisher publisher = new NoopAiObservationPublisher();

            publisher.publish(AiObservationEvent.ragNode(
                    "trace-1",
                    "node-1",
                    "retrieval",
                    "sensitive retrieval name",
                    "success",
                    Map.of("apiKey", "secret-token", "model", "glm-4")
            ));

            assertThat(output).doesNotContain("sensitive retrieval name");
            assertThat(output).doesNotContain("apiKey");
            assertThat(output).doesNotContain("secret-token");
            assertThat(output).doesNotContain("glm-4");
        } finally {
            logger.setLevel(originalLevel);
        }
    }
}
