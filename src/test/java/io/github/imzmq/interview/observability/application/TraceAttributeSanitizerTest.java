package io.github.imzmq.interview.observability.application;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceAttributeSanitizerTest {

    @Test
    void keepsOnlySafeAiObservationKeys() {
        TraceAttributeSanitizer sanitizer = new TraceAttributeSanitizer();

        Map<String, Object> sanitized = sanitizer.sanitize(Map.ofEntries(
                Map.entry("provider", "zhipu"),
                Map.entry("model", "glm-4"),
                Map.entry("latencyMs", 123),
                Map.entry("promptTokens", 10),
                Map.entry("completionTokens", 20),
                Map.entry("totalTokens", 30),
                Map.entry("estimatedCost", "0.001"),
                Map.entry("retrievalMode", "hybrid"),
                Map.entry("docCount", 4),
                Map.entry("routeType", "intent"),
                Map.entry("candidateId", "candidate-1"),
                Map.entry("candidatePriority", 5),
                Map.entry("circuitState", "CLOSED"),
                Map.entry("nodeType", "retrieval"),
                Map.entry("nodeName", "knowledge retrieval"),
                Map.entry("secret", "must-not-leak")
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
        assertThat(sanitized).containsEntry("routeType", "intent");
        assertThat(sanitized).containsEntry("candidateId", "candidate-1");
        assertThat(sanitized).containsEntry("candidatePriority", "5");
        assertThat(sanitized).containsEntry("circuitState", "CLOSED");
        assertThat(sanitized).containsEntry("nodeType", "retrieval");
        assertThat(sanitized).containsEntry("nodeName", "knowledge retrieval");
        assertThat(sanitized).doesNotContainKey("secret");
    }

    @Test
    void keepsErrorAndTruncatesLongTextToSafeLength() {
        TraceAttributeSanitizer sanitizer = new TraceAttributeSanitizer();
        String longError = "x".repeat(121);

        Map<String, Object> sanitized = sanitizer.sanitize(Map.of("error", longError));

        assertThat(sanitized).containsKey("error");
        assertThat(sanitized.get("error")).isEqualTo("x".repeat(120));
    }
}
