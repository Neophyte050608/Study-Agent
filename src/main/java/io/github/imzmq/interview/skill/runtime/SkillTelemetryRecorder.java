package io.github.imzmq.interview.skill.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import io.github.imzmq.interview.skill.core.SkillExecutionStatus;

@Service
public class SkillTelemetryRecorder {

    private static final Logger logger = LoggerFactory.getLogger(SkillTelemetryRecorder.class);
    private static final int MAX_EVENTS = 200;

    private final Deque<SkillTelemetryEvent> events = new ArrayDeque<>();

    public synchronized void record(String skillId,
                                    SkillExecutionStatus status,
                                    int attempts,
                                    boolean fallbackUsed,
                                    String message,
                                    long latencyMs,
                                    Map<String, Object> attributes) {
        SkillTelemetryEvent event = new SkillTelemetryEvent(
                Instant.now().toString(),
                skillId == null ? "" : skillId,
                status == null ? SkillExecutionStatus.FAILED : status,
                attempts,
                fallbackUsed,
                message == null ? "" : message,
                Math.max(0L, latencyMs),
                attributes == null ? Map.of() : Map.copyOf(attributes)
        );
        if (events.size() >= MAX_EVENTS) {
            events.removeFirst();
        }
        events.addLast(event);
        logger.info("skill telemetry: skill={}, status={}, attempts={}, fallback={}, latencyMs={}, message={}",
                event.skillId(), event.status(), event.attempts(), event.fallbackUsed(), event.latencyMs(), event.message());
    }

    public synchronized List<SkillTelemetryEvent> recentEvents() {
        return new ArrayList<>(events);
    }

    public synchronized List<SkillTelemetryEvent> recentEvents(int limit,
                                                               String skillId,
                                                               String status,
                                                               String traceId) {
        int normalizedLimit = limit <= 0 ? 20 : limit;
        String normalizedSkillId = normalize(skillId);
        String normalizedStatus = normalize(status);
        String normalizedTraceId = normalize(traceId);
        List<SkillTelemetryEvent> filtered = events.stream()
                .filter(event -> normalizedSkillId.isBlank() || normalize(event.skillId()).equals(normalizedSkillId))
                .filter(event -> normalizedStatus.isBlank() || normalize(event.status().name()).equals(normalizedStatus))
                .filter(event -> normalizedTraceId.isBlank() || normalize(String.valueOf(event.attributes().getOrDefault("traceId", ""))).equals(normalizedTraceId))
                .toList();
        if (filtered.size() <= normalizedLimit) {
            return filtered;
        }
        return filtered.subList(filtered.size() - normalizedLimit, filtered.size());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record SkillTelemetryEvent(
            String timestamp,
            String skillId,
            SkillExecutionStatus status,
            int attempts,
            boolean fallbackUsed,
            String message,
            long latencyMs,
            Map<String, Object> attributes
    ) {
    }
}

