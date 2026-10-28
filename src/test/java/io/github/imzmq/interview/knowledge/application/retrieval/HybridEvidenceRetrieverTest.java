package io.github.imzmq.interview.knowledge.application.retrieval;

import io.github.imzmq.interview.config.knowledge.ParentChildRetrievalProperties;
import io.github.imzmq.interview.knowledge.application.indexing.LexicalIndexService;
import io.github.imzmq.interview.knowledge.application.indexing.ParentChildIndexService;
import io.github.imzmq.interview.knowledge.application.indexing.RetrievalTokenizerService;
import io.github.imzmq.interview.knowledge.infrastructure.graph.TechConceptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HybridEvidenceRetrieverTest {
    @Test
    void preservesBlankUnicodeGraphDescriptionAsNoDescription() {
        LexicalIndexService lexical = mock(LexicalIndexService.class);
        VectorStore vector = mock(VectorStore.class);
        TechConceptRepository graph = mock(TechConceptRepository.class);
        ParentChildRetrievalProperties properties = new ParentChildRetrievalProperties();
        properties.setEnabled(false);
        when(lexical.tokenize(anyString())).thenReturn(List.of("redis"));
        when(graph.findRelatedConceptSnippetsBatch(any())).thenReturn(List.of(
                new io.github.imzmq.interview.knowledge.infrastructure.graph.BatchedConceptSnippetView() {
                    public String getAnchor() { return "redis"; }
                    public String getName() { return "Redis"; }
                    public String getType() { return "DB"; }
                    public String getDescription() { return "\u2003".repeat(80); }
                }));
        HybridEvidenceRetriever retriever = new HybridEvidenceRetriever(vectorProvider(vector), lexical, Runnable::run, graph,
                new EvidenceFusionService(lexical, new RetrievalTokenizerService()),
                new GroundingContextAssembler(properties, mock(ParentChildIndexService.class)));
        List<Document> result = retriever.retrieveHybridDocuments(RewrittenQuery.fallback("redis"), 1);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getText()).isEqualTo("知识图谱关联提示：围绕「redis」可继续延展的概念包括：Redis（DB）");
        assertThat(result.getFirst().getMetadata()).containsEntry("graph_anchor", "redis")
                .containsEntry("source_type", "graph_rag");
    }

    @Test
    void schedulesAllThreeChannelsBeforeJoiningAndKeepsPartialResults() throws Exception {
        LexicalIndexService lexical = mock(LexicalIndexService.class);
        VectorStore vector = mock(VectorStore.class);
        TechConceptRepository graph = mock(TechConceptRepository.class);
        ParentChildRetrievalProperties properties = new ParentChildRetrievalProperties();
        properties.setEnabled(false);
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        when(lexical.tokenize("redis")).thenReturn(List.of("redis"));
        when(lexical.tokenize("redis cache")).thenReturn(List.of("redis"));
        Document doc = new Document("redis cache", Map.of("file_path", "cache.md"));
        when(lexical.searchIntentDirected(anyString(), any(), anyInt())).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
            return List.of(doc);
        });
        when(vector.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
            throw new IllegalStateException("vector offline");
        });
        when(graph.findRelatedConceptSnippetsBatch(any())).thenAnswer(invocation -> {
            started.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
            return List.of();
        });
        ExecutorService pool = Executors.newFixedThreadPool(3);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            HybridEvidenceRetriever retriever = new HybridEvidenceRetriever(vectorProvider(vector), lexical, pool, graph,
                    new EvidenceFusionService(lexical, new RetrievalTokenizerService()),
                    new GroundingContextAssembler(properties, mock(ParentChildIndexService.class)));
            CompletableFuture<List<Document>> result = CompletableFuture.supplyAsync(
                    () -> retriever.retrieveHybridDocuments(RewrittenQuery.fallback("redis"), 2), caller);
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(result.get(3, TimeUnit.SECONDS)).containsExactly(doc);
            assertThat(doc.getMetadata()).containsEntry("retrieve_channel", "intent_directed");
            verify(lexical).searchIntentDirected("redis", List.of("redis"), 8);
            verify(graph).findRelatedConceptSnippetsBatch(List.of("redis"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            caller.shutdownNow();
        }
    }
    private static org.springframework.beans.factory.ObjectProvider<VectorStore> vectorProvider(VectorStore vectorStore) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public VectorStore getObject(Object... args) {
                return vectorStore;
            }

            @Override
            public VectorStore getIfAvailable() {
                return vectorStore;
            }

            @Override
            public VectorStore getIfUnique() {
                return vectorStore;
            }

            @Override
            public VectorStore getObject() {
                return vectorStore;
            }
        };
    }

}
