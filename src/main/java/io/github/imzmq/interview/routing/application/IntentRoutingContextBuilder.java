package io.github.imzmq.interview.routing.application;

import io.github.imzmq.interview.knowledge.application.context.ConversationTopicTracker;
import io.github.imzmq.interview.knowledge.domain.TopicState;
import io.github.imzmq.interview.knowledge.domain.TurnAnalysis;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class IntentRoutingContextBuilder {

    private static final int RECENT_TOPIC_LIMIT = 3;
    private static final int RECENT_HISTORY_LIMIT = 240;

    private final ConversationTopicTracker conversationTopicTracker;

    public IntentRoutingContextBuilder(ConversationTopicTracker conversationTopicTracker) {
        this.conversationTopicTracker = conversationTopicTracker;
    }

    public String build(String sessionId, String currentQuestion, String recentHistory) {
        return buildSnapshot(sessionId, currentQuestion, recentHistory).summary();
    }

    public RoutingContextSnapshot buildSnapshot(String sessionId, String currentQuestion, String recentHistory) {
        String sanitizedHistory = sanitizeRecentHistory(currentQuestion, recentHistory);
        TurnAnalysis analysis = analyzeSafely(sessionId, currentQuestion, sanitizedHistory);
        List<TopicState> recentTopics = conversationTopicTracker.getRecentTopics(sessionId, RECENT_TOPIC_LIMIT);

        List<String> lines = new ArrayList<>();
        lines.add("【路由摘要】");
        lines.add("- currentTopic: " + text(analysis.currentTopic(), "未知"));
        lines.add("- previousTopic: " + text(analysis.previousTopic(), "无"));
        lines.add("- dialogAct: " + analysis.dialogAct().name());
        lines.add("- topicSwitch: " + analysis.topicSwitch());
        lines.add(String.format("- infoNovelty: %.2f", analysis.infoNovelty()));

        if (!recentTopics.isEmpty()) {
            StringBuilder topicLine = new StringBuilder("- recentTopics: ");
            for (int i = 0; i < recentTopics.size(); i++) {
                TopicState topic = recentTopics.get(i);
                if (i > 0) {
                    topicLine.append(" | ");
                }
                topicLine.append(topic.topicId()).append("(").append(topic.turnCount()).append("轮)");
            }
            lines.add(topicLine.toString());

            TopicState latestTopic = recentTopics.get(0);
            if (latestTopic.knowledgeDigest() != null && !latestTopic.knowledgeDigest().isBlank()) {
                lines.add("- latestTopicDigest: " + truncateInline(latestTopic.knowledgeDigest(), 120));
            }
        }

        if (!sanitizedHistory.isBlank()) {
            lines.add("- recentConversation: " + truncateInline(sanitizedHistory, RECENT_HISTORY_LIMIT));
        }

        return new RoutingContextSnapshot(
                String.join("\n", lines),
                analysis.topicSwitch(),
                analysis.dialogAct().name(),
                analysis.infoNovelty(),
                text(analysis.currentTopic(), "未知"),
                text(analysis.previousTopic(), "无")
        );
    }

    public record RoutingContextSnapshot(
            String summary,
            boolean topicSwitch,
            String dialogAct,
            double infoNovelty,
            String currentTopic,
            String previousTopic
    ) {
    }

    private TurnAnalysis analyzeSafely(String sessionId, String currentQuestion, String sanitizedHistory) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return TurnAnalysis.firstTurn(fallbackTopic(currentQuestion));
            }
            return conversationTopicTracker.analyzeTurn(sessionId, currentQuestion, sanitizedHistory);
        } catch (Exception ignored) {
            return TurnAnalysis.firstTurn(fallbackTopic(currentQuestion));
        }
    }

    private String sanitizeRecentHistory(String currentQuestion, String recentHistory) {
        if (recentHistory == null || recentHistory.isBlank()) {
            return "";
        }
        String sanitized = recentHistory
                .replace("AI: 正在生成中...", "")
                .replace("AI: 正在生成中…", "")
                .replaceAll("(?m)^\\s*$\\R?", "")
                .trim();
        if (sanitized.isBlank()) {
            return "";
        }
        String normalizedQuestion = normalize(currentQuestion);
        List<String> keptLines = new ArrayList<>();
        for (String line : sanitized.split("\\R+")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (normalizedQuestion.equals(normalize(trimmed))
                    || normalizedQuestion.equals(normalize(trimmed.replaceFirst("^(User|AI):\\s*", "")))) {
                continue;
            }
            keptLines.add(trimmed);
        }
        return String.join("\n", keptLines);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\p{Punct}\\p{IsPunctuation}！？。，“”‘’：；、\\s]+", "")
                .trim()
                .toLowerCase();
    }

    private String fallbackTopic(String currentQuestion) {
        if (currentQuestion == null || currentQuestion.isBlank()) {
            return "未知话题";
        }
        String trimmed = currentQuestion.trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
    }

    private String truncateInline(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= maxLength) {
            return singleLine;
        }
        return singleLine.substring(0, maxLength) + "...";
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}






