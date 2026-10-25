package io.github.imzmq.interview.knowledge.application.contract.indexing;

import java.util.List;

/** Public document chunking capability owned by Knowledge. */
public interface KnowledgeDocumentSplitOperations {
    List<KnowledgeIndexDocument> split(List<KnowledgeIndexDocument> documents);
}
