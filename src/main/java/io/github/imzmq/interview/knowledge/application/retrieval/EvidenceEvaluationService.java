package io.github.imzmq.interview.knowledge.application.retrieval;

import io.github.imzmq.interview.config.knowledge.RagRetrievalProperties;
import io.github.imzmq.interview.skill.core.SkillExecutionBudget;
import io.github.imzmq.interview.skill.core.SkillExecutionContext;
import io.github.imzmq.interview.skill.core.SkillExecutionResult;
import io.github.imzmq.interview.skill.runtime.SkillOrchestrator;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EvidenceEvaluationService {

    private final SkillOrchestrator skillOrchestrator;
    private final RagRetrievalProperties ragRetrievalProperties;

    public EvidenceEvaluationService(SkillOrchestrator skillOrchestrator,
                                     RagRetrievalProperties ragRetrievalProperties) {
        this.skillOrchestrator = skillOrchestrator;
        this.ragRetrievalProperties = ragRetrievalProperties;
    }

    public EvidenceDecision decide(RewrittenQuery rewrittenQuery,
                                   List<Document> retrievedDocs,
                                   String context,
                                   double bestRetrievalScore,
                                   boolean allowWebFallback,
                                   String traceId,
                                   SkillExecutionBudget skillBudget) {
        SkillExecutionResult result = skillOrchestrator.execute(
                "evidence-evaluator",
                new SkillExecutionContext(
                        traceId,
                        "rag-service",
                        Map.of(
                                "retrievalQuery", rewrittenQuery == null ? "" : rewrittenQuery.fullQuery(),
                                "retrievedDocs", retrievedDocs == null ? List.of() : retrievedDocs,
                                "context", context == null ? "" : context,
                                "bestRetrievalScore", bestRetrievalScore,
                                "allowWebFallback", allowWebFallback
                        ),
                        skillBudget
                )
        );
        if (result.succeeded()) {
            return new EvidenceDecision(result.boolOutput("allowExternalLookup"), result.textOutput("reason"));
        }
        return new EvidenceDecision(
                shouldUseWebFallback(allowWebFallback, retrievedDocs, context, bestRetrievalScore),
                "LEGACY_WEB_FALLBACK"
        );
    }

    private boolean shouldUseWebFallback(boolean allowWebFallback,
                                         List<Document> retrievedDocs,
                                         String context,
                                         double bestRetrievalScore) {
        if (!allowWebFallback) {
            return false;
        }
        boolean groundedEvidencePresent = hasGroundedLocalEvidence(retrievedDocs);
        boolean graphOnlyContext = !groundedEvidencePresent && containsGraphHint(context);
        boolean emptyContext = !groundedEvidencePresent && ((context == null || context.isBlank()) || graphOnlyContext);
        RagRetrievalProperties.WebFallbackMode mode = ragRetrievalProperties.getWebFallbackMode();
        return switch (mode) {
            case NONE -> false;
            case ON_EMPTY -> emptyContext;
            case ON_LOW_QUALITY -> emptyContext || bestRetrievalScore < ragRetrievalProperties.getWebFallbackQualityThreshold();
        };
    }

    private boolean hasGroundedLocalEvidence(List<Document> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return false;
        }
        return retrievedDocs.stream().anyMatch(doc -> {
            if (doc == null) {
                return false;
            }
            String sourceType = String.valueOf(doc.getMetadata().getOrDefault("source_type", "")).trim().toLowerCase(Locale.ROOT);
            if ("graph_rag".equals(sourceType)) {
                return isSubstantiveGraphEvidence(doc.getText());
            }
            String text = doc.getText();
            return text != null && !text.isBlank();
        });
    }

    private boolean containsGraphHint(String context) {
        return context != null && context.contains("知识图谱关联提示");
    }

    private boolean isSubstantiveGraphEvidence(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        long conceptCount = text.chars().filter(ch -> ch == '（').count();
        return conceptCount >= 2 || text.contains("；");
    }

    public record EvidenceDecision(boolean allowExternalLookup, String reason) {
    }
}
