package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.observability.application.contract.OperationalObservationPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Publishes content-free operational telemetry for the structured Chat memory pipeline. */
@Component
public class ChatMemoryObservationPublisher {

    private static final Pattern CONTROLLED_TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Set<String> EVENT_NAMES = Set.of("extract", "project", "recall", "repair");

    private final OperationalObservationPublisher delegate;

    public ChatMemoryObservationPublisher(OperationalObservationPublisher delegate) {
        this.delegate = delegate;
    }

    public void extract(String status, Map<String, ?> attributes) {
        publish("extract", status, attributes);
    }

    public void project(String status, Map<String, ?> attributes) {
        publish("project", status, attributes);
    }

    public void recall(String status, Map<String, ?> attributes) {
        publish("recall", status, attributes);
    }

    public void repair(String status, Map<String, ?> attributes) {
        publish("repair", status, attributes);
    }

    private void publish(String name, String status, Map<String, ?> attributes) {
        if (!EVENT_NAMES.contains(name)) {
            return;
        }
        Map<String, Object> bounded = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((key, value) -> {
                if (key != null && value != null) {
                    bounded.put(key, value);
                }
            });
        }
        Object errorCode = bounded.get("errorCode");
        if (errorCode != null && !CONTROLLED_TOKEN.matcher(String.valueOf(errorCode)).matches()) {
            bounded.remove("errorCode");
        }
        delegate.publish(
                "chat.memory." + name,
                "chat.memory",
                name,
                normalizeStatus(status),
                bounded
        );
    }

    private String normalizeStatus(String status) {
        if (status == null || !CONTROLLED_TOKEN.matcher(status).matches()) {
            return "UNKNOWN";
        }
        return status;
    }
}
