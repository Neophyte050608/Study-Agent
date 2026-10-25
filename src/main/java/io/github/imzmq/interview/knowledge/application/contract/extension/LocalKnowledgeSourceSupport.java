package io.github.imzmq.interview.knowledge.application.contract.extension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Consumer-owned extension point for local note discovery and persisted local-index settings. */
public interface LocalKnowledgeSourceSupport {

    List<Path> loadNotePaths(String vaultPath, List<String> includeDirs, List<String> ignoredDirs);

    Map<String, String> getConfig();

    String getLocalKnowledgeIndexPath();

    void saveLocalKnowledgeIndexPath(String indexFilePath);
}
