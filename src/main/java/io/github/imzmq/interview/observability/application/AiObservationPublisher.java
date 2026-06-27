package io.github.imzmq.interview.observability.application;

public interface AiObservationPublisher {

    void publish(AiObservationEvent event);
}
