package io.github.imzmq.interview.knowledge.application.evaluation.goldset;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievedChunkIdentityResolverTest {

    @Test
    void prefersExplicitChunkIdOverInternalIndexIdentifiers() {
        Document document = new Document("spring-document-id", "same content", Map.of(
                "chunk_id", "gold-chunk-id",
                "child_id", "internal-child-id",
                "doc_id", "internal-doc-id"
        ));

        assertThat(RetrievedChunkIdentityResolver.resolve(document)).isEqualTo("gold-chunk-id");
    }

    @Test
    void fallsBackAcrossSupportedMetadataAndFinallyDocumentId() {
        assertThat(RetrievedChunkIdentityResolver.resolve(
                new Document("spring-id", "content", Map.of("child_id", "child-id"))))
                .isEqualTo("child-id");
        assertThat(RetrievedChunkIdentityResolver.resolve(
                new Document("spring-id", "content", Map.of("doc_id", "doc-id"))))
                .isEqualTo("doc-id");
        assertThat(RetrievedChunkIdentityResolver.resolve(
                new Document("spring-id", "content", Map.of())))
                .isEqualTo("spring-id");
    }

    @Test
    void ignoresBlankMetadataValuesAndNullDocuments() {
        assertThat(RetrievedChunkIdentityResolver.resolve(
                new Document("spring-id", "content", Map.of("chunk_id", "  "))))
                .isEqualTo("spring-id");
        assertThat(RetrievedChunkIdentityResolver.resolve(null)).isEmpty();
    }
}
