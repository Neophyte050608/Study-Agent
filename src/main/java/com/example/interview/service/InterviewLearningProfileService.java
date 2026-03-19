package com.example.interview.service;

import com.example.interview.agent.EvaluationAgent;
import com.example.interview.core.Question;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class InterviewLearningProfileService {

    private static final Logger logger = LoggerFactory.getLogger(InterviewLearningProfileService.class);
    private static final String DEFAULT_USER = "local-user";
    private static final String PROFILE_FILE = "learning_profiles.json";
    private static final int PROFILE_VERSION = 1;
    private final Object ioLock = new Object();

    private final ObjectMapper objectMapper;
    private final Map<String, LearningProfile> profiles = new ConcurrentHashMap<>();

    public InterviewLearningProfileService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadProfiles();
    }

    public String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return DEFAULT_USER;
        }
        String normalized = userId.trim();
        return normalized.isBlank() ? DEFAULT_USER : normalized;
    }

    public String snapshotForPrompt(String userId, String topic) {
        LearningProfile profile = profiles.getOrDefault(normalizeUserId(userId), new LearningProfile());
        if (profile.totalSessions == 0) {
            return "暂无历史面试记录。";
        }
        String weakPoints = profile.weakPoints.stream().limit(5).collect(Collectors.joining("；"));
        String blindSpots = profile.blindSpots.stream().limit(5).collect(Collectors.joining("；"));
        String frequentTopics = profile.lowScoreTopics.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining("、"));
        String weakTopic = profile.topicStats.entrySet().stream()
                .sorted(Comparator.comparingDouble(entry -> entry.getValue().averageScore()))
                .limit(1)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining("、"));
        return "历史会话数：" + profile.totalSessions +
                "\n平均分：" + String.format("%.1f", profile.averageScore()) +
                "\n画像可靠性：" + String.format("%.2f", profile.reliabilityScore) +
                "\n当前面试主题：" + topic +
                "\n薄弱点：" + (weakPoints.isBlank() ? "暂无" : weakPoints) +
                "\n盲区：" + (blindSpots.isBlank() ? "暂无" : blindSpots) +
                "\n低分高频主题：" + (frequentTopics.isBlank() ? "暂无" : frequentTopics) +
                "\n当前最弱主题：" + (weakTopic.isBlank() ? "暂无" : weakTopic);
    }

    public void recordSession(String userId, String topic, List<Question> history, EvaluationAgent.FinalReportContent reportContent, double averageScore) {
        LearningProfile profile = profiles.computeIfAbsent(normalizeUserId(userId), key -> new LearningProfile());
        profile.totalSessions++;
        profile.totalScore += averageScore;
        profile.topics.add(topic == null ? "未命名主题" : topic);
        profile.weakPoints.addAll(splitLines(reportContent.weak()));
        profile.weakPoints.addAll(splitLines(reportContent.incomplete()));
        profile.blindSpots.addAll(splitLines(reportContent.wrong()));
        profile.weakPoints = deduplicate(profile.weakPoints, 12);
        profile.blindSpots = deduplicate(profile.blindSpots, 12);

        for (Question item : history) {
            if (item.getScore() < 70) {
                String key = summarizeTopic(item.getQuestionText());
                profile.lowScoreTopics.merge(key, 1, Integer::sum);
            }
        }
        String normalizedTopic = (topic == null || topic.isBlank()) ? "未命名主题" : topic.trim();
        TopicCapability capability = profile.topicStats.computeIfAbsent(normalizedTopic, key -> new TopicCapability());
        capability.attempts++;
        capability.totalScore += averageScore;
        capability.lastScore = averageScore;
        capability.recentScores.add(averageScore);
        capability.recentTimestamps.add(Instant.now().toString());
        if (capability.recentScores.size() > 12) {
            capability.recentScores = new ArrayList<>(capability.recentScores.subList(capability.recentScores.size() - 12, capability.recentScores.size()));
            capability.recentTimestamps = new ArrayList<>(capability.recentTimestamps.subList(capability.recentTimestamps.size() - 12, capability.recentTimestamps.size()));
        }
        profile.profileVersion = PROFILE_VERSION;
        profile.lastUpdatedAt = Instant.now().toString();
        profile.reliabilityScore = computeReliability(profile);
        saveProfiles();
    }

    public String buildTargetedSuggestion(String userId) {
        LearningProfile profile = profiles.get(normalizeUserId(userId));
        if (profile == null || profile.totalSessions == 0) {
            return "暂无历史记录，建议先完成一轮面试后再生成针对性题单。";
        }
        String weak = profile.weakPoints.stream().limit(3).collect(Collectors.joining("；"));
        String blind = profile.blindSpots.stream().limit(3).collect(Collectors.joining("；"));
        String topics = profile.lowScoreTopics.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining("、"));
        String weakTopic = profile.topicStats.entrySet().stream()
                .sorted(Comparator.comparingDouble(entry -> entry.getValue().averageScore()))
                .limit(1)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining("、"));
        return "下轮建议重点出题方向：\n" +
                "- 薄弱表达强化：" + (weak.isBlank() ? "围绕核心知识做结构化复述" : weak) + "\n" +
                "- 盲区补全：" + (blind.isBlank() ? "补足边界条件和异常处理" : blind) + "\n" +
                "- 高频低分主题：" + (topics.isBlank() ? "当前暂无明显集中低分主题" : topics) + "\n" +
                "- 当前最弱主题：" + (weakTopic.isBlank() ? "暂无" : weakTopic);
    }

    public TopicCapabilityCurve getTopicCapabilityCurve(String userId, String topic) {
        LearningProfile profile = profiles.get(normalizeUserId(userId));
        if (profile == null) {
            return new TopicCapabilityCurve(topic, List.of(), List.of(), 0);
        }
        String normalizedTopic = (topic == null || topic.isBlank()) ? "未命名主题" : topic.trim();
        TopicCapability capability = profile.topicStats.get(normalizedTopic);
        if (capability == null) {
            return new TopicCapabilityCurve(normalizedTopic, List.of(), List.of(), 0);
        }
        return new TopicCapabilityCurve(normalizedTopic, capability.recentTimestamps, capability.recentScores, capability.averageScore());
    }

    private void loadProfiles() {
        synchronized (ioLock) {
            File file = new File(PROFILE_FILE);
            if (!file.exists()) {
                return;
            }
            try {
                Map<String, LearningProfile> loaded = objectMapper.readValue(file, new TypeReference<Map<String, LearningProfile>>() {});
                profiles.clear();
                if (loaded != null) {
                    loaded.forEach((key, value) -> profiles.put(key, sanitizeProfile(value)));
                }
                logger.info("Loaded learning profiles: {}", profiles.size());
            } catch (IOException e) {
                logger.warn("Failed to load learning profiles", e);
            }
        }
    }

    private void saveProfiles() {
        synchronized (ioLock) {
            try {
                Path target = Path.of(PROFILE_FILE);
                Path temp = Path.of(PROFILE_FILE + ".tmp");
                objectMapper.writeValue(temp.toFile(), profiles);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                logger.warn("Failed to save learning profiles", e);
            }
        }
    }

    private LearningProfile sanitizeProfile(LearningProfile profile) {
        LearningProfile target = profile == null ? new LearningProfile() : profile;
        if (target.topics == null) {
            target.topics = new LinkedHashSet<>();
        }
        if (target.lowScoreTopics == null) {
            target.lowScoreTopics = new ConcurrentHashMap<>();
        }
        if (target.weakPoints == null) {
            target.weakPoints = new ArrayList<>();
        }
        if (target.blindSpots == null) {
            target.blindSpots = new ArrayList<>();
        }
        if (target.topicStats == null) {
            target.topicStats = new ConcurrentHashMap<>();
        }
        if (target.profileVersion < 1) {
            target.profileVersion = PROFILE_VERSION;
        }
        if (target.reliabilityScore <= 0) {
            target.reliabilityScore = computeReliability(target);
        }
        return target;
    }

    private double computeReliability(LearningProfile profile) {
        double sessionsFactor = Math.min(1.0, profile.totalSessions / 8.0);
        double coverageFactor = Math.min(1.0, profile.topicStats.size() / 5.0);
        double freshnessFactor = 0.8;
        if (profile.lastUpdatedAt != null && !profile.lastUpdatedAt.isBlank()) {
            freshnessFactor = 1.0;
        }
        double score = 0.5 * sessionsFactor + 0.3 * coverageFactor + 0.2 * freshnessFactor;
        return Math.max(0.05, Math.min(1.0, score));
    }

    private List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(text.split("\\R")).stream()
                .map(String::trim)
                .map(item -> item.replaceFirst("^[-•\\d.\\s]+", ""))
                .filter(item -> !item.isBlank())
                .toList();
    }

    private List<String> deduplicate(List<String> source, int maxSize) {
        Set<String> seen = new LinkedHashSet<>(source);
        return seen.stream().limit(maxSize).collect(Collectors.toCollection(ArrayList::new));
    }

    private String summarizeTopic(String questionText) {
        if (questionText == null || questionText.isBlank()) {
            return "其他";
        }
        String normalized = questionText.replaceAll("[？?。,.，]", " ").trim();
        if (normalized.length() <= 16) {
            return normalized;
        }
        return normalized.substring(0, 16);
    }

    public static class LearningProfile {
        public int profileVersion = PROFILE_VERSION;
        public int totalSessions;
        public double totalScore;
        public Set<String> topics = new LinkedHashSet<>();
        public Map<String, Integer> lowScoreTopics = new ConcurrentHashMap<>();
        public List<String> weakPoints = new ArrayList<>();
        public List<String> blindSpots = new ArrayList<>();
        public Map<String, TopicCapability> topicStats = new ConcurrentHashMap<>();
        public double reliabilityScore = 0.1;
        public String lastUpdatedAt = "";

        private double averageScore() {
            return totalSessions == 0 ? 0 : totalScore / totalSessions;
        }
    }

    public static class TopicCapability {
        public int attempts;
        public double totalScore;
        public double lastScore;
        public List<Double> recentScores = new ArrayList<>();
        public List<String> recentTimestamps = new ArrayList<>();

        public double averageScore() {
            return attempts == 0 ? 0 : totalScore / attempts;
        }
    }

    public record TopicCapabilityCurve(
            String topic,
            List<String> timestamps,
            List<Double> scores,
            double averageScore
    ) {
    }
}
