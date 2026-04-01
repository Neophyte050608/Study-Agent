package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.entity.LearningEventDO;
import com.example.interview.entity.LearningProfileDO;
import com.example.interview.mapper.LearningEventMapper;
import com.example.interview.mapper.LearningProfileMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class LearningProfileAgent {

    private static final String DEFAULT_USER = "local-user";
    private static final int EVENT_LIMIT_PER_USER = 300;
    private static final int RANK_LIMIT = 8;
    private static final double INTERVIEW_SOURCE_WEIGHT = 1.0;
    private static final double CODING_SOURCE_WEIGHT = 0.75;

    private final LearningProfileMapper learningProfileMapper;
    private final LearningEventMapper learningEventMapper;
    private final ObjectMapper objectMapper;

    public LearningProfileAgent(LearningProfileMapper learningProfileMapper,
                                LearningEventMapper learningEventMapper,
                                ObjectMapper objectMapper) {
        this.learningProfileMapper = learningProfileMapper;
        this.learningEventMapper = learningEventMapper;
        this.objectMapper = objectMapper;
    }

    public String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return DEFAULT_USER;
        }
        String normalized = userId.trim();
        return normalized.isBlank() ? DEFAULT_USER : normalized;
    }

    @CacheEvict(value = "learningProfiles", key = "#input.userId()")
    public boolean upsertEvent(LearningEvent input) {
        LearningEvent event = sanitizeEvent(input);
        String userId = normalizeUserId(event.userId());

        // 保存学习事件
        LearningEventDO eventDO = new LearningEventDO();
        eventDO.setEventId(event.eventId());
        eventDO.setUserId(userId);
        eventDO.setSource(event.source().name());
        eventDO.setTopic(event.topic());
        eventDO.setScore(event.score());
        eventDO.setWeakPoints(event.weakPoints());
        eventDO.setFamiliarPoints(event.familiarPoints());
        eventDO.setEvidence(event.evidence());
        eventDO.setTimestamp(LocalDateTime.ofInstant(event.timestamp(), ZoneId.systemDefault()));

        try {
            learningEventMapper.insert(eventDO);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }

        // 获取并更新画像
        LearningProfileDO profileDO = getOrCreateProfileDO(userId);
        updateProfileDO(profileDO, event);
        learningProfileMapper.updateById(profileDO);

        return true;
    }

    @Cacheable(value = "learningProfiles", key = "#userId")
    public UserProfileState getProfileState(String userId) {
        String normalizedId = normalizeUserId(userId);
        LearningProfileDO profileDO = learningProfileMapper.selectOne(
                new LambdaQueryWrapper<LearningProfileDO>().eq(LearningProfileDO::getUserId, normalizedId));

        UserProfileState state = new UserProfileState();
        if (profileDO != null) {
            state.totalEvents = profileDO.getTotalEvents() != null ? profileDO.getTotalEvents() : 0;
            state.lastUpdatedAt = profileDO.getLastUpdatedAt() != null ? profileDO.getLastUpdatedAt().toString() : "";
            if (profileDO.getTopicMetrics() != null) {
                profileDO.getTopicMetrics().forEach((k, v) -> {
                    TopicMetricState metricState = objectMapper.convertValue(v, TopicMetricState.class);
                    state.topicMetrics.put(k, metricState);
                });
            }
            
            // 加载最近的事件
            List<LearningEventDO> eventDOs = learningEventMapper.selectList(
                    new LambdaQueryWrapper<LearningEventDO>()
                            .eq(LearningEventDO::getUserId, normalizedId)
                            .orderByDesc(LearningEventDO::getTimestamp)
                            .last("LIMIT " + EVENT_LIMIT_PER_USER)
            );
            
            for (LearningEventDO eventDO : eventDOs) {
                LearningEvent event = new LearningEvent(
                        eventDO.getEventId(),
                        eventDO.getUserId(),
                        LearningSource.valueOf(eventDO.getSource()),
                        eventDO.getTopic(),
                        eventDO.getScore(),
                        eventDO.getWeakPoints(),
                        eventDO.getFamiliarPoints(),
                        eventDO.getEvidence(),
                        eventDO.getTimestamp().atZone(ZoneId.systemDefault()).toInstant()
                );
                state.events.add(event);
                state.processedEventIds.add(event.eventId());
            }
        }
        return state;
    }

    private LearningProfileDO getOrCreateProfileDO(String userId) {
        LearningProfileDO profileDO = learningProfileMapper.selectOne(
                new LambdaQueryWrapper<LearningProfileDO>().eq(LearningProfileDO::getUserId, userId));
        if (profileDO == null) {
            profileDO = new LearningProfileDO();
            profileDO.setUserId(userId);
            profileDO.setTotalEvents(0);
            profileDO.setReliabilityScore(0.0);
            profileDO.setTopicMetrics(new LinkedHashMap<>());
            learningProfileMapper.insert(profileDO);
        } else if (profileDO.getTopicMetrics() == null) {
            profileDO.setTopicMetrics(new LinkedHashMap<>());
        }
        return profileDO;
    }

    private void updateProfileDO(LearningProfileDO profileDO, LearningEvent event) {
        profileDO.setTotalEvents((profileDO.getTotalEvents() != null ? profileDO.getTotalEvents() : 0) + 1);
        
        Map<String, Object> metricsMap = profileDO.getTopicMetrics();
        TopicMetricState metric;
        if (metricsMap.containsKey(event.topic())) {
            metric = objectMapper.convertValue(metricsMap.get(event.topic()), TopicMetricState.class);
        } else {
            metric = new TopicMetricState();
        }

        double decay = decayFactor(event.timestamp());
        double sourceWeight = sourceWeight(event.source());
        double weakSignal = Math.max(0.0, (78.0 - event.score()) / 78.0) + Math.min(0.8, event.weakPoints().size() * 0.08);
        double familiarSignal = Math.max(0.0, (event.score() - 62.0) / 38.0) + Math.min(0.8, event.familiarPoints().size() * 0.08);
        
        metric.weakScore += weakSignal * sourceWeight * decay;
        metric.familiarScore += familiarSignal * sourceWeight * decay;
        metric.weightedScoreSum += event.score() * sourceWeight * decay;
        metric.weightSum += sourceWeight * decay;
        metric.attempts++;
        metric.lastEventAt = event.timestamp().toString();

        metricsMap.put(event.topic(), metric);
        profileDO.setTopicMetrics(metricsMap);
    }

    public TrainingProfileSnapshot snapshot(String userId) {
        UserProfileState profile = getProfileState(userId);
        if (profile == null || profile.totalEvents == 0) {
            return new TrainingProfileSnapshot(List.of(), List.of(), "暂无趋势数据", List.of(), List.of(), 0, "");
        }
        List<Map<String, Object>> weakRank = rankTopics(profile, true);
        List<Map<String, Object>> familiarRank = rankTopics(profile, false);
        List<String> interviewTopics = weakRank.stream().limit(3).map(item -> String.valueOf(item.get("topic"))).toList();
        List<String> codingTopics = new ArrayList<>(interviewTopics);
        familiarRank.stream().limit(2).map(item -> String.valueOf(item.get("topic"))).forEach(codingTopics::add);
        return new TrainingProfileSnapshot(
                weakRank,
                familiarRank,
                computeTrend(profile.events),
                interviewTopics,
                codingTopics.stream().distinct().limit(4).toList(),
                profile.totalEvents,
                profile.lastUpdatedAt == null ? "" : profile.lastUpdatedAt
        );
    }

    public String snapshotForPrompt(String userId, String currentTopic) {
        TrainingProfileSnapshot snapshot = snapshot(userId);
        if (snapshot.totalEvents() == 0) {
            return "暂无历史学习画像。";
        }
        String weak = snapshot.weakTopicRank().stream()
                .limit(3)
                .map(item -> String.valueOf(item.get("topic")))
                .collect(Collectors.joining("、"));
        String familiar = snapshot.familiarTopicRank().stream()
                .limit(3)
                .map(item -> String.valueOf(item.get("topic")))
                .collect(Collectors.joining("、"));
        return "当前主题：" + (currentTopic == null ? "" : currentTopic) +
                "\n弱项优先：" + (weak.isBlank() ? "暂无" : weak) +
                "\n熟项巩固：" + (familiar.isBlank() ? "暂无" : familiar) +
                "\n近期趋势：" + snapshot.recentTrend();
    }

    public String recommend(String userId, String mode) {
        TrainingProfileSnapshot snapshot = snapshot(userId);
        if (snapshot.totalEvents() == 0) {
            return "你是新用户或暂无历史记录。建议从基础面试（如 Java 核心、Spring 框架）开始，系统将逐步建立你的学习画像。";
        }
        String normalizedMode = mode == null ? "interview" : mode.trim().toLowerCase();
        List<String> topics = "coding".equals(normalizedMode)
                ? snapshot.recommendedNextCodingTopics()
                : snapshot.recommendedNextInterviewTopics();
        if (topics.isEmpty()) {
            return "暂无明显聚焦主题，建议保持基础题稳定训练。";
        }
        String joined = String.join("、", topics);
        if ("coding".equals(normalizedMode)) {
            return "建议刷题顺序：" + joined + "。配比建议：弱项优先并穿插 1 道熟项巩固题。";
        }
        return "建议面试训练主题：" + joined + "。配比建议：弱项深挖 + 熟项复盘。";
    }

    public List<Map<String, Object>> listEvents(String userId, int limit) {
        UserProfileState profile = getProfileState(userId);
        if (profile == null || profile.events.isEmpty()) {
            return List.of();
        }
        int normalizedLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        return profile.events.stream()
                .sorted(Comparator.comparing(LearningEvent::timestamp).reversed())
                .limit(normalizedLimit)
                .map(this::toEventMap)
                .toList();
    }

    public Map<String, Object> overview(String userId) {
        TrainingProfileSnapshot snapshot = snapshot(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("snapshot", snapshot);
        data.put("recommendInterview", recommend(userId, "interview"));
        data.put("recommendCoding", recommend(userId, "coding"));
        return data;
    }

    public TopicCapabilityCurve getTopicCapabilityCurve(String userId, String topic) {
        String normalizedTopic = (topic == null || topic.isBlank()) ? "未命名主题" : topic.trim();
        UserProfileState profile = getProfileState(userId);
        if (profile == null || profile.events.isEmpty()) {
            return new TopicCapabilityCurve(normalizedTopic, List.of(), List.of(), 0.0);
        }
        List<LearningEvent> topicEvents = profile.events.stream()
                .filter(event -> normalizedTopic.equals(event.topic()))
                .sorted(Comparator.comparing(LearningEvent::timestamp))
                .toList();
        if (topicEvents.isEmpty()) {
            return new TopicCapabilityCurve(normalizedTopic, List.of(), List.of(), 0.0);
        }
        List<String> timestamps = topicEvents.stream()
                .map(event -> event.timestamp().toString())
                .toList();
        List<Double> scores = topicEvents.stream()
                .map(event -> (double) event.score())
                .toList();
        double averageScore = scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return new TopicCapabilityCurve(normalizedTopic, timestamps, scores, averageScore);
    }

    private List<Map<String, Object>> rankTopics(UserProfileState profile, boolean weakRank) {
        Comparator<Map.Entry<String, TopicMetricState>> comparator = weakRank
                ? Comparator.<Map.Entry<String, TopicMetricState>>comparingDouble(entry -> entry.getValue().weakScore).reversed()
                : Comparator.<Map.Entry<String, TopicMetricState>>comparingDouble(entry -> entry.getValue().familiarScore).reversed();
        return profile.topicMetrics.entrySet().stream()
                .sorted(comparator)
                .limit(RANK_LIMIT)
                .map(entry -> {
                    TopicMetricState metric = entry.getValue();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("topic", entry.getKey());
                    item.put("score", weakRank ? metric.weakScore : metric.familiarScore);
                    item.put("averageScore", metric.weightSum <= 0 ? 0.0 : metric.weightedScoreSum / metric.weightSum);
                    item.put("attempts", metric.attempts);
                    item.put("lastEventAt", metric.lastEventAt == null ? "" : metric.lastEventAt);
                    return item;
                })
                .toList();
    }

    private String computeTrend(List<LearningEvent> events) {
        if (events == null || events.size() < 2) {
            return "样本不足";
        }
        List<LearningEvent> sorted = events.stream()
                .sorted(Comparator.comparing(LearningEvent::timestamp))
                .toList();
        int splitIndex = Math.max(1, sorted.size() / 2);
        double oldAvg = sorted.subList(0, splitIndex).stream().mapToInt(LearningEvent::score).average().orElse(0);
        double newAvg = sorted.subList(splitIndex, sorted.size()).stream().mapToInt(LearningEvent::score).average().orElse(oldAvg);
        double delta = newAvg - oldAvg;
        if (delta >= 5) {
            return "显著提升";
        }
        if (delta >= 1) {
            return "小幅提升";
        }
        if (delta <= -5) {
            return "明显下滑";
        }
        if (delta <= -1) {
            return "轻微下滑";
        }
        return "基本稳定";
    }

    private Map<String, Object> toEventMap(LearningEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", event.eventId());
        map.put("userId", event.userId());
        map.put("source", event.source().name());
        map.put("topic", event.topic());
        map.put("score", event.score());
        map.put("weakPoints", event.weakPoints());
        map.put("familiarPoints", event.familiarPoints());
        map.put("evidence", event.evidence());
        map.put("timestamp", event.timestamp().toString());
        return map;
    }

    private LearningEvent sanitizeEvent(LearningEvent input) {
        Instant now = Instant.now();
        if (input == null) {
            return new LearningEvent(
                    "evt-" + now.toEpochMilli(),
                    normalizeUserId(""),
                    LearningSource.INTERVIEW,
                    "未命名主题",
                    60,
                    List.of(),
                    List.of(),
                    "",
                    now
            );
        }
        String eventId = input.eventId() == null || input.eventId().isBlank() ? "evt-" + now.toEpochMilli() : input.eventId().trim();
        String userId = normalizeUserId(input.userId());
        LearningSource source = input.source() == null ? LearningSource.INTERVIEW : input.source();
        String topic = input.topic() == null || input.topic().isBlank() ? "未命名主题" : input.topic().trim();
        int score = Math.max(0, Math.min(100, input.score()));
        List<String> weakPoints = sanitizePoints(input.weakPoints());
        List<String> familiarPoints = sanitizePoints(input.familiarPoints());
        String evidence = input.evidence() == null ? "" : input.evidence();
        Instant timestamp = input.timestamp() == null ? now : input.timestamp();
        return new LearningEvent(eventId, userId, source, topic, score, weakPoints, familiarPoints, evidence, timestamp);
    }

    private List<String> sanitizePoints(List<String> points) {
        if (points == null) {
            return List.of();
        }
        return points.stream()
                .map(item -> item == null ? "" : item.trim())
                .filter(item -> !item.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .limit(8)
                .toList();
    }

    private double sourceWeight(LearningSource source) {
        return source == LearningSource.CODING ? CODING_SOURCE_WEIGHT : INTERVIEW_SOURCE_WEIGHT;
    }

    private double decayFactor(Instant timestamp) {
        long days = Math.max(0, Duration.between(timestamp, Instant.now()).toDays());
        double factor = 1.0 / (1.0 + days / 14.0);
        return Math.max(0.2, Math.min(1.0, factor));
    }

    public static class UserProfileState {
        public Map<String, TopicMetricState> topicMetrics = new LinkedHashMap<>();
        public List<LearningEvent> events = new ArrayList<>();
        public java.util.Set<String> processedEventIds = new java.util.HashSet<>();
        public int totalEvents;
        public String lastUpdatedAt = "";
    }

    public static class TopicMetricState {
        public int attempts;
        public double weakScore;
        public double familiarScore;
        public double weightedScoreSum;
        public double weightSum;
        public String lastEventAt = "";
    }

    public record TopicCapabilityCurve(
            String topic,
            List<String> timestamps,
            List<Double> scores,
            double averageScore
    ) {
    }
}
