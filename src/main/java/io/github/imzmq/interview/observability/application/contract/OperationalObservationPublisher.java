package io.github.imzmq.interview.observability.application.contract;

import java.util.Map;

/** Public SDK-neutral boundary for sanitized operational AI events. */
public interface OperationalObservationPublisher {

    void publish(String eventType,
                 String category,
                 String name,
                 String status,
                 Map<String, ?> attributes);
}
