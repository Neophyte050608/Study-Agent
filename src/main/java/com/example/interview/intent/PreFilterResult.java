package com.example.interview.intent;

import java.util.Map;

/**
 * 规则前置过滤的结果。
 *
 * @param taskType    匹配到的任务类型（如 "INTERVIEW_START"），命令/问候时为 null
 * @param slots       提取的槽位参数
 * @param directReply 直接回复文本（非 null 时直接返回此文本，不走任务路由）
 * @param confidence  规则置信度（确定性命中为 1.0）
 */
public record PreFilterResult(
        String taskType,
        Map<String, Object> slots,
        String directReply,
        double confidence
) {
    public static PreFilterResult directReply(String reply) {
        return new PreFilterResult(null, Map.of(), reply, 1.0);
    }

    public static PreFilterResult routed(String taskType, Map<String, Object> slots) {
        return new PreFilterResult(taskType, slots, null, 1.0);
    }

    /**
     * 带附加回复文本的路由结果（如 /clear 需要同时执行操作和回复文字）。
     */
    public static PreFilterResult routedWithReply(String taskType, Map<String, Object> slots, String reply) {
        return new PreFilterResult(taskType, slots, reply, 1.0);
    }
}
