package io.github.imzmq.interview.observability.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceAttributeSanitizerTest {

    @Test
    void keepsOnlySafeAiObservationKeys() {
        TraceAttributeSanitizer sanitizer = new TraceAttributeSanitizer();

        Map<String, Object> sanitized = sanitizer.sanitize(Map.of(
                "provider", "zhipu",
                "model", "glm-4",
                "latencyMs", 123,
                "promptTokens", 10,
                "completionTokens", 20,
                "totalTokens", 30,
                "estimatedCost", "0.001",
                "retrievalMode", "hybrid",
                "docCount", 4,
                "secret", "must-not-leak"
        ));

        assertThat(sanitized).containsEntry("provider", "zhipu");
        assertThat(sanitized).containsEntry("model", "glm-4");
        assertThat(sanitized).containsEntry("latencyMs", "123");
        assertThat(sanitized).containsEntry("promptTokens", "10");
        assertThat(sanitized).containsEntry("completionTokens", "20");
        assertThat(sanitized).containsEntry("totalTokens", "30");
        assertThat(sanitized).containsEntry("estimatedCost", "0.001");
        assertThat(sanitized).containsEntry("retrievalMode", "hybrid");
        assertThat(sanitized).containsEntry("docCount", "4");
        assertThat(sanitized).doesNotContainKey("secret");
    }
}
