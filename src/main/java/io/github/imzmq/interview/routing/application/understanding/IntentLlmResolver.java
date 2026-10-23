package io.github.imzmq.interview.routing.application.understanding;

import io.github.imzmq.interview.modelrouting.core.ModelRouteType;
import io.github.imzmq.interview.modelrouting.core.RoutingChatService;
import io.github.imzmq.interview.routing.application.contract.result.IntentRecord;
import org.springframework.stereotype.Component;

@Component
public class IntentLlmResolver {

    private static final String SYSTEM_PROMPT = """
            你是域无关的对话理解器。请结合历史和当前输入，输出一次完整的意图理解结果。
            relation 只能是 NEW_TOPIC、FOLLOW_UP、REFINE、CORRECT、RETURN、CONFIRM、CHITCHAT 之一。
            standaloneQuery 必须是补全指代后的自包含问题；tasks 最多一个任务，intentLeafId 使用意图叶子 ID，slots 只含字符串值。
            confidence 和 task.score 必须在 0 到 1 之间。只返回 JSON，不要 markdown 或解释文字。
            JSON 结构：{"relation":"NEW_TOPIC","standaloneQuery":"...","tasks":[{"intentLeafId":"...","slots":{},"score":0.0}],"confidence":0.0,"reason":"..."}
            """;

    private final RoutingChatService routingChatService;
    private final IntentRecordMapper mapper;

    public IntentLlmResolver(RoutingChatService routingChatService, IntentRecordMapper mapper) {
        this.routingChatService = routingChatService;
        this.mapper = mapper;
    }

    public IntentRecord resolve(String sessionId, String userId, String rawQuery, String history) {
        String userPrompt = """
                sessionId: %s
                userId: %s
                history:
                %s

                currentQuery:
                %s
                """.formatted(safe(sessionId), safe(userId), safe(history), safe(rawQuery));
        String response = routingChatService.call(
                SYSTEM_PROMPT,
                userPrompt,
                ModelRouteType.GENERAL,
                "意图统一理解"
        );
        return mapper.map(sessionId, rawQuery, response);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
