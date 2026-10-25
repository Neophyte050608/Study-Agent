package io.github.imzmq.interview.search.application.contract;

import java.util.List;

/** Public web-search fallback capability. */
public interface WebSearchOperations {

    List<String> search(WebSearchQuery query);
}
