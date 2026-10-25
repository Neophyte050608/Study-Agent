package io.github.imzmq.interview.knowledge.application.contract;

import io.github.imzmq.interview.knowledge.application.contract.result.KnowledgeInterviewReportEntry;

import java.util.List;

/** Public Knowledge generation use cases used by the interview module. */
public interface KnowledgeInterviewOperations {
    String generateFirstQuestion(
            String resumeContent,
            String topic,
            String profileSnapshot,
            boolean skipIntro,
            List<String> excludedTopics
    );

    String generateFinalReport(
            String topic,
            List<KnowledgeInterviewReportEntry> history,
            String targetedSuggestion,
            String rollingSummary
    );
}
