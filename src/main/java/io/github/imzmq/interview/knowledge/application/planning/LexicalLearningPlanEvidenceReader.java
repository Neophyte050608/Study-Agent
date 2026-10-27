package io.github.imzmq.interview.knowledge.application.planning;

import io.github.imzmq.interview.knowledge.application.indexing.LexicalIndexService;
import io.github.imzmq.interview.learning.application.contract.extension.LearningPlanEvidenceReader;
import io.github.imzmq.interview.learning.application.contract.result.LearningPlanEvidence;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** First preview slice deliberately avoids nested Skill/MCP execution and model query rewriting. */
@Service
public final class LexicalLearningPlanEvidenceReader implements LearningPlanEvidenceReader {
    private final LexicalIndexService lexical;

    public LexicalLearningPlanEvidenceReader(LexicalIndexService lexical) { this.lexical = lexical; }

    @Override
    public List<LearningPlanEvidence> read(String topic) {
        Map<String, LearningPlanEvidence> references = new LinkedHashMap<>();
        for (var doc : lexical.search(topic, 4)) {
            if (doc.getId() == null || doc.getId().isBlank() || doc.getText() == null || doc.getText().isBlank()) continue;
            String path = String.valueOf(doc.getMetadata().getOrDefault("file_path", "知识材料"));
            String title = path.replace('\\', '/');
            title = title.substring(title.lastIndexOf('/') + 1);
            references.putIfAbsent(doc.getId(), new LearningPlanEvidence(doc.getId(), clip(title, 120), clip(doc.getText(), 1200)));
            if (references.size() == 4) break;
        }
        return List.copyOf(references.values());
    }

    private String clip(String text, int max) { return text.substring(0, Math.min(text.length(), max)); }
}
