package com.example.interview.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class LearningProfileAgent {

    private static final String PROFILE_V2_FILE = "learning_profiles_v2.json";
    private static final String LEGACY_PROFILE_FILE = "learning_profiles.json";
    private static final int EVENT_LIMIT_PER_USER = 300;
    private static final int RANK_LIMIT = 8;
    private static final double INTERVIEW_SOURCE_WEIGHT = 1.0;
    private static final double CODING_SOURCE_WEIGHT = 0.75;
    private final Object ioLock = new Object();

    private final ObjectMapper objectMapper;
    private final InterviewLearningProfileService interviewLearningProfileService;
    private final Map<String, UserProfileState> profiles = new ConcurrentHashMap<>();

    public LearningProfileAgent(ObjectMapper objectMapper, InterviewLearningProfileService interviewLearningProfileService) {
        this.objectMapper = objectMapper;
        this.interviewLearningProfileService = interviewLearningProfileService;
    }

    @PostConstruct
    public void init() {
        loadProfiles();
    }

    public String normalizeUserId(String userId) {
        return interviewLearningProfileService.normalizeUserId(userId);
    }

    public boolean upsertEvent(LearningEvent input) {
        // 统一写入入口：负责事件清洗、幂等去重、主题指标更新与持久化。
        LearningEvent event = sanitizeEvent(input);
        String userId = normalizeUserId(event.userId());
        UserProfileState profile = profiles.computeIfAbsent(userId, key -> new UserProfileState());
        synchronized (profile) {
            if (profile.processedEventIds.contains(event.eventId())) {
                return false;
            }
            profile.processedEventIds.add(event.eventId());
            profile.events.add(event);
            if (profile.events.size() > EVENT_LIMIT_PER_USER) {
                profile.events = new ArrayList<>(profile.events.subList(profile.events.size() - EVENT_LIMIT_PER_USER, profile.events.size()));
            }
            profile.totalEvents++;
            profile.lastUpdatedAt = event.timestamp().toString();
            TopicMetricState metric = profile.topicMetrics.computeIfAbsent(event.topic(), key -> new TopicMetricState());
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
        }
        saveProfiles();
        return true;
    }

    public TrainingProfileSnapshot snapshot(String userId) {
        // 快照面向“读画像”场景，输出弱项/熟项排行与推荐主题。
        UserProfileState profile = profiles.get(normalizeUserId(userId));
        if (profile == null) {
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
        // 根据模式输出差异化建议：面试偏弱项深挖，刷题偏弱项+熟项穿插。
        TrainingProfileSnapshot snapshot = snapshot(userId);
        if (snapshot.totalEvents() == 0) {
            return "暂无历史记录，建议先完成一轮面试或刷题。";
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
        UserProfileState profile = profiles.get(normalizeUserId(userId));
        if (profile == null) {
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
        // 时间衰减：越近期权重越高，历史样本保留最小影响系数。
        long days = Math.max(0, Duration.between(timestamp, Instant.now()).toDays());
        double factor = 1.0 / (1.0 + days / 14.0);
        return Math.max(0.2, Math.min(1.0, factor));
    }

    private void loadProfiles() {
        synchronized (ioLock) {
            File profileFile = new File(PROFILE_V2_FILE);
            if (profileFile.exists()) {
                try {
                    Map<String, UserProfileState> loaded = objectMapper.readValue(profileFile, new TypeReference<Map<String, UserProfileState>>() {
                    });
                    profiles.clear();
                    if (loaded != null) {
                        loaded.forEach((key, value) -> profiles.put(key, sanitizeState(value)));
                    }
                } catch (IOException ignored) {
                }
            }
            if (profiles.isEmpty()) {
                mergeLegacyProfiles();
                saveProfiles();
            }
        }
    }

    private void mergeLegacyProfiles() {
        // 兼容迁移：把旧版 learning_profiles.json 转换为 V2 结构。
        File legacy = new File(LEGACY_PROFILE_FILE);
        if (!legacy.exists()) {
            return;
        }
        try {
            Map<String, Map<String, Object>> loaded = objectMapper.readValue(legacy, new TypeReference<Map<String, Map<String, Object>>>() {
            });
            if (loaded == null) {
                return;
            }
            loaded.forEach((userId, rawProfile) -> {
                UserProfileState state = profiles.computeIfAbsent(normalizeUserId(userId), key -> new UserProfileState());
                Object topicStatsObj = rawProfile.get("topicStats");
                if (topicStatsObj instanceof Map<?, ?> topicStats) {
                    topicStats.forEach((topicKey, metricObj) -> {
                        String topic = topicKey == null ? "未命名主题" : topicKey.toString().trim();
                        if (topic.isBlank()) {
                            topic = "未命名主题";
                        }
                        TopicMetricState metric = state.topicMetrics.computeIfAbsent(topic, key -> new TopicMetricState());
                        if (metricObj instanceof Map<?, ?> metricMap) {
                            int attempts = toInt(metricMap.get("attempts"), 0);
                            double totalScore = toDouble(metricMap.get("totalScore"), 0);
                            double average = attempts <= 0 ? toDouble(metricMap.get("lastScore"), 0) : totalScore / attempts;
                            metric.attempts += attempts <= 0 ? 1 : attempts;
                            metric.weightedScoreSum += totalScore > 0 ? totalScore : average;
                            metric.weightSum += attempts <= 0 ? 1.0 : attempts;
                            metric.weakScore += Math.max(0, (75.0 - average) / 75.0) * Math.max(1, attempts);
                            metric.familiarScore += Math.max(0, (average - 60.0) / 40.0) * Math.max(1, attempts);
                            String lastEventAt = metricMap.get("recentTimestamps") instanceof List<?> times && !times.isEmpty()
                                    ? String.valueOf(times.get(times.size() - 1))
                                    : "";
                            if (!lastEventAt.isBlank()) {
                                metric.lastEventAt = lastEventAt;
                            }
                        }
                    });
                }
                List<String> weak = toStringList(rawProfile.get("weakPoints"));
                if (!weak.isEmpty()) {
                    TopicMetricState metric = state.topicMetrics.computeIfAbsent("历史薄弱项", key -> new TopicMetricState());
                    metric.weakScore += weak.size() * 0.2;
                    metric.attempts += weak.size();
                }
                List<String> blind = toStringList(rawProfile.get("blindSpots"));
                if (!blind.isEmpty()) {
                    TopicMetricState metric = state.topicMetrics.computeIfAbsent("历史盲区", key -> new TopicMetricState());
                    metric.weakScore += blind.size() * 0.2;
                    metric.attempts += blind.size();
                }
                state.totalEvents = Math.max(state.totalEvents, toInt(rawProfile.get("totalSessions"), 0));
                Object updatedAt = rawProfile.get("lastUpdatedAt");
                if (updatedAt != null && !updatedAt.toString().isBlank()) {
                    state.lastUpdatedAt = updatedAt.toString();
                }
            });
        } catch (IOException ignored) {
        }
    }

    private UserProfileState sanitizeState(UserProfileState state) {
        UserProfileState target = state == null ? new UserProfileState() : state;
        if (target.topicMetrics == null) {
            target.topicMetrics = new ConcurrentHashMap<>();
        }
        if (target.events == null) {
            target.events = new ArrayList<>();
        }
        if (target.processedEventIds == null) {
            target.processedEventIds = ConcurrentHashMap.newKeySet();
        }
        target.events = new ArrayList<>(target.events.stream().map(this::sanitizeEvent).toList());
        target.processedEventIds.addAll(target.events.stream().map(LearningEvent::eventId).toList());
        target.topicMetrics = target.topicMetrics.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> sanitizeMetric(entry.getValue()),
                        (left, right) -> right,
                        ConcurrentHashMap::new
                ));
        return target;
    }

    private TopicMetricState sanitizeMetric(TopicMetricState state) {
        TopicMetricState target = state == null ? new TopicMetricState() : state;
        target.attempts = Math.max(0, target.attempts);
        target.weakScore = Math.max(0, target.weakScore);
        target.familiarScore = Math.max(0, target.familiarScore);
        target.weightedScoreSum = Math.max(0, target.weightedScoreSum);
        target.weightSum = Math.max(0, target.weightSum);
        if (target.lastEventAt == null) {
            target.lastEventAt = "";
        }
        return target;
    }

    private void saveProfiles() {
        synchronized (ioLock) {
            try {
                Path target = Path.of(PROFILE_V2_FILE);
                Path temp = Path.of(PROFILE_V2_FILE + ".tmp");
                objectMapper.writeValue(temp.toFile(), profiles);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
            }
        }
    }

    private List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(item -> item == null ? "" : item.toString().trim())
                .filter(item -> !item.isBlank())
                .toList();
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private double toDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static class UserProfileState {
        public Map<String, TopicMetricState> topicMetrics = new ConcurrentHashMap<>();
        public List<LearningEvent> events = new ArrayList<>();
        public Set<String> processedEventIds = ConcurrentHashMap.newKeySet();
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
}
