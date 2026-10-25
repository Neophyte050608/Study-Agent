package io.github.imzmq.interview.routing.application.contract;

import io.github.imzmq.interview.routing.application.contract.result.PreFilterResult;

import java.util.Map;
import java.util.Optional;

/**
 * Deterministic routing pre-filter exposed to the Agent runtime.
 */
public interface IntentPreFilterOperations {

    Optional<PreFilterResult> filter(String query);

    void fillMissingSlots(String query, Map<String, Object> payload);
}
