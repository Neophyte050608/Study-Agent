package com.example.interview.intent;

import java.util.List;

/**
 * 意图候选项（Intent Candidate）。
 *
 * <p>该对象用于表示一次意图分类/路由过程中“可能的意图结果”之一，通常会以列表形式出现在
 * {@link IntentRoutingDecision#candidates()} 中，供前端展示或用于低置信度场景的澄清提示。</p>
 *
 * <p>典型用途：</p>
 * <ul>
 *     <li>当树分类命中多个叶子意图时，保留 Top-N 候选及其 score/reason，便于解释“为什么选这个”</li>
 *     <li>当缺少关键槽位（slot）导致无法直接执行任务时，指出 missingSlots 用于触发“主动澄清”</li>
 * </ul>
 *
 * @param intentId      叶子意图 ID（通常对应数据库/配置中的 intentId）
 * @param taskType      任务类型（系统内部路由使用的枚举/字符串，如 CODING_PRACTICE / INTERVIEW 等）
 * @param score         该候选的评分/置信度（0~1 或 0~100 取决于上游实现的口径；这里保持透明透传）
 * @param reason        候选入选原因（可解释文本，用于日志/调试/前端展示）
 * @param missingSlots  缺失的槽位列表（例如 topic/difficulty/count 等），为空表示槽位齐全或无需槽位
 */
public record IntentCandidate(
        String intentId,
        String taskType,
        double score,
        String reason,
        List<String> missingSlots
) {
}
