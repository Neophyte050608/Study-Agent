package io.github.imzmq.interview.routing.application.contract;

import io.github.imzmq.interview.routing.application.contract.result.IntentTreeNode;

import java.util.List;

/**
 * Read-only intent catalog needed by search suggestions.
 */
public interface IntentCatalogQuery {

    List<IntentTreeNode> loadAllLeafIntents();
}
