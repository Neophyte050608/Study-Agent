package com.example.interview.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class InterviewSession {
    private String id;
    private String topic;
    private String resumeContent;
    private List<Question> history;
    private String currentQuestion;
    private int totalScore;
    private int questionCount;
    private int totalQuestions;
    private DifficultyLevel difficultyLevel;
    private int lowScoreStreak;
    private int highScoreStreak;
    private String profileSnapshot;
    private String userId;
    private FollowUpState followUpState;
    private Map<String, Double> topicMastery;

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
        this.userId = "local-user";
        this.followUpState = FollowUpState.PROBE;
        this.topicMastery = new HashMap<>();
    }

    public void addHistory(Question question) {
        this.history.add(question);
        this.totalScore += question.getScore();
        this.questionCount++;
    }

    public double getAverageScore() {
        if (questionCount == 0) return 0;
        return (double) totalScore / questionCount;
    }

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

    public void updateAdaptiveState(String topic, int score) {
        updateDifficultyByScore(score);
        followUpState = FollowUpState.byScore(score);
        String normalizedTopic = (topic == null || topic.isBlank()) ? "默认主题" : topic.trim();
        double oldMastery = topicMastery.getOrDefault(normalizedTopic, 65.0);
        double newMastery = oldMastery * 0.7 + score * 0.3;
        topicMastery.put(normalizedTopic, newMastery);
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
    public String getTopic() { return topic; }
    public String getResumeContent() { return resumeContent; }
    public List<Question> getHistory() { return history; }
    public String getCurrentQuestion() { return currentQuestion; }
    public void setCurrentQuestion(String currentQuestion) { this.currentQuestion = currentQuestion; }
    public int getTotalQuestions() { return totalQuestions; }
    public DifficultyLevel getDifficultyLevel() { return difficultyLevel; }
    public int getLowScoreStreak() { return lowScoreStreak; }
    public int getHighScoreStreak() { return highScoreStreak; }
    public String getProfileSnapshot() { return profileSnapshot; }
    public void setProfileSnapshot(String profileSnapshot) { this.profileSnapshot = profileSnapshot == null ? "" : profileSnapshot; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId == null || userId.isBlank() ? "local-user" : userId; }
    public FollowUpState getFollowUpState() { return followUpState; }
    public double getTopicMastery(String topic) {
        String normalizedTopic = (topic == null || topic.isBlank()) ? "默认主题" : topic.trim();
        return topicMastery.getOrDefault(normalizedTopic, 65.0);
    }
}
