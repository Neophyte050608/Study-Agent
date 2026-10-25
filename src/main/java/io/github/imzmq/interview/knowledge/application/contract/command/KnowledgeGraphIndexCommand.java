package io.github.imzmq.interview.knowledge.application.contract.command;

/**
 * Knowledge-owned command for indexing a source document into the concept graph.
 */
public interface KnowledgeGraphIndexCommand {

    void index(KnowledgeGraphSourceDocument document);

    /**
     * Stable input owned by Knowledge; callers do not expose Spring AI documents or Neo4j types.
     */
    record KnowledgeGraphSourceDocument(String filePath, String content, String wikiLinks) {
    }
}
