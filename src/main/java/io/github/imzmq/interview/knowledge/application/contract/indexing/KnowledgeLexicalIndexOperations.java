package io.github.imzmq.interview.knowledge.application.contract.indexing;

import java.util.List;

/** Public lexical indexing write capability owned by Knowledge. */
public interface KnowledgeLexicalIndexOperations {
    void upsert(List<KnowledgeIndexDocument> documents);
    void removeByIds(List<String> documentIds);
}
