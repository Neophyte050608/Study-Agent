package io.github.imzmq.interview.knowledge.application;

import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.knowledge.application.observability.RAGObservabilityService;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinition;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeDefinitions;
import io.github.imzmq.interview.knowledge.application.observability.TraceNodeHandle;
import io.github.imzmq.interview.knowledge.application.retrieval.EvidenceEvaluationService;
import io.github.imzmq.interview.knowledge.application.retrieval.KnowledgeRetrievalCoordinator;
import io.github.imzmq.interview.knowledge.application.retrieval.QueryRewriteService;
import io.github.imzmq.interview.knowledge.application.retrieval.RewrittenQuery;
import io.github.imzmq.interview.knowledge.application.retrieval.WebFallbackService;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;
import io.github.imzmq.interview.media.application.ImageService;
import io.github.imzmq.interview.observability.core.RAGTraceContext;
import io.github.imzmq.interview.skill.core.SkillExecutionBudget;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class KnowledgePacketBuilder {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgePacketBuilder.class);

    private final QueryRewriteService queryRewriteService;
    private final EvidenceEvaluationService evidenceEvaluationService;
    private final WebFallbackService webFallbackService;
    private final SkillOrchestrator skillOrchestrator;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final ImageService imageService;

    public KnowledgePacketBuilder(QueryRewriteService queryRewriteService,
                                  EvidenceEvaluationService evidenceEvaluationService,
                                  WebFallbackService webFallbackService,
                                  SkillOrchestrator skillOrchestrator,
                                  ObservabilitySwitchProperties observabilitySwitchProperties,
                                  @org.springframework.lang.Nullable ImageService imageService) {
        this.queryRewriteService = queryRewriteService;
        this.evidenceEvaluationService = evidenceEvaluationService;
        this.webFallbackService = webFallbackService;
        this.skillOrchestrator = skillOrchestrator;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.imageService = imageService;
    }

    public RAGService.KnowledgePacket build(String question,
                                            String userAnswer,
                                            boolean allowWebFallback,
                                            RetrievalDelegate delegate) {
        String traceId = RAGTraceContext.getTraceId();
        String parentNodeId = RAGTraceContext.getCurrentNodeId();
        SkillExecutionBudget skillBudget = skillOrchestrator.newBudget();
        TraceNodeHandle rewriteTrace = delegate.startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.QUERY_REWRITE, Map.of("status", "RUNNING"));
        RewrittenQuery rewrittenQuery = queryRewriteService.buildRewrittenQuery(question, userAnswer, skillBudget);
        delegate.completeTraceSuccess(rewriteTrace, Map.of("status", "COMPLETED"));
        if (observabilitySwitchProperties.isRagTraceEnabled()) {
            logger.info("Rewritten Query: CORE=[{}] EXPAND=[{}]", rewrittenQuery.coreTerms(), rewrittenQuery.expandTerms());
        }

        TraceNodeHandle docRetrieveTrace = delegate.startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.DOC_RETRIEVE, Map.of("status", "RUNNING"));
        List<Document> retrievedDocs = delegate.retrieveHybridDocuments(rewrittenQuery, 5);
        String context = retrievedDocs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));
        double bestRetrievalScore = delegate.bestRetrievalScore(rewrittenQuery.fullQuery(), retrievedDocs);
        delegate.completeTraceSuccess(
                docRetrieveTrace,
                Map.of(
                        "docCount", retrievedDocs.size(),
                        "docRefs", delegate.summarizeRetrievedDocuments(retrievedDocs),
                        "status", "COMPLETED"
                ),
                new RAGObservabilityService.NodeMetrics(retrievedDocs.size(), false),
                new RAGObservabilityService.NodeDetails(
                        1,
                        KnowledgeRetrievalMode.RAG_ONLY.name(),
                        null,
                        false,
                        retrievedDocs.size(),
                        delegate.summarizeRetrievedDocuments(retrievedDocs),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        TraceNodeHandle associatedImageTrace = delegate.startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.IMAGE_ASSOC_RETRIEVE, Map.of("status", "RUNNING"));
        List<ImageService.ImageResult> associatedImages = imageService == null ? List.of() : imageService.findImagesForDocuments(retrievedDocs);
        delegate.completeTraceSuccess(associatedImageTrace, Map.of(
                "imageCount", associatedImages.size(),
                "status", "COMPLETED"
        ), null, new RAGObservabilityService.NodeDetails(
                1,
                KnowledgeRetrievalMode.RAG_ONLY.name(),
                null,
                false,
                null,
                List.of(),
                null,
                associatedImages.size(),
                null,
                null,
                null,
                null
        ));

        TraceNodeHandle semanticImageTrace = delegate.startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.IMAGE_SEMANTIC_RETRIEVE, Map.of("status", "RUNNING"));
        List<ImageService.ImageResult> semanticImages = imageService == null ? List.of() : imageService.searchRelevantImages(rewrittenQuery.fullQuery(), delegate.containsVisualIntent(rewrittenQuery.fullQuery()));
        delegate.completeTraceSuccess(semanticImageTrace, Map.of(
                "imageCount", semanticImages.size(),
                "status", "COMPLETED"
        ), null, new RAGObservabilityService.NodeDetails(
                1,
                KnowledgeRetrievalMode.RAG_ONLY.name(),
                null,
                false,
                null,
                List.of(),
                null,
                semanticImages.size(),
                null,
                null,
                null,
                null
        ));

        List<ImageService.ImageResult> retrievedImages = delegate.mergeImageResults(associatedImages, semanticImages);
        String imageContext = KnowledgeRetrievalCoordinator.buildImageContext(retrievedImages);
        String retrievalEvidence = delegate.buildRetrievalEvidence(retrievedDocs);
        boolean webFallbackUsed = false;
        EvidenceEvaluationService.EvidenceDecision evidenceDecision = evidenceEvaluationService.decide(
                rewrittenQuery,
                retrievedDocs,
                context,
                bestRetrievalScore,
                allowWebFallback,
                traceId,
                skillBudget
        );
        if (evidenceDecision.allowExternalLookup()) {
            TraceNodeHandle webFallbackTrace = delegate.startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.WEB_FALLBACK, Map.of("fallback", true, "status", "RUNNING"));
            List<String> webContext = webFallbackService.search(rewrittenQuery.fullQuery(), traceId, evidenceDecision.reason(), skillBudget);
            context = webContext.stream().collect(Collectors.joining("\n\n"));
            retrievalEvidence = delegate.buildWebEvidence(webContext);
            webFallbackUsed = true;
            delegate.completeTraceSuccess(
                    webFallbackTrace,
                    Map.of(
                            "fallback", true,
                            "fallbackReason", "LOW_RETRIEVAL_QUALITY",
                            "docCount", webContext.size(),
                            "status", "COMPLETED"
                    ),
                    new RAGObservabilityService.NodeMetrics(0, true),
                    new RAGObservabilityService.NodeDetails(
                            1,
                            KnowledgeRetrievalMode.RAG_ONLY.name(),
                            null,
                            false,
                            webContext.size(),
                            List.of(),
                            webContext.size(),
                            null,
                            evidenceDecision.reason(),
                            null,
                            null,
                            null
                    )
            );
        }

        return new RAGService.KnowledgePacket(rewrittenQuery.fullQuery(), retrievedDocs, retrievedImages, context, imageContext, retrievalEvidence, webFallbackUsed);
    }

    public interface RetrievalDelegate {
        List<Document> retrieveHybridDocuments(RewrittenQuery query, int topK);

        double bestRetrievalScore(String query, List<Document> docs);

        String buildRetrievalEvidence(List<Document> docs);

        String buildWebEvidence(List<String> webContext);

        List<ImageService.ImageResult> mergeImageResults(List<ImageService.ImageResult> associatedImages,
                                                         List<ImageService.ImageResult> semanticImages);

        boolean containsVisualIntent(String query);

        List<String> summarizeRetrievedDocuments(List<Document> retrievedDocs);

        TraceNodeHandle startTraceChild(String traceId, String parentNodeId, TraceNodeDefinition definition, Map<String, Object> attributes);

        void completeTraceSuccess(TraceNodeHandle handle, Map<String, Object> attributes);

        void completeTraceSuccess(TraceNodeHandle handle,
                                  Map<String, Object> attributes,
                                  RAGObservabilityService.NodeMetrics metrics,
                                  RAGObservabilityService.NodeDetails details);
    }
}
