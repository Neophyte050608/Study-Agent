package io.github.imzmq.interview.knowledge.application.contract.result;

/** Framework-neutral retrieval context exposed by Knowledge to consumer modules. */
public record KnowledgePacket(
        String retrievalQuery,
        int retrievedDocumentCount,
        String context,
        String imageContext,
        String retrievalEvidence,
        boolean webFallbackUsed
) {
    public KnowledgePacket {
        retrievalQuery = retrievalQuery == null ? "" : retrievalQuery;
        retrievedDocumentCount = Math.max(0, retrievedDocumentCount);
        context = context == null ? "" : context;
        imageContext = imageContext == null ? "" : imageContext;
        retrievalEvidence = retrievalEvidence == null ? "" : retrievalEvidence;
    }
}
