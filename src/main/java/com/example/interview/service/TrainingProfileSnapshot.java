package com.example.interview.service;

import java.util.List;
import java.util.Map;

/**
 * 用户学习画像快照实体。
 * 用于向 AI 或前端展示用户当前的能力状态。
 */
public record TrainingProfileSnapshot(
        /** 薄弱主题排行（基于得分和频次） */
        List<Map<String, Object>> weakTopicRank,
        /** 熟练主题排行 */
        List<Map<String, Object>> familiarTopicRank,
        /** 最近的表现趋势（如：显著提升、基本稳定等） */
        String recentTrend,
        /** 为用户推荐的下一次面试主题 */
        List<String> recommendedNextInterviewTopics,
        /** 为用户推荐的下一次刷题主题 */
        List<String> recommendedNextCodingTopics,
        /** 总学习事件数 */
        int totalEvents,
        /** 最后一次画像更新时间 */
        String lastUpdatedAt
) {
}
