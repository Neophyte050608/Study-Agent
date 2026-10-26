package io.github.imzmq.interview.observability.application;

import io.github.imzmq.interview.observability.application.contract.OperationalObservationPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class DefaultOperationalObservationPublisher implements OperationalObservationPublisher {

    private static final Logger log = LoggerFactory.getLogger(DefaultOperationalObservationPublisher.class);

    private final AiObservationPublisher delegate;
    private final TraceAttributeSanitizer sanitizer;

    public DefaultOperationalObservationPublisher(
            AiObservationPublisher delegate,
            TraceAttributeSanitizer sanitizer) {
        this.delegate = delegate;
        this.sanitizer = sanitizer;
    }

    @Override
    public void publish(String eventType,
                        String category,
                        String name,
                        String status,
                        Map<String, ?> attributes) {
        Map<String, Object> candidates = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((key, value) -> {
                if (key != null && value != null) {
                    candidates.put(key, value);
                }
            });
        }
        AiObservationEvent event = new AiObservationEvent(
                eventType,
                "",
                "",
                category,
                name,
                status,
                Instant.now(),
                sanitizer.sanitizeForExternalObservation(candidates)
        );
        try {
            delegate.publish(event);
        } catch (RuntimeException exception) {
            log.debug("Operational observation was dropped: eventType={}, status={}",
                    event.eventType(), event.status());
        }
    }
}
