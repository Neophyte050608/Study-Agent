package io.github.imzmq.interview.observability.application;

import java.util.ArrayList;
import java.util.List;

public class RecordingAiObservationPublisher implements AiObservationPublisher {

    private final List<AiObservationEvent> events = new ArrayList<>();

    @Override
    public void publish(AiObservationEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    public List<AiObservationEvent> events() {
        return List.copyOf(events);
    }
}
