package io.github.imzmq.interview.intent.domain;

import java.util.Map;

/**
 * 规则前置过滤的结果。
 *
 * @param taskType    匹配到的任务类型（如 "INTERVIEW_START"），命令/问候时为 null
 * @param domain      匹配到的域（如 "INTERVIEW"），域级命中时 taskType 为 null
 * @param slots       提取的槽位参数
 * @param directReply 直接回复文本（非 null 时直接返回此文本，不走任务路由）
 * @param confidence  规则置信度（确定性命中为 1.0，域级命中为 0.8）
 */
public record PreFilterResult(
        String taskType,
        String domain,
        Map<String, Object> slots,
        String directReply,
        double confidence
) {
    public static PreFilterResult directReply(String reply) {
        return new PreFilterResult(null, null, Map.of(), reply, 1.0);
    }

    public static PreFilterResult routed(String taskType, Map<String, Object> slots) {
        return new PreFilterResult(taskType, null, slots, null, 1.0);
    }

    /**
     * 带附加回复文本的路由结果（如 /clear 需要同时执行操作和回复文字）。
     */
    public static PreFilterResult routedWithReply(String taskType, Map<String, Object> slots, String reply) {
        return new PreFilterResult(taskType, null, slots, reply, 1.0);
    }

    /**
     * 域级命中（无法确定具体叶子意图，但能确定业务域）。
     */
    public static PreFilterResult domainOnly(String domain) {
        return new PreFilterResult(null, domain, Map.of(), null, 0.8);
    }

    /**
     * 域级命中 + 附带已提取的槽位。
     */
    public static PreFilterResult domainOnly(String domain, Map<String, Object> slots) {
        return new PreFilterResult(null, domain, slots, null, 0.8);
    }

    /**
     * 是否为域级命中（有域但无具体任务类型）。
     */
    public boolean isDomainOnly() {
        return domain != null && !domain.isBlank() && (taskType == null || taskType.isBlank());
    }
}

