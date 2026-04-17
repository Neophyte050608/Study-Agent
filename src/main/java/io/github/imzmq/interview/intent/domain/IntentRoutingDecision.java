package io.github.imzmq.interview.intent.domain;

import java.util.List;
import java.util.Map;

/**
 * 意图路由决策（Intent Routing Decision）。
 *
 * <p>该对象用于承载一次“意图识别 -> 任务路由”的最终决策结果，既可以表示：</p>
 * <ul>
 *     <li>已经确定 taskType 且槽位齐全，可以直接进入对应业务链路</li>
 *     <li>置信度不足或槽位缺失，需要向用户发起澄清问题（askClarification=true）</li>
 *     <li>树分类不可用/不命中，回退到旧版规则或旧入口（fallbackToLegacy=true）</li>
 * </ul>
 *
 * <p>字段约定：</p>
 * <ul>
 *     <li>confidence：当前决策置信度（用于阈值比较与 UI 展示）</li>
 *     <li>slots：结构化槽位（Map 透传，便于不同 taskType 承载不同字段）</li>
 *     <li>candidates：候选列表（用于解释/调试，也用于“澄清选项”生成）</li>
 *     <li>clarificationOptions：澄清选项列表（通常为 label/value 等键值对，前端用来渲染按钮）</li>
 * </ul>
 *
 * @param fallbackToLegacy       是否回退旧链路（true 表示不使用树分类结果）
 * @param taskType               最终任务类型（为空表示尚未确定或需要澄清）
 * @param confidence             决策置信度
 * @param reason                 决策原因说明（可解释文本）
 * @param slots                  结构化槽位（按 taskType 不同而不同）
 * @param candidates             Top-N 候选意图列表
 * @param askClarification       是否需要向用户澄清
 * @param clarificationQuestion  澄清问题（askClarification=true 时使用）
 * @param clarificationOptions   澄清选项（askClarification=true 时使用）
 */
public record IntentRoutingDecision(
        boolean fallbackToLegacy,
        String taskType,
        double confidence,
        String reason,
        Map<String, Object> slots,
        List<IntentCandidate> candidates,
        boolean askClarification,
        String clarificationQuestion,
        List<Map<String, String>> clarificationOptions,
        boolean topicSwitch,
        String dialogAct,
        double infoNovelty,
        String currentTopic,
        String previousTopic,
        String contextPolicy
) {

    public IntentRoutingDecision(
            boolean fallbackToLegacy,
            String taskType,
            double confidence,
            String reason,
            Map<String, Object> slots,
            List<IntentCandidate> candidates,
            boolean askClarification,
            String clarificationQuestion,
            List<Map<String, String>> clarificationOptions
    ) {
        this(
                fallbackToLegacy,
                taskType,
                confidence,
                reason,
                slots,
                candidates,
                askClarification,
                clarificationQuestion,
                clarificationOptions,
                false,
                "",
                0.5D,
                "",
                "",
                "SAFE_MIN"
        );
    }

    /**
     * 构造一个“回退旧链路”的默认决策。
     *
     * <p>用于树分类不可用/未命中等场景，调用方可据此走历史的 rule-based 或其它兼容逻辑。</p>
     *
     * @return 回退决策对象
     */
    public static IntentRoutingDecision fallback() {
        return new IntentRoutingDecision(
                true, "", 0D, "", Map.of(), List.of(), false, "", List.of(),
                false, "", 0.5D, "", "", "SAFE_MIN"
        );
    }
}

