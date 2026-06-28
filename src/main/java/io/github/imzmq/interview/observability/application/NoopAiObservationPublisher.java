package io.github.imzmq.interview.observability.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoopAiObservationPublisher implements AiObservationPublisher {

    private static final Logger logger = LoggerFactory.getLogger(NoopAiObservationPublisher.class);

    @Override
    public void publish(AiObservationEvent event) {
        if (event == null) {
            return;
        }
        logger.debug("AI observation event ignored by noop publisher: type={}, traceId={}, nodeId={}, status={}",
                event.eventType(), event.traceId(), event.nodeId(), event.status());
    }
}
