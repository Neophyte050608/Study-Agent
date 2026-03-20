package com.example.interview.service;

import java.time.Instant;
import java.util.List;

/**
 * 学习事件实体 (Record)。
 * 用于记录单次面试或刷题产生的核心指标、薄弱点和证据。
 */
public record LearningEvent(
        /** 事件唯一 ID (evt-...) */
        String eventId,
        /** 用户唯一标识 */
        String userId,
        /** 学习来源：INTERVIEW (面试) 或 CODING (刷题) */
        LearningSource source,
        /** 本次学习的主题名称 */
        String topic,
        /** 本次学习的评分 (0-100) */
        int score,
        /** 提取出的薄弱点列表 */
        List<String> weakPoints,
        /** 表现较好的熟练点列表 */
        List<String> familiarPoints,
        /** 关联的证据摘要（如：AI 的具体反馈或关键错误描述） */
        String evidence,
        /** 事件产生的时间戳 */
        Instant timestamp
) {
}
