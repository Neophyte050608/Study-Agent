package com.example.interview.service;

import java.util.List;
import java.util.Map;

public record TrainingProfileSnapshot(
        List<Map<String, Object>> weakTopicRank,
        List<Map<String, Object>> familiarTopicRank,
        String recentTrend,
        List<String> recommendedNextInterviewTopics,
        List<String> recommendedNextCodingTopics,
        int totalEvents,
        String lastUpdatedAt
) {
}
