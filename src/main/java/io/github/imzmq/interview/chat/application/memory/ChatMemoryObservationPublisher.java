package io.github.imzmq.interview.chat.application.memory;

import io.github.imzmq.interview.observability.application.contract.OperationalObservationPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Publishes content-free operational telemetry for the structured Chat memory pipeline. */
@Component
public class ChatMemoryObservationPublisher {

    private static final Pattern CONTROLLED_TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Set<String> EVENT_NAMES = Set.of("extract", "project", "recall", "repair", "lifecycle");
    private static final Set<String> LIFECYCLE_ATTRIBUTE_KEYS = Set.of(
            "stage",
            "resultCount",
            "reasonCode",
            "previousStatus",
            "nextStatus",
            "errorCode",
            "latencyMs"
    );
    private static final Set<String> LIFECYCLE_CONTROLLED_TOKEN_KEYS = Set.of(
            "reasonCode",
            "previousStatus",
            "nextStatus",
            "errorCode"
    );
    private static final Set<String> LIFECYCLE_STAGE_VALUES = Set.of("write", "management");
    private static final Set<String> LIFECYCLE_NUMBER_KEYS = Set.of("resultCount", "latencyMs");

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

    public void lifecycle(String status, Map<String, ?> attributes) {
        publish("lifecycle", status, filterLifecycleAttributes(attributes));
    }

    public void lifecycleAfterCommit(String status, Map<String, ?> attributes) {
        Map<String, Object> bounded = filterLifecycleAttributes(attributes);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish("lifecycle", status, bounded);
                }
            });
            return;
        }
        publish("lifecycle", status, bounded);
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

    private Map<String, Object> filterLifecycleAttributes(Map<String, ?> attributes) {
        Map<String, Object> bounded = new LinkedHashMap<>();
        if (attributes == null) {
            return bounded;
        }
        attributes.forEach((key, value) -> {
            if (!LIFECYCLE_ATTRIBUTE_KEYS.contains(key) || value == null) {
                return;
            }
            if (!isValidLifecycleValue(key, value)) {
                return;
            }
            bounded.put(key, value);
        });
        return bounded;
    }

    private boolean isValidLifecycleValue(String key, Object value) {
        if (LIFECYCLE_CONTROLLED_TOKEN_KEYS.contains(key)) {
            return CONTROLLED_TOKEN.matcher(String.valueOf(value)).matches();
        }
        if (LIFECYCLE_NUMBER_KEYS.contains(key)) {
            if (value instanceof Number) {
                return true;
            }
            return String.valueOf(value).matches("[0-9]{1,12}");
        }
        if ("stage".equals(key)) {
            return LIFECYCLE_STAGE_VALUES.contains(String.valueOf(value));
        }
        return false;
    }

    private String normalizeStatus(String status) {
        if (status == null || !CONTROLLED_TOKEN.matcher(status).matches()) {
            return "UNKNOWN";
        }
        return status;
    }
}
