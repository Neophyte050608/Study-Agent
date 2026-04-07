package com.example.interview.service;

import com.example.interview.config.KnowledgeRetrievalProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 本地候选召回服务。
 *
 * <p>第一阶段使用轻量确定性规则召回，先把候选集压到可交给 Ollama 的规模。</p>
 */
@Service
public class LocalCandidateRecallService {

    private final KnowledgeRetrievalProperties properties;

    public LocalCandidateRecallService(KnowledgeRetrievalProperties properties) {
        this.properties = properties;
    }

    public List<KnowledgeMapService.KnowledgeNode> recall(String question,
                                                          KnowledgeMapService.KnowledgeMapSnapshot snapshot) {
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion.isBlank() || snapshot == null || snapshot.nodes() == null || snapshot.nodes().isEmpty()) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.CANDIDATE_RECALL_EMPTY,
                    "No candidates recalled for empty question or index"
            );
        }

        List<ScoredNode> scored = new ArrayList<>();
        for (KnowledgeMapService.KnowledgeNode node : snapshot.nodes()) {
            int score = score(normalizedQuestion, node);
            if (score > 0) {
                scored.add(new ScoredNode(node, score));
            }
        }

        if (scored.isEmpty()) {
            throw new LocalGraphRetrievalException(
                    LocalGraphFailureReason.CANDIDATE_RECALL_EMPTY,
                    "No local candidates matched the question"
            );
        }

        return scored.stream()
                .sorted(Comparator.comparingInt(ScoredNode::score).reversed()
                        .thenComparing(item -> item.node().title(), String.CASE_INSENSITIVE_ORDER))
                .limit(properties.getCandidateRecallTopN())
                .map(ScoredNode::node)
                .toList();
    }

    private int score(String question, KnowledgeMapService.KnowledgeNode node) {
        int score = 0;
        String title = normalize(node.title());
        String summary = normalize(node.summary());
        String filePath = normalize(node.filePath());

        if (!title.isBlank() && question.contains(title)) {
            score += 100;
        }
        if (!title.isBlank() && title.contains(question)) {
            score += 40;
        }
        for (String alias : node.aliases()) {
            String normalizedAlias = normalize(alias);
            if (!normalizedAlias.isBlank() && question.contains(normalizedAlias)) {
                score += 80;
            }
        }
        for (String tag : node.tags()) {
            String normalizedTag = normalize(tag);
            if (!normalizedTag.isBlank() && question.contains(normalizedTag)) {
                score += 25;
            }
        }
        for (String token : question.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (title.contains(token)) {
                score += 12;
            }
            if (summary.contains(token)) {
                score += 8;
            }
            if (filePath.contains(token)) {
                score += 4;
            }
        }
        return score;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private record ScoredNode(KnowledgeMapService.KnowledgeNode node, int score) {
    }
}
