package io.github.imzmq.interview.knowledge.application.retrieval;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;
import io.github.imzmq.interview.knowledge.application.indexing.LexicalIndexService;
import io.github.imzmq.interview.knowledge.application.support.UpstreamErrorSanitizer;
import io.github.imzmq.interview.knowledge.infrastructure.graph.TechConceptRepository;
import io.github.imzmq.interview.knowledge.infrastructure.graph.TechConceptSnippetView;
import io.github.imzmq.interview.knowledge.infrastructure.graph.BatchedConceptSnippetView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClientResponseException;
import java.util.concurrent.Executor;

/**
 * 固定的词法、向量和图谱三路召回，保留现有线程池及逐通道异常隔离。
 * 顺序保持为：两路 RRF → 追加图谱 → 父子回填 → 重排，不引入通道插件体系。
 */
@Service
public class HybridEvidenceRetriever {
    private static final Logger logger = LoggerFactory.getLogger(HybridEvidenceRetriever.class);
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final LexicalIndexService lexicalIndexService;
    private final Executor ragRetrieveExecutor;
    private final TechConceptRepository techConceptRepository;
    private final EvidenceFusionService fusion;
    private final GroundingContextAssembler grounding;

    public HybridEvidenceRetriever(ObjectProvider<VectorStore> vectorStoreProvider, LexicalIndexService lexicalIndexService,
                                   @Qualifier("ragRetrieveExecutor") Executor ragRetrieveExecutor,
                                   TechConceptRepository techConceptRepository,
                                   EvidenceFusionService fusion, GroundingContextAssembler grounding) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.lexicalIndexService = lexicalIndexService;
        this.ragRetrieveExecutor = ragRetrieveExecutor;
        this.techConceptRepository = techConceptRepository;
        this.fusion = fusion;
        this.grounding = grounding;
    }

    public List<Document> retrieveHybridDocuments(RewrittenQuery query, int topK) {
        List<String> intentFocusTerms = buildIntentFocusTerms(query.coreTerms());
        // 通道 A：意图定向检索（利用词法索引服务进行高精度标签/路径匹配）
        java.util.concurrent.CompletableFuture<List<Document>> intentDirectedFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                List<Document> docs = lexicalIndexService.searchIntentDirected(query.coreTerms(), intentFocusTerms, Math.max(topK + 3, 8));
                return markRetrieveChannel(docs, "intent_directed");
            } catch (RuntimeException e) {
                logger.warn("意图定向检索失败，返回空列表。原因: {}", summarizeError(e));
                return List.<Document>of();
            }
        }, ragRetrieveExecutor);

        // 通道 B：全局向量检索（纯语义兜底）
        java.util.concurrent.CompletableFuture<List<Document>> vectorFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
                if (vectorStore == null) {
                    return List.<Document>of();
                }
                List<Document> docs = vectorStore.similaritySearch(
                        SearchRequest.builder().query(query.fullQuery()).topK(Math.max(topK + 3, 8)).build()
                );
                return markRetrieveChannel(docs, "global_vector");
            } catch (RuntimeException e) {
                logger.warn("向量检索失败，返回空列表。原因: {}", summarizeError(e));
                return List.<Document>of();
            }
        }, ragRetrieveExecutor);

        // 通道 C：GraphRAG 检索分支（图谱实体扩展）
        java.util.concurrent.CompletableFuture<List<Document>> graphFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                List<Document> graphDocs = new ArrayList<>();
                List<String> queryTokens = intentFocusTerms.isEmpty() ? lexicalIndexService.tokenize(query.coreTerms()) : intentFocusTerms;
                if (queryTokens.isEmpty()) {
                    return graphDocs;
                }

                List<BatchedConceptSnippetView> batchResults =
                        techConceptRepository.findRelatedConceptSnippetsBatch(queryTokens);
                if (batchResults == null || batchResults.isEmpty()) {
                    return graphDocs;
                }

                Map<String, List<TechConceptSnippetView>> grouped = batchResults.stream()
                        .collect(Collectors.groupingBy(
                                BatchedConceptSnippetView::getAnchor,
                                LinkedHashMap::new,
                                Collectors.mapping(item -> (TechConceptSnippetView) item, Collectors.toList())
                        ));

                for (Map.Entry<String, List<TechConceptSnippetView>> entry : grouped.entrySet()) {
                    String token = entry.getKey();
                    List<TechConceptSnippetView> relatedConcepts = entry.getValue();
                    String graphContext = buildGraphConceptContext(token, relatedConcepts);
                    if (graphContext.isBlank()) {
                        continue;
                    }
                    Document doc = new Document(graphContext);
                    doc.getMetadata().put("source_type", "graph_rag");
                    doc.getMetadata().put("retrieve_channel", "graph_rag");
                    doc.getMetadata().put("graph_anchor", token);
                    doc.getMetadata().put("evidence_snippet", truncate(graphContext, 90));
                    graphDocs.add(doc);
                }
                return graphDocs;
            } catch (Exception e) {
                logger.warn("图谱检索失败，返回空列表。原因: {}", summarizeError(e));
                return List.<Document>of();
            }
        }, ragRetrieveExecutor);

        List<Document> intentDocs = intentDirectedFuture.join();
        List<Document> vectorDocs = vectorFuture.join();
        List<Document> graphDocs = graphFuture.join();

        // RRF 融合去重与重排流水线
        List<Document> fused = fusion.reciprocalRankFuse(intentDocs, vectorDocs);
        fused.addAll(graphDocs);
        List<Document> hydrated = grounding.hydrateParentDocuments(fused, topK);
        return fusion.rerankByQueryOverlap(query, hydrated, topK);
    }

    private String buildGraphConceptContext(String anchorConcept, List<TechConceptSnippetView> relatedConcepts) {
        if (relatedConcepts == null || relatedConcepts.isEmpty()) {
            return "";
        }
        String relatedSummary = relatedConcepts.stream()
                .filter(Objects::nonNull)
                .map(this::formatGraphConceptSnippet)
                .filter(item -> item != null && !item.isBlank())
                .limit(5)
                .collect(Collectors.joining("；"));
        if (relatedSummary.isBlank()) {
            return "";
        }
        return "知识图谱关联提示：围绕「" + anchorConcept + "」可继续延展的概念包括：" + relatedSummary;
    }

    private String formatGraphConceptSnippet(TechConceptSnippetView concept) {
        if (concept == null) {
            return "";
        }
        String name = concept.getName() == null ? "" : concept.getName().trim();
        String type = concept.getType() == null ? "" : concept.getType().trim();
        String description = truncate(normalizeSnippet(concept.getDescription()), 60);
        if (name.isBlank() && description.isBlank()) {
            return "";
        }
        if (!name.isBlank() && !type.isBlank() && !description.isBlank()) {
            return name + "（" + type + "）: " + description;
        }
        if (!name.isBlank() && !description.isBlank()) {
            return name + ": " + description;
        }
        if (!name.isBlank() && !type.isBlank()) {
            return name + "（" + type + "）";
        }
        return name.isBlank() ? description : name;
    }

    private List<String> buildIntentFocusTerms(String retrievalQuery) {
        if (retrievalQuery == null || retrievalQuery.isBlank()) {
            return List.of();
        }
        return lexicalIndexService.tokenize(retrievalQuery).stream()
                .filter(token -> token.length() >= 2)
                .collect(Collectors.collectingAndThen(Collectors.toCollection(LinkedHashSet::new), ArrayList::new))
                .stream()
                .limit(8)
                .collect(Collectors.toList());
    }

    private List<Document> markRetrieveChannel(List<Document> docs, String channel) {
        if (docs == null || docs.isEmpty()) {
            return List.of();
        }
        for (Document doc : docs) {
            if (doc != null) {
                doc.getMetadata().put("retrieve_channel", channel);
            }
        }
        return docs;
    }

    private String normalizeSnippet(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private String compactMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    private String summarizeError(Throwable throwable) {
        String compact = UpstreamErrorSanitizer.sanitize(compactMessage(throwable));
        String upstream = extractUpstreamDetail(throwable);
        if (upstream.isBlank()) {
            return compact;
        }
        return compact + " | upstream=" + upstream;
    }

    private String extractUpstreamDetail(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RestClientResponseException responseException) {
                String body = responseException.getResponseBodyAsString();
                String detail = "status=" + responseException.getStatusCode().value() + ", body=" + UpstreamErrorSanitizer.sanitize(body);
                return truncate(detail, 360);
            }
            current = current.getCause();
        }
        if (throwable == null || throwable.getMessage() == null) {
            return "";
        }
        return truncate(UpstreamErrorSanitizer.sanitize(throwable.getMessage()), 300);
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "...";
    }

}
