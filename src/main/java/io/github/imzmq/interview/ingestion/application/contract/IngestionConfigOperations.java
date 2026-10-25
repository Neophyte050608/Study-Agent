package io.github.imzmq.interview.ingestion.application.contract;

import java.util.Map;

/** Public configuration operations owned by the ingestion module. */
public interface IngestionConfigOperations {

    Map<String, String> getConfig();

    Map<String, String> saveConfig(Map<String, String> payload);

    String getLocalKnowledgeIndexPath();

    void saveLocalKnowledgeIndexPath(String indexFilePath);
}
