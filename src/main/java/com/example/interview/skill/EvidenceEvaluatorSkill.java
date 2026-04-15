package com.example.interview.skill;

import com.example.interview.config.RagRetrievalProperties;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class EvidenceEvaluatorSkill implements ExecutableSkill {

    private static final SkillDefinition DEFINITION = new SkillDefinition(
            "evidence-evaluator",
            "评估本地证据质量，决定是否允许触发受控外部检索。",
            SkillExecutionMode.WORKFLOW,
            List.of("web.search"),
            new SkillFailurePolicy(1, 800L, 0L, 5, 60000L, SkillFailureFallbackMode.SKIP_SKILL)
    );

    private final RagRetrievalProperties ragRetrievalProperties;

    public EvidenceEvaluatorSkill(RagRetrievalProperties ragRetrievalProperties) {
        this.ragRetrievalProperties = ragRetrievalProperties;
    }

    @Override
    public SkillDefinition definition() {
        return DEFINITION;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionContext context) {
        boolean allowWebFallback = context != null && context.bool("allowWebFallback");
        String query = context == null ? "" : context.text("retrievalQuery");
        String joinedContext = context == null ? "" : context.text("context");
        List<?> docs = context == null ? List.of() : context.list("retrievedDocs");
        double bestRetrievalScore = context == null ? 0.0D : context.doubleValue("bestRetrievalScore", 0.0D);
        int evidenceCount = docs == null ? 0 : docs.size();
        boolean groundedEvidencePresent = hasGroundedLocalEvidence(docs);
        boolean graphOnlyContext = !groundedEvidencePresent && containsGraphHint(joinedContext);
        boolean emptyContext = !groundedEvidencePresent && ((joinedContext == null || joinedContext.isBlank()) || graphOnlyContext);
        RagRetrievalProperties.WebFallbackMode mode = ragRetrievalProperties.getWebFallbackMode();

        boolean allowExternalLookup = false;
        String reason = "LOCAL_EVIDENCE_SUFFICIENT";
        if (!allowWebFallback || mode == RagRetrievalProperties.WebFallbackMode.NONE) {
            reason = "WEB_FALLBACK_DISABLED";
        } else if (mode == RagRetrievalProperties.WebFallbackMode.ON_EMPTY && emptyContext) {
            allowExternalLookup = true;
            reason = "EMPTY_LOCAL_EVIDENCE";
        } else if (mode == RagRetrievalProperties.WebFallbackMode.ON_LOW_QUALITY
                && (emptyContext || bestRetrievalScore < ragRetrievalProperties.getWebFallbackQualityThreshold())) {
            allowExternalLookup = true;
            reason = emptyContext ? "EMPTY_LOCAL_EVIDENCE" : "LOW_RETRIEVAL_QUALITY";
        }

        return SkillExecutionResult.success(
                DEFINITION.id(),
                Map.of(
                        "allowExternalLookup", allowExternalLookup,
                        "reason", reason,
                        "bestRetrievalScore", bestRetrievalScore,
                        "evidenceCount", evidenceCount,
                        "retrievalQuery", query
                ),
                1,
                allowExternalLookup ? List.of("web.search") : List.of()
        );
    }

    private boolean hasGroundedLocalEvidence(List<?> docs) {
        if (docs == null || docs.isEmpty()) {
            return false;
        }
        return docs.stream().anyMatch(doc -> {
            if (!(doc instanceof Document document)) {
                return true;
            }
            String sourceType = String.valueOf(document.getMetadata().getOrDefault("source_type", "")).trim().toLowerCase(Locale.ROOT);
            if ("graph_rag".equals(sourceType)) {
                return isSubstantiveGraphEvidence(document.getText());
            }
            String text = document.getText();
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
}
