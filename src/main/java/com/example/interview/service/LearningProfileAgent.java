package com.example.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.interview.entity.CapabilityCurveDO;
import com.example.interview.entity.LearningDecayConfigDO;
import com.example.interview.entity.LearningTrajectoryDO;
import com.example.interview.entity.TopicDifficultyLevelDO;
import com.example.interview.entity.UserKnowledgeStateDO;
import com.example.interview.mapper.CapabilityCurveMapper;
import com.example.interview.mapper.LearningDecayConfigMapper;
import com.example.interview.mapper.LearningTrajectoryMapper;
import com.example.interview.mapper.TopicDifficultyLevelMapper;
import com.example.interview.mapper.UserKnowledgeStateMapper;
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
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LearningProfileAgent {

    private static final String DEFAULT_USER = "local-user";
    private static final String DEFAULT_TOPIC = "未命名主题";
    private static final String DEFAULT_EVENT_TYPE = "ASSESSMENT";
    private static final String TREND_UP = "UP";
    private static final String TREND_DOWN = "DOWN";
    private static final String TREND_STABLE = "STABLE";
    private static final int EVENT_LIMIT_PER_USER = 300;
    private static final int CURVE_WINDOW = 50;
    private static final int RANK_LIMIT = 8;
    private static final int MAX_RECOMMEND_DAYS = 30;
    private static final int MAX_DIFFICULTY = 5;
    private static final double INTERVIEW_SOURCE_WEIGHT = 1.0;
    private static final double CODING_SOURCE_WEIGHT = 0.9;

    private final LearningTrajectoryMapper learningTrajectoryMapper;
    private final UserKnowledgeStateMapper userKnowledgeStateMapper;
    private final CapabilityCurveMapper capabilityCurveMapper;
    private final TopicDifficultyLevelMapper topicDifficultyLevelMapper;
    private final LearningDecayConfigMapper learningDecayConfigMapper;

    public LearningProfileAgent(LearningTrajectoryMapper learningTrajectoryMapper,
                                UserKnowledgeStateMapper userKnowledgeStateMapper,
                                CapabilityCurveMapper capabilityCurveMapper,
                                TopicDifficultyLevelMapper topicDifficultyLevelMapper,
                                LearningDecayConfigMapper learningDecayConfigMapper) {
        this.learningTrajectoryMapper = learningTrajectoryMapper;
        this.userKnowledgeStateMapper = userKnowledgeStateMapper;
        this.capabilityCurveMapper = capabilityCurveMapper;
        this.topicDifficultyLevelMapper = topicDifficultyLevelMapper;
        this.learningDecayConfigMapper = learningDecayConfigMapper;
    }

    public String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return DEFAULT_USER;
        }
        String normalized = userId.trim();
        return normalized.isBlank() ? DEFAULT_USER : normalized;
    }

    @CacheEvict(value = "learningProfiles", key = "#input == null || #input.userId() == null || #input.userId().isBlank() ? 'local-user' : #input.userId().trim()")
    public boolean upsertEvent(LearningEvent input) {
        LearningEvent event = sanitizeEvent(input);
        String userId = normalizeUserId(event.userId());

        LearningTrajectoryDO trajectoryDO = new LearningTrajectoryDO();
        trajectoryDO.setEventId(event.eventId());
        trajectoryDO.setUserId(userId);
        trajectoryDO.setTopic(event.topic());
        trajectoryDO.setEventType(DEFAULT_EVENT_TYPE);
        trajectoryDO.setSource(event.source().name());
        trajectoryDO.setScore(event.score());
        trajectoryDO.setWeakPoints(event.weakPoints());
        trajectoryDO.setFamiliarPoints(event.familiarPoints());
        trajectoryDO.setEvidence(event.evidence());
        trajectoryDO.setTimestamp(toLocalDateTime(event.timestamp()));

        try {
            learningTrajectoryMapper.insert(trajectoryDO);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }

        updateKnowledgeState(userId, event.topic(), event);
        return true;
    }

    @Cacheable(value = "learningProfiles", key = "#userId == null || #userId.isBlank() ? 'local-user' : #userId.trim()")
    public UserProfileState getProfileState(String userId) {
        String normalizedId = normalizeUserId(userId);
        UserProfileState state = new UserProfileState();

        List<UserKnowledgeStateDO> knowledgeStates = userKnowledgeStateMapper.selectList(
                new LambdaQueryWrapper<UserKnowledgeStateDO>()
                        .eq(UserKnowledgeStateDO::getUserId, normalizedId)
        );
        for (UserKnowledgeStateDO knowledgeState : knowledgeStates) {
            TopicMetricState metricState = new TopicMetricState();
            metricState.attempts = safeInt(knowledgeState.getAttempts());
            metricState.masteryScore = clamp01(safeDouble(knowledgeState.getMasteryScore()));
            metricState.confidence = clamp01(safeDouble(knowledgeState.getConfidence()));
            metricState.weightedAvgScore = clamp01(safeDouble(knowledgeState.getWeightedAvgScore()));
            metricState.weakScore = 1.0 - metricState.masteryScore;
            metricState.familiarScore = metricState.masteryScore;
            metricState.difficultyLevel = normalizeDifficulty(knowledgeState.getDifficultyLevel());
            metricState.capabilityLevel = resolveCapabilityLevel(metricState.masteryScore);
            metricState.lastEventAt = knowledgeState.getLastAssessedAt() == null ? "" : knowledgeState.getLastAssessedAt().toString();
            state.topicMetrics.put(knowledgeState.getTopic(), metricState);
        }

        List<LearningTrajectoryDO> trajectoryDOs = learningTrajectoryMapper.selectList(
                new LambdaQueryWrapper<LearningTrajectoryDO>()
                        .eq(LearningTrajectoryDO::getUserId, normalizedId)
                        .orderByDesc(LearningTrajectoryDO::getTimestamp)
                        .last("LIMIT " + EVENT_LIMIT_PER_USER)
        );
        for (LearningTrajectoryDO trajectoryDO : trajectoryDOs) {
            LearningEvent event = new LearningEvent(
                    trajectoryDO.getEventId(),
                    trajectoryDO.getUserId(),
                    parseSource(trajectoryDO.getSource()),
                    trajectoryDO.getTopic(),
                    safeInt(trajectoryDO.getScore()),
                    trajectoryDO.getWeakPoints() == null ? List.of() : trajectoryDO.getWeakPoints(),
                    trajectoryDO.getFamiliarPoints() == null ? List.of() : trajectoryDO.getFamiliarPoints(),
                    trajectoryDO.getEvidence() == null ? "" : trajectoryDO.getEvidence(),
                    toInstant(trajectoryDO.getTimestamp())
            );
            state.events.add(event);
            state.processedEventIds.add(event.eventId());
        }

        state.totalEvents = state.events.size();
        state.lastUpdatedAt = state.events.isEmpty() ? "" : state.events.get(0).timestamp().toString();
        return state;
    }

    public void updateKnowledgeState(String userId, String topic, LearningEvent event) {
        String normalizedUserId = normalizeUserId(userId);
        String normalizedTopic = normalizeTopic(topic);
        TopicDifficultyLevelDO difficulty = getOrCreateTopicDifficulty(normalizedTopic);
        List<LearningTrajectoryDO> topicEvents = listTopicEvents(normalizedUserId, normalizedTopic);

        double weightedAverageScore = calculateWeightedAverageScore(topicEvents, difficulty);
        double confidence = calculateConfidence(topicEvents.size());
        double recencyBonus = calculateRecencyBonus(topicEvents, difficulty);
        double masteryScore = clamp01((weightedAverageScore * 0.6) + (confidence * 0.3) + (recencyBonus * 0.1));

        UserKnowledgeStateDO stateDO = userKnowledgeStateMapper.selectOne(
                new LambdaQueryWrapper<UserKnowledgeStateDO>()
                        .eq(UserKnowledgeStateDO::getUserId, normalizedUserId)
                        .eq(UserKnowledgeStateDO::getTopic, normalizedTopic)
        );
        if (stateDO == null) {
            stateDO = new UserKnowledgeStateDO();
            stateDO.setUserId(normalizedUserId);
            stateDO.setTopic(normalizedTopic);
        }
        stateDO.setDifficultyLevel(normalizeDifficulty(difficulty.getDifficultyLevel()));
        stateDO.setMasteryScore(masteryScore);
        stateDO.setConfidence(confidence);
        stateDO.setAttempts(topicEvents.size());
        stateDO.setWeightedAvgScore(weightedAverageScore);
        stateDO.setLastAssessedAt(toLocalDateTime(event.timestamp()));

        if (stateDO.getId() == null) {
            userKnowledgeStateMapper.insert(stateDO);
        } else {
            userKnowledgeStateMapper.updateById(stateDO);
        }

        updateCapabilityCurve(normalizedUserId, normalizedTopic, topicEvents);
        updateTopicDifficultyAggregate(difficulty, masteryScore);
    }

    public double calculateMasteryScore(String userId, String topic) {
        UserKnowledgeStateDO stateDO = userKnowledgeStateMapper.selectOne(
                new LambdaQueryWrapper<UserKnowledgeStateDO>()
                        .eq(UserKnowledgeStateDO::getUserId, normalizeUserId(userId))
                        .eq(UserKnowledgeStateDO::getTopic, normalizeTopic(topic))
        );
        return stateDO == null ? 0.0 : clamp01(safeDouble(stateDO.getMasteryScore()));
    }

    public TrainingProfileSnapshot snapshot(String userId) {
        UserProfileState profile = getProfileState(userId);
        if (profile.totalEvents == 0) {
            return new TrainingProfileSnapshot(List.of(), List.of(), "暂无趋势数据", List.of(), List.of(), 0, "");
        }
        List<Map<String, Object>> weakRank = rankTopics(profile, true);
        List<Map<String, Object>> familiarRank = rankTopics(profile, false);
        List<String> interviewTopics = recommendTopics(profile, "interview", 3);
        List<String> codingTopics = recommendTopics(profile, "coding", 4);
        return new TrainingProfileSnapshot(
                weakRank,
                familiarRank,
                computeTrend(profile.events),
                interviewTopics,
                codingTopics,
                profile.totalEvents,
                profile.lastUpdatedAt
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
            return "建议刷题顺序：" + joined + "。优先修复低掌握度主题，并穿插高频难点训练。";
        }
        return "建议面试训练主题：" + joined + "。优先补弱项，再回看高难度且久未复习的主题。";
    }

    public List<Map<String, Object>> listEvents(String userId, int limit) {
        String normalizedId = normalizeUserId(userId);
        int normalizedLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        List<LearningTrajectoryDO> trajectoryDOs = learningTrajectoryMapper.selectList(
                new LambdaQueryWrapper<LearningTrajectoryDO>()
                        .eq(LearningTrajectoryDO::getUserId, normalizedId)
                        .orderByDesc(LearningTrajectoryDO::getTimestamp)
                        .last("LIMIT " + normalizedLimit)
        );
        return trajectoryDOs.stream()
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
        String normalizedUserId = normalizeUserId(userId);
        String normalizedTopic = normalizeTopic(topic);
        CapabilityCurveDO curveDO = capabilityCurveMapper.selectOne(
                new LambdaQueryWrapper<CapabilityCurveDO>()
                        .eq(CapabilityCurveDO::getUserId, normalizedUserId)
                        .eq(CapabilityCurveDO::getTopic, normalizedTopic)
        );
        if (curveDO == null) {
            return new TopicCapabilityCurve(normalizedTopic, List.of(), List.of(), 0.0);
        }
        List<String> timestamps = curveDO.getTimestampsArray() == null ? List.of() : curveDO.getTimestampsArray();
        List<Double> scores = curveDO.getScoresArray() == null
                ? List.of()
                : curveDO.getScoresArray().stream().map(Integer::doubleValue).toList();
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
                    item.put("masteryScore", metric.masteryScore);
                    item.put("averageScore", metric.weightedAvgScore * 100.0);
                    item.put("confidence", metric.confidence);
                    item.put("difficultyLevel", metric.difficultyLevel);
                    item.put("capabilityLevel", metric.capabilityLevel);
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
        double oldAvg = sorted.subList(0, splitIndex).stream().mapToInt(LearningEvent::score).average().orElse(0.0);
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

    private Map<String, Object> toEventMap(LearningTrajectoryDO event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", event.getEventId());
        map.put("userId", event.getUserId());
        map.put("topic", event.getTopic());
        map.put("eventType", event.getEventType());
        map.put("source", event.getSource());
        map.put("score", safeInt(event.getScore()));
        map.put("weakPoints", event.getWeakPoints() == null ? List.of() : event.getWeakPoints());
        map.put("familiarPoints", event.getFamiliarPoints() == null ? List.of() : event.getFamiliarPoints());
        map.put("evidence", event.getEvidence() == null ? "" : event.getEvidence());
        map.put("timestamp", event.getTimestamp() == null ? "" : event.getTimestamp().toString());
        return map;
    }

    private LearningEvent sanitizeEvent(LearningEvent input) {
        Instant now = Instant.now();
        if (input == null) {
            return new LearningEvent(
                    "evt-" + now.toEpochMilli(),
                    DEFAULT_USER,
                    LearningSource.INTERVIEW,
                    DEFAULT_TOPIC,
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
        String topic = normalizeTopic(input.topic());
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

    private List<LearningTrajectoryDO> listTopicEvents(String userId, String topic) {
        return learningTrajectoryMapper.selectList(
                new LambdaQueryWrapper<LearningTrajectoryDO>()
                        .eq(LearningTrajectoryDO::getUserId, userId)
                        .eq(LearningTrajectoryDO::getTopic, topic)
                        .orderByAsc(LearningTrajectoryDO::getTimestamp)
                        .last("LIMIT " + CURVE_WINDOW)
        );
    }

    private TopicDifficultyLevelDO getOrCreateTopicDifficulty(String topic) {
        TopicDifficultyLevelDO difficulty = topicDifficultyLevelMapper.selectOne(
                new LambdaQueryWrapper<TopicDifficultyLevelDO>()
                        .eq(TopicDifficultyLevelDO::getTopic, topic)
        );
        if (difficulty != null) {
            return difficulty;
        }
        TopicDifficultyLevelDO created = new TopicDifficultyLevelDO();
        created.setTopic(topic);
        created.setDifficultyLevel(1);
        created.setCategory("general");
        created.setPrerequisites(List.of());
        created.setAvgMasteryScore(0.0);
        created.setTotalAssessments(0);
        created.setEnabled(Boolean.TRUE);
        topicDifficultyLevelMapper.insert(created);
        return created;
    }

    private void updateTopicDifficultyAggregate(TopicDifficultyLevelDO difficulty, double masteryScore) {
        int totalAssessments = safeInt(difficulty.getTotalAssessments());
        double avgMasteryScore = safeDouble(difficulty.getAvgMasteryScore());
        double nextAvg = ((avgMasteryScore * totalAssessments) + masteryScore) / (totalAssessments + 1);
        difficulty.setTotalAssessments(totalAssessments + 1);
        difficulty.setAvgMasteryScore(nextAvg);
        topicDifficultyLevelMapper.updateById(difficulty);
    }

    private void updateCapabilityCurve(String userId, String topic, List<LearningTrajectoryDO> topicEvents) {
        CapabilityCurveDO curveDO = capabilityCurveMapper.selectOne(
                new LambdaQueryWrapper<CapabilityCurveDO>()
                        .eq(CapabilityCurveDO::getUserId, userId)
                        .eq(CapabilityCurveDO::getTopic, topic)
        );
        if (curveDO == null) {
            curveDO = new CapabilityCurveDO();
            curveDO.setUserId(userId);
            curveDO.setTopic(topic);
        }

        List<Integer> scores = topicEvents.stream()
                .map(item -> safeInt(item.getScore()))
                .toList();
        List<String> timestamps = topicEvents.stream()
                .map(item -> item.getTimestamp() == null ? "" : item.getTimestamp().toString())
                .toList();
        double trendStrength = calculateTrendStrength(scores);

        curveDO.setEventSequence(topicEvents.size());
        curveDO.setScoresArray(scores);
        curveDO.setTimestampsArray(timestamps);
        curveDO.setTrendStrength(trendStrength);
        curveDO.setTrendDirection(resolveTrendDirection(trendStrength));

        if (curveDO.getId() == null) {
            capabilityCurveMapper.insert(curveDO);
        } else {
            capabilityCurveMapper.updateById(curveDO);
        }
    }

    private double calculateWeightedAverageScore(List<LearningTrajectoryDO> topicEvents, TopicDifficultyLevelDO difficulty) {
        if (topicEvents.isEmpty()) {
            return 0.0;
        }
        double weightedScoreSum = 0.0;
        double weightSum = 0.0;
        for (LearningTrajectoryDO topicEvent : topicEvents) {
            double weight = resolveDecayFactor(topicEvent, difficulty);
            weightedScoreSum += (safeInt(topicEvent.getScore()) / 100.0) * weight;
            weightSum += weight;
        }
        return weightSum <= 0 ? 0.0 : clamp01(weightedScoreSum / weightSum);
    }

    private double calculateConfidence(int attempts) {
        if (attempts <= 0) {
            return 0.0;
        }
        return clamp01(Math.log1p(attempts) / Math.log(8));
    }

    private double calculateRecencyBonus(List<LearningTrajectoryDO> topicEvents, TopicDifficultyLevelDO difficulty) {
        if (topicEvents.isEmpty()) {
            return 0.0;
        }
        return clamp01(resolveDecayFactor(topicEvents.get(topicEvents.size() - 1), difficulty));
    }

    private double resolveDecayFactor(LearningTrajectoryDO topicEvent, TopicDifficultyLevelDO difficulty) {
        LearningDecayConfigDO decayConfig = findDecayConfig(topicEvent.getSource(), difficulty.getDifficultyLevel());
        long days = Math.max(0, Duration.between(toInstant(topicEvent.getTimestamp()), Instant.now()).toDays());
        double baseDecay = calculateBaseDecay(days, decayConfig);
        double sourceWeight = sourceWeight(parseSource(topicEvent.getSource()));
        double difficultyFactor = difficultyFactor(difficulty.getDifficultyLevel());
        return clamp(baseDecay * sourceWeight * difficultyFactor, safeMinWeight(decayConfig), 1.0);
    }

    private LearningDecayConfigDO findDecayConfig(String source, Integer difficultyLevel) {
        String normalizedSource = source == null || source.isBlank() ? "ALL" : source.trim().toUpperCase();
        Integer normalizedDifficulty = normalizeDifficulty(difficultyLevel);
        LearningDecayConfigDO config = learningDecayConfigMapper.selectOne(
                new LambdaQueryWrapper<LearningDecayConfigDO>()
                        .eq(LearningDecayConfigDO::getEnabled, Boolean.TRUE)
                        .eq(LearningDecayConfigDO::getSource, normalizedSource)
                        .eq(LearningDecayConfigDO::getDifficultyLevel, normalizedDifficulty)
        );
        if (config != null) {
            return config;
        }
        config = learningDecayConfigMapper.selectOne(
                new LambdaQueryWrapper<LearningDecayConfigDO>()
                        .eq(LearningDecayConfigDO::getEnabled, Boolean.TRUE)
                        .eq(LearningDecayConfigDO::getSource, "ALL")
                        .eq(LearningDecayConfigDO::getDifficultyLevel, normalizedDifficulty)
        );
        if (config != null) {
            return config;
        }
        config = learningDecayConfigMapper.selectOne(
                new LambdaQueryWrapper<LearningDecayConfigDO>()
                        .eq(LearningDecayConfigDO::getEnabled, Boolean.TRUE)
                        .eq(LearningDecayConfigDO::getSource, "ALL")
                        .isNull(LearningDecayConfigDO::getDifficultyLevel)
                        .last("LIMIT 1")
        );
        if (config != null) {
            return config;
        }
        LearningDecayConfigDO fallback = new LearningDecayConfigDO();
        fallback.setConfigKey("default");
        fallback.setSource("ALL");
        fallback.setHalfLifeDays(14);
        fallback.setMinWeight(0.2);
        fallback.setDecayCurve("EXPONENTIAL");
        fallback.setEnabled(Boolean.TRUE);
        return fallback;
    }

    private double calculateBaseDecay(long days, LearningDecayConfigDO decayConfig) {
        int halfLifeDays = Math.max(1, decayConfig.getHalfLifeDays() == null ? 14 : decayConfig.getHalfLifeDays());
        String curve = decayConfig.getDecayCurve() == null ? "EXPONENTIAL" : decayConfig.getDecayCurve().trim().toUpperCase();
        double minWeight = safeMinWeight(decayConfig);
        return switch (curve) {
            case "LINEAR" -> clamp(1.0 - (days / (double) (halfLifeDays * 2)), minWeight, 1.0);
            case "SIGMOID" -> {
                double midpoint = halfLifeDays;
                double steepness = 6.0 / Math.max(1.0, halfLifeDays);
                double value = 1.0 / (1.0 + Math.exp((days - midpoint) * steepness));
                yield clamp(value, minWeight, 1.0);
            }
            default -> {
                double value = Math.pow(0.5, days / (double) halfLifeDays);
                yield clamp(value, minWeight, 1.0);
            }
        };
    }

    private List<String> recommendTopics(UserProfileState profile, String mode, int limit) {
        return profile.topicMetrics.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, TopicMetricState>>comparingDouble(entry -> recommendationPriority(entry.getValue(), mode)).reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private double recommendationPriority(TopicMetricState metric, String mode) {
        long daysSinceLastAttempt = metric.lastEventAt == null || metric.lastEventAt.isBlank()
                ? MAX_RECOMMEND_DAYS
                : Math.min(MAX_RECOMMEND_DAYS, Duration.between(LocalDateTime.parse(metric.lastEventAt).atZone(ZoneId.systemDefault()).toInstant(), Instant.now()).toDays());
        double masteryPriority = (1.0 - metric.masteryScore) * 0.5;
        double difficultyPriority = (metric.difficultyLevel / (double) MAX_DIFFICULTY) * 0.3;
        double recencyPriority = (daysSinceLastAttempt / (double) MAX_RECOMMEND_DAYS) * 0.2;
        double modeBias = "coding".equals(mode) ? Math.min(0.08, metric.confidence * 0.08) : Math.min(0.05, metric.weakScore * 0.05);
        return masteryPriority + difficultyPriority + recencyPriority + modeBias;
    }

    private String normalizeTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            return DEFAULT_TOPIC;
        }
        String normalized = topic.trim();
        return normalized.isBlank() ? DEFAULT_TOPIC : normalized;
    }

    private LearningSource parseSource(String source) {
        if (source == null || source.isBlank()) {
            return LearningSource.INTERVIEW;
        }
        try {
            return LearningSource.valueOf(source.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return LearningSource.INTERVIEW;
        }
    }

    private double sourceWeight(LearningSource source) {
        return source == LearningSource.CODING ? CODING_SOURCE_WEIGHT : INTERVIEW_SOURCE_WEIGHT;
    }

    private double difficultyFactor(Integer difficultyLevel) {
        int normalized = normalizeDifficulty(difficultyLevel);
        return clamp(1.02 - ((normalized - 1) * 0.08), 0.7, 1.02);
    }

    private int normalizeDifficulty(Integer difficultyLevel) {
        if (difficultyLevel == null) {
            return 1;
        }
        return Math.max(1, Math.min(MAX_DIFFICULTY, difficultyLevel));
    }

    private int resolveCapabilityLevel(double masteryScore) {
        if (masteryScore < 0.2) {
            return 0;
        }
        if (masteryScore < 0.4) {
            return 1;
        }
        if (masteryScore < 0.6) {
            return 2;
        }
        if (masteryScore < 0.8) {
            return 3;
        }
        return 4;
    }

    private double calculateTrendStrength(List<Integer> scores) {
        if (scores.size() < 2) {
            return 0.0;
        }
        int splitIndex = Math.max(1, scores.size() / 2);
        double oldAvg = scores.subList(0, splitIndex).stream().mapToInt(Integer::intValue).average().orElse(0.0);
        double newAvg = scores.subList(splitIndex, scores.size()).stream().mapToInt(Integer::intValue).average().orElse(oldAvg);
        return clamp((newAvg - oldAvg) / 100.0, -1.0, 1.0);
    }

    private String resolveTrendDirection(double trendStrength) {
        if (trendStrength >= 0.05) {
            return TREND_UP;
        }
        if (trendStrength <= -0.05) {
            return TREND_DOWN;
        }
        return TREND_STABLE;
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? Instant.EPOCH : dateTime.atZone(ZoneId.systemDefault()).toInstant();
    }

    private double safeDouble(Double value) {
        return value == null ? 0.0 : value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private double safeMinWeight(LearningDecayConfigDO decayConfig) {
        return decayConfig.getMinWeight() == null ? 0.2 : clamp01(decayConfig.getMinWeight());
    }

    private double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static class UserProfileState {
        public Map<String, TopicMetricState> topicMetrics = new LinkedHashMap<>();
        public List<LearningEvent> events = new ArrayList<>();
        public Set<String> processedEventIds = new LinkedHashSet<>();
        public int totalEvents;
        public String lastUpdatedAt = "";
    }

    public static class TopicMetricState {
        public int attempts;
        public double weakScore;
        public double familiarScore;
        public double masteryScore;
        public double confidence;
        public double weightedAvgScore;
        public int difficultyLevel;
        public int capabilityLevel;
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
