package io.github.imzmq.interview.knowledge.application.contract.result;

/** Minimal interview history projection needed by Knowledge report generation. */
public record KnowledgeInterviewReportEntry(
        String question,
        String answer,
        int score,
        String feedback
) {
    public KnowledgeInterviewReportEntry {
        question = question == null ? "" : question;
        answer = answer == null ? "" : answer;
        feedback = feedback == null ? "" : feedback;
    }
}
