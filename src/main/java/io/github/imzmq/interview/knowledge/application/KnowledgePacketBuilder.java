package io.github.imzmq.interview.knowledge.application;

import io.github.imzmq.interview.media.application.contract.result.ImageResult;

import io.github.imzmq.interview.observability.application.contract.trace.TraceNodeDetails;
import io.github.imzmq.interview.observability.application.contract.trace.TraceNodeMetrics;

import io.github.imzmq.interview.config.observability.ObservabilitySwitchProperties;
import io.github.imzmq.interview.observability.application.contract.trace.RagTraceOperations;
import io.github.imzmq.interview.observability.application.contract.trace.TraceNodeDefinition;
import io.github.imzmq.interview.observability.application.contract.trace.TraceNodeDefinitions;
import io.github.imzmq.interview.observability.application.contract.trace.TraceNodeHandle;
import io.github.imzmq.interview.knowledge.application.retrieval.EvidenceEvaluationService;
import io.github.imzmq.interview.knowledge.application.retrieval.KnowledgeRetrievalCoordinator;
import io.github.imzmq.interview.knowledge.application.retrieval.QueryRewriteService;
import io.github.imzmq.interview.knowledge.application.retrieval.RewrittenQuery;
import io.github.imzmq.interview.knowledge.application.retrieval.HybridEvidenceRetriever;
import io.github.imzmq.interview.knowledge.application.retrieval.EvidenceFusionService;
import io.github.imzmq.interview.knowledge.application.retrieval.GroundingContextAssembler;
import java.util.UUID;
import io.github.imzmq.interview.knowledge.application.retrieval.WebFallbackService;
import io.github.imzmq.interview.knowledge.domain.KnowledgeRetrievalMode;
import io.github.imzmq.interview.media.application.contract.MediaImageOperations;
import io.github.imzmq.interview.media.application.contract.MediaDocument;
import io.github.imzmq.interview.observability.core.RAGTraceContext;
import io.github.imzmq.interview.skill.core.SkillExecutionBudget;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 组合现有改写、召回、Grounding、质量判断和 Web fallback；不再回调兼容门面。 */
@Service
public class KnowledgePacketBuilder {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgePacketBuilder.class);

    private final QueryRewriteService queryRewriteService;
    private final EvidenceEvaluationService evidenceEvaluationService;
    private final WebFallbackService webFallbackService;
    private final SkillOrchestrator skillOrchestrator;
    private final ObservabilitySwitchProperties observabilitySwitchProperties;
    private final MediaImageOperations imageService;
    private final HybridEvidenceRetriever retriever;
    private final EvidenceFusionService fusion;
    private final GroundingContextAssembler grounding;
    private final RagTraceOperations ragTraceOperations;

    public KnowledgePacketBuilder(QueryRewriteService queryRewriteService,
                                  EvidenceEvaluationService evidenceEvaluationService,
                                  WebFallbackService webFallbackService,
                                  SkillOrchestrator skillOrchestrator,
                                  ObservabilitySwitchProperties observabilitySwitchProperties,
                                  @org.springframework.lang.Nullable MediaImageOperations imageService,
                                  HybridEvidenceRetriever retriever, EvidenceFusionService fusion,
                                  GroundingContextAssembler grounding, RagTraceOperations ragTraceOperations) {
        this.queryRewriteService = queryRewriteService;
        this.evidenceEvaluationService = evidenceEvaluationService;
        this.webFallbackService = webFallbackService;
        this.skillOrchestrator = skillOrchestrator;
        this.observabilitySwitchProperties = observabilitySwitchProperties;
        this.imageService = imageService;
        this.retriever = retriever;
        this.fusion = fusion;
        this.grounding = grounding;
        this.ragTraceOperations = ragTraceOperations;
    }

    public RAGService.KnowledgePacket build(String question,
                                            String userAnswer,
                                            boolean allowWebFallback) {
        String traceId = RAGTraceContext.getTraceId();
        String parentNodeId = RAGTraceContext.getCurrentNodeId();
        SkillExecutionBudget skillBudget = skillOrchestrator.newBudget();
        TraceNodeHandle rewriteTrace = startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.QUERY_REWRITE, Map.of("status", "RUNNING"));
        RewrittenQuery rewrittenQuery = queryRewriteService.buildRewrittenQuery(question, userAnswer, skillBudget);
        completeTraceSuccess(rewriteTrace, Map.of("status", "COMPLETED"));
        if (observabilitySwitchProperties.isRagTraceEnabled()) {
            logger.info("Rewritten Query: CORE=[{}] EXPAND=[{}]", rewrittenQuery.coreTerms(), rewrittenQuery.expandTerms());
        }

        TraceNodeHandle docRetrieveTrace = startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.DOC_RETRIEVE, Map.of("status", "RUNNING"));
        List<Document> retrievedDocs = retriever.retrieveHybridDocuments(rewrittenQuery, 5);
        String context = grounding.buildContext(retrievedDocs);
        double bestRetrievalScore = fusion.bestRetrievalScore(rewrittenQuery.fullQuery(), retrievedDocs);
        completeTraceSuccess(
                docRetrieveTrace,
                Map.of(
                        "docCount", retrievedDocs.size(),
                        "docRefs", grounding.summarizeRetrievedDocuments(retrievedDocs),
                        "status", "COMPLETED"
                ),
                new TraceNodeMetrics(retrievedDocs.size(), false),
                new TraceNodeDetails(
                        1,
                        KnowledgeRetrievalMode.RAG_ONLY.name(),
                        null,
                        false,
                        retrievedDocs.size(),
                        grounding.summarizeRetrievedDocuments(retrievedDocs),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );

        TraceNodeHandle associatedImageTrace = startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.IMAGE_ASSOC_RETRIEVE, Map.of("status", "RUNNING"));
        List<ImageResult> associatedImages = imageService == null
                ? List.of()
                : imageService.findImagesForDocuments(toMediaDocuments(retrievedDocs));
        completeTraceSuccess(associatedImageTrace, Map.of(
                "imageCount", associatedImages.size(),
                "status", "COMPLETED"
        ), null, new TraceNodeDetails(
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

        TraceNodeHandle semanticImageTrace = startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.IMAGE_SEMANTIC_RETRIEVE, Map.of("status", "RUNNING"));
        List<ImageResult> semanticImages = imageService == null ? List.of() : imageService.searchRelevantImages(rewrittenQuery.fullQuery(), grounding.containsVisualIntent(rewrittenQuery.fullQuery()));
        completeTraceSuccess(semanticImageTrace, Map.of(
                "imageCount", semanticImages.size(),
                "status", "COMPLETED"
        ), null, new TraceNodeDetails(
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

        List<ImageResult> retrievedImages = grounding.mergeImageResults(associatedImages, semanticImages);
        String imageContext = KnowledgeRetrievalCoordinator.buildImageContext(retrievedImages);
        String retrievalEvidence = grounding.buildRetrievalEvidence(retrievedDocs);
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
            TraceNodeHandle webFallbackTrace = startTraceChild(traceId, parentNodeId, TraceNodeDefinitions.WEB_FALLBACK, Map.of("fallback", true, "status", "RUNNING"));
            List<String> webContext = webFallbackService.search(rewrittenQuery.fullQuery(), traceId, evidenceDecision.reason(), skillBudget);
            context = grounding.buildWebContext(webContext);
            retrievalEvidence = grounding.buildWebEvidence(webContext);
            webFallbackUsed = true;
            completeTraceSuccess(
                    webFallbackTrace,
                    Map.of(
                            "fallback", true,
                            "fallbackReason", "LOW_RETRIEVAL_QUALITY",
                            "docCount", webContext.size(),
                            "status", "COMPLETED"
                    ),
                    new TraceNodeMetrics(0, true),
                    new TraceNodeDetails(
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

    private TraceNodeHandle startTraceChild(String traceId,
                                            String parentNodeId,
                                            TraceNodeDefinition definition,
                                            Map<String, Object> attributes) {
        String safeParentNodeId = parentNodeId == null ? RAGTraceContext.getCurrentNodeId() : parentNodeId;
        String nodeId = UUID.randomUUID().toString();
        ragTraceOperations.startNode(traceId, nodeId, safeParentNodeId, definition.nodeType(), definition.nodeName());
        return new TraceNodeHandle(traceId, nodeId, safeParentNodeId, definition);
    }

    private void completeTraceSuccess(TraceNodeHandle handle, Map<String, Object> result) {
        completeTraceSuccess(handle, result, null, null);
    }

    private void completeTraceSuccess(TraceNodeHandle handle,
                                     Map<String, Object> result,
                                     TraceNodeMetrics metrics,
                                     TraceNodeDetails details) {
        ragTraceOperations.endNode(
                handle.traceId(),
                handle.nodeId(),
                "",
                result == null ? "" : result.toString(),
                null,
                metrics,
                details
        );
    }

    private List<MediaDocument> toMediaDocuments(List<Document> documents) {
        return documents.stream()
                .map(document -> new MediaDocument(document.getId(), document.getText(), document.getMetadata()))
                .toList();
    }
}
