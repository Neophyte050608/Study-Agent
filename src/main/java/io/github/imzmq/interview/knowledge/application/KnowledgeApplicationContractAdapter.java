package io.github.imzmq.interview.knowledge.application;

import io.github.imzmq.interview.knowledge.application.contract.KnowledgeInterviewOperations;
import io.github.imzmq.interview.learning.application.contract.extension.LearningPlanGenerator;
import io.github.imzmq.interview.knowledge.application.contract.KnowledgeRetrievalOperations;
import io.github.imzmq.interview.knowledge.application.contract.result.KnowledgeInterviewReportEntry;
import io.github.imzmq.interview.knowledge.application.contract.result.KnowledgePacket;
import org.springframework.stereotype.Service;

import java.util.List;

/** Keeps the legacy RAG facade internal while exposing framework-neutral Knowledge contracts. */
@Service
public class KnowledgeApplicationContractAdapter implements
        KnowledgeRetrievalOperations,
        KnowledgeInterviewOperations,
        LearningPlanGenerator {

    private final RAGService ragService;

    public KnowledgeApplicationContractAdapter(RAGService ragService) {
        this.ragService = ragService;
    }

    @Override
    public KnowledgePacket buildKnowledgePacket(String question, String userAnswer) {
        RAGService.KnowledgePacket packet = ragService.buildKnowledgePacket(question, userAnswer);
        if (packet == null) {
            return new KnowledgePacket("", 0, "", "", "", false);
        }
        return new KnowledgePacket(
                packet.retrievalQuery(),
                packet.retrievedDocs() == null ? 0 : packet.retrievedDocs().size(),
                packet.context(),
                packet.imageContext(),
                packet.retrievalEvidence(),
                packet.webFallbackUsed()
        );
    }

    @Override
    public String generateFirstQuestion(
            String resumeContent,
            String topic,
            String profileSnapshot,
            boolean skipIntro,
            List<String> excludedTopics
    ) {
        return ragService.generateFirstQuestion(resumeContent, topic, profileSnapshot, skipIntro, excludedTopics);
    }

    @Override
    public String generateFinalReport(
            String topic,
            List<KnowledgeInterviewReportEntry> history,
            String targetedSuggestion,
            String rollingSummary
    ) {
        return ragService.generateFinalReport(topic, history, targetedSuggestion, rollingSummary);
    }

    @Override
    public String generateLearningPlan(String topic, String weakPoint, String recentPerformance) {
        return ragService.generateLearningPlan(topic, weakPoint, recentPerformance);
    }
}
