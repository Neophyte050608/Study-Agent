package io.github.imzmq.interview.knowledge.application.contract.extension;

/** Consumer-owned extension point for maintaining ingestion data behind Knowledge documents. */
public interface KnowledgeDocumentIngestionSupport {

    boolean hasRunningTasks();

    boolean deleteByFilePath(String filePath);

    boolean rechunkByFilePath(String filePath);
}
