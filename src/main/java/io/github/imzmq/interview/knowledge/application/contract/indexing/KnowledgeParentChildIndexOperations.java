package io.github.imzmq.interview.knowledge.application.contract.indexing;

import java.util.List;

/** Public parent-child indexing write and statistics capability owned by Knowledge. */
public interface KnowledgeParentChildIndexOperations {
    void rebuild(String filePath, List<KnowledgeIndexDocument> chunks);
    void deleteByFilePath(String filePath);
    long countParents();
    long countChildren();
}
