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

/**
 * 面试学习画像服务。
 * 
 * 该服务负责持久化管理用户的学习画像（Learning Profile），
 * 它是 AI 面试官实现“因材施教”的核心依据。
 * 
 * 核心功能：
 * 1. 记录会话：每次面试结束后，提取报告中的薄弱点、盲区并记录。
 * 2. 统计分析：跟踪不同主题的掌握度曲线（Topic Capability Curve）。
 * 3. 实时快照：生成用于提示词（Prompt）的画像摘要，让 AI 了解用户当前的弱项。
 * 4. 针对性建议：根据历史低分主题和薄弱点，生成下轮面试的出题建议。
 * 5. 持久化：将所有画像数据保存到 learning_profiles.json。
 */
@Service
public class InterviewLearningProfileService {

    private static final Logger logger = LoggerFactory.getLogger(InterviewLearningProfileService.class);
    private static final String DEFAULT_USER = "local-user";
    /** 画像持久化文件名 */
    private static final String PROFILE_FILE = "learning_profiles.json";
    private static final int PROFILE_VERSION = 1;
    private final Object ioLock = new Object();

    private final ObjectMapper objectMapper;
    /** 内存中的画像缓存，Key 为 userId */
    private final Map<String, LearningProfile> profiles = new ConcurrentHashMap<>();

    public InterviewLearningProfileService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadProfiles();
    }

    /** 归一化用户 ID */
    public String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return DEFAULT_USER;
        }
        String normalized = userId.trim();
        return normalized.isBlank() ? DEFAULT_USER : normalized;
    }

    /**
     * 生成用于 Prompt 的画像快照摘要。
     * 包含历史表现、平均分、薄弱点、盲区及当前最弱主题。
     */
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

    /**
     * 记录面试会话结果并更新画像。
     * 
     * @param userId 用户 ID
     * @param topic 面试主题（如：Java 集合、Redis 缓存）
     * @param history 题目历史列表
     * @param reportContent 最终报告内容实体，包含 AI 提取的薄弱点、错误点等
     * @param averageScore 该场面试的平均分
     */
    public void recordSession(String userId, String topic, List<Question> history, EvaluationAgent.FinalReportContent reportContent, double averageScore) {
        LearningProfile profile = profiles.computeIfAbsent(normalizeUserId(userId), key -> new LearningProfile());
        profile.totalSessions++;
        profile.totalScore += averageScore;
        profile.topics.add(topic == null ? "未命名主题" : topic);
        
        // 增量提取薄弱点和盲区
        profile.weakPoints.addAll(splitLines(reportContent.weak()));
        profile.weakPoints.addAll(splitLines(reportContent.incomplete()));
        profile.blindSpots.addAll(splitLines(reportContent.wrong()));
        
        // 去重并限制数量，保持画像精简
        profile.weakPoints = deduplicate(profile.weakPoints, 12);
        profile.blindSpots = deduplicate(profile.blindSpots, 12);

        // 记录低分主题（单题得分 < 70）
        for (Question item : history) {
            if (item.getScore() < 70) {
                String key = summarizeTopic(item.getQuestionText());
                profile.lowScoreTopics.merge(key, 1, Integer::sum);
            }
        }
        
        // 更新主题掌握度统计
        String normalizedTopic = (topic == null || topic.isBlank()) ? "未命名主题" : topic.trim();
        TopicCapability capability = profile.topicStats.computeIfAbsent(normalizedTopic, key -> new TopicCapability());
        capability.attempts++;
        capability.totalScore += averageScore;
        capability.lastScore = averageScore;
        capability.recentScores.add(averageScore);
        capability.recentTimestamps.add(Instant.now().toString());
        
        // 限制最近成绩记录数量 (最近 12 次)
        if (capability.recentScores.size() > 12) {
            capability.recentScores = new ArrayList<>(capability.recentScores.subList(capability.recentScores.size() - 12, capability.recentScores.size()));
            capability.recentTimestamps = new ArrayList<>(capability.recentTimestamps.subList(capability.recentTimestamps.size() - 12, capability.recentTimestamps.size()));
        }
        
        profile.profileVersion = PROFILE_VERSION;
        profile.lastUpdatedAt = Instant.now().toString();
        profile.reliabilityScore = computeReliability(profile);
        saveProfiles();
    }

    /**
     * 生成针对性出题方向建议。
     */
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

    /**
     * 获取指定主题的能力掌握曲线数据。
     */
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

    /** 从本地加载画像数据 */
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

    /** 持久化画像数据 */
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

    /** 数据清洗与容错 */
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

    /** 计算画像可靠性分数（基于样本量和覆盖面） */
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

    /** 题目文本精简，用于主题分类 */
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

    /** 学习画像实体 */
    public static class LearningProfile {
        public int profileVersion = PROFILE_VERSION;
        /** 总面试场次 */
        public int totalSessions;
        /** 总平均分之和 */
        public double totalScore;
        /** 曾面试过的主题集合 */
        public Set<String> topics = new LinkedHashSet<>();
        /** 低分高频子主题统计 */
        public Map<String, Integer> lowScoreTopics = new ConcurrentHashMap<>();
        /** 累计薄弱点 */
        public List<String> weakPoints = new ArrayList<>();
        /** 累计盲区 */
        public List<String> blindSpots = new ArrayList<>();
        /** 主题能力统计明细 */
        public Map<String, TopicCapability> topicStats = new ConcurrentHashMap<>();
        /** 画像可靠性分数 (0.05 - 1.0) */
        public double reliabilityScore = 0.1;
        /** 最后更新时间 */
        public String lastUpdatedAt = "";

        private double averageScore() {
            return totalSessions == 0 ? 0 : totalScore / totalSessions;
        }
    }

    /** 主题能力统计实体 */
    public static class TopicCapability {
        /** 尝试次数 */
        public int attempts;
        /** 累计总分 */
        public double totalScore;
        /** 最近一次得分 */
        public double lastScore;
        /** 最近成绩序列 (最近 12 次) */
        public List<Double> recentScores = new ArrayList<>();
        /** 最近成绩时间戳序列 */
        public List<String> recentTimestamps = new ArrayList<>();

        public double averageScore() {
            return attempts == 0 ? 0 : totalScore / attempts;
        }
    }

    /** 对外暴露的能力曲线记录 */
    public record TopicCapabilityCurve(
            String topic,
            List<String> timestamps,
            List<Double> scores,
            double averageScore
    ) {
    }
}
