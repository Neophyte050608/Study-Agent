package com.example.interview.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 面试会话实体类。
 * 承载单场面试的所有上下文信息，包括题目历史、当前状态、自适应难度等。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InterviewSession {
    /** 会话唯一 ID */
    private String id;
    /** 面试主题 */
    private String topic;
    /** 简历解析内容（可选） */
    private String resumeContent;
    /** 已提问的历史记录 */
    private List<Question> history;
    /** 当前正在提问的问题文本 */
    private String currentQuestion;
    /** 累计总分 */
    private int totalScore;
    /** 已回答的题目数量 */
    private int questionCount;
    /** 预设的总题数 */
    private int totalQuestions;
    /** 当前会话的难度等级 */
    private DifficultyLevel difficultyLevel;
    /** 低分连续次数，用于触发难度下调 */
    private int lowScoreStreak;
    /** 高分连续次数，用于触发难度上调 */
    private int highScoreStreak;
    /** 启动面试时的用户画像快照 */
    private String profileSnapshot;
    /** 用户对话的滚动式总结（Rolling Summary），用于缓解超长会话的 Token 压力 */
    private String rollingSummary;
    /** 关联的用户 ID */
    private String userId;
    /** 当前的追问策略状态 */
    private FollowUpState followUpState;
    /** 主题掌握度映射：Key 为子主题名，Value 为 0-100 的掌握评分 */
    private Map<String, Double> topicMastery;
    /** 当前所处的 SOP 面试环节 */
    private InterviewStage currentStage;

    public InterviewSession() {
        this.history = new ArrayList<>();
        this.topicMastery = new HashMap<>();
    }

    public InterviewSession(String topic, String resumeContent, int totalQuestions) {
        this.id = UUID.randomUUID().toString();
        this.topic = topic;
        this.resumeContent = resumeContent;
        this.history = new ArrayList<>();
        this.totalScore = 0;
        this.questionCount = 0;
        this.totalQuestions = totalQuestions;
        this.difficultyLevel = DifficultyLevel.BASIC;
        this.lowScoreStreak = 0;
        this.highScoreStreak = 0;
        this.profileSnapshot = "";
        this.rollingSummary = "";
        this.userId = "local-user";
        this.followUpState = FollowUpState.PROBE;
        this.topicMastery = new HashMap<>();
        this.currentStage = InterviewStage.INTRODUCTION; // 默认从自我介绍开始
    }

    /**
     * 将回答后的题目对象加入历史，并更新累计得分。
     */
    public void addHistory(Question question) {
        this.history.add(question);
        this.totalScore += question.getScore();
        this.questionCount++;
    }

    /**
     * 获取当前面试的平均分。
     */
    @JsonIgnore
    public double getAverageScore() {
        if (questionCount == 0) return 0;
        return (double) totalScore / questionCount;
    }

    /**
     * 根据单题得分自适应更新难度等级。
     * 逻辑：
     * 1. 低于 60 分累计 1 次即下调难度。
     * 2. 高于 85 分连续 2 次即上调难度。
     */
    public void updateDifficultyByScore(int score) {
        if (score < 60) {
            lowScoreStreak++;
            highScoreStreak = 0;
            if (lowScoreStreak >= 1) {
                difficultyLevel = difficultyLevel.easier();
            }
            return;
        }
        if (score >= 85) {
            highScoreStreak++;
            lowScoreStreak = 0;
            if (highScoreStreak >= 2) {
                difficultyLevel = difficultyLevel.harder();
                highScoreStreak = 0;
            }
            return;
        }
        lowScoreStreak = 0;
        highScoreStreak = 0;
    }

    /**
     * 更新自适应状态，包括难度、追问策略和主题掌握度。
     * 
     * @param topic 当前题目所属的子主题
     * @param score 当前题目的得分
     */
    public void updateAdaptiveState(String topic, int score) {
        updateDifficultyByScore(score);
        followUpState = FollowUpState.byScore(score);
        String normalizedTopic = (topic == null || topic.isBlank()) ? "默认主题" : topic.trim();
        // 掌握度计算：70% 历史 + 30% 当前，平滑波动
        double oldMastery = topicMastery.getOrDefault(normalizedTopic, 65.0);
        double newMastery = oldMastery * 0.7 + score * 0.3;
        topicMastery.put(normalizedTopic, newMastery);
        
        // 基于掌握度的全局难度微调
        if (newMastery < 60) {
            difficultyLevel = DifficultyLevel.BASIC;
        } else if (newMastery < 80) {
            difficultyLevel = DifficultyLevel.INTERMEDIATE;
        } else {
            difficultyLevel = DifficultyLevel.ADVANCED;
        }
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getResumeContent() { return resumeContent; }
    public void setResumeContent(String resumeContent) { this.resumeContent = resumeContent; }
    public List<Question> getHistory() { return history; }
    public void setHistory(List<Question> history) { this.history = history; }
    public String getCurrentQuestion() { return currentQuestion; }
    public void setCurrentQuestion(String currentQuestion) { this.currentQuestion = currentQuestion; }
    public int getTotalScore() { return totalScore; }
    public void setTotalScore(int totalScore) { this.totalScore = totalScore; }
    public int getQuestionCount() { return questionCount; }
    public void setQuestionCount(int questionCount) { this.questionCount = questionCount; }
    public int getTotalQuestions() { return totalQuestions; }
    public void setTotalQuestions(int totalQuestions) { this.totalQuestions = totalQuestions; }
    public DifficultyLevel getDifficultyLevel() { return difficultyLevel; }
    public void setDifficultyLevel(DifficultyLevel difficultyLevel) { this.difficultyLevel = difficultyLevel; }
    public int getLowScoreStreak() { return lowScoreStreak; }
    public void setLowScoreStreak(int lowScoreStreak) { this.lowScoreStreak = lowScoreStreak; }
    public int getHighScoreStreak() { return highScoreStreak; }
    public void setHighScoreStreak(int highScoreStreak) { this.highScoreStreak = highScoreStreak; }
    public String getProfileSnapshot() { return profileSnapshot; }
    public void setProfileSnapshot(String profileSnapshot) { this.profileSnapshot = profileSnapshot == null ? "" : profileSnapshot; }
    public String getRollingSummary() { return rollingSummary == null ? "" : rollingSummary; }
    public void setRollingSummary(String rollingSummary) { this.rollingSummary = rollingSummary; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId == null || userId.isBlank() ? "local-user" : userId; }
    public FollowUpState getFollowUpState() { return followUpState; }
    public void setFollowUpState(FollowUpState followUpState) { this.followUpState = followUpState; }
    public double getTopicMastery(String topic) {
        String normalizedTopic = (topic == null || topic.isBlank()) ? "默认主题" : topic.trim();
        return topicMastery.getOrDefault(normalizedTopic, 65.0);
    }
    public Map<String, Double> getTopicMastery() { return topicMastery; }
    public void setTopicMastery(Map<String, Double> topicMastery) { this.topicMastery = topicMastery; }
    public InterviewStage getCurrentStage() { return currentStage; }
    public void setCurrentStage(InterviewStage currentStage) { this.currentStage = currentStage; }
}
