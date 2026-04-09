package com.example.interview.service.knowledge;

import com.example.interview.modelrouting.ModelRouteType;
import com.example.interview.modelrouting.RoutingChatService;
import com.example.interview.service.PromptManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ConversationTopicTracker {
    private static final Logger log = LoggerFactory.getLogger(ConversationTopicTracker.class);
    private static final String TOPIC_STATE_KEY_PREFIX = "chat:topic:state:";
    private static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_TOPICS = 10;
    private static final int TOPIC_WINDOW = 3;
    private static final int HISTORY_LIMIT = 800;
    private static final int DIGEST_CONTEXT_LIMIT = 1500;
    private static final int DIGEST_MAX_LENGTH = 600;

    private final StringRedisTemplate redisTemplate;
    private final RoutingChatService routingChatService;
    private final PromptManager promptManager;
    private final ObjectMapper objectMapper;
    private final AtomicLong turnCounter = new AtomicLong(0);

    public ConversationTopicTracker(StringRedisTemplate redisTemplate,
                                    RoutingChatService routingChatService,
                                    PromptManager promptManager,
                                    ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.routingChatService = routingChatService;
        this.promptManager = promptManager;
        this.objectMapper = objectMapper;
    }

    public TurnAnalysis analyzeTurn(String sessionId, String currentQuestion, String recentHistory) {
        List<TopicState> recentTopics = getRecentTopics(sessionId, TOPIC_WINDOW);
        if (recentTopics.isEmpty() && (recentHistory == null || recentHistory.isBlank())) {
            return TurnAnalysis.firstTurn(extractSimpleTopic(currentQuestion));
        }

        try {
            String previousTopic = recentTopics.isEmpty() ? "" : recentTopics.get(0).topicId();
            StringBuilder topicList = new StringBuilder();
            for (TopicState topic : recentTopics) {
                topicList.append("- ")
                        .append(topic.topicId())
                        .append(" (")
                        .append(topic.turnCount())
                        .append("轮)\n");
            }

            Map<String, Object> vars = new HashMap<>();
            vars.put("currentQuestion", currentQuestion == null ? "" : currentQuestion);
            vars.put("recentHistory", truncate(recentHistory, HISTORY_LIMIT));
            vars.put("previousTopic", previousTopic);
            vars.put("topicList", topicList.toString());

            PromptManager.PromptPair pair = promptManager.renderSplit("turn-analyzer", "turn-analyzer", vars);
            String raw = routingChatService.call(pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, "轮次分析");
            return parseTurnAnalysis(raw, previousTopic, currentQuestion);
        } catch (Exception ex) {
            log.warn("轮次分析失败，降级为话题延续: sessionId={}", sessionId, ex);
            String fallbackTopic = recentTopics.isEmpty()
                    ? extractSimpleTopic(currentQuestion)
                    : recentTopics.get(0).topicId();
            return TurnAnalysis.defaultContinuation(fallbackTopic);
        }
    }

    public void updateTopicState(String sessionId, TurnAnalysis analysis, String knowledgeDigest) {
        long turnId = turnCounter.incrementAndGet();
        List<TopicState> topics = getRecentTopics(sessionId, MAX_TOPICS);
        String normalizedDigest = normalizeDigest(knowledgeDigest);

        if (analysis.topicSwitch() && analysis.dialogAct() != DialogAct.RETURN) {
            topics.add(0, new TopicState(analysis.currentTopic(), normalizedDigest, 1, turnId));
        } else if (analysis.dialogAct() == DialogAct.RETURN) {
            TopicState returned = null;
            for (int i = 0; i < topics.size(); i++) {
                if (topicMatches(topics.get(i).topicId(), analysis.currentTopic())) {
                    returned = topics.remove(i).withIncrementedTurn(turnId);
                    returned = mergeDigest(returned, normalizedDigest);
                    break;
                }
            }
            if (returned == null) {
                returned = new TopicState(analysis.currentTopic(), normalizedDigest, 1, turnId);
            }
            topics.add(0, returned);
        } else if (!topics.isEmpty()) {
            TopicState current = topics.get(0).withIncrementedTurn(turnId);
            current = mergeDigest(current, normalizedDigest);
            topics.set(0, current);
        } else {
            topics.add(new TopicState(analysis.currentTopic(), normalizedDigest, 1, turnId));
        }

        if (topics.size() > MAX_TOPICS) {
            topics = new ArrayList<>(topics.subList(0, MAX_TOPICS));
        }
        saveTopicState(sessionId, topics);
    }

    public String getTopicKnowledgeDigest(String sessionId, String topicId) {
        for (TopicState topic : getRecentTopics(sessionId, MAX_TOPICS)) {
            if (topicMatches(topic.topicId(), topicId)) {
                return topic.knowledgeDigest();
            }
        }
        return "";
    }

    public List<TopicState> getRecentTopics(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return new ArrayList<>();
        }
        try {
            String json = redisTemplate.opsForValue().get(TOPIC_STATE_KEY_PREFIX + sessionId);
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            List<TopicState> topics = objectMapper.readValue(json, new TypeReference<List<TopicState>>() {
            });
            return new ArrayList<>(topics.subList(0, Math.min(topics.size(), limit)));
        } catch (Exception ex) {
            log.warn("读取话题状态失败: sessionId={}", sessionId, ex);
            return new ArrayList<>();
        }
    }

    @Async("ragRetrieveExecutor")
    public void generateDigestAsync(String sessionId, TurnAnalysis analysis, String knowledgeContext) {
        if (sessionId == null || sessionId.isBlank() || knowledgeContext == null || knowledgeContext.isBlank()) {
            return;
        }
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("topic", analysis.currentTopic());
            vars.put("knowledgeContext", truncate(knowledgeContext, DIGEST_CONTEXT_LIMIT));

            PromptManager.PromptPair pair = promptManager.renderSplit("knowledge-digest", "knowledge-digest", vars);
            String digest = routingChatService.call(pair.systemPrompt(), pair.userPrompt(), ModelRouteType.GENERAL, "知识摘要");
            if (digest != null && !digest.isBlank()) {
                updateTopicState(sessionId, analysis, digest.trim());
            }
        } catch (Exception ex) {
            log.warn("异步知识摘要生成失败: sessionId={}, topic={}", sessionId, analysis.currentTopic(), ex);
        }
    }

    private TurnAnalysis parseTurnAnalysis(String raw, String previousTopic, String currentQuestion) {
        if (raw == null || raw.isBlank()) {
            return TurnAnalysis.defaultContinuation(resolveTopic(previousTopic, currentQuestion));
        }
        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "")
                        .replaceAll("\\s*```$", "")
                        .trim();
            }
            Map<String, Object> parsed = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {
            });
            boolean topicSwitch = Boolean.TRUE.equals(parsed.get("topicSwitch"));
            DialogAct dialogAct = DialogAct.fromString(parsed.get("dialogAct") instanceof String value ? value : null);
            double infoNovelty = 0.5;
            Object noveltyObj = parsed.get("infoNovelty");
            if (noveltyObj instanceof Number number) {
                infoNovelty = Math.max(0.0, Math.min(1.0, number.doubleValue()));
            }
            String currentTopic = parsed.get("currentTopic") instanceof String value && !value.isBlank()
                    ? value.trim()
                    : resolveTopic(previousTopic, currentQuestion);
            return new TurnAnalysis(topicSwitch, dialogAct, infoNovelty, currentTopic, previousTopic == null ? "" : previousTopic);
        } catch (Exception ex) {
            log.warn("轮次分析 JSON 解析失败，降级为话题延续: raw={}", raw, ex);
            return TurnAnalysis.defaultContinuation(resolveTopic(previousTopic, currentQuestion));
        }
    }

    private TopicState mergeDigest(TopicState topicState, String knowledgeDigest) {
        if (knowledgeDigest == null || knowledgeDigest.isBlank()) {
            return topicState;
        }
        String existing = topicState.knowledgeDigest();
        String merged = existing == null || existing.isBlank()
                ? knowledgeDigest
                : existing + "\n" + knowledgeDigest;
        if (merged.length() > DIGEST_MAX_LENGTH) {
            merged = merged.substring(merged.length() - DIGEST_MAX_LENGTH);
        }
        return topicState.withDigest(merged);
    }

    private String normalizeDigest(String knowledgeDigest) {
        if (knowledgeDigest == null || knowledgeDigest.isBlank()) {
            return "";
        }
        String normalized = knowledgeDigest.trim();
        if (normalized.length() > DIGEST_MAX_LENGTH) {
            return normalized.substring(normalized.length() - DIGEST_MAX_LENGTH);
        }
        return normalized;
    }

    private String resolveTopic(String previousTopic, String currentQuestion) {
        return previousTopic != null && !previousTopic.isBlank()
                ? previousTopic
                : extractSimpleTopic(currentQuestion);
    }

    private String extractSimpleTopic(String question) {
        if (question == null || question.isBlank()) {
            return "未知话题";
        }
        String trimmed = question.trim();
        return trimmed.length() <= 20 ? trimmed : trimmed.substring(0, 20) + "...";
    }

    private boolean topicMatches(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.equalsIgnoreCase(right) || left.contains(right) || right.contains(left);
    }

    private void saveTopicState(String sessionId, List<TopicState> topics) {
        try {
            String json = objectMapper.writeValueAsString(topics);
            redisTemplate.opsForValue().set(TOPIC_STATE_KEY_PREFIX + sessionId, json, TTL);
        } catch (Exception ex) {
            log.warn("保存话题状态失败: sessionId={}", sessionId, ex);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(text.length() - maxLen);
    }
}
