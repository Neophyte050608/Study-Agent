package io.github.imzmq.interview.routing.application.contract;

import io.github.imzmq.interview.routing.application.contract.result.IntentRoutingDecision;

import java.util.Map;

/**
 * Stable runtime intent-routing operations used outside the Routing module.
 */
public interface IntentRoutingOperations {

    boolean enabled();

    IntentRoutingDecision route(String query, String history, String domainCode);

    Map<String, Object> refineSlots(String taskType, String query, String history);
}
